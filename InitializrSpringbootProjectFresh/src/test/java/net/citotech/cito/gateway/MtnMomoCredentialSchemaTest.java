package net.citotech.cito.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MtnMomoCredentialSchemaTest {

    @Test
    void acceptsOfficialSandboxScopeWithSeparateProductCredentials() {
        assertThatCode(
                        () ->
                                MtnMomoCredentialSchema.validate(
                                        credentials(), "SANDBOX", "UG", "EUR"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsUgxForMtnSandbox() {
        Map<String, Object> credentials = credentials();
        credentials.put("baseCurrency", "UGX");

        assertThatThrownBy(
                        () -> MtnMomoCredentialSchema.validate(credentials, "SANDBOX", "UG", "UGX"))
                .isInstanceOf(PaymentGatewayException.class)
                .hasMessageContaining("must use EUR");
    }

    @Test
    void rejectsHttpsCallbackInMtnSandbox() {
        Map<String, Object> credentials = credentials();
        credentials.put("callbackUrl", "https://pay.example.com/api/v2/provider-callbacks/mtn");

        assertThatThrownBy(
                        () -> MtnMomoCredentialSchema.validate(credentials, "SANDBOX", "UG", "EUR"))
                .isInstanceOf(PaymentGatewayException.class)
                .hasMessageContaining("sandbox callbackUrl must use HTTP");
    }

    @Test
    void acceptsHttpsCallbackForUgandaProduction() {
        Map<String, Object> credentials = credentials();
        credentials.put("targetEnvironment", "mtnuganda");
        credentials.put("baseCurrency", "UGX");
        credentials.put("callbackUrl", "https://pay.example.com/api/v2/provider-callbacks/mtn");

        assertThatCode(
                        () ->
                                MtnMomoCredentialSchema.validate(
                                        credentials, "PRODUCTION", "UG", "UGX"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsCallbackUrlOnAHostDifferentFromRegisteredApiUserHost() {
        Map<String, Object> credentials = credentials();
        credentials.put("callbackUrl", "http://wrong.example/callbacks/mtn");

        assertThatThrownBy(
                        () -> MtnMomoCredentialSchema.validate(credentials, "SANDBOX", "UG", "EUR"))
                .isInstanceOf(PaymentGatewayException.class)
                .hasMessageContaining("must match");
    }

    @Test
    void derivesOfficialProductPathsAndIgnoresGenericEndpointOverrides() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("baseUrl", "https://sandbox.momodeveloper.mtn.com/");
        values.put("collectUrl", "https://untrusted.example/requesttopay");
        values.put("payoutUrl", "https://untrusted.example/transfer");

        assertThat(MtnMomoCredentialSchema.endpoint(values, "COLLECT"))
                .isEqualTo("https://sandbox.momodeveloper.mtn.com/collection/v1_0/requesttopay");
        assertThat(MtnMomoCredentialSchema.endpoint(values, "PAYOUT"))
                .isEqualTo("https://sandbox.momodeveloper.mtn.com/disbursement/v1_0/transfer");
    }

    private Map<String, Object> credentials() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("baseUrl", "https://sandbox.momodeveloper.mtn.com");
        values.put("targetEnvironment", "sandbox");
        values.put("baseCurrency", "EUR");
        values.put("callbackHost", "pay.example.com");
        values.put("callbackUrl", "http://pay.example.com/api/v2/provider-callbacks/mtn");
        values.put("collectionApiUser", "collection-user");
        values.put("collectionApiKey", "collection-key");
        values.put("collectionSubscriptionKey", "collection-subscription");
        values.put("disbursementApiUser", "disbursement-user");
        values.put("disbursementApiKey", "disbursement-key");
        values.put("disbursementSubscriptionKey", "disbursement-subscription");
        return values;
    }
}
