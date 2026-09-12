package net.citotech.cito.platform.provider;

import java.util.Set;

/**
 * Common provider contract for every provider-backed Cito domain.
 *
 * <p>Domain-specific adapters keep their own typed operations, but they all expose provider
 * identity, domain, capability and environment metadata through this one platform contract. This
 * lets service discovery, readiness, operations and certification reason about MTN, Airtel, SMS,
 * WhatsApp, identity/CRB and vending providers without importing domain-specific classes.
 */
public interface PlatformProviderAdapter {
    String providerCode();

    PlatformProviderDomain providerDomain();

    Set<String> platformCapabilities();

    default Set<String> supportedEnvironments() {
        return Set.of("SANDBOX", "PRODUCTION");
    }
}
