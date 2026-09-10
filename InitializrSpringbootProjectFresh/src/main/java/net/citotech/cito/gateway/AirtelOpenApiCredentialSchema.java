package net.citotech.cito.gateway;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Canonical Airtel Africa OpenAPI credential contract for merchant and platform stores. */
public final class AirtelOpenApiCredentialSchema {
    public static final String CHANNEL_CODE = "airtel_open_api";
    public static final String SANDBOX_BASE_URL = "https://openapiuat.airtel.africa";
    public static final String PRODUCTION_BASE_URL = "https://openapi.airtel.africa";

    private AirtelOpenApiCredentialSchema() {}

    public static void validate(
            Map<String, ?> credentials,
            String environment,
            String countryCode,
            String currencyCode) {
        List<String> required =
                List.of("baseUrl", "clientId", "clientSecret", "country", "currency");
        List<String> missing = new ArrayList<>();
        for (String field : required) {
            if (blank(value(credentials, field))) missing.add(field);
        }
        if (!missing.isEmpty()) {
            throw new PaymentGatewayException(
                    "Missing required Airtel OpenAPI credential field(s): "
                            + String.join(", ", missing));
        }
        ProviderEndpointPolicy.requireOrigin(
                value(credentials, "baseUrl"),
                "SANDBOX".equals(normalize(environment)) ? SANDBOX_BASE_URL : PRODUCTION_BASE_URL);
        for (String key :
                List.of(
                        "tokenPath",
                        "collectionPath",
                        "payoutPath",
                        "balancePath",
                        "collectionStatusPath",
                        "payoutStatusPath")) {
            ProviderEndpointPolicy.requireRelativePath(value(credentials, key));
        }
        if (!normalize(countryCode).equals(normalize(value(credentials, "country")))) {
            throw new PaymentGatewayException(
                    "Airtel country must match the credential scope country");
        }
        if (!normalize(currencyCode).equals(normalize(value(credentials, "currency")))) {
            throw new PaymentGatewayException(
                    "Airtel currency must match the credential scope currency");
        }
        boolean hasPin = !blank(value(credentials, "apiPin"));
        boolean hasKey = !blank(value(credentials, "publicKey"));
        if (hasPin != hasKey) {
            throw new PaymentGatewayException(
                    "Airtel payout credentials require both apiPin and publicKey");
        }
        if (hasKey && !value(credentials, "publicKey").contains("BEGIN PUBLIC KEY")) {
            throw new PaymentGatewayException(
                    "Airtel publicKey must be the RSA public key issued for PIN encryption");
        }
    }

    public static void validateForOperation(
            Map<String, ?> credentials,
            String environment,
            String countryCode,
            String currencyCode,
            String operation) {
        validate(credentials, environment, countryCode, currencyCode);
        if (!"COLLECT".equalsIgnoreCase(operation) && !"PAYOUT".equalsIgnoreCase(operation)) {
            throw new PaymentGatewayException("Unsupported Airtel operation");
        }
        if ("PAYOUT".equalsIgnoreCase(operation)
                && (blank(value(credentials, "apiPin"))
                        || blank(value(credentials, "publicKey")))) {
            throw new PaymentGatewayException("Airtel payout requires apiPin and publicKey");
        }
    }

    private static URI httpsUri(String raw) {
        try {
            URI uri = URI.create(raw);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || blank(uri.getHost())) {
                throw new IllegalArgumentException();
            }
            return uri;
        } catch (Exception ignored) {
            throw new PaymentGatewayException("Airtel baseUrl must be a valid HTTPS URL");
        }
    }

    private static String value(Map<String, ?> map, String key) {
        Object value = map == null ? null : map.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
