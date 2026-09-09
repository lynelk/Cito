package net.citotech.cito.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import net.citotech.cito.Model.Merchant;
import net.citotech.cito.Model.Transaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class PaymentLedgerSettlementServiceTest {
    private DoubleEntryLedgerService ledger;
    private NamedParameterJdbcTemplate jdbc;
    private PaymentLedgerSettlementService service;
    private Merchant merchant;

    @BeforeEach
    void setUp() {
        ledger = mock(DoubleEntryLedgerService.class);
        jdbc = mock(NamedParameterJdbcTemplate.class);
        service = new PaymentLedgerSettlementService(ledger, jdbc);
        merchant = new Merchant();
        merchant.setId(42L);
        merchant.setAccount_number("M00042");
    }

    @Test
    void payoutReservationCoversPrincipalAndChargesUntilTerminalOutcome() {
        Transaction tx = payout("TX-1", "merchant-ref-1", "100.00", "5.00");

        String reservation = service.reservePayout(tx, merchant);

        assertThat(reservation).isEqualTo("payout-reserve:M00042:merchant-ref-1");
        verify(ledger)
                .reserve(
                        eq(reservation),
                        eq(42L),
                        eq("merchant-ref-1"),
                        eq(new BigDecimal("105.0000")),
                        eq("UGX"));
    }

    @Test
    void pendingPayoutDoesNotPostOrCapture() {
        Transaction tx = payout("TX-2", "merchant-ref-2", "100.00", "5.00");

        service.applyTerminalProviderOutcome(tx, "PENDING", merchant);

        verifyNoInteractions(ledger);
        verifyNoInteractions(jdbc);
    }

    @Test
    void successfulPayoutPostsBalancedLedgerThenCapturesReservation() {
        Transaction tx = payout("TX-3", "merchant-ref-3", "100.00", "5.00");

        service.applyTerminalProviderOutcome(tx, "SUCCESSFUL", merchant);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LedgerEntryCommand>> entries = ArgumentCaptor.forClass(List.class);
        verify(ledger)
                .post(
                        eq("payment:TX-3"),
                        eq("PAYMENT"),
                        eq("TX-3"),
                        eq("PAYOUT merchant-ref-3"),
                        entries.capture());
        assertThat(entries.getValue()).hasSize(4);
        BigDecimal debits =
                entries.getValue().stream()
                        .filter(e -> "DR".equals(e.direction()))
                        .map(LedgerEntryCommand::amount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credits =
                entries.getValue().stream()
                        .filter(e -> "CR".equals(e.direction()))
                        .map(LedgerEntryCommand::amount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(debits).isEqualByComparingTo(credits);
        verify(ledger).captureReservation("payout-reserve:M00042:merchant-ref-3");
    }

    @Test
    void failedPayoutReversesHistoricalEagerPostingAndReleasesReservation() {
        Transaction tx = payout("TX-4", "merchant-ref-4", "100.00", "5.00");
        when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Integer.class)))
                .thenReturn(1);

        service.applyTerminalProviderOutcome(tx, "FAILED", merchant);

        verify(ledger)
                .reverse(
                        eq("payment:TX-4"),
                        eq("payment-reversal:TX-4"),
                        anyString());
        verify(jdbc)
                .update(
                        org.mockito.ArgumentMatchers.contains("reservation_status='RELEASED'"),
                        any(MapSqlParameterSource.class));
        verify(ledger, never()).captureReservation(anyString());
    }

    @Test
    void successfulCollectionPostsOnlyAfterTerminalSuccess() {
        Transaction tx = base("TX-5", "merchant-ref-5", "75.00", "0.00");
        tx.setTx_type(Transaction.TX_TYPE_PAYIN);

        service.applyTerminalProviderOutcome(tx, "SUCCESSFUL", merchant);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LedgerEntryCommand>> entries = ArgumentCaptor.forClass(List.class);
        verify(ledger)
                .post(
                        eq("payment:TX-5"),
                        eq("PAYMENT"),
                        eq("TX-5"),
                        eq("COLLECT merchant-ref-5"),
                        entries.capture());
        assertThat(entries.getValue()).hasSize(2);
        verify(ledger, never()).captureReservation(anyString());
    }

    private Transaction payout(String id, String reference, String amount, String charges) {
        Transaction tx = base(id, reference, amount, charges);
        tx.setTx_type(Transaction.TX_TYPE_PAYOUT);
        return tx;
    }

    private Transaction base(String id, String reference, String amount, String charges) {
        Transaction tx = new Transaction();
        tx.setTx_unique_id(id);
        tx.setTx_merchant_ref(reference);
        tx.setMerchant_id("42");
        tx.setGateway_id("MTNMoMoPaymentGateway");
        tx.setCurrency("UGX");
        tx.setOriginalAmountDecimal(new BigDecimal(amount));
        tx.setChargesDecimal(new BigDecimal(charges));
        return tx;
    }
}
