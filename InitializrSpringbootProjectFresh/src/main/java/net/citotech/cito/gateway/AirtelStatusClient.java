package net.citotech.cito.gateway;

import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.json.JSONObject;
import org.springframework.stereotype.Service;

/** Authenticated, bounded GET verification. Callback bodies never enter this client. */
@Service
public class AirtelStatusClient {
    private final ProviderTokenStoreService tokens;
    private final HttpClient http =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();

    public AirtelStatusClient(ProviderTokenStoreService tokens) {
        this.tokens = tokens;
    }

    public Verified verify(
            String operation,
            String reference,
            String environment,
            String country,
            String currency,
            BigDecimal amount,
            Map<String, String> credentials) {
        if (!"COLLECT".equals(operation) && !"PAYOUT".equals(operation))
            throw new PaymentGatewayException("AIRTEL_OPERATION_INVALID");
        AirtelOpenApiCredentialSchema.validate(credentials, environment, country, currency);
        String path =
                credentials.getOrDefault(
                        "PAYOUT".equals(operation) ? "payoutStatusPath" : "collectionStatusPath",
                        "PAYOUT".equals(operation)
                                ? "/standard/v2/disbursements/{reference}/"
                                : "/standard/v2/payments/{reference}/");
        String encoded = URLEncoder.encode(reference, StandardCharsets.UTF_8).replace("+", "%20");
        if (path.contains("{reference}")) path = path.replace("{reference}", encoded);
        else if (path.contains("{id}")) path = path.replace("{id}", encoded);
        else path = path.replaceAll("/+$", "") + "/" + encoded + "/";
        URI endpoint = endpoint(credentials, path);
        String token = token(credentials, environment, operation, false);
        Reply reply = get(endpoint, token, country, currency);
        if (reply.status() == 401)
            reply =
                    get(
                            endpoint,
                            token(credentials, environment, operation, true),
                            country,
                            currency);
        if (reply.status() != 200)
            throw new PaymentGatewayException("AIRTEL_STATUS_HTTP_" + reply.status());
        return parse(reply.body(), reference, amount, currency);
    }

    private Reply get(URI uri, String token, String country, String currency) {
        return exchange(
                "GET",
                uri,
                "",
                Map.of(
                        "Authorization",
                        "Bearer " + token,
                        "Accept",
                        "application/json",
                        "X-Country",
                        country,
                        "X-Currency",
                        currency));
    }

    private String token(
            Map<String, String> credentials,
            String environment,
            String operation,
            boolean refresh) {
        URI endpoint =
                endpoint(credentials, credentials.getOrDefault("tokenPath", "/auth/oauth2/token"));
        String scope =
                ProviderTokenScope.segment(
                        "AIRTEL_RECOVERY_" + operation,
                        endpoint.toString(),
                        credentials.get("clientId"),
                        credentials.get("clientSecret"),
                        credentials.get("country"),
                        credentials.get("currency"));
        if (!refresh) {
            Optional<ProviderToken> existing =
                    tokens.findValid(AirtelOpenApiAdapter.CHANNEL_CODE, scope, environment);
            if (existing != null && existing.isPresent()) return existing.get().getTokenValue();
        }
        String body =
                new JSONObject()
                        .put("client_id", credentials.get("clientId"))
                        .put("client_secret", credentials.get("clientSecret"))
                        .put("grant_type", "client_credentials")
                        .toString();
        Reply response =
                exchange(
                        "POST",
                        endpoint,
                        body,
                        Map.of("Content-Type", "application/json", "Accept", "application/json"));
        if (response.status() != 200)
            throw new PaymentGatewayException("AIRTEL_AUTH_HTTP_" + response.status());
        JSONObject value;
        try {
            value = new JSONObject(response.body());
        } catch (Exception ignored) {
            throw new PaymentGatewayException("AIRTEL_AUTH_INVALID_RESPONSE");
        }
        String token = value.optString("access_token", "").trim();
        if (token.isEmpty()) throw new PaymentGatewayException("AIRTEL_AUTH_MISSING_TOKEN");
        tokens.save(
                AirtelOpenApiAdapter.CHANNEL_CODE,
                scope,
                environment,
                token,
                ProviderTokenScope.expiresAt(value.optLong("expires_in", 60)));
        return token;
    }

    static URI endpoint(Map<String, String> credentials, String path) {
        URI base = URI.create(credentials.get("baseUrl"));
        URI uri = base.resolve(path);
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getUserInfo() != null
                || !base.getHost().equalsIgnoreCase(uri.getHost())
                || (uri.getPort() != -1 && uri.getPort() != 443)
                || uri.getQuery() != null
                || uri.getFragment() != null) {
            throw new PaymentGatewayException("AIRTEL_UNSAFE_ENDPOINT");
        }
        return uri;
    }

    // Separate transport seam supports deterministic provider contract tests without live payments.
    Reply exchange(String method, URI uri, String body, Map<String, String> headers) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15));
        headers.forEach(request::header);
        request.method(
                method,
                "GET".equals(method)
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        try {
            HttpResponse<InputStream> response =
                    http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream stream = response.body()) {
                byte[] bytes = stream.readNBytes(65537);
                if (bytes.length > 65536)
                    throw new PaymentGatewayException("AIRTEL_RESPONSE_TOO_LARGE");
                return new Reply(response.statusCode(), new String(bytes, StandardCharsets.UTF_8));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PaymentGatewayException("AIRTEL_STATUS_INTERRUPTED");
        } catch (java.io.IOException e) {
            throw new PaymentGatewayException("AIRTEL_STATUS_TRANSPORT_UNCERTAIN");
        }
    }

    static Verified parse(String body, String reference, BigDecimal amount, String currency) {
        try {
            JSONObject json = new JSONObject(body);
            JSONObject envelope = json.optJSONObject("status");
            if (envelope != null && envelope.has("success") && !envelope.getBoolean("success"))
                throw new PaymentGatewayException("AIRTEL_STATUS_ENVELOPE_REJECTED");
            if (json.has("error") && !json.isNull("error"))
                throw new PaymentGatewayException("AIRTEL_STATUS_CONFLICTING_ERROR");
            JSONObject tx = json.getJSONObject("data").getJSONObject("transaction");
            if (!reference.equals(tx.optString("id", "")))
                throw new PaymentGatewayException("AIRTEL_STATUS_REFERENCE_MISMATCH");
            if (tx.has("amount")
                    && new BigDecimal(tx.get("amount").toString()).compareTo(amount) != 0)
                throw new PaymentGatewayException("AIRTEL_STATUS_AMOUNT_MISMATCH");
            if (tx.has("currency") && !currency.equalsIgnoreCase(tx.getString("currency")))
                throw new PaymentGatewayException("AIRTEL_STATUS_CURRENCY_MISMATCH");
            String status =
                    switch (tx.optString("status", "").trim().toUpperCase(java.util.Locale.ROOT)) {
                        case "TS", "SUCCESS", "SUCCESSFUL", "COMPLETED" -> "SUCCESSFUL";
                        case "TF", "FAILED", "FAILURE", "REJECTED" -> "FAILED";
                        default -> "PENDING";
                    };
            return new Verified(status, tx.optString("airtel_money_id", "").trim());
        } catch (PaymentGatewayException e) {
            throw e;
        } catch (Exception ignored) {
            throw new PaymentGatewayException("AIRTEL_STATUS_INVALID_RESPONSE");
        }
    }

    record Reply(int status, String body) {}

    public record Verified(String status, String financialReference) {
        public boolean terminal() {
            return "SUCCESSFUL".equals(status) || "FAILED".equals(status);
        }
    }
}
