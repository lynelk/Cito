package net.citotech.cito.communication.sms;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.citotech.cito.Common;
import net.citotech.cito.communication.delivery.DeliveryLogRepository;
import net.citotech.cito.communication.delivery.DeliveryStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Two-way SMS inbox, STOP/START suppression handling and delivery-receipt correlation. */
@Service
public class SmsConversationService {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final DeliveryLogRepository deliveryLogRepository;
    private final ObjectMapper objectMapper;

    public SmsConversationService(
            NamedParameterJdbcTemplate jdbcTemplate,
            DeliveryLogRepository deliveryLogRepository,
            ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.deliveryLogRepository = deliveryLogRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Map<String, Object> receiveInbound(
            String inboundToken,
            String providerCode,
            String providerMessageId,
            String from,
            String to,
            String body,
            Map<String, Object> payload) {
        Sender sender = requireSender(inboundToken, true);
        String normalizedProvider = chooseProvider(providerCode, sender.providerCode());
        String normalizedFrom = normalizePhone(from);
        if (blank(body)) throw new IllegalArgumentException("Inbound SMS body is required.");
        String eventKey =
                eventKey("INBOUND", normalizedProvider, providerMessageId, normalizedFrom, body);
        Map<String, Object> duplicate = existingWebhook(eventKey);
        if (duplicate != null) return duplicate;

        Long contactId = findContact(sender.merchantId(), normalizedFrom);
        long conversationId =
                findOrCreateConversation(sender, normalizedProvider, normalizedFrom, contactId);
        String messagePublicId = "MSG-" + Common.randomUrlSafeToken(18);
        String payloadJson = json(payload == null ? Map.of() : payload);
        try {
            jdbcTemplate.update(
                    "INSERT INTO communication_conversation_messages"
                            + " (public_id,conversation_id,merchant_id,direction,provider_code,provider_message_id,"
                            + " from_address,to_address,body,status,raw_metadata_json) VALUES"
                            + " (:public_id,:conversation,:merchant,'INBOUND',:provider,:provider_message_id,"
                            + " :from_address,:to_address,:body,'RECEIVED',:metadata)",
                    new MapSqlParameterSource()
                            .addValue("public_id", messagePublicId)
                            .addValue("conversation", conversationId)
                            .addValue("merchant", sender.merchantId())
                            .addValue("provider", normalizedProvider)
                            .addValue("provider_message_id", trim(providerMessageId))
                            .addValue("from_address", normalizedFrom)
                            .addValue("to_address", blank(to) ? sender.senderId() : to.trim())
                            .addValue("body", body)
                            .addValue("metadata", payloadJson));
        } catch (DuplicateKeyException duplicateKeyException) {
            Map<String, Object> existing =
                    existingProviderMessage(normalizedProvider, providerMessageId);
            if (existing != null) return existing;
            throw duplicateKeyException;
        }

        jdbcTemplate.update(
                "UPDATE communication_conversations SET unread_count=unread_count+1,last_message_at=NOW(),"
                        + " status='OPEN' WHERE id=:id",
                new MapSqlParameterSource("id", conversationId));
        applyOptKeyword(sender.merchantId(), normalizedFrom, body);
        recordWebhook(
                eventKey, sender, normalizedProvider, "INBOUND", providerMessageId, payloadJson);

        return Map.of(
                "accepted",
                true,
                "conversationId",
                conversationPublicId(conversationId),
                "messageId",
                messagePublicId,
                "direction",
                "INBOUND");
    }

    @Transactional
    public Map<String, Object> receiveDeliveryReceipt(
            String inboundToken,
            String providerCode,
            String providerMessageId,
            String providerStatus,
            Map<String, Object> payload) {
        Sender sender = requireSender(inboundToken, false);
        if (blank(providerMessageId))
            throw new IllegalArgumentException("providerMessageId is required.");
        String normalizedProvider = chooseProvider(providerCode, sender.providerCode());
        DeliveryStatus status = normalizeDeliveryStatus(providerStatus);
        String eventKey = eventKey("DLR", normalizedProvider, providerMessageId, status.name(), "");
        Map<String, Object> duplicate = existingWebhook(eventKey);
        if (duplicate != null) return duplicate;

        String payloadJson = json(payload == null ? Map.of() : payload);
        int updated =
                deliveryLogRepository.updateByProviderMessageId(
                        normalizedProvider,
                        providerMessageId,
                        status,
                        "Provider delivery receipt: " + status,
                        payloadJson);
        if (updated > 0) {
            jdbcTemplate.update(
                    "UPDATE communication_messages m JOIN communication_message_deliveries d"
                            + " ON d.communication_id=m.id SET m.status=:status"
                            + " WHERE d.provider_code=:provider AND d.provider_message_id=:provider_message_id"
                            + " AND m.status<>'CANCELLED'",
                    new MapSqlParameterSource()
                            .addValue("status", status.name())
                            .addValue("provider", normalizedProvider)
                            .addValue("provider_message_id", providerMessageId.trim()));
        }
        recordWebhook(eventKey, sender, normalizedProvider, "DLR", providerMessageId, payloadJson);
        return Map.of("accepted", true, "matchedDeliveries", updated, "status", status.name());
    }

    public List<Map<String, Object>> conversations(long merchantId, int limit) {
        return jdbcTemplate.queryForList(
                "SELECT c.public_id conversationId,c.phone_e164 phone,c.status,c.unread_count unreadCount,"
                        + " c.provider_code provider,c.last_message_at lastMessageAt,ct.display_name contactName,"
                        + " s.sender_id senderId FROM communication_conversations c"
                        + " LEFT JOIN communication_contacts ct ON ct.id=c.contact_id"
                        + " LEFT JOIN communication_sender_identities s ON s.id=c.sender_identity_id"
                        + " WHERE c.merchant_id=:merchant ORDER BY c.last_message_at DESC LIMIT :limit",
                new MapSqlParameterSource()
                        .addValue("merchant", merchantId)
                        .addValue("limit", Math.max(1, Math.min(limit, 200))));
    }

    public List<Map<String, Object>> messages(
            long merchantId, String conversationPublicId, int limit) {
        List<Long> ids =
                jdbcTemplate.query(
                        "SELECT id FROM communication_conversations WHERE merchant_id=:merchant AND public_id=:public_id LIMIT 1",
                        new MapSqlParameterSource()
                                .addValue("merchant", merchantId)
                                .addValue("public_id", conversationPublicId),
                        (rs, rowNum) -> rs.getLong(1));
        if (ids.isEmpty()) throw new IllegalArgumentException("Conversation was not found.");
        jdbcTemplate.update(
                "UPDATE communication_conversations SET unread_count=0 WHERE id=:id",
                new MapSqlParameterSource("id", ids.get(0)));
        return jdbcTemplate.queryForList(
                "SELECT public_id messageId,direction,provider_code provider,provider_message_id providerMessageId,"
                        + " message_reference messageReference,from_address fromAddress,to_address toAddress,"
                        + " body,status,occurred_at occurredAt FROM communication_conversation_messages"
                        + " WHERE conversation_id=:conversation ORDER BY occurred_at DESC,id DESC LIMIT :limit",
                new MapSqlParameterSource()
                        .addValue("conversation", ids.get(0))
                        .addValue("limit", Math.max(1, Math.min(limit, 500))));
    }

    private Sender requireSender(String inboundToken, boolean requireTwoWay) {
        if (blank(inboundToken) || inboundToken.length() < 24) {
            throw new IllegalArgumentException("Invalid SMS webhook token.");
        }
        List<Sender> rows =
                jdbcTemplate.query(
                        "SELECT id,merchant_id,sender_id,provider_code,two_way_capable FROM communication_sender_identities"
                                + " WHERE inbound_token=:token AND approval_status='APPROVED' LIMIT 1",
                        new MapSqlParameterSource("token", inboundToken.trim()),
                        (rs, rowNum) ->
                                new Sender(
                                        rs.getLong("id"),
                                        rs.getLong("merchant_id"),
                                        rs.getString("sender_id"),
                                        rs.getString("provider_code"),
                                        "Y".equals(rs.getString("two_way_capable"))));
        if (rows.isEmpty()) throw new IllegalArgumentException("SMS webhook token is not active.");
        Sender sender = rows.get(0);
        if (requireTwoWay && !sender.twoWayCapable()) {
            throw new IllegalArgumentException("Sender identity is not enabled for two-way SMS.");
        }
        return sender;
    }

    private long findOrCreateConversation(
            Sender sender, String provider, String phone, Long contactId) {
        List<Long> rows =
                jdbcTemplate.query(
                        "SELECT id FROM communication_conversations WHERE merchant_id=:merchant AND phone_e164=:phone"
                                + " AND sender_identity_id=:sender AND status='OPEN' ORDER BY id DESC LIMIT 1",
                        new MapSqlParameterSource()
                                .addValue("merchant", sender.merchantId())
                                .addValue("phone", phone)
                                .addValue("sender", sender.id()),
                        (rs, rowNum) -> rs.getLong(1));
        if (!rows.isEmpty()) return rows.get(0);
        String publicId = "CONV-" + Common.randomUrlSafeToken(18);
        jdbcTemplate.update(
                "INSERT INTO communication_conversations"
                        + " (public_id,merchant_id,contact_id,phone_e164,sender_identity_id,provider_code,status)"
                        + " VALUES (:public_id,:merchant,:contact,:phone,:sender,:provider,'OPEN')",
                new MapSqlParameterSource()
                        .addValue("public_id", publicId)
                        .addValue("merchant", sender.merchantId())
                        .addValue("contact", contactId)
                        .addValue("phone", phone)
                        .addValue("sender", sender.id())
                        .addValue("provider", provider));
        return jdbcTemplate.queryForObject(
                "SELECT id FROM communication_conversations WHERE public_id=:public_id",
                new MapSqlParameterSource("public_id", publicId),
                Long.class);
    }

    private Long findContact(long merchantId, String phone) {
        List<Long> rows =
                jdbcTemplate.query(
                        "SELECT id FROM communication_contacts WHERE merchant_id=:merchant AND phone_e164=:phone"
                                + " AND active_flag='Y' LIMIT 1",
                        new MapSqlParameterSource()
                                .addValue("merchant", merchantId)
                                .addValue("phone", phone),
                        (rs, rowNum) -> rs.getLong(1));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void applyOptKeyword(long merchantId, String phone, String body) {
        String keyword = body == null ? "" : body.trim().toUpperCase();
        if (List.of("STOP", "STOPALL", "UNSUBSCRIBE", "CANCEL", "END", "QUIT").contains(keyword)) {
            jdbcTemplate.update(
                    "INSERT INTO communication_sms_suppressions"
                            + " (merchant_id,phone_e164,scope,reason,source,active_flag)"
                            + " VALUES (:merchant,:phone,'MARKETING','OPT_OUT','INBOUND_SMS','Y')"
                            + " ON DUPLICATE KEY UPDATE active_flag='Y',reason='OPT_OUT',source='INBOUND_SMS',updated_at=NOW()",
                    new MapSqlParameterSource()
                            .addValue("merchant", merchantId)
                            .addValue("phone", phone));
        } else if (List.of("START", "YES", "UNSTOP").contains(keyword)) {
            jdbcTemplate.update(
                    "UPDATE communication_sms_suppressions SET active_flag='N',reason='OPT_IN',updated_at=NOW()"
                            + " WHERE merchant_id=:merchant AND phone_e164=:phone AND scope='MARKETING'",
                    new MapSqlParameterSource()
                            .addValue("merchant", merchantId)
                            .addValue("phone", phone));
        }
    }

    private Map<String, Object> existingWebhook(String eventKey) {
        List<Map<String, Object>> rows =
                jdbcTemplate.queryForList(
                        "SELECT event_key,event_type,provider_message_id,created_at FROM communication_sms_webhook_events"
                                + " WHERE event_key=:event_key LIMIT 1",
                        new MapSqlParameterSource("event_key", eventKey));
        if (rows.isEmpty()) return null;
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("accepted", true);
        response.put("duplicate", true);
        response.putAll(rows.get(0));
        return response;
    }

    private Map<String, Object> existingProviderMessage(String provider, String providerMessageId) {
        if (blank(providerMessageId)) return null;
        List<Map<String, Object>> rows =
                jdbcTemplate.queryForList(
                        "SELECT c.public_id conversationId,m.public_id messageId,m.direction,m.status"
                                + " FROM communication_conversation_messages m JOIN communication_conversations c ON c.id=m.conversation_id"
                                + " WHERE m.provider_code=:provider AND m.provider_message_id=:provider_message_id LIMIT 1",
                        new MapSqlParameterSource()
                                .addValue("provider", provider)
                                .addValue("provider_message_id", providerMessageId));
        if (rows.isEmpty()) return null;
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("accepted", true);
        response.put("duplicate", true);
        response.putAll(rows.get(0));
        return response;
    }

    private void recordWebhook(
            String eventKey,
            Sender sender,
            String provider,
            String eventType,
            String providerMessageId,
            String payloadJson) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO communication_sms_webhook_events"
                            + " (event_key,merchant_id,sender_identity_id,provider_code,event_type,provider_message_id,payload_json)"
                            + " VALUES (:event_key,:merchant,:sender,:provider,:event_type,:provider_message_id,:payload)",
                    new MapSqlParameterSource()
                            .addValue("event_key", eventKey)
                            .addValue("merchant", sender.merchantId())
                            .addValue("sender", sender.id())
                            .addValue("provider", provider)
                            .addValue("event_type", eventType)
                            .addValue("provider_message_id", trim(providerMessageId))
                            .addValue("payload", payloadJson));
        } catch (DuplicateKeyException ignored) {
            // Provider retries are expected and deliberately idempotent.
        }
    }

    private DeliveryStatus normalizeDeliveryStatus(String status) {
        String value = blank(status) ? "UNKNOWN" : status.trim().toUpperCase();
        if (List.of("DELIVERED", "DELIVERY_SUCCESS", "SUCCESS").contains(value))
            return DeliveryStatus.DELIVERED;
        if (List.of("SENT", "QUEUED", "ACCEPTED", "SUBMITTED").contains(value))
            return DeliveryStatus.SENT;
        if (List.of("REJECTED", "BLOCKED").contains(value)) return DeliveryStatus.REJECTED;
        if (List.of("FAILED", "UNDELIVERED", "EXPIRED", "ERROR").contains(value))
            return DeliveryStatus.FAILED;
        return DeliveryStatus.SENT;
    }

    private String chooseProvider(String requested, String configured) {
        String provider = blank(requested) ? configured : requested;
        if (blank(provider))
            throw new IllegalArgumentException(
                    "providerCode is required for this sender identity.");
        if (!blank(configured) && !configured.equalsIgnoreCase(provider)) {
            throw new IllegalArgumentException("Provider does not match sender identity.");
        }
        return provider.trim().toUpperCase();
    }

    private String normalizePhone(String value) {
        if (blank(value)) throw new IllegalArgumentException("Inbound sender phone is required.");
        String normalized = value.trim().replaceAll("[\\s()-]", "");
        if (!normalized.matches("\\+?[0-9]{7,15}"))
            throw new IllegalArgumentException("Invalid inbound sender phone.");
        return normalized;
    }

    private String conversationPublicId(long conversationId) {
        return jdbcTemplate.queryForObject(
                "SELECT public_id FROM communication_conversations WHERE id=:id",
                new MapSqlParameterSource("id", conversationId),
                String.class);
    }

    private String eventKey(
            String type, String provider, String providerMessageId, String a, String b) {
        String providerId = trim(providerMessageId);
        if (!blank(providerId)) return type + ":" + provider + ":" + providerId;
        return type
                + ":"
                + provider
                + ":"
                + Integer.toHexString((String.valueOf(a) + "|" + String.valueOf(b)).hashCode());
    }

    private String json(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Webhook payload could not be recorded.");
        }
    }

    private String trim(String value) {
        return blank(value) ? null : value.trim();
    }

    private boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private record Sender(
            long id,
            long merchantId,
            String senderId,
            String providerCode,
            boolean twoWayCapable) {}
}
