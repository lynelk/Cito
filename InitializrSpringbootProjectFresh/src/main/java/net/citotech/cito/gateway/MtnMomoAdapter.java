package net.citotech.cito.gateway;

import net.citotech.cito.Model.GateWayResponse;
import org.springframework.stereotype.Component;

@Component
public class MtnMomoAdapter extends LegacyGatewayAdapter {
    public static final String CHANNEL_CODE = "mtn_momo";
    private final ProviderEndpointExecutionService executionService;
    private final MtnMomoCorrelationService correlationService;

    public MtnMomoAdapter(
            ProviderEndpointExecutionService executionService,
            MtnMomoCorrelationService correlationService) {
        super(
                CHANNEL_CODE,
                "MTN MoMo",
                "UG",
                "UGX",
                LegacyGatewayIds.MTN_MOMO,
                "25677",
                "25678",
                "25676");
        this.executionService = executionService;
        this.correlationService = correlationService;
    }

    @Override
    public GateWayResponse collect(PaymentGatewayRequest request) {
        GateWayResponse response = executionService.execute(CHANNEL_CODE, "MTN MoMo", "COLLECT", request);
        correlationService.capture(request, "COLLECT", response);
        return response;
    }

    @Override
    public GateWayResponse payout(PaymentGatewayRequest request) {
        GateWayResponse response = executionService.execute(CHANNEL_CODE, "MTN MoMo", "PAYOUT", request);
        correlationService.capture(request, "PAYOUT", response);
        return response;
    }
}
