package net.citotech.cito.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Covers the F4/N10/O3 nightly reporting aggregate upserts: the transaction-stats and
 * failure-reason GROUP BY upserts are invoked with the right day boundaries, and the float balance
 * snapshot step correctly no-ops or writes depending on what the settings/merchants/
 * merchant_statement lookups return. Mirrors this codebase's established
 * NamedParameterJdbcTemplate-mocking style (see TransactionTimeoutSchedulerTest).
 */
@SuppressWarnings({"rawtypes", "unchecked"})
class ReportingAggregateServiceTest {

    private static final LocalDate TARGET_DATE = LocalDate.of(2026, 7, 27);

    @Test
    void upsertTransactionStatsUsesTheTargetDatesFullDayBoundaries() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.update(any(String.class), any(MapSqlParameterSource.class)))
                .thenReturn(3);
        ReportingAggregateService service = new ReportingAggregateService(jdbcTemplate);

        int groups = service.upsertTransactionStats(TARGET_DATE);

        assertThat(groups).isEqualTo(3);
        verify(jdbcTemplate)
                .update(
                        argThat(
                                sql ->
                                        sql.contains("daily_transaction_stats")
                                                && sql.contains("merchant_production_transactions")
                                                && sql.contains("ON DUPLICATE KEY UPDATE")),
                        argThat(
                                (MapSqlParameterSource params) ->
                                        params.getValue("statDate")
                                                        .equals(java.sql.Date.valueOf(TARGET_DATE))
                                                && params.getValue("rangeStart")
                                                        .equals(
                                                                Timestamp.valueOf(
                                                                        TARGET_DATE.atStartOfDay()))
                                                && params.getValue("rangeEnd")
                                                        .equals(
                                                                Timestamp.valueOf(
                                                                        TARGET_DATE
                                                                                .plusDays(1)
                                                                                .atStartOfDay()))));
    }

    @Test
    void upsertFailureReasonStatsUsesTheTargetDatesFullDayBoundariesAndFailedFilter() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.update(any(String.class), any(MapSqlParameterSource.class)))
                .thenReturn(2);
        ReportingAggregateService service = new ReportingAggregateService(jdbcTemplate);

        int groups = service.upsertFailureReasonStats(TARGET_DATE);

        assertThat(groups).isEqualTo(2);
        verify(jdbcTemplate)
                .update(
                        argThat(
                                sql ->
                                        sql.contains("daily_failure_reason_stats")
                                                && sql.contains("status = 'FAILED'")
                                                && sql.contains("ON DUPLICATE KEY UPDATE")),
                        argThat(
                                (MapSqlParameterSource params) ->
                                        params.getValue("statDate")
                                                        .equals(java.sql.Date.valueOf(TARGET_DATE))
                                                && params.getValue("rangeStart")
                                                        .equals(
                                                                Timestamp.valueOf(
                                                                        TARGET_DATE.atStartOfDay()))
                                                && params.getValue("rangeEnd")
                                                        .equals(
                                                                Timestamp.valueOf(
                                                                        TARGET_DATE
                                                                                .plusDays(1)
                                                                                .atStartOfDay()))));
    }

    @Test
    void floatBalanceSnapshotIsNoopWhenNoStockAccountIsConfigured() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.query(
                        contains("FROM settings"),
                        any(MapSqlParameterSource.class),
                        any(RowMapper.class)))
                .thenReturn(List.of());
        ReportingAggregateService service = new ReportingAggregateService(jdbcTemplate);

        int written = service.upsertFloatBalanceSnapshot(TARGET_DATE);

        assertThat(written).isEqualTo(0);
        verify(jdbcTemplate, times(0))
                .update(contains("float_balance_snapshots"), any(MapSqlParameterSource.class));
    }

    @Test
    void floatBalanceSnapshotIsNoopWhenTheConfiguredAccountNumberHasNoMerchant() throws Exception {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        stubSettingLookup(jdbcTemplate, "float_stock_account", "STOCK-001");
        when(jdbcTemplate.query(
                        contains("FROM merchants"),
                        any(MapSqlParameterSource.class),
                        any(RowMapper.class)))
                .thenReturn(List.of());
        ReportingAggregateService service = new ReportingAggregateService(jdbcTemplate);

        int written = service.upsertFloatBalanceSnapshot(TARGET_DATE);

        assertThat(written).isEqualTo(0);
        verify(jdbcTemplate, times(0))
                .update(contains("float_balance_snapshots"), any(MapSqlParameterSource.class));
    }

    @Test
    void floatBalanceSnapshotIsNoopWhenTheStockMerchantHasNoStatementHistory() throws Exception {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        stubSettingLookup(jdbcTemplate, "float_stock_account", "STOCK-001");
        stubMerchantLookup(jdbcTemplate, "STOCK-001", 42L);
        when(jdbcTemplate.query(
                        contains("FROM merchant_statement"),
                        any(MapSqlParameterSource.class),
                        any(RowMapper.class)))
                .thenReturn(List.of());
        ReportingAggregateService service = new ReportingAggregateService(jdbcTemplate);

        int written = service.upsertFloatBalanceSnapshot(TARGET_DATE);

        assertThat(written).isEqualTo(0);
        verify(jdbcTemplate, times(0))
                .update(contains("float_balance_snapshots"), any(MapSqlParameterSource.class));
    }

    @Test
    void floatBalanceSnapshotWritesOneRowPerGatewayWhenBalancesArePresent() throws Exception {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.update(
                        contains("float_balance_snapshots"), any(MapSqlParameterSource.class)))
                .thenReturn(1);
        stubSettingLookup(jdbcTemplate, "float_stock_account", "STOCK-001");
        stubMerchantLookup(jdbcTemplate, "STOCK-001", 42L);

        ResultSet statementRow = mock(ResultSet.class);
        when(statementRow.getDouble("mtnmm_balance")).thenReturn(1000.0);
        when(statementRow.getDouble("airtelmm_balance")).thenReturn(2000.0);
        when(statementRow.getDouble("safaricom_balance")).thenReturn(3000.0);
        when(jdbcTemplate.query(
                        contains("FROM merchant_statement"),
                        argThat(
                                (MapSqlParameterSource p) ->
                                        p != null
                                                && Long.valueOf(42L)
                                                        .equals(p.getValue("merchantId"))),
                        any(RowMapper.class)))
                .thenAnswer(
                        invocation -> {
                            RowMapper mapper = invocation.getArgument(2);
                            return List.of(mapper.mapRow(statementRow, 1));
                        });

        ReportingAggregateService service = new ReportingAggregateService(jdbcTemplate);

        int written = service.upsertFloatBalanceSnapshot(TARGET_DATE);

        assertThat(written).isEqualTo(3);
        verify(jdbcTemplate, times(3))
                .update(contains("float_balance_snapshots"), any(MapSqlParameterSource.class));
        verify(jdbcTemplate)
                .update(
                        contains("float_balance_snapshots"),
                        argThat(
                                (MapSqlParameterSource p) ->
                                        "MTNMoMoPaymentGateway".equals(p.getValue("accountType"))
                                                && java.math.BigDecimal.valueOf(1000.0)
                                                        .equals(p.getValue("balance"))));
        verify(jdbcTemplate)
                .update(
                        contains("float_balance_snapshots"),
                        argThat(
                                (MapSqlParameterSource p) ->
                                        "AirtelMoneyPaymentGateway"
                                                        .equals(p.getValue("accountType"))
                                                && java.math.BigDecimal.valueOf(2000.0)
                                                        .equals(p.getValue("balance"))));
        verify(jdbcTemplate)
                .update(
                        contains("float_balance_snapshots"),
                        argThat(
                                (MapSqlParameterSource p) ->
                                        "SafariComPaymentGateway".equals(p.getValue("accountType"))
                                                && java.math.BigDecimal.valueOf(3000.0)
                                                        .equals(p.getValue("balance"))));
    }

    @Test
    void aggregateForDateRunsAllThreeAggregations() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.update(any(String.class), any(MapSqlParameterSource.class)))
                .thenReturn(1);
        when(jdbcTemplate.query(
                        contains("FROM settings"),
                        any(MapSqlParameterSource.class),
                        any(RowMapper.class)))
                .thenReturn(List.of());
        ReportingAggregateService service = new ReportingAggregateService(jdbcTemplate);

        service.aggregateForDate(TARGET_DATE);

        verify(jdbcTemplate)
                .update(contains("daily_transaction_stats"), any(MapSqlParameterSource.class));
        verify(jdbcTemplate)
                .update(contains("daily_failure_reason_stats"), any(MapSqlParameterSource.class));
        verify(jdbcTemplate)
                .query(
                        contains("FROM settings"),
                        any(MapSqlParameterSource.class),
                        any(RowMapper.class));
    }

    private void stubSettingLookup(
            NamedParameterJdbcTemplate jdbcTemplate, String name, String value) throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getString("setting_value")).thenReturn(value);
        when(jdbcTemplate.query(
                        contains("FROM settings"),
                        argThat(
                                (MapSqlParameterSource p) ->
                                        p != null && name.equals(p.getValue("name"))),
                        any(RowMapper.class)))
                .thenAnswer(
                        invocation -> {
                            RowMapper mapper = invocation.getArgument(2);
                            return List.of(mapper.mapRow(row, 1));
                        });
    }

    private void stubMerchantLookup(
            NamedParameterJdbcTemplate jdbcTemplate, String accountNumber, long merchantId)
            throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getLong("id")).thenReturn(merchantId);
        when(jdbcTemplate.query(
                        contains("FROM merchants"),
                        argThat(
                                (MapSqlParameterSource p) ->
                                        p != null
                                                && accountNumber.equals(
                                                        p.getValue("accountNumber"))),
                        any(RowMapper.class)))
                .thenAnswer(
                        invocation -> {
                            RowMapper mapper = invocation.getArgument(2);
                            return List.of(mapper.mapRow(row, 1));
                        });
    }
}
