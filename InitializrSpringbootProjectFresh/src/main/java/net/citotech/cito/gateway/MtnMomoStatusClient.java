package net.citotech.cito.gateway;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.citotech.cito.Common;
import net.citotech.cito.Model.HttpRequestResponse;
import org.json.JSONObject;
import org.springframework.stereotype.Service;

/**
 * Authenticated MTN status lookup used to verify callback signals before financial state changes.
 */
@Service
public class MtnMomoStatusClient {
    private final ProviderTokenStoreService tokenStoreService;

    public MtnMomoStatusClient(ProviderTokenStoreService tokenStoreService) {
        this.tokenStoreService = tokenStoreService;
    }

    public VerifiedStatus verify(
            String operation,
            String providerReference,
            String environment,
            String country,
            String currency,
            Map<String, Object> credentialValues) {
        Map<String, String> credentials = strings(credentialValues);
        MtnMomoCredentialSchema.validateForOperation(
                credentials, environment, country, currency, operation);

        String prefix = MtnMomoCredentialSchema.productPrefix(operation);
        String apiUser = required(credentials.get(prefix + "ApiUser"), prefix + "ApiUser");
        String subscriptionKey =
                required(credentials.get(prefix + "SubscriptionKey"), prefix + "SubscriptionKey");
        String segment = MtnMomoCredentialSchema.tokenSegment(credentials, operation);
        String token = accessToken(operation, environment, credentials, segment, false);

        HttpRequestResponse statusResponse =
                statusRequest(operation, providerReference, credentials, subscriptionKey, token);
        if (statusResponse != null && statusResponse.getStatusCode() == 401) {
            token = accessToken(operation, environment, credentials, segment, true);
            statusResponse =
                    statusRequest(
                            operation, providerReference, credentials, subscriptionKey, token);
        }
        if (statusResponse == null || statusResponse.getStatusCode() != 200) {
            int status = statusResponse == null ? 0 : statusResponse.getStatusCode();
            throw new PaymentGatewayException(
                    "MTN status verification failed (HTTP " + status + ")");
        }

        JSONObject json = new JSONObject(statusResponse.getResponse());
        String status = json.optString("status", "").trim().toUpperCase(Locale.ROOT);
        if (status.isEmpty()) {
            throw new PaymentGatewayException("MTN status response omitted status");
        }
        return new VerifiedStatus(
                status,
                json.optString("externalId", "").trim(),
                json.optString("financialTransactionId", "").trim());
    }

    private HttpRequestResponse statusRequest(
            String operation,
            String providerReference,
            Map<String, String> credentials,
            String subscriptionKey,
            String token) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + token);
        headers.put("Ocp-Apim-Subscription-Key", subscriptionKey);
        headers.put(
                "X-Target-Environment",
                required(credentials.get("targetEnvironment"), "targetEnvironment"));
        headers.put("Accept", "application/json");

        String base = required(credentials.get("baseUrl"), "baseUrl").replaceAll("/+$", "");
        String endpoint =
                "PAYOUT".equalsIgnoreCase(operation)
                        ? base + "/disbursement/v1_0/transfer/" + providerReference
                        : base + "/collection/v1_0/requesttopay/" + providerReference;
        return Common.doHttpRequest("GET", endpoint, "", headers);
    }

    private String accessToken(
            String operation,
            String environment,
            Map<String, String> credentials,
            String segment,
            boolean forceRefresh) {
        if (!forceRefresh) {
            Optional<ProviderToken> cached =
                    tokenStoreService.findValid(
                            MtnMomoCredentialSchema.CHANNEL_CODE, segment, environment);
            if (cached != null && cached.isPresent()) {
                return cached.get().getTokenValue();
            }
        }

        String prefix = MtnMomoCredentialSchema.productPrefix(operation);
        String apiUser = required(credentials.get(prefix + "ApiUser"), prefix + "ApiUser");
        String apiKey = required(credentials.get(prefix + "ApiKey"), prefix + "ApiKey");
        String subscriptionKey =
                required(credentials.get(prefix + "SubscriptionKey"), prefix + "SubscriptionKey");
        String basic =
                Base64.getEncoder()
                        .encodeToString((apiUser + ":" + apiKey).getBytes(StandardCharsets.UTF_8));
        Map<String, String> tokenHeaders = new LinkedHashMap<>();
        tokenHeaders.put("Authorization", "Basic " + basic);
        tokenHeaders.put("Ocp-Apim-Subscription-Key", subscriptionKey);
        tokenHeaders.put("Accept", "application/json");

        HttpRequestResponse tokenResponse =
                Common.doHttpRequest(
                        "POST",
                        MtnMomoCredentialSchema.tokenEndpoint(credentials, operation),
                        "",
                        tokenHeaders);
        if (tokenResponse == null || tokenResponse.getStatusCode() != 200) {
            int status = tokenResponse == null ? 0 : tokenResponse.getStatusCode();
            throw new PaymentGatewayException(
                    "MTN status verification could not obtain an access token (HTTP "
                            + status
                            + ")");
        }
        JSONObject tokenJson = new JSONObject(tokenResponse.getResponse());
        String token = tokenJson.optString("access_token", "").trim();
        if (token.isEmpty()) {
            throw new PaymentGatewayException(
                    "MTN status verification token response omitted access_token");
        }
        long expiresIn = tokenJson.optLong("expires_in", 3600L);
        tokenStoreService.save(
                MtnMomoCredentialSchema.CHANNEL_CODE,
                segment,
                environment,
                token,
                ProviderTokenScope.expiresAt(expiresIn));
        return token;
    }

    private Map<String, String> strings(Map<String, Object> values) {
        Map<String, String> result = new LinkedHashMap<>();
        if (values == null) return result;
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (entry.getValue() != null) {
                result.put(entry.getKey(), String.valueOf(entry.getValue()).trim());
            }
        }
        return result;
    }

    private String required(String value, String field) {
        String safe = value == null ? "" : value.trim();
        if (safe.isEmpty()) throw new PaymentGatewayException("MTN MoMo " + field + " is required");
        return safe;
    }

    public record VerifiedStatus(String status, String externalId, String financialTransactionId) {}
}
