package net.citotech.cito.communication.sms;

/** Immutable per-logical-SMS send request. */
public record SmsSendRequest(
        long id,
        long merchantId,
        String content,
        String recipients,
        String gatewayName,
        String senderId) {

    /** Compatibility constructor for existing call sites that rely on provider defaults. */
    public SmsSendRequest(
            long id, long merchantId, String content, String recipients, String gatewayName) {
        this(id, merchantId, content, recipients, gatewayName, null);
    }
}
