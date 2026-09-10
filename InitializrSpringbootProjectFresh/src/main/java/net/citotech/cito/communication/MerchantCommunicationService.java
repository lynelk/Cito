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
                        requireInbound);
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
        result.put(
                "selectedProvider",
                sender != null && sender.providerCode() != null
                        ? sender.providerCode()
                        : route.selectedProviderCode());
        result.put("expectedProviderCost", route.expectedProviderCost());
        result.put("currencyCode", route.currencyCode());
        result.put("routingStrategy", route.strategy());
        result.put("explanation", route.explanation());
        result.put("candidates", route.candidates());
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
                Map<String, Object> parsed =
                        objectMapper.readValue(String.valueOf(metadata), Map.class);
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
