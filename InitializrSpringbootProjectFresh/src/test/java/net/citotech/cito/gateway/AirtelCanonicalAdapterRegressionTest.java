package net.citotech.cito.gateway;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Map;
import net.citotech.cito.Model.GateWayResponse;
import org.junit.jupiter.api.Test;

/** The superseded recovery proposal must never replace the current native payment lifecycle. */
class AirtelCanonicalAdapterRegressionTest {
    @Test
    void canonicalProductionRequestsKeepTheirOriginalReferenceAndCredentialSnapshot() {
        var transport = mock(ProviderEndpointExecutionService.class);
        var adapter = new AirtelOpenApiAdapter(transport);
        var request = new PaymentGatewayRequest("M-test", "256700000000", 100.0,
                "original-canonical-reference", "synthetic", null,
                Map.of("gatewayState", "PRODUCTION", "providerReference", "original-canonical-reference"));
        var pending = new GateWayResponse();
        pending.setTransactionStatus("PENDING");
        when(transport.execute("airtel_open_api", "Airtel OpenAPI", "COLLECT", request)).thenReturn(pending);
        when(transport.execute("airtel_open_api", "Airtel OpenAPI", "PAYOUT", request)).thenReturn(pending);
        assertThat(adapter.collect(request)).isSameAs(pending);
        assertThat(adapter.payout(request)).isSameAs(pending);
        verify(transport).execute("airtel_open_api", "Airtel OpenAPI", "COLLECT", request);
        verify(transport).execute("airtel_open_api", "Airtel OpenAPI", "PAYOUT", request);
        verifyNoMoreInteractions(transport);
    }
}
