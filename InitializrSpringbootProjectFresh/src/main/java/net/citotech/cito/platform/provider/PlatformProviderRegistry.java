package net.citotech.cito.platform.provider;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * One runtime provider catalogue across Payments, Communications, Identity/Risk and Vending.
 *
 * <p>The registry does not contain secrets and does not grant production readiness. It exposes the
 * code-level provider capability surface so admin/service-readiness views can combine it with
 * entitlement, credential, sandbox-test and certification evidence from their owning stores.
 */
@Service
public class PlatformProviderRegistry {
    private final Map<Key, PlatformProviderAdapter> providers;

    public PlatformProviderRegistry(List<PlatformProviderAdapter> adapters) {
        Map<Key, PlatformProviderAdapter> indexed = new LinkedHashMap<>();
        for (PlatformProviderAdapter adapter : adapters) {
            Key key = new Key(adapter.providerDomain(), normalize(adapter.providerCode()));
            PlatformProviderAdapter previous = indexed.putIfAbsent(key, adapter);
            if (previous != null && previous != adapter) {
                throw new IllegalStateException(
                        "Duplicate Cito provider registration for "
                                + key.domain()
                                + ":"
                                + key.providerCode());
            }
        }
        this.providers = Map.copyOf(indexed);
    }

    public List<ProviderDefinition> definitions() {
        return providers.entrySet().stream()
                .map(entry -> definition(entry.getKey(), entry.getValue()))
                .sorted(
                        Comparator.comparing((ProviderDefinition value) -> value.domain().name())
                                .thenComparing(ProviderDefinition::providerCode))
                .toList();
    }

    public Optional<ProviderDefinition> find(PlatformProviderDomain domain, String providerCode) {
        if (domain == null || providerCode == null || providerCode.isBlank()) {
            return Optional.empty();
        }
        Key key = new Key(domain, normalize(providerCode));
        PlatformProviderAdapter adapter = providers.get(key);
        return adapter == null ? Optional.empty() : Optional.of(definition(key, adapter));
    }

    public boolean supports(
            PlatformProviderDomain domain, String providerCode, String capability) {
        if (capability == null || capability.isBlank()) {
            return false;
        }
        String normalized = normalize(capability);
        return find(domain, providerCode)
                .map(
                        definition ->
                                definition.capabilities().stream()
                                        .map(PlatformProviderRegistry::normalize)
                                        .anyMatch(normalized::equals))
                .orElse(false);
    }

    private ProviderDefinition definition(Key key, PlatformProviderAdapter adapter) {
        return new ProviderDefinition(
                key.providerCode(),
                key.domain(),
                adapter.platformCapabilities(),
                adapter.supportedEnvironments());
    }

    private static String normalize(String value) {
        return Objects.requireNonNull(value, "value").trim().toUpperCase(Locale.ROOT);
    }

    private record Key(PlatformProviderDomain domain, String providerCode) {}

    public record ProviderDefinition(
            String providerCode,
            PlatformProviderDomain domain,
            java.util.Set<String> capabilities,
            java.util.Set<String> environments) {}
}
