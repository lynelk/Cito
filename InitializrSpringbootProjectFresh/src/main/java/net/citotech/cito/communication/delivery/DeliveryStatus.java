package net.citotech.cito.communication.delivery;

/** Canonical channel-agnostic communication delivery lifecycle. */
public enum DeliveryStatus {
    PENDING,
    CANCELLED,
    SENT,
    DELIVERED,
    REJECTED,
    FAILED;

    /** REJECTED and FAILED mean the provider never accepted a billable delivery. */
    public boolean isRefundable() {
        return this == REJECTED || this == FAILED;
    }

    /** Provider acceptance or final delivery evidence is billable. */
    public boolean isBillable() {
        return this == SENT || this == DELIVERED;
    }

    public static DeliveryStatus fromString(String value) {
        if (value == null) return null;
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
