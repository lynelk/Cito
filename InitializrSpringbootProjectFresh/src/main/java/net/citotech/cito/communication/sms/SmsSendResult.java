package net.citotech.cito.communication.sms;

import net.citotech.cito.Model.SmsDeliveryStatus;

/**
 * Outcome of one logical SMS send, normalized to the {PENDING, SENT, REJECTED, FAILED} lifecycle
 * from {@link SmsDeliveryStatus} (audit P5). The worker maps this onto the merchant_sms row: SENT
 * keeps the charge, REJECTED/FAILED reverse it. {@code trace} and {@code gwResponse} are raw
 * provider/HTTP evidence and must never be exposed merchant-facing. {@code providerMessageId}
 * preserves the provider correlation key needed for delivery receipts.
 */
public record SmsSendResult(
        SmsDeliveryStatus status, String trace, String gwResponse, String providerMessageId) {

    /** Backward-compatible constructor retained for existing adapters/tests. */
    public SmsSendResult(SmsDeliveryStatus status, String trace, String gwResponse) {
        this(status, trace, gwResponse, null);
    }

    public static SmsSendResult sent(String trace, String gwResponse) {
        return sent(trace, gwResponse, null);
    }

    public static SmsSendResult sent(String trace, String gwResponse, String providerMessageId) {
        return new SmsSendResult(
                SmsDeliveryStatus.SENT, trace, gwResponse, trim(providerMessageId));
    }

    public static SmsSendResult rejected(String trace, String gwResponse) {
        return new SmsSendResult(SmsDeliveryStatus.REJECTED, trace, gwResponse, null);
    }

    public static SmsSendResult failed(String trace, String gwResponse) {
        return new SmsSendResult(SmsDeliveryStatus.FAILED, trace, gwResponse, null);
    }

    public static SmsSendResult unknown(String trace) {
        return new SmsSendResult(SmsDeliveryStatus.UNKNOWN, trace, "", null);
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
