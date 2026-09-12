package net.citotech.cito.treasury;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.HashMap;
import java.util.Map;
import net.citotech.cito.admin.AdminPermissionService;
import net.citotech.cito.api.v2.AdapterNativePaymentService;
import net.citotech.cito.gateway.PaymentGatewayException;
import net.citotech.cito.merchant.MerchantChannelCryptoService;
import net.citotech.cito.security.AdminMfaService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class ProviderLiveTestPreflightTest {
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final AdapterNativePaymentService payments = mock(AdapterNativePaymentService.class);
    private final AdminMfaService mfa = mock(AdminMfaService.class);
    private final ProviderLiveTestService service =
            new ProviderLiveTestService(
                    jdbc,
                    payments,
                    mock(AdminPermissionService.class),
                    mfa,
                    mock(MerchantChannelCryptoService.class));

    @Test
    void rejectsSandboxUgxBeforeRecordingOrSubmittingEitherProduct() {
        for (String operation : new String[] {"COLLECT", "PAYOUT"}) {
            var request = body();
            request.put("operation", operation);
            assertThatThrownBy(() -> service.request(request, "operator@example.com"))
                    .isInstanceOf(PaymentGatewayException.class)
                    .hasMessageContaining("UG/EUR");
        }
        verifyNoInteractions(jdbc, payments, mfa);
    }

    @Test
    void rejectsUnknownEnvironmentBeforeMfaOrAnyMoneyPath() {
        var request = body();
        request.put("environment", "LIVE");
        assertThatThrownBy(() -> service.request(request, "operator@example.com"))
                .isInstanceOf(PaymentGatewayException.class)
                .hasMessageContaining("SANDBOX or PRODUCTION");
        verifyNoInteractions(jdbc, payments, mfa);
    }

    @Test
    void productionStillRequiresExplicitMoneyMovementConfirmation() {
        var request = body();
        request.put("environment", "PRODUCTION");
        assertThatThrownBy(() -> service.request(request, "operator@example.com"))
                .isInstanceOf(PaymentGatewayException.class)
                .hasMessageContaining("Production confirmation");
        verifyNoInteractions(jdbc, payments, mfa);
    }

    private Map<String, Object> body() {
        return new HashMap<>(
                Map.of(
                        "operation",
                        "COLLECT",
                        "environment",
                        "SANDBOX",
                        "idempotencyKey",
                        "test-only-key",
                        "merchantId",
                        42,
                        "channelCode",
                        "mtn_momo",
                        "countryCode",
                        "UG",
                        "currencyCode",
                        "UGX",
                        "amount",
                        "1",
                        "party",
                        "46733123450"));
    }
}
