package net.citotech.cito.Model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.citotech.cito.gateway.ProviderToken;
import net.citotech.cito.gateway.ProviderTokenStoreRegistry;
import net.citotech.cito.gateway.ProviderTokenStoreService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class MobileMoneyTokenIsolationTest {
    @AfterEach
    void cleanup() {
        new ProviderTokenStoreRegistry(null);
    }

    private List<String> capturedScopes() {
        List<String> scopes = new ArrayList<>();
        ProviderTokenStoreService store = mock(ProviderTokenStoreService.class);
        ProviderToken token = new ProviderToken();
        token.setTokenValue("mock-only-token");
        when(store.findValid(anyString(), anyString(), anyString()))
                .thenAnswer(
                        call -> {
                            scopes.add(call.getArgument(1));
                            return Optional.of(token);
                        });
        new ProviderTokenStoreRegistry(store);
        return scopes;
    }

    @Test
    void airtelClientsAndSecretRotationNeverShareTokens() throws Exception {
        List<String> scopes = capturedScopes();
        AirtelMoneyOpenApiPaymentGateway gateway = new AirtelMoneyOpenApiPaymentGateway();
        gateway.setApiDetails("https://openapi.airtel.africa", "client-a", "secret-a", "");
        gateway.getToken();
        gateway.setApiDetails("https://openapi.airtel.africa", "client-b", "secret-a", "");
        gateway.getToken();
        gateway.setApiDetails("https://openapi.airtel.africa", "client-b", "secret-b", "");
        gateway.getToken();
        gateway.setSegment("disbursement");
        gateway.getToken();
        assertThat(scopes).doesNotHaveDuplicates().hasSize(4);
        assertThat(scopes)
                .allSatisfy(scope -> assertThat(scope).doesNotContain("secret-", "client-"));
    }

    @Test
    void mtnApiKeyRotationAndProductNeverShareTokens() throws Exception {
        List<String> scopes = capturedScopes();
        MTNMoMoPaymentGateway gateway = new MTNMoMoPaymentGateway();
        gateway.setApiDetails(
                "https://proxy.momoapi.mtn.com",
                "user",
                "key-a",
                "subscription",
                "payout-user",
                "payout-key",
                "payout-sub",
                "mtnuganda",
                "UGX");
        gateway.getToken();
        gateway.setApiDetails(
                "https://proxy.momoapi.mtn.com",
                "user",
                "key-b",
                "subscription",
                "payout-user",
                "payout-key",
                "payout-sub",
                "mtnuganda",
                "UGX");
        gateway.getToken();
        gateway.setSegment("disbursement");
        gateway.getToken();
        assertThat(scopes).doesNotHaveDuplicates().hasSize(3);
    }
}
