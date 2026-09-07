package net.citotech.cito.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import net.citotech.cito.gateway.PaymentGatewayException;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.math.BigDecimal;
import java.util.List;

class DoubleEntryLedgerServiceTest {

    @Test
    void rejectsUnbalancedTransactionsBeforeWriting() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        DoubleEntryLedgerService service = new DoubleEntryLedgerService(jdbcTemplate);

        assertThatThrownBy(
                        () ->
                                service.post(
                                        "TX-1",
                                        "PAYMENT",
                                        "PAY-1",
                                        "unbalanced",
                                        List.of(
                                                entry("merchant:cash", "DR", "1000"),
                                                entry("provider:float", "CR", "999"))))
                .isInstanceOf(PaymentGatewayException.class)
                .hasMessageContaining("not balanced");

        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void rejectsInvalidEntryDirectionBeforeWriting() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        DoubleEntryLedgerService service = new DoubleEntryLedgerService(jdbcTemplate);

        assertThatThrownBy(
                        () ->
                                service.post(
                                        "TX-2",
                                        "PAYMENT",
                                        "PAY-2",
                                        "bad direction",
                                        List.of(
                                                entry("merchant:cash", "DEBIT", "1000"),
                                                entry("provider:float", "CR", "1000"))))
                .isInstanceOf(PaymentGatewayException.class)
                .hasMessageContaining("DR or CR");

        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void repeatedTransactionReferenceReturnsExistingLedgerTransaction() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.query(
                        anyString(),
                        any(MapSqlParameterSource.class),
                        any(org.springframework.jdbc.core.RowMapper.class)))
                .thenReturn(List.of(55L));
        DoubleEntryLedgerService service = new DoubleEntryLedgerService(jdbcTemplate);

        long transactionId =
                service.post(
                        "TX-EXISTS",
                        "PAYMENT",
                        "PAY-EXISTS",
                        "idempotent replay",
                        List.of(
                                entry("merchant:cash", "DR", "1000"),
                                entry("provider:float", "CR", "1000")));

        assertThat(transactionId).isEqualTo(55L);
        verify(jdbcTemplate, never()).update(anyString(), any(MapSqlParameterSource.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void postScopesLedgerAccountsByOwnerCurrencyAndCode() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.query(
                        contains("FROM ledger_transactions"),
                        any(MapSqlParameterSource.class),
                        any(org.springframework.jdbc.core.RowMapper.class)))
                .thenReturn(List.of());
        when(jdbcTemplate.query(
                        contains("FROM ledger_period_locks"),
                        any(MapSqlParameterSource.class),
                        any(org.springframework.jdbc.core.RowMapper.class)))
                .thenReturn(List.of());
        when(jdbcTemplate.queryForObject(
                        eq("SELECT LAST_INSERT_ID()"),
                        any(MapSqlParameterSource.class),
                        eq(Long.class)))
                .thenReturn(700L);
        when(jdbcTemplate.queryForObject(
                        contains("owner_scope_id=:owner_scope_id"),
                        any(MapSqlParameterSource.class),
                        eq(Long.class)))
                .thenReturn(901L);
        DoubleEntryLedgerService service = new DoubleEntryLedgerService(jdbcTemplate);

        service.post(
                "TX-SCOPED-ACCOUNT",
                "PAYMENT",
                "PAY-SCOPED-ACCOUNT",
                "scoped account identity",
                List.of(
                        entry(
                                "shared:UGX:liability",
                                "MERCHANT_LIABILITY",
                                "MERCHANT",
                                101L,
                                "CR",
                                "1000",
                                "UGX"),
                        entry(
                                "shared:UGX:liability",
                                "MERCHANT_LIABILITY",
                                "MERCHANT",
                                202L,
                                "DR",
                                "1000",
                                "UGX")));

        verify(jdbcTemplate)
                .update(
                        contains("owner_id, owner_scope_id, currency"),
                        org.mockito.ArgumentMatchers.<MapSqlParameterSource>argThat(
                                params ->
                                        params.hasValue("owner_scope_id")
                                                && Long.valueOf(101L)
                                                        .equals(
                                                                params.getValue(
                                                                        "owner_scope_id"))));
        verify(jdbcTemplate)
                .update(
                        contains("owner_id, owner_scope_id, currency"),
                        org.mockito.ArgumentMatchers.<MapSqlParameterSource>argThat(
                                params ->
                                        params.hasValue("owner_scope_id")
                                                && Long.valueOf(202L)
                                                        .equals(
                                                                params.getValue(
                                                                        "owner_scope_id"))));
        verify(jdbcTemplate, org.mockito.Mockito.times(2))
                .queryForObject(
                        contains("owner_scope_id=:owner_scope_id"),
                        any(MapSqlParameterSource.class),
                        eq(Long.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void postThrowsWhenTheCurrencyIsLockedForTheCurrentPeriod() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.query(
                        contains("FROM ledger_transactions"),
                        any(MapSqlParameterSource.class),
                        any(org.springframework.jdbc.core.RowMapper.class)))
                .thenReturn(List.of());
        when(jdbcTemplate.query(
                        contains("FROM ledger_period_locks"),
                        any(MapSqlParameterSource.class),
                        any(org.springframework.jdbc.core.RowMapper.class)))
                .thenReturn(List.of("finance-ops"));
        DoubleEntryLedgerService service = new DoubleEntryLedgerService(jdbcTemplate);

        assertThatThrownBy(
                        () ->
                                service.post(
                                        "TX-LOCKED",
                                        "PAYMENT",
                                        "PAY-LOCKED",
                                        "should be blocked",
                                        List.of(
                                                entry("merchant:cash", "DR", "1000"),
                                                entry("provider:float", "CR", "1000"))))
                .isInstanceOf(PaymentGatewayException.class)
                .hasMessageContaining("UGX")
                .hasMessageContaining("locked")
                .hasMessageContaining("finance-ops");

        verify(jdbcTemplate, never())
                .update(
                        contains("INSERT INTO ledger_transactions"),
                        any(MapSqlParameterSource.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void postSucceedsWhenNoPeriodLockIsConfiguredForTheCurrency() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.query(
                        contains("FROM ledger_transactions"),
                        any(MapSqlParameterSource.class),
                        any(org.springframework.jdbc.core.RowMapper.class)))
                .thenReturn(List.of());
        when(jdbcTemplate.query(
                        contains("FROM ledger_period_locks"),
                        any(MapSqlParameterSource.class),
                        any(org.springframework.jdbc.core.RowMapper.class)))
                .thenReturn(List.of());
        when(jdbcTemplate.queryForObject(
                        eq("SELECT LAST_INSERT_ID()"),
                        any(MapSqlParameterSource.class),
                        eq(Long.class)))
                .thenReturn(99L);
        when(jdbcTemplate.queryForObject(
                        contains("FROM ledger_accounts"),
                        any(MapSqlParameterSource.class),
                        eq(Long.class)))
                .thenReturn(501L);
        DoubleEntryLedgerService service = new DoubleEntryLedgerService(jdbcTemplate);

        long txId =
                service.post(
                        "TX-UNLOCKED",
                        "PAYMENT",
                        "PAY-UNLOCKED",
                        "fail-open default",
                        List.of(
                                entry("merchant:cash", "DR", "1000"),
                                entry("provider:float", "CR", "1000")));

        assertThat(txId).isEqualTo(99L);
        verify(jdbcTemplate)
                .query(
                        contains("FROM ledger_period_locks"),
                        any(MapSqlParameterSource.class),
                        any(org.springframework.jdbc.core.RowMapper.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void postSkipsThePeriodLockCheckForAnIdempotentReplay() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.query(
                        contains("FROM ledger_transactions"),
                        any(MapSqlParameterSource.class),
                        any(org.springframework.jdbc.core.RowMapper.class)))
                .thenReturn(List.of(55L));
        DoubleEntryLedgerService service = new DoubleEntryLedgerService(jdbcTemplate);

        long txId =
                service.post(
                        "TX-EXISTS-2",
                        "PAYMENT",
                        "PAY-EXISTS-2",
                        "idempotent replay",
                        List.of(
                                entry("merchant:cash", "DR", "1000"),
                                entry("provider:float", "CR", "1000")));

        assertThat(txId).isEqualTo(55L);
        verify(jdbcTemplate, never())
                .query(
                        contains("FROM ledger_period_locks"),
                        any(MapSqlParameterSource.class),
                        any(org.springframework.jdbc.core.RowMapper.class));
    }

    @Test
    void reverseRejectsBlankReferences() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        DoubleEntryLedgerService service = new DoubleEntryLedgerService(jdbcTemplate);

        assertThatThrownBy(() -> service.reverse("", "TX-NEW", "correction"))
                .isInstanceOf(PaymentGatewayException.class)
                .hasMessageContaining("requires an original and a new transaction reference");
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @SuppressWarnings("unchecked")
    void reverseReturnsTheExistingReversalWithoutPostingAgain() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.query(
                        anyString(),
                        any(MapSqlParameterSource.class),
                        any(org.springframework.jdbc.core.RowMapper.class)))
                .thenReturn(List.of(77L));
        DoubleEntryLedgerService service = new DoubleEntryLedgerService(jdbcTemplate);

        long txId = service.reverse("TX-ORIGINAL", "TX-ALREADY-REVERSED", "correction");

        assertThat(txId).isEqualTo(77L);
        verify(jdbcTemplate, never()).update(anyString(), any(MapSqlParameterSource.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void reverseThrowsWhenTheOriginalTransactionIsNotFound() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.query(
                        anyString(),
                        any(MapSqlParameterSource.class),
                        any(org.springframework.jdbc.core.RowMapper.class)))
                .thenReturn(List.of());
        DoubleEntryLedgerService service = new DoubleEntryLedgerService(jdbcTemplate);

        assertThatThrownBy(() -> service.reverse("TX-MISSING", "TX-NEW", "correction"))
                .isInstanceOf(PaymentGatewayException.class)
                .hasMessageContaining("Original ledger transaction not found");
    }

    @Test
    @SuppressWarnings("unchecked")
    void reverseThrowsWhenTheOriginalTransactionHasNoEntries() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        // First two lookups (new-reference, then original-reference) succeed differently: no
        // reversal yet, but the original transaction id resolves - then the entries query for
        // that id comes back empty.
        when(jdbcTemplate.query(
                        anyString(),
                        any(MapSqlParameterSource.class),
                        any(org.springframework.jdbc.core.RowMapper.class)))
                .thenReturn(List.of())
                .thenReturn(List.of(10L))
                .thenReturn(List.of());
        DoubleEntryLedgerService service = new DoubleEntryLedgerService(jdbcTemplate);

        assertThatThrownBy(() -> service.reverse("TX-EMPTY", "TX-NEW", "correction"))
                .isInstanceOf(PaymentGatewayException.class)
                .hasMessageContaining("has no entries");
    }

    @Test
    @SuppressWarnings("unchecked")
    void reservationReplayWithSameAttributesDoesNotMutateExistingReservation() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.queryForList(
                        contains("FROM ledger_reservations"), any(MapSqlParameterSource.class)))
                .thenReturn(
                        List.of(
                                existingReservation(
                                        10L, "PAY-1", "80000.0000", "UGX", "RESERVED")));
        DoubleEntryLedgerService service = new DoubleEntryLedgerService(jdbcTemplate);

        service.reserve("RES-1", 10L, "PAY-1", new BigDecimal("80000"), "ugx");

        verify(jdbcTemplate, never())
                .update(
                        contains("INSERT INTO ledger_reservations"),
                        any(MapSqlParameterSource.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void reservationReplayWithDifferentAttributesIsRejectedBeforeMutation() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.queryForList(
                        contains("FROM ledger_reservations"), any(MapSqlParameterSource.class)))
                .thenReturn(
                        List.of(
                                existingReservation(
                                        10L, "PAY-1", "80000.0000", "UGX", "RESERVED")));
        DoubleEntryLedgerService service = new DoubleEntryLedgerService(jdbcTemplate);

        assertThatThrownBy(
                        () ->
                                service.reserve(
                                        "RES-1", 10L, "PAY-2", new BigDecimal("80000"), "UGX"))
                .isInstanceOf(PaymentGatewayException.class)
                .hasMessageContaining("already exists with different attributes");

        verify(jdbcTemplate, never())
                .update(
                        contains("INSERT INTO ledger_reservations"),
                        any(MapSqlParameterSource.class));
    }

    @Test
    void availableMerchantBalanceSubtractsActiveReservationsFromPostedLedgerBalance() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.queryForMap(
                        contains("active_reservations"), any(MapSqlParameterSource.class)))
                .thenReturn(
                        java.util.Map.of(
                                "posted_balance",
                                new BigDecimal("100000.0000"),
                                "active_reservations",
                                new BigDecimal("25000.0000")));
        DoubleEntryLedgerService service = new DoubleEntryLedgerService(jdbcTemplate);

        BigDecimal available = service.availableMerchantBalance(10L, "ugx");

        assertThat(available).isEqualByComparingTo("75000.0000");
    }

    @Test
    @SuppressWarnings("unchecked")
    void reservationLocksMerchantCurrencyScopeAndChecksAvailableBalanceBeforeInsert() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.queryForList(
                        contains("FROM ledger_reservations"), any(MapSqlParameterSource.class)))
                .thenReturn(List.of());
        when(jdbcTemplate.queryForMap(
                        contains("active_reservations"), any(MapSqlParameterSource.class)))
                .thenReturn(
                        java.util.Map.of(
                                "posted_balance",
                                new BigDecimal("100000.0000"),
                                "active_reservations",
                                new BigDecimal("0.0000")));
        DoubleEntryLedgerService service = new DoubleEntryLedgerService(jdbcTemplate);

        service.reserve("RES-ATOMIC-1", 10L, "PAY-1", new BigDecimal("80000"), "ugx");

        verify(jdbcTemplate)
                .update(
                        contains("INSERT INTO ledger_reservation_controls"),
                        any(MapSqlParameterSource.class));
        verify(jdbcTemplate)
                .query(
                        contains("FOR UPDATE"),
                        any(MapSqlParameterSource.class),
                        any(org.springframework.jdbc.core.RowMapper.class));
        verify(jdbcTemplate)
                .queryForMap(contains("active_reservations"), any(MapSqlParameterSource.class));
        verify(jdbcTemplate)
                .update(
                        contains("INSERT INTO ledger_reservations"),
                        any(MapSqlParameterSource.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void reservationRejectsInsufficientLedgerDerivedBalanceWithoutInsertingReservation() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.queryForList(
                        contains("FROM ledger_reservations"), any(MapSqlParameterSource.class)))
                .thenReturn(List.of());
        when(jdbcTemplate.queryForMap(
                        contains("active_reservations"), any(MapSqlParameterSource.class)))
                .thenReturn(
                        java.util.Map.of(
                                "posted_balance",
                                new BigDecimal("100000.0000"),
                                "active_reservations",
                                new BigDecimal("25000.0000")));
        DoubleEntryLedgerService service = new DoubleEntryLedgerService(jdbcTemplate);

        assertThatThrownBy(
                        () ->
                                service.reserve(
                                        "RES-ATOMIC-2",
                                        10L,
                                        "PAY-2",
                                        new BigDecimal("80000"),
                                        "UGX"))
                .isInstanceOf(PaymentGatewayException.class)
                .hasMessageContaining("Insufficient ledger-derived available balance");

        verify(jdbcTemplate)
                .update(
                        contains("INSERT INTO ledger_reservation_controls"),
                        any(MapSqlParameterSource.class));
        verify(jdbcTemplate)
                .query(
                        contains("FOR UPDATE"),
                        any(MapSqlParameterSource.class),
                        any(org.springframework.jdbc.core.RowMapper.class));
        verify(jdbcTemplate, never())
                .update(
                        contains("INSERT INTO ledger_reservations"),
                        any(MapSqlParameterSource.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void batchReservationRejectsAggregateShortfallWithoutPartialReservations() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.queryForList(
                        contains("FROM ledger_reservations"), any(MapSqlParameterSource.class)))
                .thenReturn(List.of());
        when(jdbcTemplate.queryForMap(
                        contains("active_reservations"), any(MapSqlParameterSource.class)))
                .thenReturn(
                        java.util.Map.of(
                                "posted_balance",
                                new BigDecimal("100.0000"),
                                "active_reservations",
                                new BigDecimal("0.0000")));
        DoubleEntryLedgerService service = new DoubleEntryLedgerService(jdbcTemplate);

        DoubleEntryLedgerService.BatchReservationResult result =
                service.reserveAll(
                        10L,
                        "ugx",
                        List.of(
                                new DoubleEntryLedgerService.ReservationCommand(
                                        "RES-BATCH-1", "PAYOUT-1", new BigDecimal("60")),
                                new DoubleEntryLedgerService.ReservationCommand(
                                        "RES-BATCH-2", "PAYOUT-2", new BigDecimal("60"))));

        assertThat(result.reserved()).isFalse();
        assertThat(result.required()).isEqualByComparingTo("120.0000");
        assertThat(result.available()).isEqualByComparingTo("100.0000");
        verify(jdbcTemplate, never())
                .update(
                        contains("INSERT INTO ledger_reservations"),
                        any(MapSqlParameterSource.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void batchReservationInsertsEveryReservationOnlyAfterAggregateCheckPasses() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.queryForList(
                        contains("FROM ledger_reservations"), any(MapSqlParameterSource.class)))
                .thenReturn(List.of());
        when(jdbcTemplate.queryForMap(
                        contains("active_reservations"), any(MapSqlParameterSource.class)))
                .thenReturn(
                        java.util.Map.of(
                                "posted_balance",
                                new BigDecimal("120.0000"),
                                "active_reservations",
                                new BigDecimal("0.0000")));
        DoubleEntryLedgerService service = new DoubleEntryLedgerService(jdbcTemplate);

        DoubleEntryLedgerService.BatchReservationResult result =
                service.reserveAll(
                        10L,
                        "UGX",
                        List.of(
                                new DoubleEntryLedgerService.ReservationCommand(
                                        "RES-BATCH-1", "PAYOUT-1", new BigDecimal("60")),
                                new DoubleEntryLedgerService.ReservationCommand(
                                        "RES-BATCH-2", "PAYOUT-2", new BigDecimal("60"))));

        assertThat(result.reserved()).isTrue();
        assertThat(result.required()).isEqualByComparingTo("120.0000");
        verify(jdbcTemplate, times(2))
                .update(
                        contains("INSERT INTO ledger_reservations"),
                        any(MapSqlParameterSource.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void batchReservationReplayAcceptsAnAlreadyHeldMatchingSlice() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.queryForList(
                        contains("FROM ledger_reservations"), any(MapSqlParameterSource.class)))
                .thenReturn(
                        List.of(
                                existingReservation(
                                        10L, "PAYOUT-1", "60.0000", "UGX", "RESERVED")));
        when(jdbcTemplate.queryForMap(
                        contains("active_reservations"), any(MapSqlParameterSource.class)))
                .thenReturn(
                        java.util.Map.of(
                                "posted_balance",
                                new BigDecimal("50.0000"),
                                "active_reservations",
                                new BigDecimal("60.0000")));
        DoubleEntryLedgerService service = new DoubleEntryLedgerService(jdbcTemplate);

        DoubleEntryLedgerService.BatchReservationResult result =
                service.reserveAll(
                        10L,
                        "UGX",
                        List.of(
                                new DoubleEntryLedgerService.ReservationCommand(
                                        "RES-BATCH-1", "PAYOUT-1", new BigDecimal("60"))));

        assertThat(result.reserved()).isTrue();
        assertThat(result.required()).isEqualByComparingTo("0.0000");
        verify(jdbcTemplate, never())
                .update(
                        contains("INSERT INTO ledger_reservations"),
                        any(MapSqlParameterSource.class));
    }

    @Test
    void batchReservationRejectsDuplicateInputReferencesBeforeDatabaseMutation() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        DoubleEntryLedgerService service = new DoubleEntryLedgerService(jdbcTemplate);

        assertThatThrownBy(
                        () ->
                                service.reserveAll(
                                        10L,
                                        "UGX",
                                        List.of(
                                                new DoubleEntryLedgerService.ReservationCommand(
                                                        "RES-DUP", "PAYOUT-1", BigDecimal.ONE),
                                                new DoubleEntryLedgerService.ReservationCommand(
                                                        "RES-DUP", "PAYOUT-2", BigDecimal.TEN))))
                .isInstanceOf(PaymentGatewayException.class)
                .hasMessageContaining("duplicate reference");

        verifyNoInteractions(jdbcTemplate);
    }

    private java.util.Map<String, Object> existingReservation(
            long merchantId,
            String sourceReference,
            String amount,
            String currency,
            String status) {
        return java.util.Map.of(
                "merchant_id",
                merchantId,
                "source_reference",
                sourceReference,
                "amount",
                new BigDecimal(amount),
                "currency",
                currency,
                "reservation_status",
                status);
    }

    private LedgerEntryCommand entry(
            String account,
            String accountType,
            String ownerType,
            Long ownerId,
            String direction,
            String amount,
            String currency) {
        return new LedgerEntryCommand(
                account,
                account,
                accountType,
                ownerType,
                ownerId,
                direction,
                new BigDecimal(amount),
                currency,
                "test");
    }

    private LedgerEntryCommand entry(String account, String direction, String amount) {
        return new LedgerEntryCommand(
                account,
                account,
                "CONTROL",
                "SYSTEM",
                null,
                direction,
                new BigDecimal(amount),
                "UGX",
                "test");
    }
}
