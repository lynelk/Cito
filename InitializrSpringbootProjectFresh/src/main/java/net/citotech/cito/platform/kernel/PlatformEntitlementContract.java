package net.citotech.cito.platform.kernel;

/** Server-side service access contract shared by all Cito domains. */
public interface PlatformEntitlementContract {
    boolean hasEntitlement(long merchantId, String serviceCode, String environment);

    void requireEntitlement(long merchantId, String serviceCode, String environment);
}
