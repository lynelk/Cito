package net.citotech.cito.api.v2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import net.citotech.cito.Common;
import net.citotech.cito.Model.GateWayResponse;
import net.citotech.cito.Model.Merchant;
import net.citotech.cito.api.v2.dto.PaymentPartyRequest;
import net.citotech.cito.api.v2.dto.PaymentRequest;
import net.citotech.cito.api.v2.dto.PaymentResult;
import net.citotech.cito.gateway.GatewayBalance;
import net.citotech.cito.gateway.GatewayBalanceRequest;
import net.citotech.cito.gateway.GatewayCapabilities;
import net.citotech.cito.gateway.GatewayExecutionService;
import net.citotech.cito.gateway.IntelligentPaymentRoutingService;
import net.citotech.cito.gateway.PaymentChannelAdapter;
import net.citotech.cito.gateway.PaymentChannelRegistry;
import net.citotech.cito.gateway.PaymentGatewayRequest;
import net.citotech.cito.gateway.PaymentStatusRequest;
import net.citotech.cito.merchant.MerchantChannelCredentialService;
import net.citotech.cito.merchant.MerchantEnvironmentService;
import net.citotech.cito.platform.CitoFeatureAccessService;
import net.citotech.cito.sharedprovider.SharedProviderAccessService;
import net.citotech.cito.sharedprovider.SharedProviderAccessService.CredentialContext;
import net.citotech.cito.treasury.ProviderTreasuryService;
import org.junit.jupiter.api.Test;

class AdapterNativePaymentServiceTest {

    @Test
    void productionModeUsesProductionMerchantChannelCredentials() {
        CapturingAdapter adapter = new CapturingAdapter();
        MerchantChannelCredentialService credentialService =
                mock(MerchantChannelCredentialService.class);
        MerchantEnvironmentService environmentService = mock(MerchantEnvironmentService.class);
        IntelligentPaymentRoutingService routingService =
                mock(IntelligentPaymentRoutingService.class);
        CitoFeatureAccessService featureAccessService = mock(CitoFeatureAccessService.class);
        SharedProviderAccessService sharedProviderAccessService =
                mock(SharedProviderAccessService.class);
        ProviderTreasuryService treasuryService = mock(ProviderTreasuryService.class);
        GatewayExecutionService gatewayExecutionService = new GatewayExecutionService();
        BigDecimal canonicalAmount = new BigDecimal("1000.0000");
        when(environmentService.normalizedEnvironment("PRODUCTION")).thenReturn("PRODUCTION");
        when(sharedProviderAccessService.isReady(
                        any(Merchant.class),
                        eq("mtn_momo"),
                        eq("PRODUCTION"),
                        eq("UG"),
                        eq("UGX"),
                        eq("COLLECT"),
                        eq(canonicalAmount),
                        isNull()))
                .thenReturn(true);
        CredentialContext credentialContext =
                new CredentialContext(
                        SharedProviderAccessService.MERCHANT,
                        Map.of("collectUrl", "https://provider.example/collect"),
                        null,
                        "UG",
                        "UGX",
                        "COLLECT");
        when(sharedProviderAccessService.resolve(
                        any(Merchant.class),
                        eq("mtn_momo"),
                        eq("PRODUCTION"),
                        eq("UG"),
                        eq("UGX"),
                        eq("COLLECT"),
                        eq(canonicalAmount),
                        isNull()))
                .thenReturn(credentialContext);

        AdapterNativePaymentService service =
                new AdapterNativePaymentService(
                        new PaymentChannelRegistry(List.of(adapter)),
                        credentialService,
                        environmentService,
                        gatewayExecutionService,
                        routingService,
                        featureAccessService,
                        sharedProviderAccessService,
                        treasuryService,
                        "PRODUCTION");

        net.citotech.cito.gateway.MobileMoneyExecutionService lifecycle =
                mock(net.citotech.cito.gateway.MobileMoneyExecutionService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(
                service, "mobileMoney", lifecycle);
        PaymentResult persisted = new PaymentResult();
        persisted.setEnvironment("PRODUCTION");
        persisted.setTransactionId("durable-transaction");
        when(lifecycle.submit(any(), any(), eq("PRODUCTION"), eq("COLLECT"), eq(adapter)))
                .thenReturn(persisted);
        PaymentResult result = service.collect(paymentRequest(), merchant(), "PRODUCTION");

        verify(lifecycle)
                .submit(
                        any(PaymentRequest.class),
                        any(Merchant.class),
                        eq("PRODUCTION"),
                        eq("COLLECT"),
                        eq(adapter));
        org.mockito.Mockito.verifyNoInteractions(treasuryService);
        assertThat(result.getTransactionId()).isEqualTo("durable-transaction");

        gatewayExecutionService.shutdown();
    }

    private PaymentRequest paymentRequest() {
        PaymentPartyRequest payer = new PaymentPartyRequest();
        payer.setType("phone");
        payer.setValue("256770000000");

        PaymentRequest request = new PaymentRequest();
        request.setMerchantNumber("M100");
        request.setAmount("1000");
        request.setCurrency("UGX");
        request.setCountry("UG");
        request.setChannel("mtn_momo");
        request.setPayer(payer);
        request.setReference("PROD-REF-1");
        request.setDescription("Production readiness test");
        request.setCallbackUrl("https://merchant.example/callback");
        return request;
    }

    private Merchant merchant() {
        Merchant merchant = new Merchant();
        merchant.setId(10L);
        merchant.setAccount_number("M100");
        merchant.setStatus("ACTIVE");
        merchant.setAllowed_apis(new String[] {Common.API_MOBILE_MONEY_PAYIN});
        return merchant;
    }

    private static class CapturingAdapter implements PaymentChannelAdapter {
        private PaymentGatewayRequest lastRequest;

        @Override
        public String channelCode() {
            return "mtn_momo";
        }

        @Override
        public String displayName() {
            return "MTN MoMo";
        }

        @Override
        public String countryCode() {
            return "UG";
        }

        @Override
        public String currencyCode() {
            return "UGX";
        }

        @Override
        public GatewayCapabilities capabilities() {
            return GatewayCapabilities.mobileMoneyDefaults();
        }

        @Override
        public boolean supportsAccount(String accountIdentifier) {
            return true;
        }

        @Override
        public GateWayResponse collect(PaymentGatewayRequest request) {
            lastRequest = request;
            GateWayResponse response = new GateWayResponse();
            response.setNetworkId("MTN-PROD-1");
            response.setTransactionStatus("SUBMITTED");
            response.setMessage("submitted");
            response.setStatus("SUCCESS");
            return response;
        }

        @Override
        public GateWayResponse payout(PaymentGatewayRequest request) {
            return collect(request);
        }

        @Override
        public GateWayResponse checkStatus(PaymentStatusRequest request) {
            return new GateWayResponse();
        }

        @Override
        public GatewayBalance getBalance(GatewayBalanceRequest request) {
            return null;
        }
    }
}
