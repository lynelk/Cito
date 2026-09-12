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
    public record ProbeCheck(String operation, String status, String message) {}

    public void verify(
            String channel,
            String environment,
            String country,
            String currency,
            Map<String, Object> credentials) {
        List<ProbeCheck> checks = probe(channel, environment, country, currency, credentials);
        if (checks.stream().anyMatch(check -> !"VERIFIED".equals(check.status())))
            throw new PaymentGatewayException(
                    checks.stream()
                            .filter(check -> !"VERIFIED".equals(check.status()))
                            .map(check -> check.operation() + ": " + check.message())
                            .collect(java.util.stream.Collectors.joining("; ")));
    }

    public List<ProbeCheck> probe(
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
        List<ProbeCheck> checks = new ArrayList<>();
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
                checks.add(
                        check(
                                operation,
                                () ->
                                        Common.doHttpRequest(
                                                "POST",
                                                MtnMomoCredentialSchema.tokenEndpoint(
                                                        values, operation),
                                                "",
                                                Map.of(
                                                        "Authorization",
                                                        "Basic " + basic,
                                                        "Ocp-Apim-Subscription-Key",
                                                        values.get(prefix + "SubscriptionKey")))));
            }
        } else if ("airtel_open_api".equals(channel)) {
            AirtelOpenApiCredentialSchema.validate(credentials, environment, country, currency);
            String path = values.getOrDefault("tokenPath", "/auth/oauth2/token");
            ProviderEndpointPolicy.requireRelativePath(path);
            checks.add(
                    check(
                            "AUTHENTICATION",
                            () ->
                                    Common.doHttpRequest(
                                            "POST",
                                            values.get("baseUrl").replaceAll("/+$", "") + path,
                                            new JSONObject()
                                                    .put("client_id", values.get("clientId"))
                                                    .put(
                                                            "client_secret",
                                                            values.get("clientSecret"))
                                                    .put("grant_type", "client_credentials")
                                                    .toString(),
                                            Map.of("Content-Type", "application/json"))));
        } else
            throw new PaymentGatewayException(
                    "Provider connectivity verification is not supported for this channel");
        return List.copyOf(checks);
    }

    private ProbeCheck check(
            String operation, java.util.function.Supplier<HttpRequestResponse> request) {
        int status = 0;
        try {
            HttpRequestResponse response = request.get();
            status = response == null ? 0 : response.getStatusCode();
            if (response != null
                    && status == 200
                    && !new JSONObject(response.getResponse())
                            .optString("access_token", "")
                            .isBlank())
                return new ProbeCheck(
                        operation,
                        "VERIFIED",
                        "Product authentication verified. No payment was submitted.");
        } catch (RuntimeException ignored) {
            /* Raw provider/token bodies are never public diagnostics. */
        }
        String message =
                switch (status) {
                    case 401, 403 ->
                            "Authentication rejected (HTTP "
                                    + status
                                    + "). Check this product's API user, API key and subscription"
                                    + " key in the selected provider environment.";
                    case 404 ->
                            "Token endpoint unavailable (HTTP 404). Check product provisioning with"
                                    + " the provider.";
                    case 429 ->
                            "Provider rate limit reached (HTTP 429). Wait before verifying again.";
                    case 200 ->
                            "Provider returned an invalid token response. Contact provider support"
                                    + " if this persists.";
                    default ->
                            status >= 500
                                    ? "Provider temporarily unavailable. Verify again later."
                                    : "Provider authentication could not be reached or verified."
                                            + " Check connectivity and provider availability.";
                };
        return new ProbeCheck(operation, "FAILED", message);
    }
}
