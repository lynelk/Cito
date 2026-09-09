package net.citotech.cito.communication.sms;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Provider-neutral SMS callback surface. Each approved sender identity owns an unguessable token,
 * so provider callbacks never depend on a logged-in merchant session.
 */
@RestController
@RequestMapping("/webhooks/communication/sms")
public class SmsWebhookController {

    private final SmsConversationService conversationService;
    private final ObjectMapper objectMapper;

    public SmsWebhookController(SmsConversationService conversationService, ObjectMapper objectMapper) {
        this.conversationService = conversationService;
        this.objectMapper = objectMapper;
    }

    @PostMapping(path = "/{token}/inbound", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> inbound(
            @PathVariable("token") String token,
            HttpServletRequest request) {
        try {
            Map<String, Object> payload = payload(request);
            return ResponseEntity.accepted().body(conversationService.receiveInbound(
                    token,
                    first(payload, "providerCode", "provider", "gateway"),
                    first(payload, "providerMessageId", "messageId", "id", "MessageSid", "smsMessageId"),
                    first(payload, "from", "From", "sender", "phone", "source", "msisdn"),
                    first(payload, "to", "To", "recipient", "destination", "shortCode"),
                    first(payload, "body", "Body", "text", "message", "content"),
                    payload));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("accepted", false, "code", "INVALID_SMS_INBOUND", "message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("accepted", false, "code", "SMS_INBOUND_UNAVAILABLE"));
        }
    }

    @PostMapping(path = "/{token}/delivery", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> delivery(
            @PathVariable("token") String token,
            HttpServletRequest request) {
        try {
            Map<String, Object> payload = payload(request);
            return ResponseEntity.accepted().body(conversationService.receiveDeliveryReceipt(
                    token,
                    first(payload, "providerCode", "provider", "gateway"),
                    first(payload, "providerMessageId", "messageId", "id", "MessageSid", "smsMessageId"),
                    first(payload, "status", "MessageStatus", "deliveryStatus", "state"),
                    payload));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("accepted", false, "code", "INVALID_SMS_DELIVERY_RECEIPT", "message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("accepted", false, "code", "SMS_DELIVERY_RECEIPT_UNAVAILABLE"));
        }
    }

    private Map<String, Object> payload(HttpServletRequest request) {
        Map<String, Object> values = new LinkedHashMap<>();
        request.getParameterMap().forEach((key, value) -> {
            if (value != null && value.length > 0) values.put(key, value[0]);
        });
        String contentType = request.getContentType();
        if (contentType != null && contentType.toLowerCase().contains("json")) {
            try {
                Map<String, Object> json = objectMapper.readValue(
                        request.getInputStream(), new TypeReference<Map<String, Object>>() {});
                if (json != null) values.putAll(json);
            } catch (Exception ignored) {
                // Parameter-based provider callbacks remain valid even when an optional body is absent.
            }
        }
        return values;
    }

    private String first(Map<String, Object> payload, String... keys) {
        for (String key : keys) {
            Object value = payload.get(key);
            if (value != null && !String.valueOf(value).isBlank()) return String.valueOf(value).trim();
        }
        return null;
    }
}
