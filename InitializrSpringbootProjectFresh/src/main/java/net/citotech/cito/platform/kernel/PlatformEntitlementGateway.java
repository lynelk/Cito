package net.citotech.cito.platform.kernel;

import net.citotech.cito.platform.CitoEntitlementService;
import org.springframework.stereotype.Service;

/**
 * Delegates the platform entitlement contract to the existing authoritative Cito entitlement store.
 */
@Service
public class PlatformEntitlementGateway implements PlatformEntitlementContract {
    private final CitoEntitlementService entitlements;

    public PlatformEntitlementGateway(CitoEntitlementService entitlements) {
        this.entitlements = entitlements;
    }

    @Override
    public boolean hasEntitlement(long merchantId, String serviceCode, String environment) {
        return entitlements.hasEntitlement(merchantId, serviceCode, environment);
    }

    @Override
    public void requireEntitlement(long merchantId, String serviceCode, String environment) {
        entitlements.requireEntitlement(merchantId, serviceCode, environment);
    }
}
