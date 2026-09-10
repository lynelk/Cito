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

/** Yo! SMS provider adapter. */
@Component
public class YoSmsGatewayAdapter implements SmsGatewayAdapter {

    private static final Logger logger = Logger.getLogger(YoSmsGatewayAdapter.class.getName());
    private static final String DEFAULT_API_URL = "https://sms.yo.co.ug/yosms/api/v2/send";
    private final NamedParameterJdbcTemplate jdbcTemplate;

    public YoSmsGatewayAdapter(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public SmsSendResult send(SmsSendRequest request) {
        String username = settingValue("yo_sms_username");
        String password = settingValue("yo_sms_password");
        String senderId =
                blank(request.senderId())
                        ? settingValue("yo_sms_sender_id")
                        : request.senderId().trim();
        String apiUrl = settingValue("yo_sms_api_url");
        if (blank(username) || blank(password)) {
            return SmsSendResult.failed("yo_sms_username/yo_sms_password not configured", "");
        }
        if (blank(apiUrl)) apiUrl = DEFAULT_API_URL;

        String payload =
                "origin="
                        + Common.urlEncodeValue(senderId)
                        + "&destinations="
                        + Common.urlEncodeValue(stripTrailingComma(request.recipients()))
                        + "&message="
                        + Common.urlEncodeValue(request.content())
                        + "&username="
                        + Common.urlEncodeValue(username)
                        + "&password="
                        + Common.urlEncodeValue(password);
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/x-www-form-urlencoded");
        return normalize(Common.doHttpRequest("POST", apiUrl, payload, headers));
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
