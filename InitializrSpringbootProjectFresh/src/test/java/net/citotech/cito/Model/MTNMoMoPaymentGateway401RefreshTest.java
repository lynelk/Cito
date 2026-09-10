package net.citotech.cito.Model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.citotech.cito.gateway.ProviderToken;
import net.citotech.cito.gateway.ProviderTokenStoreRegistry;
import net.citotech.cito.gateway.ProviderTokenStoreService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Covers audit C2 for MTNMoMoPaymentGateway: a token our own DB-backed store still considers valid
 * can be rejected by the provider (revoked early, clock skew, provider-side session invalidation) -
 * previously every non-2xx/202 response, 401 included, was treated as a hard failure with no retry.
 *
 * <p>Two things are verified with a real local HTTP server (so the actual request/response cycle is
 * exercised, not just the decision logic):
 *
 * <ol>
 *   <li>a 401 triggers exactly one forced token refresh and retry;
 *   <li>single-flight: when multiple concurrent callers hit a 401 for the same gateway/segment at
 *       the same time, only one of them actually calls the provider's token endpoint - the lock
 *       table backing this is static (see MTNMoMoPaymentGateway.TOKEN_REFRESH_LOCKS), not an
 *       instance field, because this gateway is constructed fresh per merchant channel config
 *       rather than held as a Spring singleton, so the test deliberately uses two separate gateway
 *       instances to prove the coordination is not merely per-instance.
 * </ol>
 */
class MTNMoMoPaymentGateway401RefreshTest {

    @AfterEach
    void resetTokenRegistry() {
        net.citotech.cito.gateway.ProviderHttpTestTransport.reset();
        new ProviderTokenStoreRegistry(null);
    }

    @Test
    void retriesExactlyOnceWithAFreshTokenAfterA401() throws Exception {
        AtomicInteger businessCalls = new AtomicInteger(0);
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext(
                "/collection/token/",
                exchange -> {
                    byte[] body = "{\"access_token\":\"fresh-token\"}".getBytes();
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.createContext(
                "/collection/v1_0/requesttopay",
                exchange -> {
                    int callNumber = businessCalls.incrementAndGet();
                    String auth = exchange.getRequestHeaders().getFirst("Authorization");
                    byte[] body;
                    int status;
                    if (callNumber == 1) {
                        // First call: still carrying the stale token our store thought was valid.
                        assertThat(auth).isEqualTo("Bearer stale-token");
                        status = 401;
                        body = "{\"code\":\"INVALID_TOKEN\"}".getBytes();
                    } else {
                        assertThat(auth).isEqualTo("Bearer fresh-token");
                        status = 202;
                        body = "{}".getBytes();
                    }
                    exchange.sendResponseHeaders(status, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.start();

        try {
            ProviderTokenStoreService tokenStoreService = mock(ProviderTokenStoreService.class);
            ProviderToken staleToken = mock(ProviderToken.class);
            when(staleToken.getTokenValue()).thenReturn("stale-token");
            when(tokenStoreService.findValid(anyString(), anyString(), anyString()))
                    .thenReturn(Optional.of(staleToken));
            new ProviderTokenStoreRegistry(tokenStoreService);

            MTNMoMoPaymentGateway gateway =
                    newGateway("http://localhost:" + server.getAddress().getPort());

            GateWayResponse response =
                    gateway.doPayIn(1000.0, "256770000000", "ref-1", "narrative");

            assertThat(businessCalls.get()).isEqualTo(2);
            assertThat(response.getTransactionStatus()).isEqualTo("PENDING");
            assertThat(response.getStatus()).isEqualTo("OK");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void singleFlightRefreshesTokenOnlyOnceUnderConcurrent401s() throws Exception {
        AtomicInteger tokenEndpointCalls = new AtomicInteger(0);
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext(
                "/collection/token/",
                exchange -> {
                    tokenEndpointCalls.incrementAndGet();
                    byte[] body = "{\"access_token\":\"fresh-token\"}".getBytes();
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.createContext(
                "/collection/v1_0/requesttopay",
                exchange -> {
                    String auth = exchange.getRequestHeaders().getFirst("Authorization");
                    byte[] body;
                    int status;
                    if ("Bearer fresh-token".equals(auth)) {
                        status = 202;
                        body = "{}".getBytes();
                    } else {
                        status = 401;
                        body = "{\"code\":\"INVALID_TOKEN\"}".getBytes();
                    }
                    exchange.sendResponseHeaders(status, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.start();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // A store double that behaves like the real DB-backed one: findValid() returns
            // whatever save() last wrote, starting out with a token the provider will reject.
            AtomicReference<String> storedToken = new AtomicReference<>("stale-token");
            ProviderTokenStoreService tokenStoreService = mock(ProviderTokenStoreService.class);
            when(tokenStoreService.findValid(anyString(), anyString(), anyString()))
                    .thenAnswer(
                            invocation -> {
                                ProviderToken token = mock(ProviderToken.class);
                                when(token.getTokenValue()).thenReturn(storedToken.get());
                                return Optional.of(token);
                            });
            doAnswer(
                            invocation -> {
                                storedToken.set(invocation.getArgument(3, String.class));
                                return null;
                            })
                    .when(tokenStoreService)
                    .save(anyString(), anyString(), anyString(), anyString(), any());
            new ProviderTokenStoreRegistry(tokenStoreService);

            String baseUrl = "http://localhost:" + server.getAddress().getPort();
            MTNMoMoPaymentGateway gatewayA = newGateway(baseUrl);
            MTNMoMoPaymentGateway gatewayB = newGateway(baseUrl);

            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            Callable<GateWayResponse> taskA =
                    () -> {
                        ready.countDown();
                        go.await();
                        return gatewayA.doPayIn(1000.0, "256770000001", "ref-a", "narrative");
                    };
            Callable<GateWayResponse> taskB =
                    () -> {
                        ready.countDown();
                        go.await();
                        return gatewayB.doPayIn(1000.0, "256770000002", "ref-b", "narrative");
                    };

            Future<GateWayResponse> futureA = pool.submit(taskA);
            Future<GateWayResponse> futureB = pool.submit(taskB);
            ready.await();
            go.countDown();

            GateWayResponse responseA = futureA.get(10, TimeUnit.SECONDS);
            GateWayResponse responseB = futureB.get(10, TimeUnit.SECONDS);

            // The crux of C2's single-flight requirement: despite two concurrent, separate gateway
            // instances each independently hitting a 401, the provider's token endpoint is called
            // exactly once - the second caller waits on the static lock and then reuses the token
            // the first caller already saved, instead of requesting a second one of its own.
            assertThat(tokenEndpointCalls.get()).isEqualTo(1);
            assertThat(responseA.getTransactionStatus()).isEqualTo("PENDING");
            assertThat(responseB.getTransactionStatus()).isEqualTo("PENDING");
        } finally {
            pool.shutdownNow();
            server.stop(0);
        }
    }

    private static MTNMoMoPaymentGateway newGateway(String baseUrl) {
        MTNMoMoPaymentGateway gateway = new MTNMoMoPaymentGateway();
        net.citotech.cito.gateway.ProviderHttpTestTransport.route(
                "https://sandbox.momodeveloper.mtn.com", baseUrl);
        gateway.setApiDetails(
                "https://sandbox.momodeveloper.mtn.com",
                "collections-user",
                "collections-key",
                "collections-subscription",
                "disbursements-user",
                "disbursements-key",
                "disbursements-subscription",
                "sandbox",
                "EUR");
        gateway.setSegment("collection");
        return gateway;
    }
}
