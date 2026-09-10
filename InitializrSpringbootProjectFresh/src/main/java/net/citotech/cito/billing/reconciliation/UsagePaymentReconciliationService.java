package net.citotech.cito.billing.reconciliation;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Compares {@code merchant_transactions_log} (PAYIN rows) against {@code billing_usage_events}
 * ({@code service_code='PAYMENT'}) for a trailing window - the artifact proving the Phase 1 exit
 * criterion ("payments are 1:1 events... zero tolerance"). Nothing currently consumes {@code
 * billing_outbox} into {@code billing_usage_events} (that {@code OutboxEventHandler} wiring is
 * Phase 2), so running this today against live data will show every {@code
 * merchant_transactions_log} row as missing from usage events - expected until Phase 2 lands, not a
 * bug in this query.
 */
@Service
public class UsagePaymentReconciliationService {
    private final NamedParameterJdbcTemplate jdbcTemplate;

    public UsagePaymentReconciliationService(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public UsageReconciliationResult reconcilePayments(Instant windowStart, Instant windowEnd) {
        MapSqlParameterSource p = windowParams(windowStart, windowEnd);

        List<String> missingFromUsageEvents =
                jdbcTemplate.query(
                        "SELECT tl.tx_unique_id FROM merchant_production_transactions tl "
                                + "WHERE tl.tx_type = 'PAYIN' AND tl.created_on >= :window_start "
                                + "AND tl.created_on < :window_end "
                                + "AND NOT EXISTS (SELECT 1 FROM billing_usage_events ue "
                                + "WHERE ue.service_code = 'PAYMENT' AND ue.source_reference = tl.tx_unique_id)",
                        p,
                        (rs, rowNum) -> rs.getString("tx_unique_id"));

        List<String> missingFromTransactionLog =
                jdbcTemplate.query(
                        "SELECT ue.source_reference FROM billing_usage_events ue "
                                + "WHERE ue.service_code = 'PAYMENT' AND ue.event_time >= :window_start "
                                + "AND ue.event_time < :window_end "
                                + "AND NOT EXISTS (SELECT 1 FROM merchant_production_transactions tl "
                                + "WHERE tl.tx_type = 'PAYIN' AND tl.tx_unique_id = ue.source_reference)",
                        p,
                        (rs, rowNum) -> rs.getString("source_reference"));

        return new UsageReconciliationResult(
                windowStart,
                windowEnd,
                countUsageEvents(windowStart, windowEnd),
                countTransactionLog(windowStart, windowEnd),
                missingFromUsageEvents,
                missingFromTransactionLog);
    }

    private long countTransactionLog(Instant from, Instant to) {
        Long count =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM merchant_production_transactions WHERE tx_type = 'PAYIN' "
                                + "AND created_on >= :window_start AND created_on < :window_end",
                        windowParams(from, to),
                        Long.class);
        return count == null ? 0L : count;
    }

    private long countUsageEvents(Instant from, Instant to) {
        Long count =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM billing_usage_events WHERE service_code = 'PAYMENT' "
                                + "AND event_time >= :window_start AND event_time < :window_end",
                        windowParams(from, to),
                        Long.class);
        return count == null ? 0L : count;
    }

    private MapSqlParameterSource windowParams(Instant from, Instant to) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("window_start", Timestamp.from(from));
        p.addValue("window_end", Timestamp.from(to));
        return p;
    }
}
