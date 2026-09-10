package net.citotech.cito.Model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import net.citotech.cito.gateway.ProviderToken;
import net.citotech.cito.gateway.ProviderTokenStoreRegistry;
import net.citotech.cito.gateway.ProviderTokenStoreService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Covers audit C2: a token our own store still considers valid can be rejected by the provider
 * (revoked early, clock skew, provider-side session invalidation) - previously every non-200
 * response, 401 included, was treated as a hard failure with no retry. This verifies a 401 now
 * triggers exactly one forced token refresh and retry, using a real local HTTP server so the actual
 * request/response cycle (not just the decision logic) is exercised.
 */
class AirtelMoneyOpenApiPaymentGateway401RefreshTest {

    @AfterEach
    void resetTokenRegistry() {
        new ProviderTokenStoreRegistry(null);
    }

    @Test
    void retriesExactlyOnceWithAFreshTokenAfterA401() throws Exception {
        AtomicInteger disbursementCalls = new AtomicInteger(0);
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext(
                "/auth/oauth2/token",
                exchange -> {
                    byte[] body = "{\"access_token\":\"fresh-token\"}".getBytes();
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.createContext(
                "/standard/v2/disbursements/",
                exchange -> {
                    int callNumber = disbursementCalls.incrementAndGet();
                    String auth = exchange.getRequestHeaders().getFirst("Authorization");
                    byte[] body;
                    int status;
                    if (callNumber == 1) {
                        // First call: still carrying the stale token our store thought was valid.
                        assertThat(auth).isEqualTo("Bearer stale-token");
                        status = 401;
                        body = "{\"error\":\"invalid_token\"}".getBytes();
                    } else {
                        assertThat(auth).isEqualTo("Bearer fresh-token");
                        status = 200;
                        body =
                                ("{\"data\":{\"transaction\":{\"status\":\"TS\",\"airtel_money_id\":\"AM123\"}},"
                                                + "\"status\":{\"result_code\":\"0\",\"message\":\"OK\"}}")
                                        .getBytes();
                    }
                    exchange.sendResponseHeaders(status, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.start();
        net.citotech.cito.gateway.ProviderHttpTestTransport.route(
                "https://openapiuat.airtel.africa",
                "http://localhost:" + server.getAddress().getPort());

        try {
            ProviderTokenStoreService tokenStoreService = mock(ProviderTokenStoreService.class);
            ProviderToken staleToken = mock(ProviderToken.class);
            when(staleToken.getTokenValue()).thenReturn("stale-token");
            when(tokenStoreService.findValid(
                            org.mockito.ArgumentMatchers.anyString(),
                            org.mockito.ArgumentMatchers.anyString(),
                            org.mockito.ArgumentMatchers.anyString()))
                    .thenReturn(Optional.of(staleToken));
            new ProviderTokenStoreRegistry(tokenStoreService);

            AirtelMoneyOpenApiPaymentGateway gateway = new AirtelMoneyOpenApiPaymentGateway();
            gateway.setApiDetails(
                    "https://openapiuat.airtel.africa", "client-id", "client-secret", "1234");
            gateway.setTransactionContext("SANDBOX", "UG", "UGX");
            gateway.setSegment("disbursement");
            gateway.setPublicKey(generateTestPublicKeyBase64());

            GateWayResponse response =
                    gateway.doPayOut(1000.0, "256700000000", "ref-1", "narrative");

            assertThat(disbursementCalls.get()).isEqualTo(2);
            assertThat(response.getTransactionStatus()).isEqualTo("SUCCESSFUL");
        } finally {
            server.stop(0);
            net.citotech.cito.gateway.ProviderHttpTestTransport.reset();
        }
    }

    /**
     * A real, freshly-generated RSA public key, base64-encoded with no PEM markers - matching
     * exactly what {@code setPublicKey}/{@code RSAUtil.encrypt} expect (this class does not strip
     * PEM headers itself, unlike the private-key handling elsewhere in this codebase). This test
     * never decrypts anything or talks to a real Airtel environment; it only needs something
     * structurally valid for RSAUtil.encrypt(pin, key) to encrypt against.
     */
    private static String generateTestPublicKeyBase64() throws Exception {
        java.security.KeyPairGenerator generator =
                java.security.KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        java.security.KeyPair keyPair = generator.generateKeyPair();
        return java.util.Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
    }
}
