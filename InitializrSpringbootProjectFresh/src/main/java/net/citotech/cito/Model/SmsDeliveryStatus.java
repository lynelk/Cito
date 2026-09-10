package net.citotech.cito.Model;

/**
 * Explicit lifecycle for {@code merchant_sms.status} (audit P5). The dispatch cron previously only
 * distinguished "we got zero response from the gateway" (refunded) from "we got any response at
 * all" (always marked SENT, never refunded) - so a provider that responded with an error status
 * (4xx/5xx, e.g. invalid number, blocked content, insufficient provider credit) was recorded as
 * successfully sent and the merchant was never credited back for a message that never went out.
 * REJECTED closes that gap: a non-2xx provider response is refunded exactly like a transport
 * failure, using the same status-code-range check {@code
 * net.citotech.cito.webhook.MerchantWebhookService} already uses to judge webhook delivery success.
 */
public enum SmsDeliveryStatus {
    PENDING,
    UNKNOWN,
    CANCELLED,
    SENT,
    REJECTED,
    FAILED;

    /** REJECTED and FAILED both mean the customer's charge must be reversed. */
    public boolean isRefundable() {
        return this == REJECTED || this == FAILED;
    }

    public static SmsDeliveryStatus fromString(String value) {
        if (value == null) {
            return null;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
