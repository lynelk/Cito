package net.citotech.cito.billing.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Compares {@link RatingEngine}'s computed {@code billing_rated_charges.rated_amount} against the
 * legacy {@code DoPayGateway} charge already recorded on {@code merchant_transactions_log.charges}
 * for every transaction in a window that has both (only transactions for a tenant with an active
 * price book configured produce a rated charge at all - see {@code RatedChargeOutboxHandler}).
 * Comparison/observability only: nothing here writes back to {@code merchant_transactions_log} or
 * makes {@link RatingEngine} authoritative - that cutover is a deliberately separate, later,
 * explicitly-gated slice (billing plan Risk Register #1: dual-charging risk).
 */
@Service
public class ChargeShadowComparisonService {
    private static final int COMPARISON_SCALE = 2;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public ChargeShadowComparisonService(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public ChargeShadowComparisonResult compare(Instant windowStart, Instant windowEnd) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("window_start", Timestamp.from(windowStart));
        p.addValue("window_end", Timestamp.from(windowEnd));

        List<ChargeShadowDelta> rows =
                jdbcTemplate.query(
                        "SELECT tl.tx_unique_id AS source_reference, tl.charges AS legacy_charge, "
                                + "rc.rated_amount AS rated_charge "
                                + "FROM merchant_production_transactions tl "
                                + "JOIN billing_rated_charges rc ON rc.source_reference = tl.tx_unique_id "
                                + "WHERE tl.created_on >= :window_start AND tl.created_on < :window_end "
                                + "AND rc.charge_type = 'CUSTOMER_CHARGE'",
                        p,
                        this::mapDelta);

        List<ChargeShadowDelta> diverging = rows.stream().filter(d -> !d.matches()).toList();
        long matchingCount = rows.size() - diverging.size();

        return new ChargeShadowComparisonResult(
                windowStart, windowEnd, rows.size(), matchingCount, diverging);
    }

    private ChargeShadowDelta mapDelta(ResultSet rs, int rowNum) throws SQLException {
        BigDecimal legacyCharge =
                rs.getBigDecimal("legacy_charge").setScale(COMPARISON_SCALE, RoundingMode.HALF_UP);
        BigDecimal ratedCharge =
                rs.getBigDecimal("rated_charge").setScale(COMPARISON_SCALE, RoundingMode.HALF_UP);
        return new ChargeShadowDelta(
                rs.getString("source_reference"),
                legacyCharge,
                ratedCharge,
                ratedCharge.subtract(legacyCharge));
    }
}
