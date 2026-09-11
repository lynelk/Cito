package net.citotech.cito.gateway;

import net.citotech.cito.Model.GateWayResponse;
import org.springframework.stereotype.Component;

@Component
public class AirtelOpenApiAdapter extends LegacyGatewayAdapter {
    public static final String CHANNEL_CODE = "airtel_open_api";
    private final AirtelRecoveryService recoveryService;

    public AirtelOpenApiAdapter(AirtelRecoveryService recoveryService) {
        super(
                CHANNEL_CODE,
                "Airtel OpenAPI",
                "UG",
                "UGX",
                LegacyGatewayIds.AIRTEL_OPEN_API,
                "25675",
                "25670",
                "25676");
        this.recoveryService = recoveryService;
    }

    @Override
    public GateWayResponse collect(PaymentGatewayRequest request) {
        return recoveryService.submit(request, "COLLECT");
    }

    @Override
    public GateWayResponse payout(PaymentGatewayRequest request) {
        return recoveryService.submit(request, "PAYOUT");
    }
}
