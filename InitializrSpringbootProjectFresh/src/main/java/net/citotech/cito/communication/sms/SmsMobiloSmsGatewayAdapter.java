package net.citotech.cito.communication.sms;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.citotech.cito.Common;
import net.citotech.cito.Model.HttpRequestResponse;
import net.citotech.cito.Model.Setting;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * SMSMobilo REST API adapter.
 *
 * <p>SMSMobilo documents a JSON API rooted at https://smsmobilo.com/api/v1, UTF-8 over HTTPS, with
 * Bearer authentication preferred and X-API-Key supported. Endpoint and payload-field names are
 * intentionally settings-driven because they are provider contract details and must not be guessed
 * in production code. The adapter remains unavailable until those settings and an API key are
 * configured.
 */
@Component
public class SmsMobiloSmsGatewayAdapter implements SmsGatewayAdapter {

    private static final Logger logger =
            Logger.getLogger(SmsMobiloSmsGatewayAdapter.class.getName());
    private static final String DEFAULT_BASE_URL = "https://smsmobilo.com/api/v1";

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public SmsMobiloSmsGatewayAdapter(
            NamedParameterJdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public SmsSendResult send(SmsSendRequest request) {
        String apiKey = settingValue("smsmobilo_sms_api_key");
        String sendPath = settingValue("smsmobilo_sms_send_path");
        String recipientField = settingValue("smsmobilo_sms_recipient_field");
        String messageField = settingValue("smsmobilo_sms_message_field");
        String senderField = settingValue("smsmobilo_sms_sender_field");
        if (blank(apiKey)) {
            return SmsSendResult.failed("smsmobilo_sms_api_key not configured", "");
        }
        if (blank(sendPath) || blank(recipientField) || blank(messageField)) {
            return SmsSendResult.failed(
                    "SMSMobilo send path/recipient/message field mapping not configured", "");
        }

        String apiUrl = settingValue("smsmobilo_sms_api_url");
        if (blank(apiUrl)) apiUrl = DEFAULT_BASE_URL;
        String targetUrl = resolveUrl(apiUrl, sendPath);
        if (!targetUrl.toLowerCase().startsWith("https://")) {
            return SmsSendResult.failed("SMSMobilo production endpoint must use HTTPS", "");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put(recipientField.trim(), stripTrailingComma(request.recipients()));
        body.put(messageField.trim(), request.content());
        String senderId =
                blank(request.senderId())
                        ? settingValue("smsmobilo_sms_sender_id")
                        : request.senderId().trim();
        if (!blank(senderId) && !blank(senderField)) body.put(senderField.trim(), senderId);

        final String payload;
        try {
            payload = objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException ex) {
            logger.log(Level.WARNING, "Failed to serialize SMSMobilo request", ex);
            return SmsSendResult.failed("SMSMobilo request could not be serialized", "");
        }

        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json; charset=UTF-8");
        headers.put("Accept", "application/json");
        String authMode = settingValue("smsmobilo_sms_auth_mode");
        if ("X_API_KEY".equalsIgnoreCase(authMode)) {
            headers.put("X-API-Key", apiKey);
        } else {
            headers.put("Authorization", "Bearer " + apiKey);
        }
        return normalize(Common.doHttpRequest("POST", targetUrl, payload, headers));
    }

    private SmsSendResult normalize(HttpRequestResponse response) {
        if (response == null || response.getStatusCode() == 0) {
            return SmsSendResult.failed(
                    response == null ? "No gateway response" : response.toString(),
                    response == null ? "" : response.getResponse());
        }
        if (response.getStatusCode() >= 200 && response.getStatusCode() < 300) {
            return SmsSendResult.sent(response.toString(), response.getResponse());
        }
        if (response.getStatusCode() == 408
                || response.getStatusCode() == 425
                || response.getStatusCode() == 429
                || response.getStatusCode() >= 500) {
            return SmsSendResult.failed(response.toString(), response.getResponse());
        }
        return SmsSendResult.rejected(response.toString(), response.getResponse());
    }

    private String resolveUrl(String baseUrl, String sendPath) {
        String trimmed = sendPath.trim();
        if (trimmed.startsWith("https://")) return trimmed;
        return baseUrl.replaceAll("/+$", "") + "/" + trimmed.replaceAll("^/+", "");
    }

    private String settingValue(String name) {
        try {
            Setting setting = Common.getSettings(name, jdbcTemplate);
            return setting == null ? "" : setting.getSetting_value();
        } catch (Exception ex) {
            logger.log(Level.WARNING, "Failed to read SMS setting " + name, ex);
            return "";
        }
    }

    private String stripTrailingComma(String value) {
        return value == null ? "" : value.replaceAll("[,]$", "");
    }

    private boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
