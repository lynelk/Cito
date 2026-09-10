package net.citotech.cito.gateway;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Durable immutable correlations and fenced, expiring per-row leases for multiple replicas. */
@Repository
public class AirtelRecoveryStore {
    private final NamedParameterJdbcTemplate jdbc;

    public AirtelRecoveryStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Instant now() {
        return jdbc.queryForObject(
                        "SELECT CURRENT_TIMESTAMP", new MapSqlParameterSource(), Timestamp.class)
                .toInstant();
    }

    public Ticket prepare(Entry entry) {
        boolean created = true;
        try {
            jdbc.update(
                    "INSERT INTO airtel_recovery (provider_reference,merchant_id,merchant_number,merchant_reference,"
                            + "transaction_id,operation,environment,country_code,currency_code,credential_source,credential_identity,"
                            + "amount,request_hash,callback_url,recovery_state,next_poll_at) VALUES"
                            + " (:provider,:merchant,:number,:reference,:transaction,:operation,:environment,:country,:currency,:source,:identity,"
                            + ":amount,:hash,:callback,'PREPARED',:next)",
                    params(entry).addValue("next", Timestamp.from(now().plusSeconds(120))));
        } catch (DuplicateKeyException e) {
            created = false;
        }
        List<Entry> found =
                jdbc.query(
                        "SELECT * FROM airtel_recovery WHERE merchant_id=:merchant AND environment=:environment"
                                + " AND merchant_reference=:reference FOR UPDATE",
                        params(entry),
                        this::map);
        if (found.size() != 1 || !found.get(0).requestHash().equals(entry.requestHash()))
            throw new PaymentGatewayException("AIRTEL_REFERENCE_CONFLICT");
        return new Ticket(found.get(0), created);
    }

    public List<Entry> findByMerchantReference(long merchantId, String reference) {
        return jdbc.query(
                "SELECT * FROM airtel_recovery WHERE merchant_id=:merchant AND merchant_reference=:reference LIMIT 2",
                new MapSqlParameterSource("merchant", merchantId).addValue("reference", reference),
                this::map);
    }

    public void submitted(String provider, String rejection) {
        jdbc.update(
                "UPDATE airtel_recovery SET recovery_state='READY',submission_rejection=:code,next_poll_at=:next"
                        + " WHERE provider_reference=:provider AND recovery_state='PREPARED'",
                new MapSqlParameterSource("provider", provider)
                        .addValue("code", rejection)
                        .addValue("next", Timestamp.from(now().plusSeconds(30))));
    }

    public List<Entry> due(int limit) {
        return jdbc.query(
                "SELECT * FROM airtel_recovery WHERE recovery_state IN ('PREPARED','READY','REVIEW')"
                        + " AND next_poll_at<=CURRENT_TIMESTAMP AND (claim_until IS NULL OR claim_until<CURRENT_TIMESTAMP)"
                        + " ORDER BY next_poll_at,id LIMIT :limit",
                new MapSqlParameterSource("limit", Math.max(1, Math.min(limit, 100))),
                this::map);
    }

    public String claim(long id) {
        String claim = UUID.randomUUID().toString();
        int changed =
                jdbc.update(
                        "UPDATE airtel_recovery SET claim_token=:claim,claim_until=:until"
                                + " WHERE id=:id AND recovery_state IN ('PREPARED','READY','REVIEW')"
                                + " AND next_poll_at<=CURRENT_TIMESTAMP AND (claim_until IS NULL OR claim_until<CURRENT_TIMESTAMP)",
                        new MapSqlParameterSource("id", id)
                                .addValue("claim", claim)
                                .addValue("until", Timestamp.from(now().plusSeconds(90))));
        return changed == 1 ? claim : null;
    }

    public Entry locked(long id, String claim) {
        List<Entry> rows =
                jdbc.query(
                        "SELECT * FROM airtel_recovery WHERE id=:id AND claim_token=:claim"
                                + " AND claim_until>CURRENT_TIMESTAMP AND recovery_state<>'RESOLVED' FOR UPDATE",
                        new MapSqlParameterSource("id", id).addValue("claim", claim),
                        this::map);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void retry(Entry entry, String claim, String code) {
        int attempt = Math.min(entry.attempts() + 1, 1000000);
        long delay = Math.min(1800, 30L << Math.min(attempt, 6));
        jdbc.update(
                "UPDATE airtel_recovery SET recovery_state=:state,attempt_count=:attempt,last_code=:code,"
                        + "next_poll_at=:next,claim_token=NULL,claim_until=NULL WHERE id=:id AND claim_token=:claim",
                new MapSqlParameterSource("id", entry.id())
                        .addValue("claim", claim)
                        .addValue("attempt", attempt)
                        .addValue("state", attempt >= 48 ? "REVIEW" : "READY")
                        .addValue("code", code)
                        .addValue("next", Timestamp.from(now().plusSeconds(delay))));
    }

    public void resolved(Entry entry, String claim, String status, String financialReference) {
        int changed =
                jdbc.update(
                        "UPDATE airtel_recovery SET recovery_state='RESOLVED',terminal_status=:status,"
                                + "financial_reference=:financial,last_code='VERIFIED',claim_token=NULL,claim_until=NULL"
                                + " WHERE id=:id AND claim_token=:claim AND claim_until>CURRENT_TIMESTAMP",
                        new MapSqlParameterSource("id", entry.id())
                                .addValue("claim", claim)
                                .addValue("status", status)
                                .addValue("financial", financialReference));
        if (changed != 1) throw new PaymentGatewayException("AIRTEL_RECOVERY_LEASE_LOST");
    }

    public void signal(String provider) {
        jdbc.update(
                "UPDATE airtel_recovery SET next_poll_at=LEAST(next_poll_at,CURRENT_TIMESTAMP),last_signal_at=CURRENT_TIMESTAMP"
                        + " WHERE provider_reference=:provider AND recovery_state IN ('READY','REVIEW')"
                        + " AND (last_signal_at IS NULL OR last_signal_at<:earliest)",
                new MapSqlParameterSource("provider", provider)
                        .addValue("earliest", Timestamp.from(now().minusSeconds(30))));
    }

    private MapSqlParameterSource params(Entry e) {
        return new MapSqlParameterSource("provider", e.provider())
                .addValue("merchant", e.merchantId())
                .addValue("number", e.merchantNumber())
                .addValue("reference", e.merchantReference())
                .addValue("transaction", e.transactionId())
                .addValue("operation", e.operation())
                .addValue("environment", e.environment())
                .addValue("country", e.country())
                .addValue("currency", e.currency())
                .addValue("source", e.source())
                .addValue("identity", e.identity())
                .addValue("amount", e.amount())
                .addValue("hash", e.requestHash())
                .addValue("callback", e.callbackUrl());
    }

    private Entry map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Entry(
                rs.getLong("id"),
                rs.getString("provider_reference"),
                rs.getLong("merchant_id"),
                rs.getString("merchant_number"),
                rs.getString("merchant_reference"),
                rs.getObject("transaction_id") == null
                        ? null
                        : ((Number) rs.getObject("transaction_id")).longValue(),
                rs.getString("operation"),
                rs.getString("environment"),
                rs.getString("country_code"),
                rs.getString("currency_code"),
                rs.getString("credential_source"),
                rs.getString("credential_identity"),
                rs.getBigDecimal("amount"),
                rs.getString("request_hash"),
                rs.getString("callback_url"),
                rs.getInt("attempt_count"),
                rs.getString("submission_rejection"),
                rs.getString("terminal_status"));
    }

    public record Ticket(Entry entry, boolean created) {}

    public record Entry(
            long id,
            String provider,
            long merchantId,
            String merchantNumber,
            String merchantReference,
            Long transactionId,
            String operation,
            String environment,
            String country,
            String currency,
            String source,
            String identity,
            BigDecimal amount,
            String requestHash,
            String callbackUrl,
            int attempts,
            String submissionRejection,
            String terminalStatus) {}
}
