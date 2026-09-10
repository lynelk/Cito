package net.citotech.cito.gateway;

import net.citotech.cito.Model.GateWayResponse;
import org.springframework.stereotype.Component;

/** Narrow bridge for the existing non-Spring legacy gateway; production fails closed without it. */
@Component
public class AirtelRecoveryRegistry {
    private static volatile AirtelRecoveryService service;

    public AirtelRecoveryRegistry(AirtelRecoveryService service) {
        AirtelRecoveryRegistry.service = service;
    }

    public static GateWayResponse submit(
            long merchantId,
            Double amount,
            String account,
            String reference,
            String narrative,
            String operation) {
        AirtelRecoveryService active = service;
        if (active == null) throw new PaymentGatewayException("AIRTEL_RECOVERY_NOT_INITIALISED");
        return active.submitLegacy(merchantId, amount, account, reference, narrative, operation);
    }
}
