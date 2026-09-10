package net.citotech.cito.communication.provider;

import net.citotech.cito.Model.SmsDeliveryStatus;
import net.citotech.cito.communication.sms.SmsSendResult;

/**
 * Maps a legacy {@link SmsSendResult} into the channel-neutral {@link ProviderSendResult} (ISO
 * domain mapping: communication/provider). The mapping preserves the existing SMS semantics while
 * carrying the provider message id through to the canonical delivery log so delivery receipts can
 * be correlated deterministically.
 */
public final class SmsResultMapper {

    private SmsResultMapper() {}

    public static ProviderSendResult toProviderResult(String providerCode, SmsSendResult result) {
        if (result == null || result.status() == null) {
            return ProviderSendResult.unknown(providerCode, "no SMS result", "");
        }
        return switch (result.status()) {
            case SENT -> ProviderSendResult.sent(providerCode, result.providerMessageId(), "SENT");
            case REJECTED ->
                    ProviderSendResult.rejected(
                            providerCode, "REJECTED", result.trace(), result.gwResponse());
            case FAILED ->
                    ProviderSendResult.failed(
                            providerCode,
                            "TRANSPORT_FAILURE",
                            result.trace(),
                            result.gwResponse(),
                            true);
            default ->
                    ProviderSendResult.unknown(providerCode, result.trace(), result.gwResponse());
        };
    }

    /** Package-internal helper kept for completeness of the status vocabulary. */
    static SmsDeliveryStatus normalizeStatus(ProviderSendResult.Status status) {
        return switch (status) {
            case ACCEPTED, SENT, DELIVERED -> SmsDeliveryStatus.SENT;
            case REJECTED -> SmsDeliveryStatus.REJECTED;
            case FAILED, UNKNOWN -> SmsDeliveryStatus.FAILED;
        };
    }
}
