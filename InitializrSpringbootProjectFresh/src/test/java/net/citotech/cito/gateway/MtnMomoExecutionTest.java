package net.citotech.cito.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import net.citotech.cito.Model.GateWayResponse;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class MtnMomoExecutionTest {

    @Test
    void collectionObtainsProductTokenAndSubmitsOfficialRequestToPayShape() throws Exception {
        AtomicReference<String> tokenAuthorization = new AtomicReference<>();
        AtomicReference<String> subscriptionAtToken = new AtomicReference<>();
        AtomicReference<String> paymentBody = new AtomicReference<>();
        AtomicReference<String> callbackUrl = new AtomicReference<>();
        AtomicReference<String> referenceId = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext(
                "/collection/token/",
                exchange -> {
                    tokenAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    subscriptionAtToken.set(
                            exchange.getRequestHeaders().getFirst("Ocp-Apim-Subscription-Key"));
                    respond(exchange, 200, "{\"access_token\":\"token-123\",\"expires_in\":3600}");
                });
        server.createContext(
                "/collection/v1_0/requesttopay",
                exchange -> {
                    paymentBody.set(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8));
                    callbackUrl.set(exchange.getRequestHeaders().getFirst("X-Callback-Url"));
                    referenceId.set(exchange.getRequestHeaders().getFirst("X-Reference-Id"));
                    assertThat(exchange.getRequestHeaders().getFirst("Authorization"))
                            .isEqualTo("Bearer token-123");
                    assertThat(exchange.getRequestHeaders().getFirst("X-Target-Environment"))
                            .isEqualTo("sandbox");
                    respond(exchange, 202, "");
                });
        server.start();
        try {
            ProviderTokenStoreService tokenStore = mock(ProviderTokenStoreService.class);
            when(tokenStore.findValid(anyString(), anyString(), anyString()))
                    .thenReturn(Optional.empty());
            ProviderEndpointExecutionService service =
                    new ProviderEndpointExecutionService(
                            mock(NamedParameterJdbcTemplate.class),
                            tokenStore,
                            new ChannelCircuitBreaker());

            GateWayResponse result =
                    service.execute(
                            "mtn_momo",
                            "MTN MoMo",
                            "COLLECT",
                            request(server.getAddress().getPort()));

            assertThat(result.getTransactionStatus()).isEqualTo("PENDING");
            assertThat(result.getNetworkId()).isEqualTo(referenceId.get());
            assertThat(callbackUrl.get())
                    .isEqualTo(
                            "https://pay.example.com/api/v2/provider-callbacks/mtn/"
                                    + referenceId.get());
            assertThat(tokenAuthorization.get())
                    .isEqualTo(
                            "Basic "
                                    + Base64.getEncoder()
                                            .encodeToString(
                                                    "collection-user:collection-key"
                                                            .getBytes(StandardCharsets.UTF_8)));
            assertThat(subscriptionAtToken.get()).isEqualTo("collection-subscription");
            JSONObject body = new JSONObject(paymentBody.get());
            assertThat(body.getString("amount")).isEqualTo("1000.0");
            assertThat(body.getString("currency")).isEqualTo("EUR");
            assertThat(body.getString("externalId")).isEqualTo("ORDER-100");
            assertThat(body.getJSONObject("payer").getString("partyIdType")).isEqualTo("MSISDN");
            assertThat(body.getJSONObject("payer").getString("partyId")).isEqualTo("46733123499");
            verify(tokenStore)
                    .save(anyString(), anyString(), anyString(), anyString(), any(Instant.class));
        } finally {
            server.stop(0);
        }
    }

    private PaymentGatewayRequest request(int port) {
        Map<String, String> credentials = new HashMap<>();
        credentials.put("baseUrl", "http://localhost:" + port);
        credentials.put("targetEnvironment", "sandbox");
        credentials.put("currency", "EUR");
        credentials.put("gatewayState", "SANDBOX");
        credentials.put("callbackUrl", "https://pay.example.com/api/v2/provider-callbacks/mtn");
        credentials.put("collectionApiUser", "collection-user");
        credentials.put("collectionApiKey", "collection-key");
        credentials.put("collectionSubscriptionKey", "collection-subscription");
        return new PaymentGatewayRequest(
                "M-1",
                "46733123499",
                1000.0,
                "ORDER-100",
                "Sandbox collection",
                "https://merchant.example/callback",
                credentials);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
