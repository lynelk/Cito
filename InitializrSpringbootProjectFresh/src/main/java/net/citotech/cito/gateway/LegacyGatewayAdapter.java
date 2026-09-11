package net.citotech.cito.gateway;

import net.citotech.cito.Model.GateWayResponse;

/**
 * Base class for provider adapters that can run independently of the v2 orchestration switch path.
 */
public abstract class LegacyGatewayAdapter implements PaymentChannelAdapter {
    private final String channelCode;
    private final String displayName;
    private final String countryCode;
    private final String currencyCode;
    private final String legacyGatewayId;
    private final String[] supportedPrefixes;

    protected LegacyGatewayAdapter(
            String channelCode,
            String displayName,
            String countryCode,
            String currencyCode,
            String legacyGatewayId,
            String... supportedPrefixes) {
        this.channelCode = channelCode;
        this.displayName = displayName;
        this.countryCode = countryCode;
        this.currencyCode = currencyCode;
        this.legacyGatewayId = legacyGatewayId;
        this.supportedPrefixes = supportedPrefixes == null ? new String[0] : supportedPrefixes;
    }

    @Override
    public String channelCode() {
        return channelCode;
    }

    @Override
    public String displayName() {
        return displayName;
    }

    @Override
    public String countryCode() {
        return countryCode;
    }

    @Override
    public String currencyCode() {
        return currencyCode;
    }

    public String legacyGatewayId() {
        return legacyGatewayId;
    }

    @Override
    public GatewayCapabilities capabilities() {
        return new GatewayCapabilities(true, true, false, false, false, false);
    }

    @Override
    public boolean supportsAccount(String accountIdentifier) {
        if (accountIdentifier == null) return false;
        String trimmed = accountIdentifier.trim();
        Boolean routed =
                net.citotech.cito.gateway.ChannelRoutingRegistry.matchesConfiguredPrefix(
                        legacyGatewayId, trimmed);
        if (routed != null) return routed;
        for (String prefix : supportedPrefixes) {
            if (trimmed.startsWith(prefix)) return true;
        }
        return false;
    }

    @Override
    public GateWayResponse collect(PaymentGatewayRequest request) {
        return accepted("COLLECT", request);
    }

    @Override
    public GateWayResponse payout(PaymentGatewayRequest request) {
        return accepted("PAYOUT", request);
    }

    @Override
    public GateWayResponse checkStatus(PaymentStatusRequest request) {
        throw new PaymentGatewayException(
                "Direct adapter status lookup is unavailable; query the canonical transaction status");
    }

    @Override
    public GatewayBalance getBalance(GatewayBalanceRequest request) {
        return new GatewayBalance(
                channelCode,
                currencyCode,
                null,
                "Adapter balance check requires provider credentials");
    }

    private GateWayResponse accepted(String operation, PaymentGatewayRequest request) {
        GateWayResponse response = new GateWayResponse();
        response.setStatus("SUCCESS");
        response.setTransactionStatus("SUBMITTED");
        response.setMessage(
                displayName + " " + operation + " submitted through adapter-native v2 path");
        response.setHttpStatus("202");
        response.setNetworkId(channelCode + "-" + request.getReference());
        response.setRequestTrace("adapter=" + channelCode + ", operation=" + operation);
        return response;
    }
}
