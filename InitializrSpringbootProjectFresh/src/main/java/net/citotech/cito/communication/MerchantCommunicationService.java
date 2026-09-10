package net.citotech.cito.communication;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.citotech.cito.Common;
import net.citotech.cito.communication.routing.SmartSmsRoutingService;
import net.citotech.cito.communication.sms.SmsEncodingService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Merchant-facing API service over the durable communications outbox. */
@Service
public class MerchantCommunicationService {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final SmsEncodingService smsEncodingService;
    private final SmartSmsRoutingService smartSmsRoutingService;

    public MerchantCommunicationService(
            NamedParameterJdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            SmsEncodingService smsEncodingService,
            SmartSmsRoutingService smartSmsRoutingService) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.smsEncodingService = smsEncodingService;
        this.smartSmsRoutingService = smartSmsRoutingService;
    }

    /** Compatibility entry point retained for existing callers. */
    @Transactional
    public Map<String, Object> enqueueSms(
            long merchantId,
            String recipient,
            String content,
            String purpose,
            String externalReference,
            String idempotencyKey,
            Integer expiresInSeconds) {
        return enqueueSms(
                merchantId,
                recipient,
                content,
                purpose,
                externalReference,
                idempotencyKey,
                expiresInSeconds,
                SmsOptions.defaults());
    }

    /**
     * Enqueues one SMS. Provider selection is deliberately deferred to dispatch unless an approved
     * sender identity is provider-specific. That makes scheduled sends and retries react to live
     * cost and provider health.
     */
    @Transactional
    public Map<String, Object> enqueueSms(
            long merchantId,
            String recipient,
            String content,
            String purpose,
            String externalReference,
            String idempotencyKey,
            Integer expiresInSeconds,
            SmsOptions options) {
        if (merchantId <= 0) throw new IllegalArgumentException("merchantId is required.");
        String normalizedRecipient = normalizeRecipient(recipient);
        if (blank(content)) throw new IllegalArgumentException("content is required.");
        if (content.length() > 1600) throw new IllegalArgumentException("SMS content is too long.");

        SmsOptions safeOptions = options == null ? SmsOptions.defaults() : options.normalized();
        String normalizedPurpose = blank(purpose) ? "TRANSACTIONAL" : purpose.trim().toUpperCase();
        if (!List.of("TRANSACTIONAL", "OTP", "SECURITY", "NOTIFICATION", "MARKETING")
                .contains(normalizedPurpose)) {
            throw new IllegalArgumentException("Unsupported communication purpose.");
        }
        String normalizedExternal = trimToNull(externalReference, 128);
        String normalizedIdempotency = trimToNull(idempotencyKey, 128);
        if (normalizedIdempotency == null) normalizedIdempotency = normalizedExternal;
        if (normalizedIdempotency != null) {
            Map<String, Object> existing = findByIdempotency(merchantId, normalizedIdempotency);
            if (existing != null) return existing;
        }

        if ("MARKETING".equals(normalizedPurpose)
                && isMarketingSuppressed(merchantId, normalizedRecipient)) {
            throw new IllegalArgumentException("Recipient has opted out of marketing SMS.");
        }

        SenderIdentity sender =
                validateSenderIdentity(
                        merchantId, safeOptions.senderId(), safeOptions.requireInbound());
        if (safeOptions.requireInbound() && sender == null) {
            throw new IllegalArgumentException(
                    "A two-way capable sender identity is required for inbound SMS.");
        }

        Instant now = Instant.now();
        Instant scheduledAt = parseSchedule(safeOptions.scheduledAt(), now);
        int ttl =
                expiresInSeconds == null
                        ? defaultTtl(normalizedPurpose)
                        : Math.max(60, Math.min(604800, expiresInSeconds));
        Instant expiresAt = scheduledAt.plusSeconds(ttl);
        var analysis = smsEncodingService.analyze(content);

        String pinnedProvider = sender == null ? null : sender.providerCode();
        boolean fallbackEnabled = safeOptions.fallbackEnabled() && pinnedProvider == null;
        String publicId = "COM-" + Common.randomUrlSafeToken(18);
        String metadataJson = metadata(content, analysis, safeOptions, sender);
        String status = scheduledAt.isAfter(now.plusSeconds(2)) ? "SCHEDULED" : "RECEIVED";

        MapSqlParameterSource p =
                new MapSqlParameterSource()
                        .addValue("public_id", publicId)
                        .addValue("merchant_id", merchantId)
                        .addValue("external_reference", normalizedExternal)
                        .addValue("idempotency_key", normalizedIdempotency)
                        .addValue("purpose", normalizedPurpose)
                        .addValue("recipient", normalizedRecipient)
                        .addValue("provider", pinnedProvider)
                        .addValue("fallback", fallbackEnabled ? "Y" : "N")
                        .addValue("status", status)
                        .addValue("scheduled_at", Timestamp.from(scheduledAt))
                        .addValue("expires_at", Timestamp.from(expiresAt))
                        .addValue("metadata_json", metadataJson);
        try {
            jdbcTemplate.update(
                    "INSERT INTO communication_messages "
                            + "(public_id, merchant_id, external_reference, idempotency_key, purpose,"
                            + " recipient_type, recipient, requested_channels, selected_channel,"
                            + " selected_provider_code, template_key, fallback_enabled, status,"
                            + " scheduled_at, expires_at, metadata_json) VALUES"
                            + " (:public_id,:merchant_id,:external_reference,:idempotency_key,:purpose,"
                            + " 'PHONE',:recipient,'SMS','SMS',:provider,NULL,:fallback,:status,"
                            + " :scheduled_at,:expires_at,:metadata_json)",
                    p);
        } catch (DuplicateKeyException duplicate) {
            if (normalizedIdempotency != null) {
                Map<String, Object> existing = findByIdempotency(merchantId, normalizedIdempotency);
                if (existing != null) return existing;
            }
            throw duplicate;
        }

        Long communicationId =
                jdbcTemplate.queryForObject(
                        "SELECT id FROM communication_messages WHERE public_id=:public_id AND merchant_id=:merchant_id",
                        p,
                        Long.class);
        if (communicationId == null)
            throw new IllegalStateException("Communication could not be persisted.");
        jdbcTemplate.update(
                "INSERT INTO communication_outbox"
                        + " (communication_id,event_type,status,priority,attempts,next_attempt_at)"
                        + " VALUES (:communication_id,'DISPATCH','PENDING',:priority,0,:next_attempt_at)",
                new MapSqlParameterSource()
                        .addValue("communication_id", communicationId)
                        .addValue("priority", priorityFor(normalizedPurpose))
                        .addValue("next_attempt_at", Timestamp.from(scheduledAt)));
        return findByPublicId(merchantId, publicId);
    }

    /** Merchant-safe preview: internal provider cost and candidate scoring are never exposed. */
    public Map<String, Object> previewSms(
            long merchantId,
            String content,
            String senderId,
            String countryCode,
            String currencyCode,
            String routingStrategy,
            boolean requireDeliveryReceipts,
            boolean requireInbound) {
        if (merchantId <= 0) throw new IllegalArgumentException("merchantId is required.");
        if (blank(content)) throw new IllegalArgumentException("content is required.");
        SenderIdentity sender = validateSenderIdentity(merchantId, senderId, requireInbound);
        if (requireInbound && sender == null) {
            throw new IllegalArgumentException(
                    "A two-way capable sender identity is required for inbound SMS.");
        }
        var analysis = smsEncodingService.analyze(content);
        var route =
                smartSmsRoutingService.preview(
                        merchantId,
                        content,
                        countryCode,
                        currencyCode,
                        routingStrategy,
                        requireDeliveryReceipts,
                        requireInbound,
                        sender == null ? null : sender.providerCode());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("encoding", analysis.encoding());
        result.put("characters", analysis.characters());
        result.put("encodingUnits", analysis.encodingUnits());
        result.put("segments", analysis.segments());
        result.put("segmentLimit", analysis.segmentLimit());
        result.put("remainingInCurrentSegment", analysis.remainingInCurrentSegment());
        result.put("longMessageWarning", analysis.longMessageWarning());
        result.put("routeDecisionReference", route.decisionReference());
        result.put("routable", route.routable());
        result.put("selectedProvider", route.selectedProviderCode());
        result.put("estimatedCharge", route.expectedCustomerCharge());
        result.put("currencyCode", route.currencyCode());
        result.put("routingStrategy", route.strategy());
        result.put("explanation", merchantRouteExplanation(route, sender));
        return result;
    }

    public Map<String, Object> analyzeSms(String content) {
        var analysis = smsEncodingService.analyze(content == null ? "" : content);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("encoding", analysis.encoding());
        result.put("characters", analysis.characters());
        result.put("encodingUnits", analysis.encodingUnits());
        result.put("segments", analysis.segments());
        result.put("segmentLimit", analysis.segmentLimit());
        result.put("remainingInCurrentSegment", analysis.remainingInCurrentSegment());
        result.put("longMessageWarning", analysis.longMessageWarning());
        return result;
    }

    public Map<String, Object> status(long merchantId, String publicId) {
        if (merchantId <= 0 || blank(publicId)) return null;
        return findByPublicId(merchantId, publicId.trim());
    }

    /** Cancel a message only while its durable outbox event is still pending. */
    @Transactional
    public Map<String, Object> cancelSms(long merchantId, String publicId) {
        if (merchantId <= 0 || blank(publicId))
            throw new IllegalArgumentException("messageReference is required.");
        List<Map<String, Object>> rows =
                jdbcTemplate.queryForList(
                        "SELECT m.id communication_id,m.status message_status,o.status outbox_status"
                                + " FROM communication_messages m LEFT JOIN communication_outbox o"
                                + " ON o.communication_id=m.id AND o.event_type='DISPATCH'"
                                + " WHERE m.merchant_id=:merchant AND m.public_id=:reference"
                                + " ORDER BY o.id DESC LIMIT 1 FOR UPDATE",
                        new MapSqlParameterSource()
                                .addValue("merchant", merchantId)
                                .addValue("reference", publicId.trim()));
        if (rows.isEmpty()) return null;
        String messageStatus = String.valueOf(rows.get(0).get("message_status"));
        if ("CANCELLED".equalsIgnoreCase(messageStatus)) return findByPublicId(merchantId, publicId);
        Object outboxValue = rows.get(0).get("outbox_status");
        String outboxStatus = outboxValue == null ? null : String.valueOf(outboxValue);
        if (!"PENDING".equalsIgnoreCase(outboxStatus)) {
            throw new IllegalArgumentException("Message can no longer be cancelled safely.");
        }
        long communicationId = ((Number) rows.get(0).get("communication_id")).longValue();
        jdbcTemplate.update(
                "UPDATE communication_outbox SET status='CANCELLED',locked_until=NULL,locked_by=NULL,updated_at=NOW()"
                        + " WHERE communication_id=:id AND event_type='DISPATCH' AND status='PENDING'",
                new MapSqlParameterSource("id", communicationId));
        jdbcTemplate.update(
                "UPDATE communication_messages SET status='CANCELLED',updated_at=NOW()"
                        + " WHERE id=:id AND merchant_id=:merchant",
                new MapSqlParameterSource()
                        .addValue("id", communicationId)
                        .addValue("merchant", merchantId));
        return findByPublicId(merchantId, publicId);
    }

    /** Reschedule a message only while it is still safely pending in the durable outbox. */
    @Transactional
    public Map<String, Object> rescheduleSms(long merchantId, String publicId, String scheduledAt) {
        if (merchantId <= 0 || blank(publicId))
            throw new IllegalArgumentException("messageReference is required.");
        if (blank(scheduledAt)) throw new IllegalArgumentException("scheduledAt is required.");
        List<Map<String, Object>> rows =
                jdbcTemplate.queryForList(
                        "SELECT m.id communication_id,m.status message_status,m.scheduled_at,m.expires_at,"
                                + " o.status outbox_status FROM communication_messages m"
                                + " LEFT JOIN communication_outbox o ON o.communication_id=m.id AND o.event_type='DISPATCH'"
                                + " WHERE m.merchant_id=:merchant AND m.public_id=:reference"
                                + " ORDER BY o.id DESC LIMIT 1 FOR UPDATE",
                        new MapSqlParameterSource()
                                .addValue("merchant", merchantId)
                                .addValue("reference", publicId.trim()));
        if (rows.isEmpty()) return null;
        Object outboxValue = rows.get(0).get("outbox_status");
        String outboxStatus = outboxValue == null ? null : String.valueOf(outboxValue);
        if (!"PENDING".equalsIgnoreCase(outboxStatus)) {
            throw new IllegalArgumentException("Message can no longer be rescheduled safely.");
        }
        Instant now = Instant.now();
        Instant newSchedule = parseSchedule(scheduledAt, now);
        Timestamp oldScheduled = (Timestamp) rows.get(0).get("scheduled_at");
        Timestamp oldExpires = (Timestamp) rows.get(0).get("expires_at");
        long ttl = defaultTtl("TRANSACTIONAL");
        if (oldScheduled != null && oldExpires != null) {
            ttl = Math.max(60, oldExpires.toInstant().getEpochSecond() - oldScheduled.toInstant().getEpochSecond());
        }
        long communicationId = ((Number) rows.get(0).get("communication_id")).longValue();
        String status = newSchedule.isAfter(now.plusSeconds(2)) ? "SCHEDULED" : "RECEIVED";
        MapSqlParameterSource params =
                new MapSqlParameterSource()
                        .addValue("id", communicationId)
                        .addValue("merchant", merchantId)
                        .addValue("scheduled", Timestamp.from(newSchedule))
                        .addValue("expires", Timestamp.from(newSchedule.plusSeconds(ttl)))
                        .addValue("status", status);
        jdbcTemplate.update(
                "UPDATE communication_messages SET scheduled_at=:scheduled,expires_at=:expires,status=:status,updated_at=NOW()"
                        + " WHERE id=:id AND merchant_id=:merchant",
                params);
        jdbcTemplate.update(
                "UPDATE communication_outbox SET next_attempt_at=:scheduled,attempts=0,last_error=NULL,updated_at=NOW()"
                        + " WHERE communication_id=:id AND event_type='DISPATCH' AND status='PENDING'",
                params);
        return findByPublicId(merchantId, publicId);
    }

    private Map<String, Object> findByIdempotency(long merchantId, String key) {
        List<Map<String, Object>> rows =
                jdbcTemplate.queryForList(
                        messageSelect()
                                + " WHERE merchant_id=:merchant_id AND idempotency_key=:idempotency_key LIMIT 1",
                        new MapSqlParameterSource()
                                .addValue("merchant_id", merchantId)
                                .addValue("idempotency_key", key));
        return rows.isEmpty() ? null : view(rows.get(0));
    }

    private Map<String, Object> findByPublicId(long merchantId, String publicId) {
        List<Map<String, Object>> rows =
                jdbcTemplate.queryForList(
                        messageSelect()
                                + " WHERE merchant_id=:merchant_id AND public_id=:public_id LIMIT 1",
                        new MapSqlParameterSource()
                                .addValue("merchant_id", merchantId)
                                .addValue("public_id", publicId));
        return rows.isEmpty() ? null : view(rows.get(0));
    }

    private String messageSelect() {
        return "SELECT public_id, external_reference, purpose, recipient, selected_channel,"
                + " selected_provider_code, status, scheduled_at, expires_at, metadata_json, created_at, updated_at"
                + " FROM communication_messages";
    }

    private Map<String, Object> view(Map<String, Object> row) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("messageReference", row.get("public_id"));
        view.put("externalReference", row.get("external_reference"));
        view.put("purpose", row.get("purpose"));
        view.put("recipient", row.get("recipient"));
        view.put("channel", row.get("selected_channel"));
        view.put("provider", row.get("selected_provider_code"));
        view.put("status", row.get("status"));
        view.put("scheduledAt", row.get("scheduled_at"));
        view.put("expiresAt", row.get("expires_at"));
        view.put("createdAt", row.get("created_at"));
        view.put("updatedAt", row.get("updated_at"));
        Object metadata = row.get("metadata_json");
        if (metadata != null) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = objectMapper.readValue(String.valueOf(metadata), Map.class);
                view.put("sms", parsed);
            } catch (Exception ignored) {
                // Status remains usable even if optional metadata was malformed historically.
            }
        }
        return view;
    }

    private SenderIdentity validateSenderIdentity(
            long merchantId, String senderId, boolean requireInbound) {
        if (blank(senderId)) return null;
        List<SenderIdentity> rows =
                jdbcTemplate.query(
                        "SELECT sender_id, sender_type, provider_code, country_code, two_way_capable"
                                + " FROM communication_sender_identities WHERE merchant_id=:merchant"
                                + " AND sender_id=:sender AND approval_status='APPROVED'"
                                + " ORDER BY default_flag DESC, id ASC LIMIT 1",
                        new MapSqlParameterSource()
                                .addValue("merchant", merchantId)
                                .addValue("sender", senderId.trim()),
                        (rs, rowNum) ->
                                new SenderIdentity(
                                        rs.getString("sender_id"),
                                        rs.getString("sender_type"),
                                        rs.getString("provider_code"),
                                        rs.getString("country_code"),
                                        "Y".equals(rs.getString("two_way_capable"))));
        if (rows.isEmpty())
            throw new IllegalArgumentException(
                    "Sender identity is not approved for this merchant.");
        SenderIdentity identity = rows.get(0);
        if (requireInbound && !identity.twoWayCapable()) {
            throw new IllegalArgumentException(
                    "Selected sender identity does not support two-way SMS.");
        }
        return identity;
    }

    private boolean isMarketingSuppressed(long merchantId, String phone) {
        Integer count =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM communication_sms_suppressions WHERE merchant_id=:merchant"
                                + " AND phone_e164=:phone AND scope='MARKETING' AND active_flag='Y'",
                        new MapSqlParameterSource()
                                .addValue("merchant", merchantId)
                                .addValue("phone", phone),
                        Integer.class);
        return count != null && count > 0;
    }

    private String merchantRouteExplanation(
            SmartSmsRoutingService.RouteDecision route, SenderIdentity sender) {
        if (!route.routable()) {
            return "No eligible SMS route is currently available for the requested capability set.";
        }
        if (sender != null && sender.providerCode() != null) {
            return "The approved provider-specific sender identity is currently eligible. Availability and capability are rechecked before dispatch.";
        }
        return "Smart routing selected the best eligible provider using configured cost, availability, capability and reliability rules. The route is rechecked before dispatch.";
    }

    private String metadata(
            String body,
            SmsEncodingService.SmsAnalysis analysis,
            SmsOptions options,
            SenderIdentity sender) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("body", body);
        metadata.put("encoding", analysis.encoding());
        metadata.put("characters", analysis.characters());
        metadata.put("encodingUnits", analysis.encodingUnits());
        metadata.put("segments", analysis.segments());
        metadata.put("longMessageWarning", analysis.longMessageWarning());
        if (sender != null) metadata.put("senderId", sender.senderId());
        if (!blank(options.countryCode()))
            metadata.put("countryCode", options.countryCode().trim().toUpperCase());
        if (!blank(options.currencyCode()))
            metadata.put("currencyCode", options.currencyCode().trim().toUpperCase());
        if (!blank(options.routingStrategy()))
            metadata.put("routingStrategy", options.routingStrategy().trim().toUpperCase());
        metadata.put("requireDeliveryReceipts", options.requireDeliveryReceipts());
        metadata.put("requireInbound", options.requireInbound());
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Communication content could not be encoded.");
        }
    }

    private Instant parseSchedule(String value, Instant now) {
        if (blank(value)) return now;
        try {
            Instant parsed;
            try {
                parsed = Instant.parse(value.trim());
            } catch (DateTimeParseException e) {
                parsed = OffsetDateTime.parse(value.trim()).toInstant();
            }
            if (parsed.isBefore(now.minusSeconds(30)))
                throw new IllegalArgumentException("scheduledAt cannot be in the past.");
            if (parsed.isAfter(now.plusSeconds(366L * 86400L))) {
                throw new IllegalArgumentException(
                        "scheduledAt cannot be more than one year ahead.");
            }
            return parsed.isBefore(now) ? now : parsed;
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "scheduledAt must be an ISO-8601 timestamp with timezone.");
        }
    }

    private String normalizeRecipient(String value) {
        if (blank(value)) throw new IllegalArgumentException("recipient is required.");
        String normalized = value.trim().replaceAll("[\\s()-]", "");
        if (!normalized.matches("\\+?[0-9]{7,15}")) {
            throw new IllegalArgumentException(
                    "recipient must be a valid international phone number.");
        }
        return normalized;
    }

    private int defaultTtl(String purpose) {
        return switch (purpose) {
            case "OTP", "SECURITY" -> 600;
            default -> 86400;
        };
    }

    private String priorityFor(String purpose) {
        return List.of("OTP", "SECURITY").contains(purpose) ? "URGENT" : "NORMAL";
    }

    private String trimToNull(String value, int maxLength) {
        if (blank(value)) return null;
        String trimmed = value.trim();
        if (trimmed.length() > maxLength)
            throw new IllegalArgumentException("Reference is too long.");
        return trimmed;
    }

    private boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    public record SmsOptions(
            String senderId,
            String scheduledAt,
            String routingStrategy,
            String countryCode,
            String currencyCode,
            boolean requireDeliveryReceipts,
            boolean requireInbound,
            boolean fallbackEnabled) {
        public static SmsOptions defaults() {
            return new SmsOptions(null, null, "BALANCED", null, "UGX", false, false, true);
        }

        SmsOptions normalized() {
            String strategy =
                    blankStatic(routingStrategy)
                            ? "BALANCED"
                            : routingStrategy.trim().toUpperCase();
            if (!List.of("BALANCED", "LOWEST_COST", "RELIABILITY_FIRST", "PRIORITY")
                    .contains(strategy)) {
                throw new IllegalArgumentException("Unsupported routing strategy.");
            }
            return new SmsOptions(
                    senderId,
                    scheduledAt,
                    strategy,
                    countryCode,
                    blankStatic(currencyCode) ? "UGX" : currencyCode.trim().toUpperCase(),
                    requireDeliveryReceipts,
                    requireInbound,
                    fallbackEnabled);
        }

        private static boolean blankStatic(String value) {
            return value == null || value.trim().isEmpty();
        }
    }

    private record SenderIdentity(
            String senderId,
            String senderType,
            String providerCode,
            String countryCode,
            boolean twoWayCapable) {}
}
