package net.citotech.cito.communication.sms;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import net.citotech.cito.Common;
import net.citotech.cito.Model.HttpRequestResponse;
import net.citotech.cito.communication.credentials.CommunicationCredentialStore;
import org.springframework.stereotype.Component;

/**
 * SMSMobilo public send contract, https://smsmobilo.com/#api (2026-09-10). Status-query and
 * callback contracts require the provider's authenticated API documentation.
 */
@Component
public class SmsMobiloSmsGatewayAdapter implements SmsGatewayAdapter {
    static final String SEND_URL = "https://smsmobilo.com/api/v1/send";
    private final CommunicationCredentialStore credentials;
    private final ObjectMapper mapper;
    private String runtimeApiKey = "";

    public SmsMobiloSmsGatewayAdapter(
            CommunicationCredentialStore credentials, ObjectMapper mapper) {
        this.credentials = credentials;
        this.mapper = mapper;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SmsMobiloSmsGatewayAdapter(
            CommunicationCredentialStore credentials,
            ObjectMapper mapper,
            @org.springframework.beans.factory.annotation.Value("${SMSMOBILO_API_KEY:}")
                    String runtimeApiKey) {
        this(credentials, mapper);
        this.runtimeApiKey = runtimeApiKey;
    }

    public boolean isConfigured() {
        if (runtimeApiKey != null && !runtimeApiKey.isBlank()) return true;
        try {
            String key = credentials.credential("SMSMOBILO_SMS", "api_key");
            return key != null && !key.isBlank();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    @Override
    public SmsSendResult send(SmsSendRequest request) {
        final String key;
        try {
            key =
                    runtimeApiKey == null || runtimeApiKey.isBlank()
                            ? credentials.credential("SMSMOBILO_SMS", "api_key")
                            : runtimeApiKey;
        } catch (RuntimeException unavailable) {
            return SmsSendResult.rejected("SMSMobilo encrypted API key is unavailable", "");
        }
        if (key == null || key.isBlank()) {
            return SmsSendResult.rejected("SMSMobilo encrypted API key is unavailable", "");
        }
        final String body;
        try {
            body = mapper.writeValueAsString(payload(request));
        } catch (Exception invalid) {
            return SmsSendResult.rejected("Invalid SMSMobilo message", "");
        }
        // Never include HttpRequestResponse.toString(): it contains request authentication headers.
        return normalize(
                Common.doHttpRequest(
                        "POST",
                        SEND_URL,
                        body,
                        Map.of(
                                "X-API-Key",
                                key,
                                "Content-Type",
                                "application/json",
                                "Accept",
                                "application/json")));
    }

    static Map<String, Object> payload(SmsSendRequest request) {
        String phone = request.recipients() == null ? "" : request.recipients().trim();
        if (!phone.matches("\\+?[0-9]{7,15}")
                || request.content() == null
                || request.content().isBlank()) {
            throw new IllegalArgumentException(
                    "One international recipient and message are required");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("to", phone);
        body.put("message", request.content());
        if (request.senderId() != null && !request.senderId().isBlank())
            body.put("from", request.senderId().trim());
        return body;
    }

    SmsSendResult normalize(HttpRequestResponse response) {
        if (response == null || response.getStatusCode() == 0) {
            return SmsSendResult.unknown(
                    "SMSMobilo transport outcome unavailable; reconcile before retry");
        }
        int code = response.getStatusCode();
        String trace = "SMSMobilo HTTP " + code;
        if (code >= 200 && code < 300) {
            try {
                var root = mapper.readTree(response.getResponse());
                var data = root.path("data");
                String id = data.path("message_id").asText("");
                String state = data.path("status").asText("");
                if ("ok".equals(root.path("status").asText())
                        && !id.isBlank()
                        && java.util.List.of("queued", "sent", "delivered").contains(state)) {
                    // API acceptance is SENT; only correlated delivery evidence may set DELIVERED.
                    return SmsSendResult.sent(trace, "", id);
                }
                return SmsSendResult.unknown(
                        "SMSMobilo did not confirm message acceptance; reconcile before retry");
            } catch (Exception malformed) {
                return SmsSendResult.unknown(
                        "SMSMobilo acceptance could not be verified; reconcile before retry");
            }
        }
        if (code == 429) return SmsSendResult.failed(trace, "");
        if (code == 408 || code >= 500)
            return SmsSendResult.unknown(trace + "; reconcile before retry");
        return SmsSendResult.rejected(trace, "");
    }
}
