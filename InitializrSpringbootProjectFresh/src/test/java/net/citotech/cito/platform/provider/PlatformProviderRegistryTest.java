package net.citotech.cito.platform.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PlatformProviderRegistryTest {
    @Test
    void oneProviderCatalogueMergesChannelMetadataButKeepsDomainBoundaries() {
        PlatformProviderRegistry registry = new PlatformProviderRegistry(List.of(
                adapter(" shared ", PlatformProviderDomain.COMMUNICATION, Set.of("SEND", "CHANNEL_SMS")),
                adapter("SHARED", PlatformProviderDomain.COMMUNICATION, Set.of("SEND", "CHANNEL_EMAIL")),
                adapter("shared", PlatformProviderDomain.PAYMENT, Set.of("COLLECTION")),
                adapter("identity", PlatformProviderDomain.IDENTITY, Set.of("VERIFY_SYNC")),
                adapter("device", PlatformProviderDomain.VENDING, Set.of("DEVICE_COMMAND"))));
        assertThat(registry.definitions()).hasSize(4);
        assertThat(registry.find(PlatformProviderDomain.COMMUNICATION, "ShArEd").orElseThrow().capabilities())
                .containsExactlyInAnyOrder("SEND", "CHANNEL_SMS", "CHANNEL_EMAIL");
        assertThat(registry.supports(PlatformProviderDomain.PAYMENT, "shared", "collection")).isTrue();
        assertThat(registry.supports(PlatformProviderDomain.PAYMENT, "shared", "SEND")).isFalse();
        assertThat(registry.supports(PlatformProviderDomain.COMMUNICATION, "shared", "COLLECTION")).isFalse();
        assertThat(registry.find(null, "shared")).isEmpty();
        assertThat(registry.find(PlatformProviderDomain.PAYMENT, " ")).isEmpty();
    }

    @Test
    void exposedCapabilityMetadataIsImmutableAndIsNotAnActivationDecision() {
        PlatformProviderRegistry registry = new PlatformProviderRegistry(List.of(
                adapter("test", PlatformProviderDomain.VENDING, Set.of("DEVICE_COMMAND"))));
        var definition = registry.definitions().getFirst();
        assertThat(definition.environments()).containsExactly("SANDBOX");
        assertThatThrownBy(() -> definition.capabilities().add("PAYOUT")).isInstanceOf(UnsupportedOperationException.class);
        assertThat(registry.supports(PlatformProviderDomain.VENDING, "test", "CERTIFIED")).isFalse();
    }

    private PlatformProviderAdapter adapter(String code, PlatformProviderDomain domain, Set<String> capabilities) {
        return new PlatformProviderAdapter() {
            public String providerCode() { return code; }
            public PlatformProviderDomain providerDomain() { return domain; }
            public Set<String> platformCapabilities() { return capabilities; }
            public Set<String> supportedEnvironments() { return Set.of("SANDBOX"); }
        };
    }
}
