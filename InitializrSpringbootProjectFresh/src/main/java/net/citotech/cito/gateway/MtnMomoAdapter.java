package net.citotech.cito.gateway;

import java.util.Map;
import net.citotech.cito.Model.GateWayResponse;
import org.springframework.stereotype.Component;

@Component
public class MtnMomoAdapter extends LegacyGatewayAdapter {
    public static final String CHANNEL_CODE = "mtn_momo";
    private final ProviderEndpointExecutionService executionService;
    private final MtnMomoCorrelationService correlationService;

    public MtnMomoAdapter(ProviderEndpointExecutionService executionService) {
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
        validate(request, "COLLECT");
        return executionService.execute(CHANNEL_CODE, "MTN MoMo", "COLLECT", request);
    }

    @Override
    public GateWayResponse payout(PaymentGatewayRequest request) {
        validate(request, "PAYOUT");
        return executionService.execute(CHANNEL_CODE, "MTN MoMo", "PAYOUT", request);
    }

    private void validate(PaymentGatewayRequest request, String operation) {
        if (request == null) {
            throw new PaymentGatewayException("MTN MoMo request is required");
        }
        Map<String, String> metadata = request.getMetadata();
        if (metadata == null) {
            throw new PaymentGatewayException("MTN MoMo credentials are required");
        }
        String environment = metadata.getOrDefault("gatewayState", "SANDBOX");
        String country = metadata.getOrDefault("country", countryCode());
        String currency = metadata.get("currency");
        MtnMomoCredentialSchema.validateForOperation(
                metadata, environment, country, currency, operation);
    }
}
