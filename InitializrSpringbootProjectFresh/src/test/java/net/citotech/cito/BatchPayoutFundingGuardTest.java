package net.citotech.cito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import net.citotech.cito.Model.AirtelMoneyOpenApiPaymentGateway;
import net.citotech.cito.Model.AirtelMoneyPaymentGateway;
import org.junit.jupiter.api.Test;

class BatchPayoutFundingGuardTest {

    @Test
    void openApiAirtelUsesTheSharedLegacyAirtelBalanceBucket() {
        assertThat(
                        BatchPayoutFundingGuard.legacyBalanceGatewayId(
                                AirtelMoneyOpenApiPaymentGateway.gateway_id))
                .isEqualTo(AirtelMoneyPaymentGateway.gateway_id);
    }

    @Test
    void zeroChargeIsAllowedAndPayoutPrecisionIsNormalized() {
        assertThat(BatchPayoutFundingGuard.requiredAmount(new BigDecimal("1000"), 0D))
                .isEqualByComparingTo("1000.0000");
    }

    @Test
    void negativeChargeFailsClosed() {
        assertThatThrownBy(
                        () -> BatchPayoutFundingGuard.requiredAmount(new BigDecimal("1000"), -1D))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be negative");
    }
}
