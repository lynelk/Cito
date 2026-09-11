package net.citotech.cito.Model;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.util.Optional;
import net.citotech.cito.gateway.ProviderToken;
import net.citotech.cito.gateway.ProviderTokenStoreRegistry;
import net.citotech.cito.gateway.ProviderTokenStoreService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Covers audit K2: WireMock-based provider API mocking. {@code
 * AirtelMoneyOpenApiPaymentGateway401RefreshTest} (audit C2) proved the 401-retry flow using a
 * hand-rolled {@code com.sun.net.httpserver.HttpServer}. This complements it by demonstrating the
 * same class of test (a provider HTTP integration) built on WireMock's stubbing DSL instead -
 * covering the three response shapes a real integration must survive: a clean success, a
 * provider-declined failure, and a malformed/unparseable success body - without needing to
 * hand-write raw HTTP response bytes for each case.
 */
class AirtelMoneyOpenApiPaymentGatewayWireMockTest {

    private WireMockServer wireMockServer;

    @BeforeEach
    void startWireMock() {
        wireMockServer = new WireMockServer(0);
        wireMockServer.start();
        net.citotech.cito.gateway.ProviderHttpTestTransport.route(
                "https://openapiuat.airtel.africa", wireMockServer.baseUrl());

        ProviderTokenStoreService tokenStoreService = mock(ProviderTokenStoreService.class);
        ProviderToken validToken = mock(ProviderToken.class);
        when(validToken.getTokenValue()).thenReturn("wiremock-valid-token");
        when(tokenStoreService.findValid(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Optional.of(validToken));
        new ProviderTokenStoreRegistry(tokenStoreService);
    }

    @AfterEach
    void stopWireMockAndResetRegistry() {
        wireMockServer.stop();
        net.citotech.cito.gateway.ProviderHttpTestTransport.reset();
        new ProviderTokenStoreRegistry(null);
    }

    @Test
    void aSuccessfulDisbursementResponseIsMappedToSuccessful() throws Exception {
        wireMockServer.stubFor(
                com.github.tomakehurst.wiremock.client.WireMock.post(
                                urlEqualTo("/standard/v2/disbursements/"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                "{\"data\":{\"transaction\":{\"status\":\"TS\",\"airtel_money_id\":\"AM-WM-1\"}},"
                                                        + "\"status\":{\"result_code\":\"0\",\"message\":\"OK\"}}")));

        GateWayResponse response =
                gateway().doPayOut(1000.0, "256700000000", "wm-ref-1", "narrative");

        assertThat(response.getTransactionStatus()).isEqualTo("SUCCESSFUL");
        assertThat(response.getNetworkId()).isEqualTo("AM-WM-1");
        wireMockServer.verify(
                postRequestedFor(urlEqualTo("/standard/v2/disbursements/"))
                        .withHeader("Authorization", equalTo("Bearer wiremock-valid-token")));
    }

    @Test
    void aProviderDeclinedResponseIsMappedToFailed() throws Exception {
        wireMockServer.stubFor(
                com.github.tomakehurst.wiremock.client.WireMock.post(
                                urlEqualTo("/standard/v2/disbursements/"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                "{\"data\":{\"transaction\":{\"status\":\"TF\",\"airtel_money_id\":\"AM-WM-2\"}},"
                                                        + "\"status\":{\"result_code\":\"ESB000010\",\"message\":\"Insufficient float\"}}")));

        GateWayResponse response =
                gateway().doPayOut(1000.0, "256700000000", "wm-ref-2", "narrative");

        assertThat(response.getTransactionStatus()).isEqualTo("FAILED");
        // Audit C6: Airtel's own decline wording ("Insufficient float") is raw provider text and
        // must
        // not reach the merchant directly - only the translated, merchant-safe message does. The
        // raw
        // resultCode/message is still available internally via requestTrace for support diagnosis.
        assertThat(response.getMessage()).isEqualTo("The payment provider declined this request.");
        assertThat(response.getRequestTrace())
                .contains("httpStatus=200")
                .doesNotContain("Insufficient float", "ESB000010");
    }

    @Test
    void aProviderOutageRemainsUndeterminedAndDoesNotInviteResubmission() throws Exception {
        wireMockServer.stubFor(
                com.github.tomakehurst.wiremock.client.WireMock.post(
                                urlEqualTo("/standard/v2/disbursements/"))
                        .willReturn(
                                aResponse()
                                        .withStatus(503)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody("{\"error\":\"service_unavailable\"}")));

        GateWayResponse response =
                gateway().doPayOut(1000.0, "256700000000", "wm-ref-3", "narrative");

        assertThat(response.getStatus()).isEqualTo("ERROR");
        assertThat(response.getTransactionStatus()).isEqualTo("UNDETERMINED");
        // Audit C6: previously the raw response body ("{\"error\":\"service_unavailable\"}") was
        // handed straight to the merchant as the message. A 503 is classified as a retryable
        // provider
        // outage rather than a hard decline; the raw body is preserved in requestTrace, not
        // message.
        assertThat(response.getMessage())
                .isEqualTo("Payment outcome is not yet confirmed; check status before retrying.");
        assertThat(response.getMessage()).doesNotContain("service_unavailable");
        assertThat(response.getRequestTrace())
                .contains("httpStatus=503")
                .doesNotContain("service_unavailable");
    }

    @Test
    void aMalformedJsonSuccessBodyFailsClosedRatherThanCrashingTheCaller() throws Exception {
        wireMockServer.stubFor(
                com.github.tomakehurst.wiremock.client.WireMock.post(
                                urlEqualTo("/standard/v2/disbursements/"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody("{this is not valid json")));

        GateWayResponse response =
                gateway().doPayOut(1000.0, "256700000000", "wm-ref-4", "narrative");

        // submit()'s catch-all Exception handler maps any parsing failure to UNDETERMINED rather
        // than letting a malformed provider response propagate as an uncaught exception.
        assertThat(response.getTransactionStatus()).isEqualTo("UNDETERMINED");
    }

    @Test
    void accepted202RemainsPendingAndPreservesReference() throws Exception {
        wireMockServer.stubFor(
                com.github.tomakehurst.wiremock.client.WireMock.post(
                                urlEqualTo("/standard/v2/disbursements/"))
                        .willReturn(aResponse().withStatus(202)));
        GateWayResponse response =
                gateway().doPayOut(1000.0, "256700000000", "accepted-ref", "test");
        assertThat(response.getStatus()).isEqualTo("OK");
        assertThat(response.getTransactionStatus()).isEqualTo("PENDING");
        assertThat(response.getOurUniqueTxId()).isEqualTo("accepted-ref");
    }

    @Test
    void statusResourceNotFoundIsNotTransactionFailure() throws Exception {
        wireMockServer.stubFor(
                com.github.tomakehurst.wiremock.client.WireMock.get(
                                urlEqualTo("/standard/v2/disbursements/missing-ref/"))
                        .willReturn(aResponse().withStatus(404)));
        GateWayResponse response = gateway().checkStatus("missing-ref");
        assertThat(response.getTransactionStatus()).isEqualTo("UNDETERMINED");
        assertThat(response.getOurUniqueTxId()).isEqualTo("missing-ref");
    }

    @Test
    void acceptsPemEncodedPublicKeyForPayout() throws Exception {
        wireMockServer.stubFor(
                com.github.tomakehurst.wiremock.client.WireMock.post(
                                urlEqualTo("/standard/v2/disbursements/"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withBody(
                                                "{\"data\":{\"transaction\":{\"status\":\"TS\"}}}")));
        AirtelMoneyOpenApiPaymentGateway gateway = gateway();
        gateway.setPublicKey(
                "-----BEGIN PUBLIC KEY-----\n"
                        + generateTestPublicKeyBase64()
                        + "\n-----END PUBLIC KEY-----");
        assertThat(
                        gateway.doPayOut(1000.0, "256700000000", "pem-ref", "test")
                                .getTransactionStatus())
                .isEqualTo("SUCCESSFUL");
    }

    @Test
    void duplicateSubmissionResponseRequiresStatusLookup() throws Exception {
        wireMockServer.stubFor(
                com.github.tomakehurst.wiremock.client.WireMock.post(
                                urlEqualTo("/standard/v2/disbursements/"))
                        .willReturn(aResponse().withStatus(409)));
        GateWayResponse response =
                gateway().doPayOut(1000.0, "256700000000", "duplicate-ref", "test");
        assertThat(response.getTransactionStatus()).isEqualTo("UNDETERMINED");
    }

    private AirtelMoneyOpenApiPaymentGateway gateway() throws Exception {
        AirtelMoneyOpenApiPaymentGateway gateway = new AirtelMoneyOpenApiPaymentGateway();
        gateway.setApiDetails(
                "https://openapiuat.airtel.africa", "client-id", "client-secret", "1234");
        gateway.setTransactionContext("SANDBOX", "UG", "UGX");
        gateway.setSegment("disbursement");
        gateway.setPublicKey(generateTestPublicKeyBase64());
        return gateway;
    }

    private static String generateTestPublicKeyBase64() throws Exception {
        java.security.KeyPairGenerator generator =
                java.security.KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        java.security.KeyPair keyPair = generator.generateKeyPair();
        return java.util.Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
    }
}
