package net.citotech.cito.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.citotech.cito.Common;
import net.citotech.cito.Model.HttpRequestResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ProviderCredentialProbeServiceTest {
    private final ProviderCredentialProbeService service = new ProviderCredentialProbeService();

    @AfterEach
    void reset() {
        Common.setOutboundHttpExecutor(null);
    }

    @Test
    void checksBothProductsWithTheirOwnCredentialsEvenWhenCollectionFails() {
        List<String> paths = new ArrayList<>();
        Common.setOutboundHttpExecutor(
                (method, url, body, headers) -> {
                    assertThat(method).isEqualTo("POST");
                    assertThat(url).endsWith("/token/");
                    assertThat(body).isEmpty();
                    paths.add(url);
                    boolean collection = url.contains("/collection/");
                    assertThat(headers.get("Ocp-Apim-Subscription-Key"))
                            .isEqualTo(collection ? "collection-sub" : "payout-sub");
                    String basic =
                            new String(
                                    java.util.Base64.getDecoder()
                                            .decode(headers.get("Authorization").substring(6)),
                                    java.nio.charset.StandardCharsets.UTF_8);
                    assertThat(basic)
                            .isEqualTo(
                                    collection
                                            ? "collection-user:collection-key"
                                            : "payout-user:payout-key");
                    return response(
                            collection ? 401 : 200,
                            collection
                                    ? "provider-secret-error-body"
                                    : "{\"access_token\":\"private-token\"}");
                });
        var checks = service.probe("mtn_momo", "SANDBOX", "UG", "EUR", credentials());
        assertThat(paths)
                .containsExactly(
                        MtnMomoCredentialSchema.SANDBOX_BASE_URL + "/collection/token/",
                        MtnMomoCredentialSchema.SANDBOX_BASE_URL + "/disbursement/token/");
        assertThat(checks)
                .extracting(ProviderCredentialProbeService.ProbeCheck::status)
                .containsExactly("FAILED", "VERIFIED");
        assertThat(checks.get(0).message()).contains("HTTP 401", "subscription key");
        assertThat(checks.toString())
                .doesNotContain("private-token", "provider-secret-error-body", "collection-key");
    }

    @Test
    void legacyVerificationStillRejectsPartialSuccess() {
        Common.setOutboundHttpExecutor(
                (method, url, body, headers) ->
                        response(
                                url.contains("/collection/") ? 200 : 403,
                                "{\"access_token\":\"private-token\"}"));
        assertThatThrownBy(() -> service.verify("mtn_momo", "SANDBOX", "UG", "EUR", credentials()))
                .isInstanceOf(PaymentGatewayException.class)
                .hasMessageContaining("PAYOUT:")
                .hasMessageContaining("HTTP 403")
                .hasMessageNotContaining("private-token");
    }

    @Test
    void malformedTokenAndTransportFailureAreSafeProductFailures() {
        Common.setOutboundHttpExecutor(
                (method, url, body, headers) -> {
                    if (url.contains("/collection/"))
                        return response(200, "not-json-sensitive-body");
                    throw new IllegalStateException("secret-transport-details");
                });
        var checks = service.probe("mtn_momo", "SANDBOX", "UG", "EUR", credentials());
        assertThat(checks)
                .extracting(ProviderCredentialProbeService.ProbeCheck::status)
                .containsExactly("FAILED", "FAILED");
        assertThat(checks.get(0).message()).contains("invalid token response");
        assertThat(checks.toString()).doesNotContain("sensitive-body", "secret-transport-details");
    }

    private HttpRequestResponse response(int status, String body) {
        HttpRequestResponse response = new HttpRequestResponse();
        response.setStatusCode(status);
        response.setResponse(body);
        return response;
    }

    private Map<String, Object> credentials() {
        return Map.ofEntries(
                Map.entry("baseUrl", MtnMomoCredentialSchema.SANDBOX_BASE_URL),
                Map.entry("targetEnvironment", "sandbox"),
                Map.entry("baseCurrency", "EUR"),
                Map.entry("callbackHost", "pay.example.com"),
                Map.entry("callbackUrl", "https://pay.example.com/api/v2/provider-callbacks/mtn"),
                Map.entry("collectionApiUser", "collection-user"),
                Map.entry("collectionApiKey", "collection-key"),
                Map.entry("collectionSubscriptionKey", "collection-sub"),
                Map.entry("disbursementApiUser", "payout-user"),
                Map.entry("disbursementApiKey", "payout-key"),
                Map.entry("disbursementSubscriptionKey", "payout-sub"));
    }
}
