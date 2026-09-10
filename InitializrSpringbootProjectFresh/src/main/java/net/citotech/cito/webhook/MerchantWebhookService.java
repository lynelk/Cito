package net.citotech.cito.webhook;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import net.citotech.cito.Common;
import net.citotech.cito.Model.HttpRequestResponse;
import net.citotech.cito.gateway.PaymentGatewayException;
import net.citotech.cito.merchant.MerchantChannelCryptoService;
import net.citotech.cito.security.CanonicalRequestSigner;
import net.citotech.cito.webhook.WebhookEventCatalog.EventDefinition;
import org.json.JSONObject;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MerchantWebhookService {
    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final MerchantChannelCryptoService cryptoService;
    private net.citotech.cito.communication.notification.NotificationOrchestrator notifications;
    private final SecureRandom secureRandom = new SecureRandom();

    public MerchantWebhookService(
            NamedParameterJdbcTemplate jdbcTemplate, MerchantChannelCryptoService cryptoService) {
        this.jdbcTemplate = jdbcTemplate;
        this.cryptoService = cryptoService;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public MerchantWebhookService(
            NamedParameterJdbcTemplate jdbcTemplate,
            MerchantChannelCryptoService cryptoService,
            net.citotech.cito.communication.notification.NotificationOrchestrator notifications) {
        this(jdbcTemplate, cryptoService);
        this.notifications = notifications;
    }

    @Transactional
    public Map<String, Object> registerEndpoint(
            long merchantId, String eventType, String endpointUrl, String actor) {
        if (merchantId <= 0 || blank(eventType) || blank(endpointUrl)) {
            throw new PaymentGatewayException(
                    "merchantId, eventType, and endpointUrl are required");
        }
        if (!WebhookEventCatalog.isKnown(eventType)) {
            throw new PaymentGatewayException(
                    "Unknown webhook event type: "
                            + eventType
                            + ". See GET /api/v2/webhooks/events for the catalog.");
        }
        String secret = generateSecret();
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("merchant_id", merchantId);
        p.addValue("event_type", normalizeEvent(eventType));
        p.addValue("endpoint_url", endpointUrl.trim());
        p.addValue("secret_hash", CanonicalRequestSigner.sha256Hex(secret));
        p.addValue("secret_value", cryptoService.encrypt(secret));
        p.addValue("actor", blank(actor) ? "system" : actor.trim());
        jdbcTemplate.update(
                "INSERT INTO merchant_webhook_endpoints "
                        + "(merchant_id, event_type, endpoint_url, secret_hash, secret_value, endpoint_status) "
                        + "VALUES (:merchant_id, :event_type, :endpoint_url, :secret_hash, :secret_value, 'ACTIVE') "
                        + "ON DUPLICATE KEY UPDATE endpoint_url=:endpoint_url, secret_hash=:secret_hash, "
                        + "secret_value=:secret_value, endpoint_status='ACTIVE'",
                p);
        return Map.of("code", "000", "eventType", normalizeEvent(eventType), "secret", secret);
    }

    public List<Map<String, Object>> listEndpoints(long merchantId) {
        return jdbcTemplate.queryForList(
                "SELECT id, merchant_id, event_type, endpoint_url, secret_hash, endpoint_status, created_at, updated_at "
                        + "FROM merchant_webhook_endpoints WHERE merchant_id=:merchant_id ORDER BY event_type",
                new MapSqlParameterSource("merchant_id", merchantId));
    }

    @Transactional
    public Map<String, Object> rotateSecret(long endpointId) {
        String secret = generateSecret();
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("id", endpointId);
        p.addValue("secret_hash", CanonicalRequestSigner.sha256Hex(secret));
        p.addValue("secret_value", cryptoService.encrypt(secret));
        int updated =
                jdbcTemplate.update(
                        "UPDATE merchant_webhook_endpoints SET secret_hash=:secret_hash, secret_value=:secret_value "
                                + "WHERE id=:id AND endpoint_status='ACTIVE'",
                        p);
        if (updated == 0) {
            throw new PaymentGatewayException("Webhook endpoint was not found");
        }
        return Map.of("code", "000", "secret", secret);
    }

    /**
     * Audit N6: merchant self-service equivalent of {@link #rotateSecret(long)}. The admin-facing
     * overload above trusts the caller (an authenticated admin) to pass any endpointId; this one is
     * called from a merchant's own session, so it scopes the UPDATE to the merchant's own row - a
     * merchant supplying another merchant's endpointId simply finds no matching row rather than
     * rotating someone else's webhook secret.
     */
    @Transactional
    public Map<String, Object> rotateSecret(long merchantId, long endpointId) {
        String secret = generateSecret();
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("id", endpointId);
        p.addValue("merchant_id", merchantId);
        p.addValue("secret_hash", CanonicalRequestSigner.sha256Hex(secret));
        p.addValue("secret_value", cryptoService.encrypt(secret));
        int updated =
                jdbcTemplate.update(
                        "UPDATE merchant_webhook_endpoints SET secret_hash=:secret_hash, secret_value=:secret_value "
                                + "WHERE id=:id AND merchant_id=:merchant_id AND endpoint_status='ACTIVE'",
                        p);
        if (updated == 0) {
            throw new PaymentGatewayException("Webhook endpoint was not found");
        }
        return Map.of("code", "000", "secret", secret);
    }

    @Transactional
    public int enqueue(
            long merchantId, String eventType, String eventReference, String payloadJson) {
        EventDefinition definition =
                WebhookEventCatalog.lookup(eventType)
                        .orElseThrow(
                                () ->
                                        new PaymentGatewayException(
                                                "Unknown webhook event type: " + eventType));
        String envelopedPayload = envelope(definition, payloadJson);
        // Capture once even when no external webhook endpoint is subscribed. Synthetic probes do
        // not send SMS.
        if (notifications != null
                && !new JSONObject(envelopedPayload).optBoolean("test", false)
                && !"SANDBOX"
                        .equalsIgnoreCase(new JSONObject(envelopedPayload).optString("environment"))
                && eventReference != null
                && !eventReference.startsWith("test-")) {
            String identity =
                    CanonicalRequestSigner.sha256Hex(
                            normalizeEvent(eventType) + ":" + eventReference);
            notifications.record(merchantId, identity, eventType, identity);
        }
        List<EndpointRow> endpoints = activeEndpoints(merchantId, eventType);
        int queued = 0;
        for (EndpointRow endpoint : endpoints) {
            MapSqlParameterSource p = new MapSqlParameterSource();
            p.addValue("merchant_id", merchantId);
            p.addValue("endpoint_id", endpoint.id());
            p.addValue("event_type", normalizeEvent(eventType));
            p.addValue("event_reference", eventReference);
            p.addValue("payload_json", envelopedPayload);
            queued +=
                    jdbcTemplate.update(
                            "INSERT IGNORE INTO merchant_webhook_deliveries "
                                    + "(merchant_id, endpoint_id, event_type, event_reference, payload_json, delivery_status, next_attempt_at) "
                                    + "VALUES (:merchant_id, :endpoint_id, :event_type, :event_reference, :payload_json, 'PENDING', CURRENT_TIMESTAMP)",
                            p);
        }
        return queued;
    }

    /**
     * Merchant callback verification (audit item): queues a synthetic event for the merchant's
     * active endpoint(s) so a callback URL can be verified before production activation. Returns
     * the number of deliveries queued (0 = no active endpoint for this event type). The payload is
     * a clearly-marked TEST event so a merchant receiver never mistakes it for real money.
     */
    @Transactional
    public int testCallback(long merchantId, String eventType) {
        if (merchantId <= 0 || blank(eventType)) {
            throw new PaymentGatewayException("merchantId and eventType are required");
        }
        if (!WebhookEventCatalog.isKnown(eventType)) {
            throw new PaymentGatewayException(
                    "Unknown webhook event type: "
                            + eventType
                            + ". See GET /api/v2/webhooks/events for the catalog.");
        }
        String reference = "test-callback-" + Common.generateUuid();
        String payload =
                "{"
                        + "\"eventType\":\""
                        + normalizeEvent(eventType)
                        + "\","
                        + "\"merchantNumber\":\""
                        + merchantNumber(merchantId)
                        + "\","
                        + "\"reference\":\""
                        + reference
                        + "\","
                        + "\"transactionId\":\"test-callback\","
                        + "\"status\":\"TEST\","
                        + "\"amount\":\"0\","
                        + "\"currency\":\"UGX\""
                        + "}";
        return enqueue(merchantId, eventType, reference, payload);
    }

    private String merchantNumber(long merchantId) {
        try {
            List<String> rows =
                    jdbcTemplate.query(
                            "SELECT account_number FROM merchants WHERE id=:id LIMIT 1",
                            new MapSqlParameterSource("id", merchantId),
                            (rs, rowNum) -> rs.getString("account_number"));
            return rows.isEmpty() ? "" : rows.get(0);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Adds the versioned envelope fields (eventId/eventVersion/createdAt) from the catalog on top
     * of the caller's payload, additively - existing fields the caller already set (eventType,
     * merchantNumber, etc.) are left untouched so a merchant reading only those is unaffected.
     */
    private String envelope(EventDefinition definition, String payloadJson) {
        JSONObject obj = new JSONObject(payloadJson);
        obj.put("eventId", Common.generateUuid());
        obj.put("eventVersion", definition.version());
        obj.put("createdAt", Instant.now().toString());
        return obj.toString();
    }

    @Transactional
    public int replay(long deliveryId) {
        return jdbcTemplate.update(
                "UPDATE merchant_webhook_deliveries SET delivery_status='PENDING', next_attempt_at=CURRENT_TIMESTAMP "
                        + "WHERE id=:id AND delivery_status IN ('FAILED','DELIVERED')",
                new MapSqlParameterSource("id", deliveryId));
    }

    /**
     * Audit N6: merchant self-service equivalent of {@link #replay(long)} - see the matching note
     * on the merchant-scoped {@code rotateSecret} overload above for why this needs its own
     * merchant_id-scoped query rather than reusing the admin-facing one.
     */
    @Transactional
    public int replay(long merchantId, long deliveryId) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("id", deliveryId);
        p.addValue("merchant_id", merchantId);
        return jdbcTemplate.update(
                "UPDATE merchant_webhook_deliveries SET delivery_status='PENDING', next_attempt_at=CURRENT_TIMESTAMP "
                        + "WHERE id=:id AND merchant_id=:merchant_id AND delivery_status IN ('FAILED','DELIVERED')",
                p);
    }

    /**
     * Audit N6: the merchant-facing webhook delivery log (self-service replay + rotation UI). The
     * schema (V14) already carried a merchant_id column and index on merchant_webhook_deliveries
     * specifically for this query; only the service method and endpoint were missing.
     */
    public List<Map<String, Object>> listDeliveries(long merchantId, int limit) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("merchant_id", merchantId);
        p.addValue("limit", Math.max(1, Math.min(limit, 200)));
        return jdbcTemplate.queryForList(
                "SELECT id, endpoint_id, event_type, event_reference, delivery_status, attempt_count, "
                        + "last_http_status, last_response_summary, next_attempt_at, created_at, updated_at "
                        + "FROM merchant_webhook_deliveries WHERE merchant_id=:merchant_id "
                        + "ORDER BY created_at DESC LIMIT :limit",
                p);
    }

    @Transactional
    public int deliverDue(int limit) {
        List<DeliveryRow> deliveries = dueDeliveries(limit);
        int processed = 0;
        for (DeliveryRow delivery : deliveries) {
            deliver(delivery);
            processed++;
        }
        return processed;
    }

    private void deliver(DeliveryRow delivery) {
        try {
            Map<String, String> headers =
                    Map.of(
                            "Content-Type", "application/json",
                            "X-CPay-Event", delivery.eventType(),
                            "X-CPay-Reference", delivery.eventReference(),
                            "X-CPay-Signature",
                                    CanonicalRequestSigner.sha256Hex(
                                            delivery.payloadJson() + "." + delivery.secret()));
            HttpRequestResponse response =
                    Common.doHttpRequest(
                            "POST", delivery.endpointUrl(), delivery.payloadJson(), headers);
            int status = response == null ? 0 : response.getStatusCode();
            boolean ok = status >= 200 && status < 300;
            updateDelivery(
                    delivery.id(),
                    ok ? "DELIVERED" : nextStatus(delivery.attemptCount() + 1),
                    delivery.attemptCount() + 1,
                    status,
                    response == null ? "No response" : response.getResponse());
        } catch (Exception ex) {
            updateDelivery(
                    delivery.id(),
                    nextStatus(delivery.attemptCount() + 1),
                    delivery.attemptCount() + 1,
                    0,
                    ex.getMessage());
        }
    }

    private String nextStatus(int attemptCount) {
        return attemptCount >= 5 ? "FAILED" : "PENDING";
    }

    private void updateDelivery(
            long id, String status, int attempts, int httpStatus, String responseSummary) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("id", id);
        p.addValue("status", status);
        p.addValue("attempts", attempts);
        p.addValue("http_status", httpStatus);
        p.addValue("response", trim(responseSummary));
        p.addValue(
                "next_attempt",
                Timestamp.from(
                        Instant.now().plus(Math.min(60, attempts * 5L), ChronoUnit.MINUTES)));
        jdbcTemplate.update(
                "UPDATE merchant_webhook_deliveries SET delivery_status=:status, attempt_count=:attempts, "
                        + "last_http_status=:http_status, last_response_summary=:response, "
                        + "next_attempt_at=CASE WHEN :status='PENDING' THEN :next_attempt ELSE next_attempt_at END "
                        + "WHERE id=:id",
                p);
    }

    private List<EndpointRow> activeEndpoints(long merchantId, String eventType) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("merchant_id", merchantId);
        p.addValue("event_type", normalizeEvent(eventType));
        return jdbcTemplate.query(
                "SELECT id FROM merchant_webhook_endpoints "
                        + "WHERE merchant_id=:merchant_id AND event_type=:event_type AND endpoint_status='ACTIVE'",
                p,
                (rs, rowNum) -> new EndpointRow(rs.getLong("id")));
    }

    private List<DeliveryRow> dueDeliveries(int limit) {
        MapSqlParameterSource p =
                new MapSqlParameterSource("limit", Math.max(1, Math.min(limit, 100)));
        return jdbcTemplate.query(
                "SELECT d.id, d.event_type, d.event_reference, d.payload_json, d.attempt_count, "
                        + "e.endpoint_url, e.secret_value "
                        + "FROM merchant_webhook_deliveries d "
                        + "JOIN merchant_webhook_endpoints e ON e.id=d.endpoint_id "
                        + "WHERE d.delivery_status='PENDING' AND d.next_attempt_at <= CURRENT_TIMESTAMP "
                        + "AND e.endpoint_status='ACTIVE' ORDER BY d.id ASC LIMIT :limit",
                p,
                (rs, rowNum) ->
                        new DeliveryRow(
                                rs.getLong("id"),
                                rs.getString("event_type"),
                                rs.getString("event_reference"),
                                rs.getString("payload_json"),
                                rs.getInt("attempt_count"),
                                rs.getString("endpoint_url"),
                                decrypt(rs.getString("secret_value"))));
    }

    private String decrypt(String value) {
        try {
            return cryptoService.decrypt(value);
        } catch (IllegalStateException ignored) {
            return value;
        }
    }

    private String generateSecret() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String normalizeEvent(String eventType) {
        return eventType.trim().toLowerCase();
    }

    private String trim(String value) {
        if (value == null) {
            return "";
        }
        return value.length() > 1000 ? value.substring(0, 1000) : value;
    }

    private boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private record EndpointRow(long id) {}

    private record DeliveryRow(
            long id,
            String eventType,
            String eventReference,
            String payloadJson,
            int attemptCount,
            String endpointUrl,
            String secret) {}
}
