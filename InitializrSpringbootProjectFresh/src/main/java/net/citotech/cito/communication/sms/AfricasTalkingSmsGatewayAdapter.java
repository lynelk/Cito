package net.citotech.cito.communication.sms;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.citotech.cito.Common;
import net.citotech.cito.Model.HttpRequestResponse;
import net.citotech.cito.Model.Setting;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/** Africa's Talking SMS provider adapter. */
@Component
public class AfricasTalkingSmsGatewayAdapter implements SmsGatewayAdapter {

    private static final Logger logger = Logger.getLogger(AfricasTalkingSmsGatewayAdapter.class.getName());
    private static final String DEFAULT_API_URL = "https://api.africastalking.com/version1/messaging";
    private final NamedParameterJdbcTemplate jdbcTemplate;

    public AfricasTalkingSmsGatewayAdapter(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public SmsSendResult send(SmsSendRequest request) {
        String username = settingValue("africastalking_username");
        String apiKey = settingValue("africastalking_api_key");
        String senderId = blank(request.senderId())
                ? settingValue("africastalking_sender_id") : request.senderId().trim();
        String apiUrl = settingValue("africastalking_sms_api_url");
        if (blank(username) || blank(apiKey)) {
            return SmsSendResult.failed("africastalking_username/africastalking_api_key not configured", "");
        }
        if (blank(apiUrl)) apiUrl = DEFAULT_API_URL;

        StringBuilder payload = new StringBuilder()
                .append("username=").append(Common.urlEncodeValue(username))
                .append("&to=").append(Common.urlEncodeValue(stripTrailingComma(request.recipients())))
                .append("&message=").append(Common.urlEncodeValue(request.content()));
        if (!blank(senderId)) payload.append("&from=").append(Common.urlEncodeValue(senderId));
        payload.append("&bulkSMSMode=0");

        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/x-www-form-urlencoded");
        headers.put("apiKey", apiKey);
        return normalize(Common.doHttpRequest("POST", apiUrl, payload.toString(), headers));
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
        return SmsSendResult.rejected(response.toString(), response.getResponse());
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
