package net.citotech.cito.communication.notification;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import net.citotech.cito.admin.AdminAuditService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v2/admin/communication/notifications")
@PreAuthorize("hasRole('ADMIN')")
public class NotificationAdminController {
    private final NamedParameterJdbcTemplate jdbc;
    private final AdminAuditService audit;

    public NotificationAdminController(NamedParameterJdbcTemplate jdbc, AdminAuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @GetMapping("/catalogue")
    public List<NotificationEventCatalog.Definition> catalogue() {
        return NotificationEventCatalog.all();
    }

    @GetMapping("/policies")
    public List<Map<String, Object>> policies() {
        return jdbc.queryForList(
                "SELECT * FROM notification_alert_policies ORDER BY event_type", Map.of());
    }

    @GetMapping("/groups")
    public Map<String, Object> groups() {
        return Map.of(
                "groups",
                jdbc.queryForList(
                        "SELECT * FROM notification_admin_groups ORDER BY group_code", Map.of()),
                "recipients",
                jdbc.queryForList(
                        "SELECT r.*,a.name,a.phone FROM notification_admin_recipients r JOIN admins a ON a.id=r.admin_id ORDER BY group_code,escalation_level,admin_id",
                        Map.of()));
    }

    @GetMapping("/evidence")
    public List<Map<String, Object>> evidence(@RequestParam(defaultValue = "0") long afterId) {
        return jdbc.queryForList(
                "SELECT e.id,e.event_id,e.merchant_id,e.event_type,e.source_reference,e.status event_status,e.last_error_safe,"
                        + "n.audience,n.recipient,n.template_key,n.template_version,n.template_snapshot,n.outcome,"
                        + "m.public_id communication_reference,m.status communication_status,m.metadata_json,m.created_at,m.updated_at,"
                        + "d.provider_code,d.provider_message_id,d.attempt_no,d.status delivery_status,d.sent_at,d.delivered_at, d.billed_flag,CONCAT('COMM_DELIVERY:',d.id) billing_source_reference, r.decision_reference,r.sms_segments,r.expected_provider_cost,r.currency_code,r.explanation routing_explanation"
                        + " FROM notification_events e LEFT JOIN notification_evidence n ON n.event_row_id=e.id"
                        + " LEFT JOIN communication_messages m ON m.id=n.communication_id"
                        + " LEFT JOIN communication_message_deliveries d ON d.communication_id=m.id"
                        + " LEFT JOIN communication_routing_decisions r ON r.id=(SELECT MAX(r2.id) FROM communication_routing_decisions r2 WHERE r2.communication_id=m.id AND r2.selected_provider_code=d.provider_code)"
                        + " WHERE e.id>:after ORDER BY e.id DESC,n.id,d.id LIMIT 200",
                Map.of("after", Math.max(0, afterId)));
    }

    @PostMapping("/events/{id}/acknowledge")
    @Transactional
    public Map<String, Object> acknowledge(@PathVariable long id) {
        int found =
                jdbc.update(
                        "UPDATE notification_events SET acknowledged_at=COALESCE(acknowledged_at,CURRENT_TIMESTAMP) WHERE id=:id",
                        Map.of("id", id));
        if (found == 0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification event not found");
        jdbc.update(
                "UPDATE communication_outbox o JOIN notification_evidence n ON n.communication_id=o.communication_id"
                        + " SET o.status='CANCELLED',o.completed_at=CURRENT_TIMESTAMP WHERE n.event_row_id=:id AND n.audience='ADMIN_ESCALATION' AND o.status='PENDING'",
                Map.of("id", id));
        jdbc.update(
                "UPDATE communication_messages m JOIN notification_evidence n ON n.communication_id=m.id JOIN communication_outbox o ON o.communication_id=m.id"
                        + " SET m.status='CANCELLED' WHERE n.event_row_id=:id AND n.audience='ADMIN_ESCALATION' AND o.status='CANCELLED'",
                Map.of("id", id));
        audit.record(
                "COMMUNICATION_MANAGE",
                "NOTIFICATION_ACKNOWLEDGED",
                String.valueOf(id),
                "Pending escalation cancelled");
        return Map.of("acknowledged", true);
    }

    @PostMapping("/policies")
    @Transactional
    public Map<String, Object> policy(@RequestBody PolicyRequest request) {
        var definition = NotificationEventCatalog.require(request.eventType());
        if (definition.group() == null
                || request.dedupSeconds() < 0
                || request.dedupSeconds() > 86400
                || request.maxPerHour() < 1
                || request.maxPerHour() > 1000
                || request.escalationSeconds() < 60
                || request.escalationSeconds() > 86400
                || (!request.enabled()
                        && definition.classification()
                                == NotificationEventCatalog.Classification.MANDATORY))
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Invalid policy or mandatory alert disable request");
        var p =
                new MapSqlParameterSource()
                        .addValue("type", definition.type())
                        .addValue("enabled", request.enabled() ? "Y" : "N")
                        .addValue("dedup", request.dedupSeconds())
                        .addValue("limit", request.maxPerHour())
                        .addValue("escalation", request.escalationSeconds());
        jdbc.update(
                "UPDATE notification_alert_policies SET enabled_flag=:enabled,dedup_seconds=:dedup,max_per_hour=:limit,escalation_seconds=:escalation WHERE event_type=:type",
                p);
        audit.record(
                "COMMUNICATION_MANAGE",
                "NOTIFICATION_POLICY_CHANGED",
                definition.type(),
                "Notification alert policy updated");
        return Map.of("saved", true);
    }

    @PostMapping("/groups/{group}/recipients")
    @Transactional
    public Map<String, Object> recipient(
            @PathVariable String group, @RequestBody RecipientRequest request) {
        requireGroup(group);
        if (request.adminId() <= 0
                || request.escalationLevel() < 0
                || request.escalationLevel() > 5)
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Invalid admin or escalation level");
        Integer valid =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM admins WHERE id=:id AND status='ACTIVE' AND phone<>''",
                        Map.of("id", request.adminId()),
                        Integer.class);
        if (valid == null || valid != 1)
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "An active administrator with a phone is required");
        jdbc.update(
                "INSERT INTO notification_admin_recipients(group_code,admin_id,escalation_level,active_flag) VALUES (:group,:admin,:level,:active)"
                        + " ON DUPLICATE KEY UPDATE escalation_level=:level,active_flag=:active",
                Map.of(
                        "group",
                        group,
                        "admin",
                        request.adminId(),
                        "level",
                        request.escalationLevel(),
                        "active",
                        request.active() ? "Y" : "N"));
        audit.record(
                "COMMUNICATION_MANAGE",
                "NOTIFICATION_RECIPIENT_CHANGED",
                group,
                "Admin " + request.adminId() + "; active=" + request.active());
        return Map.of("saved", true);
    }

    @PostMapping("/groups/{group}/quiet-hours")
    @Transactional
    public Map<String, Object> quiet(
            @PathVariable String group, @RequestBody QuietRequest request) {
        requireGroup(group);
        try {
            ZoneId.of(request.timezone());
            if ((request.start() == null) != (request.end() == null))
                throw new IllegalArgumentException();
            if (request.start() != null) {
                LocalTime.parse(request.start());
                LocalTime.parse(request.end());
            }
        } catch (RuntimeException invalid) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Valid timezone and quiet-hours pair required");
        }
        jdbc.update(
                "UPDATE notification_admin_groups SET quiet_start=:start,quiet_end=:end,timezone=:zone WHERE group_code=:group",
                new MapSqlParameterSource()
                        .addValue("group", group)
                        .addValue("start", request.start())
                        .addValue("end", request.end())
                        .addValue("zone", request.timezone()));
        audit.record(
                "COMMUNICATION_MANAGE",
                "NOTIFICATION_QUIET_HOURS_CHANGED",
                group,
                "Notification quiet hours updated");
        return Map.of("saved", true);
    }

    private void requireGroup(String group) {
        if (!List.of("PLATFORM_OPERATIONS", "FINANCE", "SECURITY", "COMPLIANCE", "EXECUTIVE")
                .contains(group))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown notification group");
    }

    public record PolicyRequest(
            String eventType,
            boolean enabled,
            int dedupSeconds,
            int maxPerHour,
            int escalationSeconds) {}

    public record RecipientRequest(long adminId, int escalationLevel, boolean active) {}

    public record QuietRequest(String start, String end, String timezone) {}
}
