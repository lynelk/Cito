package net.citotech.cito.communication.outbox;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.citotech.cito.communication.delivery.CommunicationDeliveryDispatcher;
import net.citotech.cito.communication.delivery.DeliveryStatus;
import net.citotech.cito.communication.provider.CommunicationProviderHealthService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Durable, retryable communications outbox worker. */
@Component
@ConditionalOnProperty(value = "cpay.communication.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class CommunicationOutboxWorker {

    private static final Logger logger = Logger.getLogger(CommunicationOutboxWorker.class.getName());
    static final long[] BACKOFF_SECONDS = {5, 30, 120, 600, 1800};

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final CommunicationDeliveryDispatcher dispatcher;
    private final CommunicationProviderHealthService healthService;
    private final int batchSize;
    private final int maxAttempts;

    public CommunicationOutboxWorker(
            NamedParameterJdbcTemplate jdbcTemplate,
            CommunicationDeliveryDispatcher dispatcher,
            CommunicationProviderHealthService healthService,
            @org.springframework.beans.factory.annotation.Value("${cpay.communication.outbox.batch-size:100}") int batchSize,
            @org.springframework.beans.factory.annotation.Value("${cpay.communication.outbox.max-attempts:5}") int maxAttempts) {
        this.jdbcTemplate = jdbcTemplate;
        this.dispatcher = dispatcher;
        this.healthService = healthService;
        this.batchSize = Math.max(1, batchSize);
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    @Scheduled(fixedDelayString = "${cpay.communication.outbox.fixed-delay-ms:1000}")
    @SchedulerLock(name = "communicationOutboxWorker", lockAtMostFor = "PT2M", lockAtLeastFor = "PT1S")
    public void processDue() {
        try {
            int processed = processDue(batchSize);
            if (processed > 0) logger.log(Level.INFO, "Communication outbox dispatched {0} message(s)", processed);
        } catch (Exception ex) {
            logger.log(Level.WARNING, "Communication outbox sweep failed: " + ex.getMessage(), ex);
        }
    }

    int processDue(int limit) {
        List<OutboxRow> batch = claimBatch(Math.max(1, limit));
        int terminal = 0;
        for (OutboxRow row : batch) if (processOne(row)) terminal++;
        return terminal;
    }

    private List<OutboxRow> claimBatch(int limit) {
        String claimToken = "worker-" + java.util.UUID.randomUUID();
        jdbcTemplate.update(
                "UPDATE communication_outbox SET status='DISPATCHING', claimed_by=:claimed_by, claimed_at=NOW(), attempts=attempts+1"
                        + " WHERE id IN (SELECT id FROM (SELECT id FROM communication_outbox"
                        + " WHERE status='PENDING' AND next_attempt_at<=NOW()"
                        + " ORDER BY priority ASC, next_attempt_at ASC, id ASC LIMIT :limit) t)",
                new MapSqlParameterSource().addValue("claimed_by", claimToken).addValue("limit", limit));
        return jdbcTemplate.query(
                "SELECT o.id, o.communication_id, m.merchant_id, m.recipient_type, m.recipient,"
                        + " m.purpose, m.requested_channels, m.selected_channel, m.selected_provider_code,"
                        + " m.template_key, m.fallback_enabled, m.expires_at, o.attempts"
                        + " FROM communication_outbox o JOIN communication_messages m ON m.id=o.communication_id"
                        + " WHERE o.claimed_by=:claimed_by AND o.status='DISPATCHING' ORDER BY o.id ASC",
                new MapSqlParameterSource("claimed_by", claimToken),
                (rs, rowNum) -> new OutboxRow(
                        rs.getLong("id"), rs.getLong("communication_id"), rs.getLong("merchant_id"),
                        rs.getString("recipient_type"), rs.getString("recipient"), rs.getString("purpose"),
                        rs.getString("requested_channels"), rs.getString("selected_channel"),
                        rs.getString("selected_provider_code"), rs.getString("template_key"),
                        "Y".equals(rs.getString("fallback_enabled")),
                        rs.getTimestamp("expires_at") == null ? null : rs.getTimestamp("expires_at").toInstant(),
                        rs.getInt("attempts")));
    }

    private boolean processOne(OutboxRow row) {
        try {
            if (row.expiresAt() != null && Instant.now().isAfter(row.expiresAt())) {
                complete(row.id());
                markMessageStatus(row.communicationId(), "EXPIRED");
                return true;
            }

            String channel = row.selectedChannel() == null || row.selectedChannel().isBlank()
                    ? firstRequestedChannel(row.requestedChannels()) : row.selectedChannel();
            if (channel == null) {
                fail(row, "NO_CHANNEL", "No deliverable channel on communication");
                return true;
            }

            String content = resolveContent(row);
            if (content == null || content.isBlank()) {
                fail(row, "CONTENT_UNAVAILABLE", "Message body could not be resolved");
                return true;
            }

            Map<String, Object> metadata = dispatchMetadata(row.communicationId());
            var outcome = dispatcher.dispatch(
                    row.merchantId(), channel, row.recipient(), subjectFor(row), content,
                    row.selectedProviderCode(), row.communicationId(), metadata);

            if (outcome.status() == DeliveryStatus.SENT || outcome.status() == DeliveryStatus.DELIVERED) {
                complete(row.id());
                markMessageStatus(row.communicationId(), outcome.status().name());
                recordOutcome(outcome.providerCode(), channel, true);
                return true;
            }
            if (outcome.status() == DeliveryStatus.REJECTED) {
                complete(row.id());
                markMessageStatus(row.communicationId(), "REJECTED");
                return true;
            }

            recordOutcome(outcome.providerCode(), channel, false);
            return handleFailure(row);
        } catch (Exception ex) {
            logger.log(Level.WARNING, "Outbox processing failed for row " + row.id() + ": " + ex.getMessage(), ex);
            return handleFailure(row);
        }
    }

    private boolean handleFailure(OutboxRow row) {
        if (row.attempts() >= maxAttempts) {
            fail(row, "MAX_ATTEMPTS_EXCEEDED", "Dispatch failed after " + row.attempts() + " attempts");
            return true;
        }
        long backoffSeconds = BACKOFF_SECONDS[Math.min(row.attempts() - 1, BACKOFF_SECONDS.length - 1)];
        jdbcTemplate.update(
                "UPDATE communication_outbox SET status='PENDING', claimed_by=NULL, claimed_at=NULL,"
                        + " last_error_code='DISPATCH_RETRYABLE', last_error_safe='Retry scheduled',"
                        + " next_attempt_at=DATE_ADD(NOW(), INTERVAL :backoff SECOND) WHERE id=:id",
                new MapSqlParameterSource().addValue("backoff", backoffSeconds).addValue("id", row.id()));

        if (row.fallbackEnabled()) {
            // Re-evaluate provider health/cost on the next attempt instead of pinning the failed route.
            jdbcTemplate.update(
                    "UPDATE communication_messages SET selected_provider_code=NULL, status='FALLBACK_PENDING' WHERE id=:id",
                    new MapSqlParameterSource("id", row.communicationId()));
        } else {
            markMessageStatus(row.communicationId(), "RETRY_PENDING");
        }
        return false;
    }

    private void fail(OutboxRow row, String errorCode, String safeMessage) {
        jdbcTemplate.update(
                "UPDATE communication_outbox SET status='FAILED', completed_at=NOW(),"
                        + " last_error_code=:code, last_error_safe=:safe WHERE id=:id",
                new MapSqlParameterSource().addValue("code", errorCode).addValue("safe", safeMessage).addValue("id", row.id()));
        markMessageStatus(row.communicationId(), "FAILED");
    }

    private void complete(long outboxId) {
        jdbcTemplate.update(
                "UPDATE communication_outbox SET status='COMPLETED', completed_at=NOW(),"
                        + " last_error_code=NULL, last_error_safe=NULL WHERE id=:id",
                new MapSqlParameterSource("id", outboxId));
    }

    private void markMessageStatus(long communicationId, String status) {
        jdbcTemplate.update(
                "UPDATE communication_messages SET status=:status WHERE id=:id AND status NOT IN ('DELIVERED','CANCELLED')",
                new MapSqlParameterSource().addValue("status", status).addValue("id", communicationId));
    }

    private void recordOutcome(String providerCode, String channel, boolean success) {
        if (providerCode == null || providerCode.isBlank()) return;
        try {
            healthService.record(providerCode, channel, success);
        } catch (Exception ex) {
            logger.log(Level.WARNING,
                    "Provider health recording failed for " + providerCode + "/" + channel + ": " + ex.getMessage());
        }
    }

    private Map<String, Object> dispatchMetadata(long communicationId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT JSON_UNQUOTE(JSON_EXTRACT(metadata_json, '$.senderId')) sender_id,"
                        + " JSON_UNQUOTE(JSON_EXTRACT(metadata_json, '$.countryCode')) country_code,"
                        + " JSON_UNQUOTE(JSON_EXTRACT(metadata_json, '$.currencyCode')) currency_code,"
                        + " JSON_UNQUOTE(JSON_EXTRACT(metadata_json, '$.routingStrategy')) routing_strategy,"
                        + " JSON_UNQUOTE(JSON_EXTRACT(metadata_json, '$.requireDeliveryReceipts')) require_dlr,"
                        + " JSON_UNQUOTE(JSON_EXTRACT(metadata_json, '$.requireInbound')) require_inbound"
                        + " FROM communication_messages WHERE id=:id",
                new MapSqlParameterSource("id", communicationId));
        if (rows.isEmpty()) return Map.of();
        Map<String, Object> source = rows.get(0);
        Map<String, Object> metadata = new LinkedHashMap<>();
        putText(metadata, "senderId", source.get("sender_id"));
        putText(metadata, "countryCode", source.get("country_code"));
        putText(metadata, "currencyCode", source.get("currency_code"));
        putText(metadata, "routingStrategy", source.get("routing_strategy"));
        putBoolean(metadata, "requireDeliveryReceipts", source.get("require_dlr"));
        putBoolean(metadata, "requireInbound", source.get("require_inbound"));
        return metadata;
    }

    private void putText(Map<String, Object> target, String key, Object value) {
        if (value != null && !String.valueOf(value).isBlank() && !"null".equalsIgnoreCase(String.valueOf(value))) {
            target.put(key, String.valueOf(value));
        }
    }

    private void putBoolean(Map<String, Object> target, String key, Object value) {
        if (value != null && !"null".equalsIgnoreCase(String.valueOf(value))) {
            target.put(key, Boolean.parseBoolean(String.valueOf(value)));
        }
    }

    private String resolveContent(OutboxRow row) {
        List<String> bodies = jdbcTemplate.query(
                "SELECT JSON_UNQUOTE(JSON_EXTRACT(metadata_json, '$.body')) FROM communication_messages WHERE id=:id",
                new MapSqlParameterSource("id", row.communicationId()), (rs, rowNum) -> rs.getString(1));
        String fromMetadata = bodies.isEmpty() ? null : bodies.get(0);
        if (fromMetadata != null && !fromMetadata.isBlank()) return fromMetadata;
        if (row.templateKey() == null || row.templateKey().isBlank()) return null;
        try {
            var rendered = new net.citotech.cito.communication.template.TemplateService(jdbcTemplate)
                    .render(row.templateKey(), row.selectedChannel(), Map.of());
            return rendered.body();
        } catch (Exception ex) {
            logger.log(Level.WARNING, "Template render failed for communication " + row.communicationId() + ": " + ex.getMessage());
            return null;
        }
    }

    private String subjectFor(OutboxRow row) {
        if (row.templateKey() == null || row.templateKey().isBlank()) return "";
        try {
            var rendered = new net.citotech.cito.communication.template.TemplateService(jdbcTemplate)
                    .render(row.templateKey(), row.selectedChannel(), Map.of());
            return rendered.subject() == null ? "" : rendered.subject();
        } catch (Exception ex) {
            return "";
        }
    }

    private String firstRequestedChannel(String requestedChannels) {
        if (requestedChannels == null || requestedChannels.isBlank()) return null;
        for (String part : requestedChannels.split(",")) if (!part.isBlank()) return part.trim().toUpperCase();
        return null;
    }

    record OutboxRow(
            long id,
            long communicationId,
            long merchantId,
            String recipientType,
            String recipient,
            String purpose,
            String requestedChannels,
            String selectedChannel,
            String selectedProviderCode,
            String templateKey,
            boolean fallbackEnabled,
            Instant expiresAt,
            int attempts) {}
}
