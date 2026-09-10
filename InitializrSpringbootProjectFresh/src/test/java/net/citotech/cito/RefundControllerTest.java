package net.citotech.cito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import net.citotech.cito.Model.Merchant;
import net.citotech.cito.refund.*;
import net.citotech.cito.security.SignatureVerificationService;
import net.citotech.cito.service.RateLimiterService;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RefundControllerTest {
    private final RefundController controller = new RefundController();
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    RefundControllerTest() {
        controller.jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        controller.refundService = mock(RefundService.class);
        controller.rateLimiterService = mock(RateLimiterService.class);
        when(controller.rateLimiterService.tryConsume("MERCHANT")).thenReturn(true);
    }

    @ParameterizedTest
    @EnumSource(
            value = RefundStatus.class,
            names = {"PENDING_APPROVAL", "PROCESSING", "COMPLETED"})
    void compatibilityRequestUsesGovernedLifecycleAndReportsSettlementHonestly(
            RefundStatus status) {
        Merchant merchant = new Merchant();
        merchant.setId(1L);
        merchant.setAccount_number("MERCHANT");
        merchant.setStatus("ACTIVE");
        try (var common = mockStatic(Common.class);
                var signatures = mockStatic(SignatureVerificationService.class)) {
            common.when(
                            () ->
                                    Common.getMerchantByAccountNumber(
                                            "MERCHANT", controller.jdbcTemplate))
                    .thenReturn(merchant);
            common.when(() -> Common.getIpAddress(request)).thenReturn("192.0.2.1");
            when(controller.refundService.requestRefund(
                            merchant,
                            "PAYIN",
                            "REFUND",
                            null,
                            "reason",
                            "API:MERCHANT",
                            "https://merchant.example.com/refunds",
                            "192.0.2.1"))
                    .thenReturn(
                            new RefundRecord(
                                    1,
                                    "REFUND",
                                    1,
                                    2,
                                    "PAYIN",
                                    null,
                                    new BigDecimal("90"),
                                    status,
                                    "reason",
                                    null));

            JSONObject result =
                    new JSONObject(controller.doMobileMoneyRefund(body(), request, response));
            assertThat(result.getString("state")).isEqualTo("OK");
            assertThat(result.getString("refundStatus")).isEqualTo(status.name());
            assertThat(result.getJSONObject("txDetails").getString("transactionStatus"))
                    .isEqualTo(
                            status == RefundStatus.COMPLETED
                                    ? "SUCCESSFUL"
                                    : status == RefundStatus.PENDING_APPROVAL
                                            ? "PENDING_APPROVAL"
                                            : "PENDING");
            signatures.verify(
                    () ->
                            SignatureVerificationService.verify(
                                    merchant, "MERCHANTPAYINREFUNDreason", "signature"));
            verify(controller.refundService)
                    .requestRefund(
                            merchant,
                            "PAYIN",
                            "REFUND",
                            null,
                            "reason",
                            "API:MERCHANT",
                            "https://merchant.example.com/refunds",
                            "192.0.2.1");
        }
    }

    @Test
    void invalidSignatureCannotCreateARefundClaim() {
        Merchant merchant = new Merchant();
        merchant.setId(1L);
        merchant.setStatus("ACTIVE");
        try (var common = mockStatic(Common.class);
                var signatures = mockStatic(SignatureVerificationService.class)) {
            common.when(
                            () ->
                                    Common.getMerchantByAccountNumber(
                                            "MERCHANT", controller.jdbcTemplate))
                    .thenReturn(merchant);
            signatures
                    .when(
                            () ->
                                    SignatureVerificationService.verify(
                                            any(), anyString(), anyString()))
                    .thenReturn(GeneralException.getError("116", "Signature verification failed"));
            assertThat(
                            new JSONObject(
                                            controller.doMobileMoneyRefund(
                                                    body(), request, response))
                                    .getString("code"))
                    .isEqualTo("116");
            verifyNoInteractions(controller.refundService);
        }
    }

    @Test
    void malformedAndRateLimitedRequestsCannotCreateARefundClaim() {
        assertThat(
                        new JSONObject(controller.doMobileMoneyRefund("{}", request, response))
                                .getString("code"))
                .isEqualTo("114");
        when(controller.rateLimiterService.tryConsume("MERCHANT")).thenReturn(false);
        assertThat(
                        new JSONObject(controller.doMobileMoneyRefund(body(), request, response))
                                .getString("code"))
                .isEqualTo("145");
        assertThat(response.getStatus()).isEqualTo(429);
        verifyNoInteractions(controller.refundService);
    }

    private String body() {
        return new JSONObject()
                .put("merchant_number", "MERCHANT")
                .put("original_reference", "PAYIN")
                .put("reference", "REFUND")
                .put("description", "reason")
                .put("callback_url", "https://merchant.example.com/refunds")
                .put("signature", "signature")
                .toString();
    }
}
