package net.citotech.cito.identity;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import net.citotech.cito.platform.provider.PlatformProviderAdapter;
import net.citotech.cito.platform.provider.PlatformProviderDomain;

/** Contract for third-party identity-verification providers. */
public interface IdentityVerificationConnector extends PlatformProviderAdapter {

    /** Stable machine code, for example {@code gnugrid}. */
    String providerCode();

    /** Whether this provider supports the synchronous request/response flow. */
    boolean supportsSync();

    /** Whether this provider can deliver results via an outbound callback. */
    boolean supportsAsync();

    /** Document types this configured connector may authoritatively verify. */
    default Set<String> supportedIdentityTypes() {
        return Set.of("NIN");
    }

    /** ISO alpha-2 countries this configured connector may authoritatively verify. */
    default Set<String> supportedCountries() {
        return Set.of("UG");
    }

    @Override
    default PlatformProviderDomain providerDomain() {
        return PlatformProviderDomain.IDENTITY;
    }

    @Override
    default Set<String> platformCapabilities() {
        Set<String> capabilities = new LinkedHashSet<>();
        if (supportsSync()) capabilities.add("VERIFY_SYNC");
        if (supportsAsync()) capabilities.add("VERIFY_ASYNC");
        supportedIdentityTypes()
                .forEach(type -> capabilities.add("IDENTITY_" + type.toUpperCase()));
        return Set.copyOf(capabilities);
    }

    /**
     * Whether this connector can satisfy the synchronous verification operation for the requested
     * document type/country. Async-only providers remain visible through the capability methods but
     * are not selected by the synchronous /verify flow.
     */
    default boolean supports(String identityType, String country) {
        return supportsSync()
                && identityType != null
                && country != null
                && supportedIdentityTypes().stream().anyMatch(identityType::equalsIgnoreCase)
                && supportedCountries().stream().anyMatch(country::equalsIgnoreCase);
    }

    IdentityRecords.VerifiedIdentity verify(IdentityRecords.IdentityVerificationRequest request);

    IdentityRecords.VerifiedIdentity parseCallback(
            String callbackBody, Map<String, String> callbackHeaders);

    boolean validateCallbackHeaders(Map<String, String> callbackHeaders);
}
