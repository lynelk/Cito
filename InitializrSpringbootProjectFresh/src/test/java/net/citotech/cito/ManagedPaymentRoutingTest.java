package net.citotech.cito;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import net.citotech.cito.api.v2.dto.PaymentPartyRequest;
import net.citotech.cito.api.v2.dto.PaymentRequest;
import net.citotech.cito.gateway.LegacyGatewayIds;
import org.junit.jupiter.api.Test;

class ManagedPaymentRoutingTest {
    @Test
    void implicitPayoutRoutingUsesPayeeEvenWhenPayerIsProvided() {
        var service =
                new PaymentOrchestrationService(null, null, null, null, null, null, null, null);
        var request = new PaymentRequest();
        var payer = new PaymentPartyRequest();
        payer.setValue("256770000001");
        var payee = new PaymentPartyRequest();
        payee.setValue("256700000001");
        request.setPayer(payer);
        request.setPayee(payee);
        try (var gateway = mockStatic(DoPayGateway.class)) {
            gateway.when(() -> DoPayGateway.getGatewayIdByMsisdn(eq(payer.getValue()), any()))
                    .thenReturn(LegacyGatewayIds.MTN_MOMO);
            gateway.when(() -> DoPayGateway.getGatewayIdByMsisdn(eq(payee.getValue()), any()))
                    .thenReturn(LegacyGatewayIds.AIRTEL_OPEN_API);
            assertThat(service.usesManagedMobileMoney(request, false)).isTrue();
            assertThat(request.getChannel()).isEqualTo("airtel_open_api");
            request.setChannel("");
            assertThat(service.usesManagedMobileMoney(request, true)).isTrue();
            assertThat(request.getChannel()).isEqualTo("mtn_momo");
            request.setChannel("");
            gateway.when(() -> DoPayGateway.getGatewayIdByMsisdn(eq(payee.getValue()), any()))
                    .thenReturn(LegacyGatewayIds.YO_PAYMENTS);
            assertThat(service.usesManagedMobileMoney(request, false)).isFalse();
        }
    }
}
