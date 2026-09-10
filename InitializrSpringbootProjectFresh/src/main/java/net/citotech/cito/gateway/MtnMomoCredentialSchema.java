package net.citotech.cito.gateway;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Canonical MTN MoMo credential contract shared by merchant and platform credential stores. */
public final class MtnMomoCredentialSchema {
    public static final String CHANNEL_CODE = "mtn_momo";
    public static final String SANDBOX_BASE_URL = "https://sandbox.momodeveloper.mtn.com";
    public static final String PRODUCTION_BASE_URL = "https://proxy.momoapi.mtn.com";

    private MtnMomoCredentialSchema() {}

    /**
     * Validate a complete MTN connection that is intended to support both Collections and
     * Disbursements.
     */
    public static void validate(
            Map<String, ?> credentials,
            String environment,
            String countryCode,
            String currencyCode) {
        validateCommon(credentials, environment, countryCode, currencyCode);
        requireProductCredentials(credentials, "collection");
        requireProductCredentials(credentials, "disbursement");
    }

    /**
     * Validate only the MTN product needed for the requested operation. Collections and
     * Disbursements have separate API users/keys and subscription keys, so a collection request
     * must never accidentally authenticate with Disbursement credentials, or vice versa.
     */
    public static void validateForOperation(
            Map<String, ?> credentials,
            String environment,
            String countryCode,
            String currencyCode,
            String operation) {
        validateCommon(credentials, environment, countryCode, currencyCode);
        requireProductCredentials(credentials, productPrefix(operation));
    }

    public static String tokenSegment(Map<String, String> credentials, String operation) {
        String prefix = productPrefix(operation);
        return ProviderTokenScope.segment(
                prefix,
                tokenEndpoint(credentials, operation),
                value(credentials, "targetEnvironment"),
                value(credentials, "baseCurrency"),
                value(credentials, prefix + "ApiUser"),
                value(credentials, prefix + "ApiKey"),
                value(credentials, prefix + "SubscriptionKey"));
    }

    private static void validateCommon(
            Map<String, ?> credentials,
            String environment,
            String countryCode,
            String currencyCode) {
        List<String> required =
                List.of(
                        "baseUrl",
                        "targetEnvironment",
                        "baseCurrency",
                        "callbackHost",
                        "callbackUrl");
        List<String> missing = new ArrayList<>();
        for (String key : required) {
            if (blank(value(credentials, key))) missing.add(key);
        }
        if (!missing.isEmpty()) {
            throw new PaymentGatewayException(
                    "Missing required MTN MoMo credential field(s): " + String.join(", ", missing));
        }

        String env = normalize(environment);
        String target = value(credentials, "targetEnvironment").toLowerCase(Locale.ROOT);
        String configuredCurrency = normalize(value(credentials, "baseCurrency"));
        String scopedCurrency = normalize(currencyCode);
        if (!scopedCurrency.isEmpty() && !configuredCurrency.equals(scopedCurrency)) {
            throw new PaymentGatewayException(
                    "MTN baseCurrency must match the credential scope currency " + scopedCurrency);
        }
        if ("SANDBOX".equals(env)) {
            if (!"sandbox".equals(target)) {
                throw new PaymentGatewayException(
                        "MTN sandbox X-Target-Environment must be sandbox");
            }
            if (!"EUR".equals(configuredCurrency)) {
                throw new PaymentGatewayException("MTN sandbox transactions must use EUR");
            }
        } else {
            if ("sandbox".equals(target)) {
                throw new PaymentGatewayException(
                        "MTN production credentials cannot use the sandbox target environment");
            }
            if ("UG".equals(normalize(countryCode))) {
                if (!"mtnuganda".equals(target)) {
                    throw new PaymentGatewayException(
                            "MTN Uganda production X-Target-Environment must be mtnuganda");
                }
                if (!"UGX".equals(configuredCurrency)) {
                    throw new PaymentGatewayException(
                            "MTN Uganda production transactions must use UGX");
                }
            }
        }

        URI baseUrl = httpsUri(value(credentials, "baseUrl"), "baseUrl");
        if (baseUrl.getQuery() != null || baseUrl.getFragment() != null) {
            throw new PaymentGatewayException("MTN baseUrl cannot contain a query or fragment");
        }
        URI callbackUrl = httpsUri(value(credentials, "callbackUrl"), "callbackUrl");
        String callbackHost = value(credentials, "callbackHost").toLowerCase(Locale.ROOT);
        if (callbackHost.contains(":")
                || callbackHost.contains("/")
                || callbackHost.contains(" ")) {
            throw new PaymentGatewayException(
                    "MTN callbackHost must be a hostname without scheme, port or path");
        }
        if (!callbackHost.equalsIgnoreCase(callbackUrl.getHost())) {
            throw new PaymentGatewayException(
                    "MTN callbackUrl host must match the API user's callbackHost");
        }
    }

    private static void requireProductCredentials(Map<String, ?> credentials, String prefix) {
        List<String> keys =
                List.of(prefix + "ApiUser", prefix + "ApiKey", prefix + "SubscriptionKey");
        List<String> missing = new ArrayList<>();
        for (String key : keys) {
            if (blank(value(credentials, key))) missing.add(key);
        }
        if (!missing.isEmpty()) {
            throw new PaymentGatewayException(
                    "Missing required MTN MoMo credential field(s): " + String.join(", ", missing));
        }
    }

    public static String endpoint(Map<String, String> credentials, String operation) {
        String base = value(credentials, "baseUrl").replaceAll("/+$", "");
        return "PAYOUT".equalsIgnoreCase(operation)
                ? base + "/disbursement/v1_0/transfer"
                : base + "/collection/v1_0/requesttopay";
    }

    public static String statusEndpoint(
            Map<String, String> credentials, String operation, String referenceId) {
        String endpoint = endpoint(credentials, operation);
        return endpoint + "/" + requiredReference(referenceId);
    }

    public static String tokenEndpoint(Map<String, String> credentials, String operation) {
        String base = value(credentials, "baseUrl").replaceAll("/+$", "");
        return base
                + ("PAYOUT".equalsIgnoreCase(operation)
                        ? "/disbursement/token/"
                        : "/collection/token/");
    }

    public static String productPrefix(String operation) {
        return "PAYOUT".equalsIgnoreCase(operation) ? "disbursement" : "collection";
    }

    private static String requiredReference(String referenceId) {
        String value = referenceId == null ? "" : referenceId.trim();
        if (value.isEmpty()) {
            throw new PaymentGatewayException("MTN referenceId is required");
        }
        return value;
    }

    private static URI httpsUri(String raw, String field) {
        try {
            URI uri = URI.create(raw);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || blank(uri.getHost())) {
                throw new IllegalArgumentException();
            }
            return uri;
        } catch (Exception ignored) {
            throw new PaymentGatewayException("MTN " + field + " must be a valid HTTPS URL");
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
