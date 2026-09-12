package net.citotech.cito.gateway;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import net.citotech.cito.Model.GateWayResponse;
import net.citotech.cito.platform.provider.PlatformProviderAdapter;
import net.citotech.cito.platform.provider.PlatformProviderDomain;

/**
 * Adapter contract for adding payment channels without expanding the legacy DoPayGateway switch/if
 * chain.
 *
 * <p>All payment adapters are also Cito platform provider adapters, so provider discovery,
 * readiness and certification use the same contract as communications, identity and vending.
 */
public interface PaymentChannelAdapter extends PlatformProviderAdapter {
    /** Stable machine code, for example mtn_momo, airtel_money, or safaricom_mpesa. */
    String channelCode();

    /** Human-readable display name for admin and merchant portals. */
    String displayName();

    /** Country code, for example UG or KE. */
    String countryCode();

    /** Settlement or transaction currency, for example UGX or KES. */
    String currencyCode();

    GatewayCapabilities capabilities();

    /** Return true when this adapter can route the supplied customer account. */
    boolean supportsAccount(String accountIdentifier);

    GateWayResponse collect(PaymentGatewayRequest request);

    GateWayResponse payout(PaymentGatewayRequest request);

    GateWayResponse checkStatus(PaymentStatusRequest request);

    GatewayBalance getBalance(GatewayBalanceRequest request);

    @Override
    default String providerCode() {
        return channelCode();
    }

    @Override
    default PlatformProviderDomain providerDomain() {
        return PlatformProviderDomain.PAYMENT;
    }

    @Override
    default Set<String> platformCapabilities() {
        GatewayCapabilities value = capabilities();
        Set<String> capabilities = new LinkedHashSet<>();
        if (value.supportsCollections()) capabilities.add("COLLECTION");
        if (value.supportsPayouts()) capabilities.add("PAYOUT");
        if (value.supportsBalanceCheck()) capabilities.add("BALANCE");
        if (value.supportsStatusCheck()) capabilities.add("STATUS");
        if (value.supportsRefunds()) capabilities.add("REFUND");
        if (value.supportsCallbacks()) capabilities.add("CALLBACK");
        return Set.copyOf(capabilities);
    }

    /**
     * Verifies that a provider response/callback is authentic before its result is trusted (audit
     * C9). Most adapters here are driven through synchronous request/response HTTP calls and carry
     * no verifiable signature material from the provider, so the default implementation preserves
     * existing behaviour for adapters that do not override it.
     */
    default boolean verifyCallback(
            Map<String, String> responseHeaders,
            String responseBody,
            Map<String, String> channelConfig) {
        return true;
    }
}
