package net.citotech.cito.scheduler;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import net.citotech.cito.Common;
import net.citotech.cito.Model.GateWayResponse;
import net.citotech.cito.Model.Transaction;
import net.citotech.cito.gateway.PaymentGatewayException;
import net.citotech.cito.ledger.PaymentLedgerSettlementService;
import net.citotech.cito.sharedprovider.SharedProviderAccessService;
import net.citotech.cito.treasury.ProviderTreasuryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class AirtelRecoverySafetyTest {
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final PaymentLedgerSettlementService ledger =
            mock(PaymentLedgerSettlementService.class);
    private final SharedProviderAccessService credentials = mock(SharedProviderAccessService.class);
    private final ProviderTreasuryService treasury = mock(ProviderTreasuryService.class);
    private final SimpleTransactionStatus transactionStatus = new SimpleTransactionStatus();
    private final AirtelOpenApiStatusPollScheduler worker =
            new AirtelOpenApiStatusPollScheduler(jdbc, manager, ledger, credentials, treasury);

    @BeforeEach
    void prepare() {
        when(manager.getTransaction(any())).thenReturn(transactionStatus);
    }

    @Test
    void sharedLookupUsesOriginalSubmissionNotNetworkReceipt() {
        assertThat(
                        AirtelOpenApiStatusPollScheduler.statusReference(
                                Map.of(
                                        "merchant_reference",
                                        "submitted-001",
                                        "provider_reference",
                                        "airtel-receipt-999")))
                .isEqualTo("submitted-001");
        assertThat(
                        AirtelOpenApiStatusPollScheduler.statusReference(
                                Map.of("provider_reference", "receipt")))
                .isEmpty();
    }

    @Test
    void ambiguousSharedReferenceNeverCallsProviderOrSettlesFloat() {
        when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Integer.class)))
                .thenReturn(2);
        assertThatThrownBy(
                        () ->
                                worker.reconcileSharedProvider(
                                        Map.of(
                                                "id",
                                                9L,
                                                "operation",
                                                "COLLECT",
                                                "merchant_reference",
                                                "duplicate",
                                                "treasury_account_id",
                                                3L)))
                .isInstanceOf(PaymentGatewayException.class);
        verifyNoInteractions(credentials, treasury, ledger);
    }

    @Test
    void unsupportedSharedOperationDoesNotBecomeACollection() {
        worker.reconcileSharedProvider(
                Map.of(
                        "id",
                        9L,
                        "operation",
                        "REFUND",
                        "merchant_reference",
                        "ref",
                        "treasury_account_id",
                        3L));
        verifyNoInteractions(jdbc, credentials, treasury, ledger);
    }

    @Test
    void uncertainOrUnavailableStatusCannotFinalizeOrReleaseAnything() {
        for (GateWayResponse response :
                List.of(
                        response("500", "OK", "FAILED"),
                        response("429", "ERROR", "FAILED"),
                        response("200", "OK", "UNDETERMINED"),
                        response("202", "OK", "SUCCESSFUL")))
            worker.finalizeLegacy(tx("PENDING"), response);
        verifyNoInteractions(jdbc, manager, ledger, treasury);
    }

    @Test
    @SuppressWarnings("unchecked")
    void repeatedTerminalStatusIsFinanciallyIdempotent() {
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(tx("SUCCESSFUL")));
        try (MockedStatic<Common> common = mockStatic(Common.class)) {
            common.when(Common::getTransactionRowMapper).thenReturn(mock(RowMapper.class));
            worker.finalizeLegacy(tx("PENDING"), response("200", "OK", "SUCCESSFUL"));
            verifyNoInteractions(ledger, treasury);
            common.verify(
                    () -> Common.updateTx(any(Transaction.class), eq(jdbc), eq(manager)), never());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void changedMerchantScopeRejectsTerminalEvidence() {
        Transaction changed = tx("PENDING");
        changed.setMerchant_id("43");
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(changed));
        try (MockedStatic<Common> common = mockStatic(Common.class)) {
            common.when(Common::getTransactionRowMapper).thenReturn(mock(RowMapper.class));
            assertThatThrownBy(
                            () ->
                                    worker.finalizeLegacy(
                                            tx("PENDING"), response("200", "OK", "SUCCESSFUL")))
                    .isInstanceOf(PaymentGatewayException.class);
            verify(manager).rollback(transactionStatus);
            verifyNoInteractions(ledger);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectedCanonicalUpdateRollsBackWithoutPostingLedger() {
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(tx("PENDING")));
        try (MockedStatic<Common> common = mockStatic(Common.class)) {
            common.when(Common::getTransactionRowMapper).thenReturn(mock(RowMapper.class));
            common.when(() -> Common.updateTx(any(Transaction.class), eq(jdbc), eq(manager)))
                    .thenReturn("error");
            assertThatThrownBy(
                            () ->
                                    worker.finalizeLegacy(
                                            tx("PENDING"), response("200", "OK", "SUCCESSFUL")))
                    .isInstanceOf(PaymentGatewayException.class);
            verify(manager).rollback(transactionStatus);
            verifyNoInteractions(ledger);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void silentlyIgnoredCanonicalUpdateDoesNotPostLedger() {
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(tx("PENDING")));
        when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(String.class)))
                .thenReturn("PENDING");
        try (MockedStatic<Common> common = mockStatic(Common.class)) {
            common.when(Common::getTransactionRowMapper).thenReturn(mock(RowMapper.class));
            common.when(() -> Common.updateTx(any(Transaction.class), eq(jdbc), eq(manager)))
                    .thenReturn("success");
            assertThatThrownBy(
                            () ->
                                    worker.finalizeLegacy(
                                            tx("PENDING"), response("200", "OK", "SUCCESSFUL")))
                    .isInstanceOf(PaymentGatewayException.class);
            verify(manager).rollback(transactionStatus);
            verifyNoInteractions(ledger);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void successfulFinalizationPersistsResolverAndCommitsWithLedger() {
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(tx("PENDING")));
        when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(String.class)))
                .thenReturn("SUCCESSFUL");
        try (MockedStatic<Common> common = mockStatic(Common.class)) {
            common.when(Common::getTransactionRowMapper).thenReturn(mock(RowMapper.class));
            common.when(() -> Common.updateTx(any(Transaction.class), eq(jdbc), eq(manager)))
                    .thenReturn("success");
            worker.finalizeLegacy(tx("PENDING"), response("200", "OK", "SUCCESSFUL"));
            verify(ledger).applyTerminalProviderOutcome(any(Transaction.class), eq("SUCCESSFUL"));
            verify(jdbc)
                    .update(
                            contains("resolved_by='AIRTEL_STATUS_POLL'"),
                            any(MapSqlParameterSource.class));
            verify(manager).commit(transactionStatus);
        }
    }

    @Test
    void recoveryLeaseCoversBoundedProviderTimeouts() throws Exception {
        var lock =
                AirtelOpenApiStatusPollScheduler.class
                        .getMethod("reconcilePendingAirtelTransactions")
                        .getAnnotation(
                                net.javacrumbs.shedlock.spring.annotation.SchedulerLock.class);
        assertThat(java.time.Duration.parse(lock.lockAtMostFor()))
                .isGreaterThanOrEqualTo(java.time.Duration.ofMinutes(20));
    }

    private Transaction tx(String status) {
        Transaction tx = new Transaction();
        tx.setId(1L);
        tx.setMerchant_id("42");
        tx.setTx_unique_id("AIRTEL-TEST-1");
        tx.setTx_type(Transaction.TX_TYPE_PAYIN);
        tx.setCurrency("UGX");
        tx.setOriginalAmountDecimal(new BigDecimal("1000"));
        tx.setStatus(status);
        return tx;
    }

    private GateWayResponse response(String http, String status, String transactionStatus) {
        GateWayResponse response = new GateWayResponse();
        response.setHttpStatus(http);
        response.setStatus(status);
        response.setTransactionStatus(transactionStatus);
        return response;
    }
}
