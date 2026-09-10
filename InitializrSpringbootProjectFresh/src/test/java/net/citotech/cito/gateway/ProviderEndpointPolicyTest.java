package net.citotech.cito.gateway;

import static org.assertj.core.api.Assertions.*;

import java.util.Map;
import net.citotech.cito.Model.HttpRequestResponse;
import org.junit.jupiter.api.Test;

class ProviderEndpointPolicyTest {
    @Test
    void rejectsUnreviewedOriginsAndOriginOverrides() {
        for (String url :
                java.util.List.of(
                        "https://openapiuat.attacker.test",
                        "https://openapiuat.airtel.africa.attacker.test",
                        "https://127.0.0.1",
                        "https://user:pass@openapiuat.airtel.africa",
                        "https://openapiuat.airtel.africa:8443",
                        "https://openapiuat.airtel.africa/path"))
            assertThatThrownBy(
                            () ->
                                    ProviderEndpointPolicy.requireOrigin(
                                            url, AirtelOpenApiCredentialSchema.SANDBOX_BASE_URL))
                    .isInstanceOf(PaymentGatewayException.class);
        for (String path :
                java.util.List.of(
                        "https://attacker.test/pay",
                        "//attacker.test/pay",
                        "/../pay",
                        "/%2e%2e/pay",
                        "/pay?target=x"))
            assertThatThrownBy(() -> ProviderEndpointPolicy.requireRelativePath(path))
                    .isInstanceOf(PaymentGatewayException.class);
        assertThatCode(
                        () ->
                                ProviderEndpointPolicy.requireOrigin(
                                        MtnMomoCredentialSchema.PRODUCTION_BASE_URL,
                                        MtnMomoCredentialSchema.PRODUCTION_BASE_URL))
                .doesNotThrowAnyException();
    }

    @Test
    void diagnosticsCannotContainProviderCredentialsOrPayloads() {
        HttpRequestResponse response = new HttpRequestResponse();
        response.setUrl("https://secret:password@provider.test?token=hidden");
        response.setRequestHeaders(
                Map.of(
                        "Authorization",
                        "Bearer secret-bearer",
                        "Ocp-Apim-Subscription-Key",
                        "secret-subscription"));
        response.setRequestData("client_secret=secret-client");
        response.setResponse("access_token=secret-token");
        response.setErrorMessage("secret-error");
        response.setStatusCode(503);
        assertThat(response.toString())
                .contains("503")
                .doesNotContain("secret", "hidden", "password", "Authorization", "access_token");
    }

    @Test
    void ambiguousMtnHttpOutcomesRemainUnresolved() {
        for (int status : new int[] {0, 408, 409, 429, 500, 503})
            assertThat(ProviderEndpointPolicy.ambiguousSubmission(status)).isTrue();
        for (int status : new int[] {400, 401, 403, 422})
            assertThat(ProviderEndpointPolicy.ambiguousSubmission(status)).isFalse();
    }
}
