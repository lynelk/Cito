package net.citotech.cito.communication.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import net.citotech.cito.communication.notification.NotificationEventCatalog.Classification;
import net.citotech.cito.communication.preference.PreferenceService;
import net.citotech.cito.communication.sms.SmsEncodingService;
import net.citotech.cito.communication.template.TemplateService;
import net.citotech.cito.merchant.MerchantNotificationPreferenceService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional event journal -> policy -> existing templates/messages/outbox, without network I/O.
 */
@Service
public class NotificationOrchestrator {
    private final NamedParameterJdbcTemplate jdbc;
    private final MerchantNotificationPreferenceService preferences;
    private final PreferenceService channels;
    private final TemplateService templates;
    private final SmsEncodingService encoding;
    private final ObjectMapper mapper;

    public NotificationOrchestrator(
            NamedParameterJdbcTemplate jdbc,
            MerchantNotificationPreferenceService preferences,
            PreferenceService channels,
            TemplateService templates,
            SmsEncodingService encoding,
            ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.preferences = preferences;
        this.channels = channels;
        this.templates = templates;
        this.encoding = encoding;
        this.mapper = mapper;
    }

    @Transactional
    public void record(long merchantId, String eventId, String type, String reference) {
        var definition = NotificationEventCatalog.require(type);
        if (merchantId < 0
                || eventId == null
                || eventId.isBlank()
                || eventId.length() > 128
                || reference == null
                || !reference.matches("[a-zA-Z0-9._:/-]{1,128}"))
            throw new IllegalArgumentException("Valid event identity and safe reference required");
        var p =
                new MapSqlParameterSource()
                        .addValue("merchant", merchantId)
                        .addValue("event", eventId)
                        .addValue("type", definition.type())
                        .addValue("reference", reference);
        jdbc.update(
                "INSERT INTO notification_events(merchant_id,event_id,event_type,source_reference)"
                        + " VALUES (:merchant,:event,:type,:reference) ON DUPLICATE KEY UPDATE id=id",
                p);
        var prior =
                jdbc.queryForMap(
                        "SELECT event_type,source_reference FROM notification_events"
                                + " WHERE merchant_id=:merchant AND event_id=:event",
                        p);
        if (!definition.type().equals(prior.get("event_type"))
                || !reference.equals(prior.get("source_reference")))
            throw new IllegalArgumentException(
                    "Event identity conflicts with previous notification");
    }

    @Transactional
    public void process(long id) {
        var p = new MapSqlParameterSource("id", id);
        var event =
                jdbc.queryForMap("SELECT * FROM notification_events WHERE id=:id FOR UPDATE", p);
        if (!"PENDING".equals(event.get("status"))) return;
        var definition = NotificationEventCatalog.require(String.valueOf(event.get("event_type")));
        long merchant = ((Number) event.get("merchant_id")).longValue();
        String reference = String.valueOf(event.get("source_reference"));
        var template =
                templates
                        .find(definition.templateKey(), "SMS")
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Notification template unavailable"));
        String body =
                templates
                        .render(definition.templateKey(), "SMS", Map.of("reference", reference))
                        .body();
        Instant now = Instant.now();
        if (merchant > 0) {
            boolean mandatory = definition.classification() == Classification.MANDATORY;
            var preference = preferences.resolveChannel(merchant, definition.type());
            if (mandatory) {
                var recipients =
                        jdbc.queryForList(
                                "SELECT phone FROM merchant_admins WHERE merchant_id=:merchant"
                                        + " AND status='ACTIVE' ORDER BY id",
                                Map.of("merchant", merchant));
                if (recipients.isEmpty())
                    evidence(
                            id,
                            "MERCHANT",
                            "",
                            definition,
                            template.bodyTemplate(),
                            null,
                            "NO_RECIPIENT");
                for (var recipient : recipients)
                    enqueue(
                            id,
                            merchant,
                            "MERCHANT",
                            String.valueOf(recipient.get("phone")),
                            definition,
                            template.bodyTemplate(),
                            body,
                            now);
            } else if (preference.channel() == MerchantNotificationPreferenceService.Channel.SMS
                    && preference.shouldSend()
                    && channels.isChannelEnabled(merchant, "SMS")) {
                Instant due =
                        channels.find(merchant, "SMS")
                                .map(
                                        row ->
                                                afterQuietHours(
                                                        now,
                                                        row.quietHoursStart(),
                                                        row.quietHoursEnd(),
                                                        "UTC"))
                                .orElse(now);
                enqueue(
                        id,
                        merchant,
                        "MERCHANT",
                        preference.address(),
                        definition,
                        template.bodyTemplate(),
                        body,
                        due);
            } else {
                evidence(
                        id,
                        "MERCHANT",
                        "",
                        definition,
                        template.bodyTemplate(),
                        null,
                        "PREFERENCE_SUPPRESSED");
            }
        }
        if (definition.group() != null && event.get("acknowledged_at") == null)
            adminRecipients(id, definition, template.bodyTemplate(), body, now);
        jdbc.update(
                "UPDATE notification_events SET status='PROCESSED',processed_at=CURRENT_TIMESTAMP,last_error_safe=NULL WHERE id=:id",
                p);
    }

    private void adminRecipients(
            long id,
            NotificationEventCatalog.Definition definition,
            String snapshot,
            String body,
            Instant now) {
        var p = new MapSqlParameterSource("type", definition.type());
        var policies =
                jdbc.queryForList(
                        "SELECT * FROM notification_alert_policies WHERE event_type=:type FOR UPDATE",
                        p);
        if (policies.isEmpty()) throw new IllegalStateException("Admin alert policy unavailable");
        var policy = policies.getFirst();
        boolean mandatory = definition.classification() == Classification.MANDATORY;
        Timestamp last = (Timestamp) policy.get("last_dispatched_at");
        Timestamp start = (Timestamp) policy.get("window_started_at");
        int count =
                start == null || start.toInstant().plusSeconds(3600).isBefore(now)
                        ? 0
                        : ((Number) policy.get("window_count")).intValue();
        boolean dedup =
                last != null
                        && last.toInstant()
                                .plusSeconds(((Number) policy.get("dedup_seconds")).intValue())
                                .isAfter(now);
        if ((!mandatory && !"Y".equals(policy.get("enabled_flag")))
                || dedup
                || count >= ((Number) policy.get("max_per_hour")).intValue()) {
            evidence(
                    id,
                    "ADMIN",
                    "",
                    definition,
                    snapshot,
                    null,
                    dedup ? "DEDUPLICATED" : "POLICY_LIMITED");
            return;
        }
        var recipients =
                jdbc.queryForList(
                        "SELECT a.phone,CASE WHEN r.group_code='EXECUTIVE' THEN GREATEST(1,r.escalation_level) ELSE r.escalation_level END escalation_level,g.quiet_start,g.quiet_end,g.timezone"
                                + " FROM notification_admin_recipients r JOIN admins a ON a.id=r.admin_id"
                                + " JOIN notification_admin_groups g ON g.group_code=r.group_code"
                                + " WHERE (r.group_code=:group OR (r.group_code='EXECUTIVE' AND :critical=true)) AND r.active_flag='Y' AND a.status='ACTIVE'",
                        Map.of(
                                "group",
                                policy.get("group_code"),
                                "critical",
                                "CRITICAL".equals(definition.severity())));
        if (recipients.isEmpty())
            evidence(id, "ADMIN", "", definition, snapshot, null, "NO_RECIPIENT");
        for (var row : recipients) {
            Instant due =
                    now.plusSeconds(
                            ((Number) row.get("escalation_level")).longValue()
                                    * ((Number) policy.get("escalation_seconds")).longValue());
            if (!mandatory && !"CRITICAL".equals(definition.severity()))
                due =
                        afterQuietHours(
                                due,
                                text(row.get("quiet_start")),
                                text(row.get("quiet_end")),
                                text(row.get("timezone")));
            enqueue(
                    id,
                    0,
                    ((Number) row.get("escalation_level")).intValue() > 0
                            ? "ADMIN_ESCALATION"
                            : "ADMIN",
                    text(row.get("phone")),
                    definition,
                    snapshot,
                    body,
                    due);
        }
        jdbc.update(
                "UPDATE notification_alert_policies SET last_dispatched_at=:now,window_count=:count,"
                        + " window_started_at=:start WHERE event_type=:type",
                p.addValue("now", Timestamp.from(now))
                        .addValue("count", count + 1)
                        .addValue("start", count == 0 ? Timestamp.from(now) : start));
    }

    private void enqueue(
            long eventId,
            long merchant,
            String audience,
            String phone,
            NotificationEventCatalog.Definition definition,
            String snapshot,
            String body,
            Instant due) {
        phone = phone == null ? "" : phone.replaceAll("[\\s()-]", "");
        if (!phone.matches("\\+?[0-9]{7,15}")) {
            evidence(eventId, audience, phone, definition, snapshot, null, "NO_VALID_PHONE");
            return;
        }
        // STOP is marketing-only. Operational preferences are resolved above; mandatory notices
        // bypass both.
        if (definition.classification() == Classification.MARKETING) {
            Integer suppressed =
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM communication_sms_suppressions"
                                    + " WHERE merchant_id=:merchant AND phone_e164=:phone AND scope='MARKETING' AND active_flag='Y'",
                            Map.of("merchant", merchant, "phone", phone),
                            Integer.class);
            if (suppressed != null && suppressed > 0) {
                evidence(
                        eventId, audience, phone, definition, snapshot, null, "CONSENT_SUPPRESSED");
                return;
            }
        }
        String key = "notification:" + eventId + ":" + audience + ":" + phone;
        String publicId = "COM-" + UUID.randomUUID();
        String metadata;
        try {
            metadata =
                    mapper.writeValueAsString(
                            Map.of(
                                    "body",
                                    body,
                                    "segments",
                                    encoding.analyze(body).segments(),
                                    "notificationEventId",
                                    eventId,
                                    "templateVersion",
                                    definition.templateVersion(),
                                    "currencyCode",
                                    "UGX"));
        } catch (Exception e) {
            throw new IllegalStateException("Notification serialization failed");
        }
        var p =
                new MapSqlParameterSource()
                        .addValue("merchant", merchant)
                        .addValue("key", key)
                        .addValue("public", publicId)
                        .addValue("recipient", phone)
                        .addValue("template", definition.templateKey())
                        .addValue("metadata", metadata)
                        .addValue(
                                "purpose",
                                definition.classification() == Classification.MARKETING
                                        ? "MARKETING"
                                        : definition.classification() == Classification.MANDATORY
                                                ? "SECURITY"
                                                : "NOTIFICATION")
                        .addValue("due", Timestamp.from(due))
                        .addValue("expires", Timestamp.from(due.plusSeconds(86400)));
        jdbc.update(
                "INSERT INTO communication_messages(public_id,merchant_id,idempotency_key,purpose,recipient_type,recipient,"
                        + "requested_channels,selected_channel,template_key,fallback_enabled,status,scheduled_at,expires_at,metadata_json)"
                        + " VALUES (:public,:merchant,:key,:purpose,'PHONE',:recipient,'SMS','SMS',:template,'Y','RECEIVED',:due,:expires,:metadata)"
                        + " ON DUPLICATE KEY UPDATE id=id",
                p);
        Long communication =
                jdbc.queryForObject(
                        "SELECT id FROM communication_messages WHERE merchant_id=:merchant AND idempotency_key=:key",
                        p,
                        Long.class);
        jdbc.update(
                "INSERT INTO communication_outbox(communication_id,event_type,priority,next_attempt_at)"
                        + " VALUES (:communication,'DISPATCH',:priority,:due) ON DUPLICATE KEY UPDATE id=id",
                p.addValue("communication", communication)
                        .addValue(
                                "priority",
                                "CRITICAL".equals(definition.severity()) ? "URGENT" : "NORMAL"));
        evidence(eventId, audience, phone, definition, snapshot, communication, "QUEUED");
    }

    private void evidence(
            long event,
            String audience,
            String recipient,
            NotificationEventCatalog.Definition definition,
            String snapshot,
            Long communication,
            String outcome) {
        jdbc.update(
                "INSERT INTO notification_evidence(event_row_id,audience,recipient,template_key,template_version,template_snapshot,communication_id,outcome)"
                        + " VALUES (:event,:audience,:recipient,:template,:version,:snapshot,:communication,:outcome) ON DUPLICATE KEY UPDATE id=id",
                new MapSqlParameterSource()
                        .addValue("event", event)
                        .addValue("audience", audience)
                        .addValue("recipient", recipient)
                        .addValue("template", definition.templateKey())
                        .addValue("version", definition.templateVersion())
                        .addValue("snapshot", snapshot)
                        .addValue("communication", communication)
                        .addValue("outcome", outcome));
    }

    static Instant afterQuietHours(Instant time, String start, String end, String zone) {
        if (start == null || end == null || start.isBlank() || end.isBlank()) return time;
        var local = time.atZone(ZoneId.of(zone));
        LocalTime from = LocalTime.parse(start),
                to = LocalTime.parse(end),
                current = local.toLocalTime();
        boolean overnight = !from.isBefore(to);
        boolean quiet =
                overnight
                        ? !current.isBefore(from) || current.isBefore(to)
                        : !current.isBefore(from) && current.isBefore(to);
        if (!quiet) return time;
        var endDate = local.toLocalDate().plusDays(overnight && !current.isBefore(from) ? 1 : 0);
        return endDate.atTime(to).atZone(local.getZone()).toInstant();
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
