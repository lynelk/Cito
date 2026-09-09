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

    private MtnMomoCredentialSchema() {}

    public static void validate(
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
                        "callbackUrl",
                        "collectionApiUser",
                        "collectionApiKey",
                        "collectionSubscriptionKey",
                        "disbursementApiUser",
                        "disbursementApiKey",
                        "disbursementSubscriptionKey");
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
            if ("UG".equals(normalize(countryCode)) && !"mtnuganda".equals(target)) {
                throw new PaymentGatewayException(
                        "MTN Uganda production X-Target-Environment must be mtnuganda");
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

    public static String endpoint(Map<String, String> credentials, String operation) {
        String base = value(credentials, "baseUrl").replaceAll("/+$", "");
        return "PAYOUT".equalsIgnoreCase(operation)
                ? base + "/disbursement/v1_0/transfer"
                : base + "/collection/v1_0/requesttopay";
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
