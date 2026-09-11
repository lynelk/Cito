package net.citotech.cito.identity;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.citotech.cito.Common;
import net.citotech.cito.Model.HttpRequestResponse;
import net.citotech.cito.security.CanonicalRequestSigner;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * GnuGrid identity-verification adapter. Provider coverage is configuration-driven so CPay can
 * expose additional official document types without changing NOLI. The default remains Uganda NIN.
 * Asynchronous results are not supported until the provider authentication contract and durable
 * request correlation/replay protections have been implemented and certified.
 */
@Component
public class GnuGridConnector implements IdentityVerificationConnector {

    public static final String PROVIDER_CODE = "gnugrid";
    static final String ENDPOINT_PATH = "/v1/verifications";
    static final String SANDBOX_FAIL_SUFFIX = "00011";

    private final String baseUrl;
    private final String apiKey;

    @Value("${cpay.identity.gnugrid.supported-types:NIN}")
    private String supportedTypes = "NIN";

    @Value("${cpay.identity.gnugrid.supported-countries:UG}")
    private String supportedCountries = "UG";

    public GnuGridConnector(
            @Value("${cpay.identity.gnugrid.base-url:}") String baseUrl,
            @Value("${cpay.identity.gnugrid.api-key:}") String apiKey) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    @Override
    public String providerCode() {
        return PROVIDER_CODE;
    }

    @Override
    public boolean supportsSync() {
        return true;
    }

    @Override
    public boolean supportsAsync() {
        // A header-only hook cannot authenticate the request body or prevent replay.
        // Do not advertise a capability whose provider trust contract is not implemented.
        return false;
    }

    @Override
    public Set<String> supportedIdentityTypes() {
        return configuredSet(supportedTypes);
    }

    @Override
    public Set<String> supportedCountries() {
        return configuredSet(supportedCountries);
    }

    @Override
    public IdentityRecords.VerifiedIdentity verify(
            IdentityRecords.IdentityVerificationRequest request) {
        if (!supports(request.identityType(), request.country())) {
            throw new IdentityVerificationException(
                    "configured identity provider does not support this document type/country");
        }
        String ref = request.requestReference();
        if (baseUrl.isEmpty()) {
            return sandbox(request);
        }
        String url = baseUrl.replaceAll("/+$", "") + ENDPOINT_PATH;
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("X-CPay-Reference", ref);
        if (!apiKey.isEmpty()) {
            headers.put("Authorization", "Bearer " + apiKey);
        }
        try {
            HttpRequestResponse httpResponse =
                    Common.doHttpRequest("POST", url, requestBody(request), headers);
            int status = httpResponse.getStatusCode();
            String body = httpResponse.getResponse() == null ? "" : httpResponse.getResponse();
            if (status >= 200 && status < 300 && !body.isBlank()) {
                return parseResponse(ref, body);
            }
            String reason =
                    status == 0
                            ? "provider unreachable: " + safeProviderMessage(httpResponse.getErrorMessage())
                            : "provider rejected the request (HTTP " + status + ")";
            throw new IdentityVerificationException(reason);
        } catch (IdentityVerificationException e) {
            throw e;
        } catch (Exception e) {
            throw new IdentityVerificationException(
                    "identity verification failed: " + safeProviderMessage(e.getMessage()));
        }
    }

    @Override
    public IdentityRecords.VerifiedIdentity parseCallback(
            String callbackBody, Map<String, String> callbackHeaders) {
        // Defence in depth: direct connector callers must not bypass the controller's
        // fail-closed check or turn an untrusted JSON assertion into a verified identity.
        throw new IdentityVerificationException(
                "GnuGrid asynchronous callbacks are not supported pending provider certification.");
    }

    @Override
    public boolean validateCallbackHeaders(Map<String, String> callbackHeaders) {
        // Never infer authenticity from a present header, an outbound API key, or an
        // invented signing scheme. The provider's body-bound contract must be certified.
        return false;
    }

    private IdentityRecords.VerifiedIdentity sandbox(
            IdentityRecords.IdentityVerificationRequest request) {
        String identityNumber = request.identityNumber().trim();
        String ref = request.requestReference();
        if (identityNumber.endsWith(SANDBOX_FAIL_SUFFIX)) {
            return IdentityRecords.VerifiedIdentity.failed(
                    ref, PROVIDER_CODE, "sandbox-" + ref, "SANDBOX_NOT_VERIFIED");
        }
        return IdentityRecords.VerifiedIdentity.matched(
                ref,
                PROVIDER_CODE,
                "sandbox-" + ref,
                maskName(request.fullName()),
                Instant.now(),
                Instant.now().plusSeconds(30L * 24L * 60L * 60L),
                "SANDBOX_VERIFIED");
    }

    private IdentityRecords.VerifiedIdentity parseResponse(String ref, String body) {
        JSONObject json = new JSONObject(body);
        boolean verified = json.optBoolean("verified", false);
        String providerReference = json.optString("providerReference", "");
        if (!verified) {
            return IdentityRecords.VerifiedIdentity.failed(
                    ref, PROVIDER_CODE, providerReference, body);
        }
        String fullName = firstNonBlank(
                json.optString("fullName", ""),
                json.optString("firstName", ""),
                json.optString("lastName", ""));
        return IdentityRecords.VerifiedIdentity.matched(
                ref,
                PROVIDER_CODE,
                providerReference,
                maskName(fullName),
                Instant.now(),
                parseExpiry(json.optString("expiresAt", "")),
                body);
    }

    private String requestBody(IdentityRecords.IdentityVerificationRequest request) {
        JSONObject body = new JSONObject();
        body.put("reference", request.requestReference());
        body.put("identityType", request.identityType());
        body.put("country", request.country());
        body.put("identityNumber", request.identityNumber());
        if ("NIN".equalsIgnoreCase(request.identityType())) {
            body.put("nin", request.identityNumber());
        }
        body.put("fullName", request.fullName() == null ? "" : request.fullName());
        body.put("msisdn", request.msisdn() == null ? "" : request.msisdn());
        return body.toString();
    }

    private Set<String> configuredSet(String csv) {
        return Stream.of(csv == null ? new String[0] : csv.split(","))
                .map(this::normalize)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private Instant parseExpiry(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private String safeProviderMessage(String message) {
        if (message == null || message.isBlank()) return "no provider detail";
        return message.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ._-]", "");
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return "";
    }

    static String maskName(String fullName) {
        if (fullName == null || fullName.isBlank()) return "";
        String trimmed = fullName.trim();
        if (trimmed.length() <= 2) return trimmed.charAt(0) + "*";
        return trimmed.substring(0, 1)
                + "*".repeat(trimmed.length() - 2)
                + trimmed.substring(trimmed.length() - 1);
    }

    static String maskMsisdn(String msisdn) {
        if (msisdn == null || msisdn.isBlank()) return "";
        String digits = msisdn.replaceAll("[^0-9]", "");
        if (digits.length() <= 5) return "*".repeat(Math.max(1, digits.length()));
        return digits.substring(0, 3)
                + "*".repeat(digits.length() - 5)
                + digits.substring(digits.length() - 2);
    }

    static String sha256Hex(String value) {
        return CanonicalRequestSigner.sha256Hex(
                value == null ? "" : value.trim().toUpperCase(Locale.ROOT));
    }
}
