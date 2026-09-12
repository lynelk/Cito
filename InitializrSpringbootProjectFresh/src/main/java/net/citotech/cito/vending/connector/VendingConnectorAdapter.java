package net.citotech.cito.vending.connector;

import java.util.Map;
import java.util.Set;
import net.citotech.cito.platform.provider.PlatformProviderAdapter;
import net.citotech.cito.platform.provider.PlatformProviderDomain;

/**
 * Manufacturer/device integration seam. A ChargeNow cabinet or another vending platform is added by
 * implementing this contract instead of contaminating rental/payment logic with vendor-specific
 * HTTP calls.
 */
public interface VendingConnectorAdapter extends PlatformProviderAdapter {
    String connectorCode();

    VendingCommandResult execute(VendingCommand command);

    @Override
    default String providerCode() {
        return connectorCode();
    }

    @Override
    default PlatformProviderDomain providerDomain() {
        return PlatformProviderDomain.VENDING;
    }

    @Override
    default Set<String> platformCapabilities() {
        return Set.of("DEVICE_COMMAND");
    }

    record VendingCommand(
            long merchantId,
            long deviceId,
            String externalDeviceId,
            String commandReference,
            String commandType,
            Map<String, String> parameters) {}

    record VendingCommandResult(
            boolean success, String providerReference, String status, String message) {}
}
