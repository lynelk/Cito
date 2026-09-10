package net.citotech.cito.billing.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/**
 * Covers {@link UsagePaymentReconciliationService}'s query composition against a mocked {@code
 * NamedParameterJdbcTemplate}. See {@code UsagePaymentReconciliationServiceTestcontainersTest}
 * (docker-tagged) for the real proof that the {@code NOT EXISTS} queries actually flag a seeded
 * divergent row - a mock can only assert what it is told to return, not that the SQL is correct.
 */
@SuppressWarnings("unchecked")
class UsagePaymentReconciliationServiceTest {

    @Test
    void reconcilePaymentsIsFullyReconciledWhenBothSidesMatch() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.query(
                        any(String.class), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());
        when(jdbcTemplate.queryForObject(
                        contains("merchant_production_transactions"),
                        any(SqlParameterSource.class),
                        org.mockito.ArgumentMatchers.eq(Long.class)))
                .thenReturn(5L);
        when(jdbcTemplate.queryForObject(
                        contains("billing_usage_events"),
                        any(SqlParameterSource.class),
                        org.mockito.ArgumentMatchers.eq(Long.class)))
                .thenReturn(5L);

        Instant to = Instant.now();
        Instant from = to.minus(7, ChronoUnit.DAYS);
        UsageReconciliationResult result =
                new UsagePaymentReconciliationService(jdbcTemplate).reconcilePayments(from, to);

        assertThat(result.isFullyReconciled()).isTrue();
        assertThat(result.transactionLogCount()).isEqualTo(5L);
        assertThat(result.usageEventCount()).isEqualTo(5L);
    }

    @Test
    void reconcilePaymentsFlagsRowsMissingFromUsageEvents() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        // The "missing from usage events" query's SELECT reads merchant_transactions_log but its
        // NOT EXISTS subquery also references "FROM billing_usage_events" (and vice versa for the
        // other query), so the matcher must key off the SELECT column list, not a FROM/table-name
        // substring, to avoid both stubs matching the same invocation.
        when(jdbcTemplate.query(
                        contains("SELECT tl.tx_unique_id"),
                        any(SqlParameterSource.class),
                        any(RowMapper.class)))
                .thenReturn(List.of("TX-MISSING-1"));
        when(jdbcTemplate.query(
                        contains("SELECT ue.source_reference"),
                        any(SqlParameterSource.class),
                        any(RowMapper.class)))
                .thenReturn(List.of());
        when(jdbcTemplate.queryForObject(
                        any(String.class),
                        any(SqlParameterSource.class),
                        org.mockito.ArgumentMatchers.eq(Long.class)))
                .thenReturn(1L);

        Instant to = Instant.now();
        Instant from = to.minus(7, ChronoUnit.DAYS);
        UsageReconciliationResult result =
                new UsagePaymentReconciliationService(jdbcTemplate).reconcilePayments(from, to);

        assertThat(result.isFullyReconciled()).isFalse();
        assertThat(result.missingFromUsageEvents()).containsExactly("TX-MISSING-1");
        assertThat(result.missingFromTransactionLog()).isEmpty();
    }
}
