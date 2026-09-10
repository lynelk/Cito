package net.citotech.cito.communication.provider;

import net.citotech.cito.communication.domain.CommunicationChannel;
import net.citotech.cito.communication.sms.SmsGatewayAdapter;
import net.citotech.cito.communication.sms.SmsSendRequest;
import net.citotech.cito.communication.sms.SmsSendResult;

/** Compatibility bridge from the channel-neutral provider SPI to SMS gateway adapters. */
public final class SmsCommunicationProviderAdapter implements CommunicationProviderAdapter {

    private final SmsGatewayAdapter delegate;
    private final String providerCode;

    public SmsCommunicationProviderAdapter(SmsGatewayAdapter delegate, String providerCode) {
        this.delegate = delegate;
        this.providerCode = providerCode;
    }

    @Override
    public String providerCode() {
        return providerCode;
    }

    @Override
    public CommunicationChannel channel() {
        return CommunicationChannel.SMS;
    }

    @Override
    public ProviderCapabilities capabilities() {
        return ProviderCapabilities.builder().send(true).build();
    }

    @Override
    public ProviderSendResult send(ProviderSendRequest request) {
        Object senderValue = request.metadata() == null ? null : request.metadata().get("senderId");
        String senderId =
                senderValue == null || String.valueOf(senderValue).isBlank()
                        ? null
                        : String.valueOf(senderValue).trim();
        SmsSendResult result =
                delegate.send(
                        new SmsSendRequest(
                                request.deliveryId(),
                                request.merchantId(),
                                request.content(),
                                request.recipient(),
                                providerCode,
                                senderId));
        return SmsResultMapper.toProviderResult(providerCode, result);
    }
}
