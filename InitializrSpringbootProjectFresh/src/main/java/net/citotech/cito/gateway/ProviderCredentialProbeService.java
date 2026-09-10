package net.citotech.cito.gateway;

import java.nio.charset.StandardCharsets;
import java.util.*;
import net.citotech.cito.Common;
import net.citotech.cito.Model.HttpRequestResponse;
import org.json.JSONObject;
import org.springframework.stereotype.Service;

/** Non-financial connectivity check; it never submits a collection or disbursement. */
@Service
public class ProviderCredentialProbeService {
    public void verify(
            String channel,
            String environment,
            String country,
            String currency,
            Map<String, Object> credentials) {
        Map<String, String> values = new LinkedHashMap<>();
        credentials.forEach(
                (k, v) -> {
                    if (v != null) values.put(k, String.valueOf(v));
                });
        if ("mtn_momo".equals(channel)) {
            MtnMomoCredentialSchema.validate(credentials, environment, country, currency);
            for (String operation : List.of("COLLECT", "PAYOUT")) {
                String prefix = MtnMomoCredentialSchema.productPrefix(operation);
                String basic =
                        Base64.getEncoder()
                                .encodeToString(
                                        (values.get(prefix + "ApiUser")
                                                        + ":"
                                                        + values.get(prefix + "ApiKey"))
                                                .getBytes(StandardCharsets.UTF_8));
                check(
                        Common.doHttpRequest(
                                "POST",
                                MtnMomoCredentialSchema.tokenEndpoint(values, operation),
                                "",
                                Map.of(
                                        "Authorization",
                                        "Basic " + basic,
                                        "Ocp-Apim-Subscription-Key",
                                        values.get(prefix + "SubscriptionKey"))));
            }
        } else if ("airtel_open_api".equals(channel)) {
            AirtelOpenApiCredentialSchema.validate(credentials, environment, country, currency);
            String path = values.getOrDefault("tokenPath", "/auth/oauth2/token");
            ProviderEndpointPolicy.requireRelativePath(path);
            check(
                    Common.doHttpRequest(
                            "POST",
                            values.get("baseUrl").replaceAll("/+$", "") + path,
                            new JSONObject()
                                    .put("client_id", values.get("clientId"))
                                    .put("client_secret", values.get("clientSecret"))
                                    .put("grant_type", "client_credentials")
                                    .toString(),
                            Map.of("Content-Type", "application/json")));
        } else
            throw new PaymentGatewayException(
                    "Provider connectivity verification is not supported for this channel");
    }

    private void check(HttpRequestResponse response) {
        try {
            if (response != null
                    && response.getStatusCode() == 200
                    && !new JSONObject(response.getResponse())
                            .optString("access_token", "")
                            .isBlank()) return;
        } catch (RuntimeException ignored) {
            /* Raw provider/token bodies are never public diagnostics. */
        }
        throw new PaymentGatewayException(
                "Provider authentication could not be verified; check the configured credentials");
    }
}
