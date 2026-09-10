package net.citotech.cito.merchant;

import java.util.Locale;

/**
 * Merchant-side team role (audit N7). Every merchant user under an account previously had identical
 * (full) access - this enum gives merchant self-service and gateway code a small, explicit
 * capability matrix to check against instead of treating all merchant users the same. Kept additive
 * and decoupled from {@link net.citotech.cito.Model.MerchantUser} (mirrors how {@link
 * net.citotech.cito.Model.TransactionStatus} is a plain enum-with-behavior alongside the
 * free-string `status` column it interprets), so existing call sites are unaffected until they opt
 * in to a role check.
 *
 * <ul>
 *   <li>{@link #OWNER} - full access: can manage other merchant users/billing, channels, and money
 *       movement.
 *   <li>{@link #FINANCE} - can view/export statements and initiate payouts/refunds, but cannot
 *       manage users or channel credentials.
 *   <li>{@link #DEVELOPER} - can manage API keys/webhooks/channel config, but cannot move money.
 *   <li>{@link #VIEWER} - read-only: can view statements/transactions, no mutating action.
 * </ul>
 */
public enum MerchantRole {
    OWNER,
    FINANCE,
    DEVELOPER,
    VIEWER;

    /**
     * Manage other merchant users on the account (invite/edit/remove, assign roles) and billing.
     */
    public boolean canManageUsers() {
        return this == OWNER;
    }

    /** Manage channel credentials, API keys, and webhook/config registration. */
    public boolean canManageChannels() {
        return this == OWNER || this == DEVELOPER;
    }

    /** Initiate money-moving actions such as payouts/refunds. */
    public boolean canInitiatePayouts() {
        return this == OWNER || this == FINANCE;
    }

    /** Every role can view statements/transactions - read-only access is never restricted. */
    public boolean canViewStatements() {
        return true;
    }

    /** Missing or unknown roles retain read access and cannot mutate credentials or money. */
    public static MerchantRole fromString(String value) {
        if (value != null) {
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                // Unknown roles fail closed to read-only access.
            }
        }
        return VIEWER;
    }
}
