package net.citotech.cito.scheduler;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Computes and upserts the nightly reporting aggregates backing the admin/ops dashboards (audit F4:
 * {@code daily_transaction_stats}, N10: {@code daily_failure_reason_stats}, O3: {@code
 * float_balance_snapshots}), driven by {@link ReportingAggregateScheduler}.
 *
 * <p>Transaction/failure stats are computed with plain {@code GROUP BY} aggregate SQL over {@code
 * merchant_transactions_log} for a single target day - never by looping over raw rows in
 * application code. This table has no read replica, so keeping the aggregation to one bounded
 * (single day) scan and writing pre-aggregated rows is what lets dashboards avoid repeatedly
 * scanning the full log (see also the F3 archival work, which added {@code archived_on} / {@code
 * idx_mtl_status_created_on} to the same table on a separate branch).
 *
 * <p>This class intentionally does not modify {@code merchant_transactions_log}'s schema and does
 * not touch {@code Common.java}'s doPayIn/doPayOut logic - it only reads from existing tables and
 * writes to the new additive tables created by {@code V27__reporting_aggregates.sql}.
 */
@Service
public class ReportingAggregateService {
    private static final Logger logger =
            Logger.getLogger(ReportingAggregateService.class.getName());

    /**
     * Gateway ids whose balances are tracked on the float/stock merchant account (see {@code
     * net.citotech.cito.scheduler.FloatAlertScheduler} and {@code
     * net.citotech.cito.Model.Balance}), each mapped to the {@code merchant_statement} column
     * holding its running balance.
     */
    private static final Map<String, String> FLOAT_BALANCE_COLUMNS_BY_GATEWAY =
            Map.of(
                    "MTNMoMoPaymentGateway", "mtnmm_balance",
                    "AirtelMoneyPaymentGateway", "airtelmm_balance",
                    "SafariComPaymentGateway", "safaricom_balance");

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public ReportingAggregateService(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Runs all three aggregations for a single target day (e.g. "yesterday"). */
    public void aggregateForDate(LocalDate targetDate) {
        int txStatsGroups = upsertTransactionStats(targetDate);
        int failureGroups = upsertFailureReasonStats(targetDate);
        int balanceSnapshots = upsertFloatBalanceSnapshot(targetDate);
        logger.log(
                Level.INFO,
                "Reporting aggregates for {0}: daily_transaction_stats groups={1}, "
                        + "daily_failure_reason_stats groups={2}, float_balance_snapshots={3}",
                new Object[] {targetDate, txStatsGroups, failureGroups, balanceSnapshots});
    }

    /**
     * Audit F4: rolls up {@code merchant_transactions_log} for {@code targetDate} into one row per
     * (merchant, gateway, tx_type, status), upserting counts/amounts so re-running the same day is
     * idempotent.
     */
    public int upsertTransactionStats(LocalDate targetDate) {
        MapSqlParameterSource params = dateRangeParams(targetDate);
        String sql =
                "INSERT INTO daily_transaction_stats "
                        + "(stat_date, merchant_id, gateway_id, tx_type, status, tx_count, total_amount, total_charges) "
                        + "SELECT :statDate, COALESCE(merchant_id, 0) AS merchant_id, gateway_id, tx_type, status, "
                        + "       COUNT(*) AS tx_count, "
                        + "       COALESCE(SUM(original_amount), 0) AS total_amount, "
                        + "       COALESCE(SUM(charges), 0) AS total_charges "
                        + "FROM merchant_production_transactions "
                        + "WHERE created_on >= :rangeStart AND created_on < :rangeEnd "
                        + "GROUP BY merchant_id, gateway_id, tx_type, status "
                        + "ON DUPLICATE KEY UPDATE "
                        + "  tx_count = VALUES(tx_count), "
                        + "  total_amount = VALUES(total_amount), "
                        + "  total_charges = VALUES(total_charges), "
                        + "  updated_at = CURRENT_TIMESTAMP";
        return jdbcTemplate.update(sql, params);
    }

    /**
     * Audit N10: rolls up FAILED rows for {@code targetDate} by gateway and failure reason. {@code
     * merchant_transactions_log} has no dedicated structured error-code column - failures are only
     * ever recorded as a raw provider/callback trace in {@code tx_update_trace} (async
     * status-check/callback path) or {@code tx_request_trace} (synchronous decline), see {@code
     * Common.java}'s doPayIn/doPayOut FAILED handling. When that trace happens to be the structured
     * JSON produced by {@code GeneralException.getError} (it has a {@code "code"} field), that
     * legacy numeric code is extracted into {@code error_code} so it can be resolved via {@code
     * ErrorCatalog} at read time; otherwise {@code error_code} stays blank and {@code
     * failure_reason} holds a truncated/normalized version of the raw trace text.
     */
    public int upsertFailureReasonStats(LocalDate targetDate) {
        MapSqlParameterSource params = dateRangeParams(targetDate);
        String sql =
                "INSERT INTO daily_failure_reason_stats "
                        + "(stat_date, gateway_id, error_code, failure_reason, tx_count) "
                        + "SELECT :statDate, gateway_id, "
                        + "       CASE WHEN JSON_VALID(reason_text) "
                        + "            THEN COALESCE(JSON_UNQUOTE(JSON_EXTRACT(reason_text, '$.code')), '') "
                        + "            ELSE '' END AS error_code, "
                        + "       LEFT(COALESCE(NULLIF(reason_text, ''), 'UNKNOWN'), 191) AS failure_reason, "
                        + "       COUNT(*) AS tx_count "
                        + "FROM ( "
                        + "  SELECT gateway_id, "
                        + "         COALESCE(NULLIF(CONVERT(tx_update_trace USING utf8mb4), ''), "
                        + "                  CONVERT(tx_request_trace USING utf8mb4), '') AS reason_text "
                        + "  FROM merchant_production_transactions "
                        + "  WHERE status = 'FAILED' AND created_on >= :rangeStart AND created_on < :rangeEnd "
                        + ") failed_tx "
                        + "GROUP BY gateway_id, error_code, failure_reason "
                        + "ON DUPLICATE KEY UPDATE "
                        + "  tx_count = VALUES(tx_count), "
                        + "  updated_at = CURRENT_TIMESTAMP";
        return jdbcTemplate.update(sql, params);
    }

    /**
     * Audit O3: snapshots the current float/stock account balance per gateway as of {@code
     * targetDate}. Unlike the two aggregates above, there is no per-transaction table to {@code
     * GROUP BY} here - the "current" balance is already a running total on the float merchant's
     * latest {@code merchant_statement} row (the same source {@code FloatAlertScheduler} reads), so
     * this snapshots that small, fixed set of gateway balances rather than aggregating raw rows.
     *
     * @return the number of gateway balance rows written (0 if no float/stock account is configured
     *     or it has no statement history yet)
     */
    public int upsertFloatBalanceSnapshot(LocalDate targetDate) {
        String stockAccountNumber = settingValue("float_stock_account");
        if (stockAccountNumber == null || stockAccountNumber.isBlank()) {
            return 0;
        }
        Long merchantId = resolveMerchantId(stockAccountNumber.trim());
        if (merchantId == null) {
            return 0;
        }
        Map<String, BigDecimal> balances = latestFloatBalances(merchantId);
        if (balances == null || balances.isEmpty()) {
            return 0;
        }

        String upsertSql =
                "INSERT INTO float_balance_snapshots (stat_date, account_type, balance) "
                        + "VALUES (:statDate, :accountType, :balance) "
                        + "ON DUPLICATE KEY UPDATE balance = VALUES(balance), updated_at = CURRENT_TIMESTAMP";
        int written = 0;
        for (Map.Entry<String, BigDecimal> entry : balances.entrySet()) {
            MapSqlParameterSource params = new MapSqlParameterSource();
            params.addValue("statDate", Date.valueOf(targetDate));
            params.addValue("accountType", entry.getKey());
            params.addValue("balance", entry.getValue());
            written += jdbcTemplate.update(upsertSql, params);
        }
        return written;
    }

    private MapSqlParameterSource dateRangeParams(LocalDate targetDate) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("statDate", Date.valueOf(targetDate));
        params.addValue("rangeStart", Timestamp.valueOf(targetDate.atStartOfDay()));
        params.addValue("rangeEnd", Timestamp.valueOf(targetDate.plusDays(1).atStartOfDay()));
        return params;
    }

    private String settingValue(String name) {
        List<String> values =
                jdbcTemplate.query(
                        "SELECT setting_value FROM settings WHERE name = :name",
                        new MapSqlParameterSource("name", name),
                        (rs, rowNum) -> rs.getString("setting_value"));
        return values.isEmpty() ? null : values.get(0);
    }

    private Long resolveMerchantId(String accountNumber) {
        List<Long> ids =
                jdbcTemplate.query(
                        "SELECT id FROM merchants WHERE account_number = :accountNumber",
                        new MapSqlParameterSource("accountNumber", accountNumber),
                        (rs, rowNum) -> rs.getLong("id"));
        return ids.isEmpty() ? null : ids.get(0);
    }

    private Map<String, BigDecimal> latestFloatBalances(long merchantId) {
        List<Map<String, BigDecimal>> rows =
                jdbcTemplate.query(
                        "SELECT mtnmm_balance, airtelmm_balance, safaricom_balance FROM merchant_statement "
                                + "WHERE merchant_id = :merchantId ORDER BY id DESC LIMIT 1",
                        new MapSqlParameterSource("merchantId", merchantId),
                        (rs, rowNum) -> {
                            Map<String, BigDecimal> map = new LinkedHashMap<>();
                            for (Map.Entry<String, String> gatewayColumn :
                                    FLOAT_BALANCE_COLUMNS_BY_GATEWAY.entrySet()) {
                                map.put(
                                        gatewayColumn.getKey(),
                                        BigDecimal.valueOf(rs.getDouble(gatewayColumn.getValue())));
                            }
                            return map;
                        });
        return rows.isEmpty() ? null : rows.get(0);
    }
}
