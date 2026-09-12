package net.citotech.cito.communication.provider;

import java.util.LinkedHashSet;
import java.util.Set;
import net.citotech.cito.communication.domain.CommunicationChannel;
import net.citotech.cito.platform.provider.PlatformProviderAdapter;
import net.citotech.cito.platform.provider.PlatformProviderDomain;

/**
 * Channel-neutral provider boundary for Cito Communications. Every channel adapter implements this
 * interface so routing and platform readiness can reason about providers through one shared Cito
 * provider contract instead of channel-specific registries leaking into the control plane.
 */
public interface CommunicationProviderAdapter extends PlatformProviderAdapter {

    /** Stable provider identifier used by communication provider configuration and routing rules. */
    String providerCode();

    /** Channel this adapter serves. */
    CommunicationChannel channel();

    /** Capability flags of this adapter. */
    ProviderCapabilities capabilities();

    /** Executes one send and returns a normalized, PII-safe result. */
    ProviderSendResult send(ProviderSendRequest request);

    /** Health signal; defaults to UNKNOWN until the provider health service observes traffic. */
    default ProviderHealth health() {
        return new ProviderHealth(ProviderHealth.State.UNKNOWN, null, null);
    }

    @Override
    default PlatformProviderDomain providerDomain() {
        return PlatformProviderDomain.COMMUNICATION;
    }

    @Override
    default Set<String> platformCapabilities() {
        ProviderCapabilities value = capabilities();
        Set<String> capabilities = new LinkedHashSet<>();
        if (value.send()) capabilities.add("SEND");
        if (value.templates()) capabilities.add("TEMPLATE");
        if (value.deliveryReceipts()) capabilities.add("DELIVERY_RECEIPT");
        if (value.inbound()) capabilities.add("INBOUND");
        if (value.statusQuery()) capabilities.add("STATUS");
        capabilities.add("CHANNEL_" + channel().name());
        return Set.copyOf(capabilities);
    }
}
