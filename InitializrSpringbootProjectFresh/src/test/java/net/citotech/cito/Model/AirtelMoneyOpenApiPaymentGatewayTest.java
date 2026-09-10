package net.citotech.cito.Model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AirtelMoneyOpenApiPaymentGatewayTest {

    @Test
    void defaultEndpointConfigurationUsesAirtelV2Routes() {
        AirtelMoneyOpenApiPaymentGateway gateway = new AirtelMoneyOpenApiPaymentGateway();
        gateway.setApiDetails(
                "https://openapiuat.airtel.africa", "client-id", "client-secret", "1234");

        assertThat(gateway.tokenUrl())
                .isEqualTo("https://openapiuat.airtel.africa/auth/oauth2/token");
        assertThat(gateway.collectionUrl())
                .isEqualTo("https://openapiuat.airtel.africa/merchant/v2/payments/");
        assertThat(gateway.disbursementUrl())
                .isEqualTo("https://openapiuat.airtel.africa/standard/v2/disbursements/");
        assertThat(gateway.balanceUrl())
                .isEqualTo("https://openapiuat.airtel.africa/standard/v2/users/balance");
        assertThat(gateway.statusUrl("collection", "CPAY-001"))
                .isEqualTo("https://openapiuat.airtel.africa/standard/v2/payments/CPAY-001/");
        assertThat(gateway.statusUrl("disbursement", "CPAY-002"))
                .isEqualTo("https://openapiuat.airtel.africa/standard/v2/disbursements/CPAY-002/");
    }

    @Test
    void endpointConfigurationRejectsAbsoluteOverrides() {
        AirtelMoneyOpenApiPaymentGateway gateway = new AirtelMoneyOpenApiPaymentGateway();
        gateway.setEndpointDetails(
                null, null, "https://untrusted.example/payout", null, null, null);
        org.assertj.core.api.Assertions.assertThatThrownBy(gateway::disbursementUrl)
                .isInstanceOf(net.citotech.cito.gateway.PaymentGatewayException.class);
    }

    @Test
    void blankEndpointOverridesKeepTheCurrentDefaults() {
        AirtelMoneyOpenApiPaymentGateway gateway = new AirtelMoneyOpenApiPaymentGateway();
        gateway.setApiDetails("", "client-id", "client-secret", "1234");
        gateway.setEndpointDetails("", null, "  ", "", null, "");

        assertThat(gateway.collectionUrl())
                .isEqualTo("https://openapiuat.airtel.africa/merchant/v2/payments/");
        assertThat(gateway.disbursementUrl())
                .isEqualTo("https://openapiuat.airtel.africa/standard/v2/disbursements/");
    }
}
