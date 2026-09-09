package net.citotech.cito.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.citotech.cito.Common;
import net.citotech.cito.Model.HttpRequestResponse;
import org.junit.jupiter.api.Test;

class MtnMomoStatusClientTest {

    @Test
    void verifiesCollectionThroughProductOauthAndOfficialStatusEndpoint() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> statusUrl = new AtomicReference<>();
        AtomicReference<Map<String, String>> statusHeaders = new AtomicReference<>();
        AtomicReference<Map<String, String>> tokenHeaders = new AtomicReference<>();
        ProviderTokenStoreService tokenStore = mock(ProviderTokenStoreService.class);
        when(tokenStore.findValid(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());

        Common.setOutboundHttpExecutor(
                (method, url, data, headers) -> {
                    int call = calls.incrementAndGet();
                    HttpRequestResponse response = new HttpRequestResponse();
                    response.setUrl(url);
                    response.setRequestData(data);
                    response.setRequestHeaders(headers);
                    response.setErrorMessage("");
                    if (call == 1) {
                        tokenHeaders.set(new LinkedHashMap<>(headers));
                        response.setStatusCode(200);
                        response.setResponse(
                                "{\"access_token\":\"verified-token\",\"expires_in\":3600}");
                    } else {
                        statusUrl.set(url);
                        statusHeaders.set(new LinkedHashMap<>(headers));
                        response.setStatusCode(200);
                        response.setResponse(
                                "{\"status\":\"SUCCESSFUL\",\"externalId\":\"ORDER-42\","
                                        + "\"financialTransactionId\":\"MTN-9001\"}");
                    }
                    return response;
                });

        try {
            MtnMomoStatusClient.VerifiedStatus verified =
                    new MtnMomoStatusClient(tokenStore)
                            .verify(
                                    "COLLECT",
                                    "8e7f67ca-0e25-4c95-9d2e-6d88b09d02e0",
                                    "SANDBOX",
                                    "UG",
                                    "EUR",
                                    credentials());

            assertThat(verified.status()).isEqualTo("SUCCESSFUL");
            assertThat(verified.externalId()).isEqualTo("ORDER-42");
            assertThat(verified.financialTransactionId()).isEqualTo("MTN-9001");
            assertThat(calls.get()).isEqualTo(2);
            assertThat(tokenHeaders.get())
                    .containsEntry(
                            "Authorization",
                            "Basic "
                                    + Base64.getEncoder()
                                            .encodeToString(
                                                    "collection-user:collection-key"
                                                            .getBytes(StandardCharsets.UTF_8)))
                    .containsEntry("Ocp-Apim-Subscription-Key", "collection-subscription");
            assertThat(statusUrl.get())
                    .isEqualTo(
                            "https://sandbox.momodeveloper.mtn.com/collection/v1_0/requesttopay/"
                                    + "8e7f67ca-0e25-4c95-9d2e-6d88b09d02e0");
            assertThat(statusHeaders.get())
                    .containsEntry("Authorization", "Bearer verified-token")
                    .containsEntry("Ocp-Apim-Subscription-Key", "collection-subscription")
                    .containsEntry("X-Target-Environment", "sandbox");
            verify(tokenStore)
                    .save(anyString(), anyString(), anyString(), anyString(), any(Instant.class));
        } finally {
            Common.setOutboundHttpExecutor(null);
        }
    }

    @Test
    void reusesCachedProductTokenWithoutCallingOauth() {
        ProviderTokenStoreService tokenStore = mock(ProviderTokenStoreService.class);
        ProviderToken cached = new ProviderToken();
        cached.setTokenValue("cached-token");
        when(tokenStore.findValid(anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(cached));
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Map<String, String>> headersSeen = new AtomicReference<>();

        Common.setOutboundHttpExecutor(
                (method, url, data, headers) -> {
                    calls.incrementAndGet();
                    headersSeen.set(new LinkedHashMap<>(headers));
                    HttpRequestResponse response = new HttpRequestResponse();
                    response.setStatusCode(200);
                    response.setResponse("{\"status\":\"PENDING\",\"externalId\":\"ORDER-42\"}");
                    return response;
                });
        try {
            MtnMomoStatusClient.VerifiedStatus verified =
                    new MtnMomoStatusClient(tokenStore)
                            .verify(
                                    "COLLECT",
                                    "8e7f67ca-0e25-4c95-9d2e-6d88b09d02e0",
                                    "SANDBOX",
                                    "UG",
                                    "EUR",
                                    credentials());

            assertThat(verified.status()).isEqualTo("PENDING");
            assertThat(calls.get()).isEqualTo(1);
            assertThat(headersSeen.get()).containsEntry("Authorization", "Bearer cached-token");
        } finally {
            Common.setOutboundHttpExecutor(null);
        }
    }

    private Map<String, Object> credentials() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("baseUrl", "https://sandbox.momodeveloper.mtn.com");
        values.put("targetEnvironment", "sandbox");
        values.put("baseCurrency", "EUR");
        values.put("callbackHost", "pay.example.com");
        values.put("callbackUrl", "https://pay.example.com/api/v2/provider-callbacks/mtn");
        values.put("collectionApiUser", "collection-user");
        values.put("collectionApiKey", "collection-key");
        values.put("collectionSubscriptionKey", "collection-subscription");
        values.put("disbursementApiUser", "disbursement-user");
        values.put("disbursementApiKey", "disbursement-key");
        values.put("disbursementSubscriptionKey", "disbursement-subscription");
        return values;
    }
}
