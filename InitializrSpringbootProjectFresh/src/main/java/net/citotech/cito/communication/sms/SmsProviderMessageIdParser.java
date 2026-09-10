package net.citotech.cito.communication.sms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Extracts provider correlation ids from successful SMS gateway responses. */
final class SmsProviderMessageIdParser {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private SmsProviderMessageIdParser() {}

    static String twilio(String responseBody) {
        JsonNode root = parse(responseBody);
        return root == null ? null : text(root.get("sid"));
    }

    static String africasTalking(String responseBody) {
        JsonNode root = parse(responseBody);
        if (root == null) return null;
        JsonNode recipients = root.path("SMSMessageData").path("Recipients");
        if (!recipients.isArray() || recipients.isEmpty()) return null;
        return text(recipients.get(0).get("messageId"));
    }

    private static JsonNode parse(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) return null;
        try {
            return OBJECT_MAPPER.readTree(responseBody);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull()) return null;
        String value = node.asText();
        return value == null || value.isBlank() ? null : value.trim();
    }
}
