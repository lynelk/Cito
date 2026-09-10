package net.citotech.cito.reconciliation;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
public class ReconciliationRepository {
    private static final BigDecimal LEGACY_AMOUNT_TOLERANCE = new BigDecimal("0.0001");

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public ReconciliationRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long createImport(
            String providerCode,
            String channelCode,
            String fileName,
            String importedBy,
            int totalRecords) {
        String sql =
                "INSERT INTO reconciliation_imports (provider_code, channel_code, source_file_name,"
                        + " imported_by, total_records) VALUES (:provider_code, :channel_code,"
                        + " :source_file_name, :imported_by, :total_records)";
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("provider_code", providerCode);
        p.addValue("channel_code", channelCode);
        p.addValue("source_file_name", fileName);
        p.addValue("imported_by", importedBy);
        p.addValue("total_records", totalRecords);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(sql, p, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public void insertStatementRow(long importId, StatementRow row) {
        String sql =
                "INSERT INTO reconciliation_records (import_id, provider_code, channel_code,"
                        + " provider_reference, merchant_reference, amount, currency, match_status)"
                        + " VALUES (:import_id, :provider_code, :channel_code, :provider_reference,"
                        + " :merchant_reference, :amount, :currency, 'UNMATCHED')";
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("import_id", importId);
        p.addValue("provider_code", row.providerCode);
        p.addValue("channel_code", row.channelCode);
        p.addValue("provider_reference", row.providerReference);
        p.addValue("merchant_reference", row.merchantReference);
        p.addValue("amount", row.amount);
        p.addValue("currency", row.currency == null ? null : row.currency.trim().toUpperCase());
        jdbcTemplate.update(sql, p);
    }

    public List<ReconciliationRecord> findUnmatched(int limit) {
        String sql =
                "SELECT * FROM reconciliation_records WHERE match_status='UNMATCHED' ORDER BY id"
                        + " ASC LIMIT :limit";
        return jdbcTemplate.query(
                sql,
                new MapSqlParameterSource("limit", limit),
                (rs, rowNum) -> {
                    ReconciliationRecord record = new ReconciliationRecord();
                    record.id = rs.getLong("id");
                    record.providerCode = rs.getString("provider_code");
                    record.channelCode = rs.getString("channel_code");
                    record.providerReference = rs.getString("provider_reference");
                    record.merchantReference = rs.getString("merchant_reference");
                    record.transactionId = rs.getString("transaction_id");
                    record.amount = rs.getBigDecimal("amount");
                    record.currency = rs.getString("currency");
                    record.matchStatus = rs.getString("match_status");
                    record.matchReason = rs.getString("match_reason");
                    Timestamp createdAt = rs.getTimestamp("created_at");
                    record.createdAt = createdAt == null ? null : createdAt.toLocalDateTime();
                    return record;
                });
    }

    /**
     * Automatically matches only a unique, financially equivalent, final transaction candidate. A
     * reference alone is never sufficient evidence. Ambiguous or financially inconsistent rows
     * remain UNMATCHED for operator review.
     */
    public int autoMatchByMerchantReference() {
        String sql =
                "UPDATE reconciliation_records rr "
                        + "JOIN ("
                        + " SELECT rr2.id AS reconciliation_id, MIN(tx.tx_unique_id) AS transaction_id, COUNT(*) AS candidate_count"
                        + " FROM reconciliation_records rr2"
                        + " JOIN merchant_production_transactions tx ON tx.tx_merchant_ref = rr2.merchant_reference"
                        + "  AND ABS(CAST(tx.original_amount AS DECIMAL(24,8)) - rr2.amount) < :amount_tolerance"
                        + "  AND UPPER(tx.currency) = UPPER(rr2.currency)"
                        + "  AND UPPER(tx.status) IN ('SUCCESS','SUCCESSFUL','COMPLETED','PAID')"
                        + " WHERE rr2.match_status='UNMATCHED'"
                        + "  AND rr2.merchant_reference IS NOT NULL"
                        + "  AND rr2.amount IS NOT NULL"
                        + "  AND rr2.currency IS NOT NULL"
                        + " GROUP BY rr2.id HAVING COUNT(*) = 1"
                        + ") candidate ON candidate.reconciliation_id = rr.id "
                        + "SET rr.transaction_id = candidate.transaction_id, rr.match_status='MATCHED',"
                        + " rr.match_reason='merchant_reference+amount+currency+final_status+unique_candidate' "
                        + "WHERE rr.match_status='UNMATCHED'";
        return jdbcTemplate.update(
                sql, new MapSqlParameterSource("amount_tolerance", LEGACY_AMOUNT_TOLERANCE));
    }

    public void markOperatorMatch(long recordId, String transactionId, String reason) {
        String sql =
                "UPDATE reconciliation_records SET transaction_id=:transaction_id,"
                        + " match_status='MANUAL_MATCH', match_reason=:match_reason WHERE id=:id";
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("id", recordId);
        p.addValue("transaction_id", transactionId);
        p.addValue("match_reason", reason);
        jdbcTemplate.update(sql, p);
    }

    /**
     * Candidate-transaction search backing the manual-match workbench. The legacy transaction
     * amount column can contain floating-point history, so the search uses a narrow tolerance. This
     * tolerance is for discovery only; automatic matching additionally requires currency, finality
     * and uniqueness.
     */
    public List<CandidateTransaction> findCandidateTransactions(
            String reference,
            BigDecimal amount,
            String currency,
            LocalDate from,
            LocalDate to,
            int limit) {
        StringBuilder sql =
                new StringBuilder(
                        "SELECT id, tx_unique_id, tx_merchant_ref, tx_gateway_ref, merchant_id,"
                                + " original_amount, currency, status, tx_type, created_on,"
                                + " payer_number FROM merchant_production_transactions WHERE 1=1");
        MapSqlParameterSource p = new MapSqlParameterSource();
        if (StringUtils.hasText(reference)) {
            sql.append(
                    " AND (tx_unique_id LIKE :reference OR tx_merchant_ref LIKE :reference OR"
                            + " tx_gateway_ref LIKE :reference)");
            p.addValue("reference", "%" + reference + "%");
        }
        if (amount != null) {
            sql.append(
                    " AND ABS(CAST(original_amount AS DECIMAL(24,8)) - :amount) < :amount_tolerance");
            p.addValue("amount", amount);
            p.addValue("amount_tolerance", LEGACY_AMOUNT_TOLERANCE);
        }
        if (StringUtils.hasText(currency)) {
            sql.append(" AND UPPER(currency) = :currency");
            p.addValue("currency", currency.trim().toUpperCase());
        }
        if (from != null) {
            sql.append(" AND created_on >= :from");
            p.addValue("from", Timestamp.valueOf(from.atStartOfDay()));
        }
        if (to != null) {
            sql.append(" AND created_on < :to");
            p.addValue("to", Timestamp.valueOf(to.plusDays(1).atStartOfDay()));
        }
        sql.append(" ORDER BY created_on DESC LIMIT :limit");
        p.addValue("limit", limit);
        return jdbcTemplate.query(
                sql.toString(),
                p,
                (rs, rowNum) -> {
                    CandidateTransaction tx = new CandidateTransaction();
                    tx.id = rs.getLong("id");
                    tx.txUniqueId = rs.getString("tx_unique_id");
                    tx.txMerchantRef = rs.getString("tx_merchant_ref");
                    tx.txGatewayRef = rs.getString("tx_gateway_ref");
                    long merchantId = rs.getLong("merchant_id");
                    tx.merchantId = rs.wasNull() ? null : merchantId;
                    tx.originalAmount = rs.getBigDecimal("original_amount");
                    tx.currency = rs.getString("currency");
                    tx.status = rs.getString("status");
                    tx.txType = rs.getString("tx_type");
                    Timestamp createdOn = rs.getTimestamp("created_on");
                    tx.createdOn = createdOn == null ? null : createdOn.toLocalDateTime();
                    tx.payerNumber = rs.getString("payer_number");
                    return tx;
                });
    }
}
