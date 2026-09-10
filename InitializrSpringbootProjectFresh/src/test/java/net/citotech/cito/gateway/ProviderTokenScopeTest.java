package net.citotech.cito.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProviderTokenScopeTest {
    @Test
    void shortTokenLifetimeIsNeverExtended() {
        Instant before = Instant.now();
        Instant expires = ProviderTokenScope.expiresAt(5);
        assertThat(expires).isAfter(before).isBeforeOrEqualTo(Instant.now().plusSeconds(5));
    }

    @Test
    void nonpositiveTokenLifetimeIsRejected() {
        assertThatThrownBy(() -> ProviderTokenScope.expiresAt(0))
                .isInstanceOf(PaymentGatewayException.class);
        assertThatThrownBy(() -> ProviderTokenScope.expiresAt(-1))
                .isInstanceOf(PaymentGatewayException.class);
    }

    @Test
    void framingPreventsDelimiterCollisions() {
        assertThat(ProviderTokenScope.segment("collection", "a|b", "c"))
                .isNotEqualTo(ProviderTokenScope.segment("collection", "a", "b|c"));
        assertThat(ProviderTokenScope.segment("disbursement", "secret"))
                .hasSize(77)
                .doesNotContain("secret");
    }

    @Test
    void mtnCredentialRotationAndEndpointChangeAlterScope() {
        Map<String, String> credentials =
                new LinkedHashMap<>(
                        Map.of(
                                "baseUrl",
                                "https://proxy.momoapi.mtn.com",
                                "collectionApiUser",
                                "u",
                                "collectionApiKey",
                                "k1",
                                "collectionSubscriptionKey",
                                "s"));
        String original = MtnMomoCredentialSchema.tokenSegment(credentials, "COLLECT");
        credentials.put("collectionApiKey", "k2");
        String rotated = MtnMomoCredentialSchema.tokenSegment(credentials, "COLLECT");
        credentials.put("baseUrl", "https://sandbox.momodeveloper.mtn.com");
        String changedEndpoint = MtnMomoCredentialSchema.tokenSegment(credentials, "COLLECT");
        assertThat(original).isNotEqualTo(rotated);
        assertThat(rotated).isNotEqualTo(changedEndpoint);
    }
}
