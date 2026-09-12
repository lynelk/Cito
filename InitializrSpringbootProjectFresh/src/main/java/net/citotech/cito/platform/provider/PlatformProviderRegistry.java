package net.citotech.cito.platform.provider;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * One runtime provider catalogue across Payments, Communications, Identity/Risk and Vending.
 *
 * <p>The registry does not contain secrets and does not grant production readiness. Multiple
 * channel adapters belonging to the same provider/domain are merged into one capability definition
 * instead of being treated as duplicate providers.
 */
@Service
public class PlatformProviderRegistry {
    private final Map<Key, List<PlatformProviderAdapter>> providers;

    public PlatformProviderRegistry(List<PlatformProviderAdapter> adapters) {
        Map<Key, List<PlatformProviderAdapter>> indexed = new LinkedHashMap<>();
        for (PlatformProviderAdapter adapter : adapters) {
            Key key = new Key(adapter.providerDomain(), normalize(adapter.providerCode()));
            indexed.computeIfAbsent(key, ignored -> new ArrayList<>()).add(adapter);
        }
        Map<Key, List<PlatformProviderAdapter>> immutable = new LinkedHashMap<>();
        indexed.forEach((key, values) -> immutable.put(key, List.copyOf(values)));
        this.providers = Map.copyOf(immutable);
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
        List<PlatformProviderAdapter> adapters = providers.get(key);
        return adapters == null ? Optional.empty() : Optional.of(definition(key, adapters));
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

    private ProviderDefinition definition(Key key, List<PlatformProviderAdapter> adapters) {
        Set<String> capabilities = new LinkedHashSet<>();
        Set<String> environments = new LinkedHashSet<>();
        adapters.forEach(
                adapter -> {
                    capabilities.addAll(adapter.platformCapabilities());
                    environments.addAll(adapter.supportedEnvironments());
                });
        return new ProviderDefinition(
                key.providerCode(), key.domain(), Set.copyOf(capabilities), Set.copyOf(environments));
    }

    private static String normalize(String value) {
        return Objects.requireNonNull(value, "value").trim().toUpperCase(Locale.ROOT);
    }

    private record Key(PlatformProviderDomain domain, String providerCode) {}

    public record ProviderDefinition(
            String providerCode,
            PlatformProviderDomain domain,
            Set<String> capabilities,
            Set<String> environments) {}
}
