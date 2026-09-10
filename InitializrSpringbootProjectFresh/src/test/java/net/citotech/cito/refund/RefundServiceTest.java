package net.citotech.cito.refund;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.util.List;
import net.citotech.cito.Model.Merchant;
import net.citotech.cito.gateway.PaymentGatewayException;
import net.citotech.cito.ledger.DoubleEntryLedgerService;
import net.citotech.cito.merchant.MerchantNotificationPreferenceService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Covers audit B6: refunds support partial amounts tracked cumulatively against the original payin
 * (so the total refunded can never exceed what was collected), and a retried request with the same
 * reference is idempotent rather than double-refunding.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
class RefundServiceTest {

    private static final BigDecimal APPROVAL_THRESHOLD = new BigDecimal("500000");

    @Test
    void rejectsARefundThatExceedsTheUnrefundedBalance() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        DoubleEntryLedgerService ledgerService = mock(DoubleEntryLedgerService.class);

        MerchantNotificationPreferenceService notificationPreferenceService =
                mock(MerchantNotificationPreferenceService.class);
        stubNoExistingRefund(jdbcTemplate);
        stubOriginalPayin(jdbcTemplate, 1L, 1000.0);
        stubRefundedSoFar(jdbcTemplate, new BigDecimal("400"));

        RefundService service =
                new RefundService(
                        jdbcTemplate,
                        transactionManager,
                        ledgerService,
                        notificationPreferenceService,
                        APPROVAL_THRESHOLD);

        assertThatThrownBy(
                        () ->
                                service.requestRefund(
                                        merchant(),
                                        "PAY-1",
                                        "REF-1",
                                        new BigDecimal("700"),
                                        "too much"))
                .isInstanceOf(PaymentGatewayException.class)
                .hasMessageContaining("exceeds the unrefunded balance");

        verify(jdbcTemplate, never())
                .update(contains("INSERT INTO refunds"), any(MapSqlParameterSource.class), any());
    }

    @Test
    void rejectsARefundWhenNoSuccessfulPayinExistsForTheReference() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        DoubleEntryLedgerService ledgerService = mock(DoubleEntryLedgerService.class);

        MerchantNotificationPreferenceService notificationPreferenceService =
                mock(MerchantNotificationPreferenceService.class);
        stubNoExistingRefund(jdbcTemplate);
        when(jdbcTemplate.query(
                        contains("merchant_production_transactions"),
                        any(MapSqlParameterSource.class),
                        any(RowMapper.class)))
                .thenReturn(List.of());

        RefundService service =
                new RefundService(
                        jdbcTemplate,
                        transactionManager,
                        ledgerService,
                        notificationPreferenceService,
                        APPROVAL_THRESHOLD);

        assertThatThrownBy(
                        () ->
                                service.requestRefund(
                                        merchant(), "PAY-MISSING", "REF-1", null, "reason"))
                .isInstanceOf(PaymentGatewayException.class)
                .hasMessageContaining("No successful payin found");
    }

    @Test
    void retriedRequestWithTheSameReferenceReturnsTheExistingRefundInstead() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        DoubleEntryLedgerService ledgerService = mock(DoubleEntryLedgerService.class);

        ResultSet refundRow = mock(ResultSet.class);
        try {
            when(refundRow.getLong("id")).thenReturn(9L);
            when(refundRow.getString("refund_reference")).thenReturn("REF-1");
            when(refundRow.getLong("merchant_id")).thenReturn(12L);
            when(refundRow.getLong("original_transaction_id")).thenReturn(1L);
            when(refundRow.getString("original_merchant_ref")).thenReturn("PAY-1");
            when(refundRow.getObject("payout_transaction_id")).thenReturn(null);
            when(refundRow.getBigDecimal("requested_amount")).thenReturn(new BigDecimal("500"));
            when(refundRow.getString("refund_status")).thenReturn("COMPLETED");
            when(refundRow.getString("reason")).thenReturn("reason");
            when(refundRow.getString("failure_message")).thenReturn(null);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        when(jdbcTemplate.query(
                        contains("FROM refunds"),
                        any(MapSqlParameterSource.class),
                        any(RowMapper.class)))
                .thenAnswer(
                        invocation -> {
                            RowMapper mapper = invocation.getArgument(2);
                            return List.of(mapper.mapRow(refundRow, 1));
                        });

        MerchantNotificationPreferenceService notificationPreferenceService =
                mock(MerchantNotificationPreferenceService.class);
        RefundService service =
                new RefundService(
                        jdbcTemplate,
                        transactionManager,
                        ledgerService,
                        notificationPreferenceService,
                        APPROVAL_THRESHOLD);
        RefundRecord result =
                service.requestRefund(
                        merchant(), "PAY-1", "REF-1", new BigDecimal("500"), "reason");

        assertThat(result.status()).isEqualTo(RefundStatus.COMPLETED);
        assertThatThrownBy(
                        () ->
                                service.requestRefund(
                                        merchant(),
                                        "OTHER-PAYMENT",
                                        "REF-1",
                                        new BigDecimal("500"),
                                        "reason"))
                .isInstanceOf(PaymentGatewayException.class)
                .hasMessageContaining("bound to another request");
        assertThatThrownBy(
                        () ->
                                service.requestRefund(
                                        merchant(),
                                        "PAY-1",
                                        "REF-1",
                                        new BigDecimal("501"),
                                        "reason"))
                .isInstanceOf(PaymentGatewayException.class)
                .hasMessageContaining("bound to another request");
        verify(jdbcTemplate, never())
                .update(contains("INSERT INTO refunds"), any(MapSqlParameterSource.class), any());
    }

    private void stubNoExistingRefund(NamedParameterJdbcTemplate jdbcTemplate) {
        when(jdbcTemplate.query(
                        contains("FROM refunds"),
                        any(MapSqlParameterSource.class),
                        any(RowMapper.class)))
                .thenReturn(List.of());
    }

    private void stubOriginalPayin(
            NamedParameterJdbcTemplate jdbcTemplate, long txId, double amount) {
        ResultSet row = mock(ResultSet.class);
        try {
            when(row.getLong("id")).thenReturn(txId);
            when(row.getString("charging_method")).thenReturn("percentage");
            when(row.getDouble("charges")).thenReturn(0.0);
            when(row.getBigDecimal("original_amount")).thenReturn(BigDecimal.valueOf(amount));
            when(row.getString("created_on")).thenReturn("2026-01-01 00:00:00");
            when(row.getString("updated_on")).thenReturn("2026-01-01 00:00:00");
            when(row.getString("gateway_id")).thenReturn("MTNMoMoPaymentGateway");
            when(row.getString("status")).thenReturn("SUCCESSFUL");
            when(row.getString("merchant_id")).thenReturn("12");
            when(row.getString("tx_description")).thenReturn("");
            when(row.getString("tx_gateway_ref")).thenReturn("");
            when(row.getString("tx_merchant_description")).thenReturn("");
            when(row.getString("tx_request_trace")).thenReturn("");
            when(row.getString("tx_unique_id")).thenReturn("uid-1");
            when(row.getString("tx_update_trace")).thenReturn("");
            when(row.getString("payer_number")).thenReturn("256770000000");
            when(row.getString("tx_type")).thenReturn("PAYIN");
            when(row.getString("callback_trace")).thenReturn("");
            when(row.getString("tx_merchant_ref")).thenReturn("PAY-1");
            when(row.getDouble("tx_cost")).thenReturn(0.0);
            when(row.getString("callback_url")).thenReturn("");
            when(row.getString("safaricom_request_reference")).thenReturn("");
            when(row.getString("originate_ip")).thenReturn("");
            when(row.getString("currency")).thenReturn("UGX");
            when(row.getString("callback_status")).thenReturn("");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        when(jdbcTemplate.query(
                        contains("merchant_production_transactions"),
                        any(MapSqlParameterSource.class),
                        any(RowMapper.class)))
                .thenAnswer(
                        invocation -> {
                            RowMapper mapper = invocation.getArgument(2);
                            return List.of(mapper.mapRow(row, 1));
                        });
    }

    private void stubRefundedSoFar(NamedParameterJdbcTemplate jdbcTemplate, BigDecimal amount) {
        when(jdbcTemplate.queryForList(
                        contains("SELECT requested_amount FROM refunds"),
                        any(MapSqlParameterSource.class),
                        org.mockito.ArgumentMatchers.eq(BigDecimal.class)))
                .thenReturn(List.of(amount));
    }

    private Merchant merchant() {
        Merchant merchant = new Merchant();
        merchant.setId(12L);
        merchant.setAccount_number("1000003");
        merchant.setStatus("ACTIVE");
        merchant.setShort_name("Test");
        return merchant;
    }
}
