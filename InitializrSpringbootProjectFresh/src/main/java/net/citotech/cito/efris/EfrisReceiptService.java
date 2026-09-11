package net.citotech.cito.efris;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import net.citotech.cito.Common;
import net.citotech.cito.Model.HttpRequestResponse;
import net.citotech.cito.security.CanonicalRequestSigner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * EFRIS e-receipt hook (compliance roadmap: "successful Ugandan merchant payins should eventually
 * trigger an e-receipt hook when configured").
 *
 * <p>{@link #enqueueSuccessfulPayin} queues a receipt once a Ugandan (UGX) payin is SUCCESSFUL, and
 * {@link #enqueueMissingSuccessfulPayins} backfills the outbox from {@code
 * merchant_transactions_log} so a successful payin is captured regardless of which code path
 * resolved it (v2 orchestrator or legacy callback scan). Only the SHA-256 hash of the payer MSISDN
 * is persisted in the outbox ({@code payer_msisdn_hash}); the raw number is read back from the
 * ledger only at delivery time, in-process, and is never written to the outbox or logged. {@link
 * #deliverDue} (driven by {@link EfrisReceiptScheduler}) POSTs each pending receipt to the
 * configured EFRIS endpoint with exponential backoff (5 attempts), mirroring the webhook delivery
 * pattern.
 *
 * <p>Both the on/off flag and the endpoint URL are read from the {@code settings} table so an
 * operator can configure EFRIS at runtime without a redeploy ({@code cpay.efris.enabled} = YES/NO,
 * {@code cpay.efris.endpoint} = HTTPS URL), matching how the other runtime controls are stored.
 */
@Service
public class EfrisReceiptService {

    private static final Logger logger = LoggerFactory.getLogger(EfrisReceiptService.class);
    private static final int MAX_ATTEMPTS = 5;
    private static final String ENABLED_SETTING = "cpay.efris.enabled";
    private static final String ENDPOINT_SETTING = "cpay.efris.endpoint";

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public EfrisReceiptService(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Queues an e-receipt for a successful Ugandan payin. Returns the outbox row id, or {@code 0}
     * when EFRIS is disabled or the transaction is not a UGX payin (idempotent; a second call for
     * the same transaction reference finds the existing row and returns its id).
     */
    @Transactional
    public long enqueueSuccessfulPayin(
            long merchantId,
            String merchantNumber,
            String transactionReference,
            String merchantTransactionRef,
            String payerMsisdn,
            String amount,
            String currency) {
        if (!blank(currency) && !"UGX".equalsIgnoreCase(currency.trim())) {
            return 0;
        }
        if (!settingEnabled(ENABLED_SETTING)) {
            return 0;
        }
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("receipt_reference", "EFRIS-" + Common.generateUuid());
        p.addValue("merchant_id", merchantId);
        p.addValue("merchant_number", merchantNumber);
        p.addValue("transaction_reference", transactionReference);
        p.addValue("merchant_transaction_ref", merchantTransactionRef);
        p.addValue("payer_hash", CanonicalRequestSigner.sha256Hex(normalize(payerMsisdn)));
        p.addValue("amount", new BigDecimal(amount));
        p.addValue("currency", blank(currency) ? "UGX" : currency.trim().toUpperCase());
        jdbcTemplate.update(
                "INSERT INTO efris_receipts "
                        + "(receipt_reference, merchant_id, merchant_number, transaction_reference, "
                        + "merchant_transaction_ref, payer_msisdn_hash, amount, currency, receipt_status, next_retry_at) "
                        + "VALUES (:receipt_reference, :merchant_id, :merchant_number, :transaction_reference, "
                        + ":merchant_transaction_ref, :payer_hash, :amount, :currency, 'PENDING', CURRENT_TIMESTAMP) "
                        + "ON DUPLICATE KEY UPDATE receipt_reference=receipt_reference",
                p);
        Long id =
                jdbcTemplate.queryForObject(
                        "SELECT id FROM efris_receipts WHERE transaction_reference=:transaction_reference",
                        new MapSqlParameterSource("transaction_reference", transactionReference),
                        Long.class);
        return id == null ? 0L : id;
    }

    /**
     * Backfills the outbox from the ledger: enqueues any SUCCESSFUL Ugandan (UGX) payins from the
     * last {@code lookbackDays} that do not yet have an outbox row. Called by the scheduler before
     * delivery so a successful payin is captured no matter which code path resolved it.
     */
    @Transactional
    public int enqueueMissingSuccessfulPayins(int lookbackDays, int batchLimit) {
        if (!settingEnabled(ENABLED_SETTING)) {
            return 0;
        }
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue(
                "since",
                Timestamp.valueOf(LocalDateTime.now().minusDays(Math.max(1, lookbackDays))));
        p.addValue("limit", Math.max(1, Math.min(batchLimit, 500)));
        List<Map<String, Object>> rows =
                jdbcTemplate.queryForList(
                        "SELECT m.id AS tx_row_id, m.merchant_id, m.original_amount, m.payer_number, "
                                + "m.tx_unique_id, m.tx_merchant_ref, COALESCE(merch.account_number, '') AS merchant_number "
                                + "FROM merchant_production_transactions m "
                                + "LEFT JOIN merchants merch ON merch.id = m.merchant_id "
                                + "WHERE m.tx_type='PAYIN' AND m.status='SUCCESSFUL' "
                                + "AND UPPER(m.currency)='UGX' AND m.created_on >= :since "
                                + "AND NOT EXISTS (SELECT 1 FROM efris_receipts e WHERE e.transaction_reference=m.tx_unique_id) "
                                + "ORDER BY m.id ASC LIMIT :limit",
                        p);
        int enqueued = 0;
        for (Map<String, Object> row : rows) {
            try {
                long id =
                        enqueueSuccessfulPayin(
                                ((Number) row.get("merchant_id")).longValue(),
                                String.valueOf(row.get("merchant_number")),
                                String.valueOf(row.get("tx_unique_id")),
                                row.get("tx_merchant_ref") == null
                                        ? null
                                        : String.valueOf(row.get("tx_merchant_ref")),
                                row.get("payer_number") == null
                                        ? ""
                                        : String.valueOf(row.get("payer_number")),
                                String.valueOf(row.get("original_amount")),
                                "UGX");
                if (id > 0) {
                    enqueued++;
                }
            } catch (Exception ex) {
                logger.warn(
                        "EFRIS backfill skipped tx row {}: {}",
                        row.get("tx_row_id"),
                        ex.getMessage());
            }
        }
        return enqueued;
    }

    /** Delivers due pending receipts; returns the number processed (webhook-sweep pattern). */
    public int deliverDue(int limit) {
        String endpoint = endpointSetting();
        if (blank(endpoint)) {
            return 0;
        }
        List<ReceiptRow> receipts = due(limit, endpoint);
        int processed = 0;
        for (ReceiptRow receipt : receipts) {
            deliver(receipt);
            processed++;
        }
        return processed;
    }

    private void deliver(ReceiptRow receipt) {
        try {
            String payload = payload(receipt);
            HttpRequestResponse response =
                    Common.doHttpRequest(
                            "POST",
                            receipt.endpointUrl(),
                            payload,
                            Map.of(
                                    "Content-Type",
                                    "application/json",
                                    "X-CPay-EFRIS-Reference",
                                    receipt.receiptReference()));
            int status = response == null ? 0 : response.getStatusCode();
            boolean ok = status >= 200 && status < 300;
            update(
                    receipt.id(),
                    ok ? "SENT" : nextStatus(receipt.attemptCount() + 1),
                    receipt.attemptCount() + 1,
                    status,
                    response == null ? "No response" : response.getResponse());
        } catch (Exception ex) {
            update(
                    receipt.id(),
                    nextStatus(receipt.attemptCount() + 1),
                    receipt.attemptCount() + 1,
                    0,
                    ex.getMessage());
        }
    }

    /** Builds the EFRIS payload, reading the raw payer MSISDN from the ledger in-process. */
    private String payload(ReceiptRow receipt) {
        String payerNumber =
                payerNumber(receipt.transactionReference(), receipt.merchantTransactionRef());
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"receiptReference\":\"").append(json(receipt.receiptReference())).append("\",");
        sb.append("\"transactionReference\":\"")
                .append(json(receipt.transactionReference()))
                .append("\",");
        sb.append("\"merchantNumber\":\"").append(json(receipt.merchantNumber())).append("\",");
        sb.append("\"amount\":\"").append(receipt.amount().toPlainString()).append("\",");
        sb.append("\"currency\":\"").append(json(receipt.currency())).append("\",");
        sb.append("\"payerMsisdn\":\"").append(json(payerNumber)).append("\"");
        sb.append("}");
        return sb.toString();
    }

    private String payerNumber(String transactionReference, String merchantTransactionRef) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("tx_unique_id", transactionReference);
        p.addValue("tx_merchant_ref", merchantTransactionRef);
        List<String> numbers =
                jdbcTemplate.query(
                        "SELECT payer_number FROM merchant_production_transactions "
                                + "WHERE (tx_unique_id=:tx_unique_id OR tx_merchant_ref=:tx_merchant_ref) "
                                + "AND payer_number IS NOT NULL LIMIT 1",
                        p,
                        (rs, rowNum) -> rs.getString("payer_number"));
        return numbers.isEmpty() ? "" : numbers.get(0);
    }

    private String nextStatus(int attemptCount) {
        return attemptCount >= MAX_ATTEMPTS ? "FAILED" : "PENDING";
    }

    private void update(
            long id, String status, int attempts, int httpStatus, String responseSummary) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("id", id);
        p.addValue("status", status);
        p.addValue("attempts", attempts);
        p.addValue("http_status", httpStatus);
        p.addValue("response", trim(responseSummary));
        p.addValue(
                "next_attempt",
                Timestamp.from(
                        Instant.now().plus(Math.min(60L, attempts * 5L), ChronoUnit.MINUTES)));
        jdbcTemplate.update(
                "UPDATE efris_receipts SET receipt_status=:status, retry_count=:attempts, "
                        + "efris_response_json=:response, "
                        + "next_retry_at=CASE WHEN :status='PENDING' THEN :next_attempt ELSE next_retry_at END "
                        + "WHERE id=:id",
                p);
    }

    private List<ReceiptRow> due(int limit, String endpoint) {
        MapSqlParameterSource p =
                new MapSqlParameterSource("limit", Math.max(1, Math.min(limit, 100)));
        return jdbcTemplate.query(
                "SELECT id, receipt_reference, merchant_number, transaction_reference, "
                        + "merchant_transaction_ref, amount, currency, retry_count "
                        + "FROM efris_receipts "
                        + "WHERE receipt_status='PENDING' AND next_retry_at <= CURRENT_TIMESTAMP "
                        + "ORDER BY id ASC LIMIT :limit",
                p,
                (rs, rowNum) ->
                        new ReceiptRow(
                                rs.getLong("id"),
                                rs.getString("receipt_reference"),
                                rs.getString("merchant_number"),
                                rs.getString("transaction_reference"),
                                rs.getString("merchant_transaction_ref"),
                                rs.getBigDecimal("amount"),
                                rs.getString("currency"),
                                rs.getInt("retry_count"),
                                endpoint));
    }

    private boolean settingEnabled(String name) {
        String value = settingValue(name);
        return value != null
                && ("YES".equalsIgnoreCase(value.trim()) || "TRUE".equalsIgnoreCase(value.trim()));
    }

    private String endpointSetting() {
        return settingValue(ENDPOINT_SETTING);
    }

    private String settingValue(String name) {
        List<String> values =
                jdbcTemplate.query(
                        "SELECT setting_value FROM settings WHERE name=:name",
                        new MapSqlParameterSource("name", name),
                        (rs, rowNum) -> rs.getString("setting_value"));
        return values.isEmpty() ? null : values.get(0);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private String json(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String trim(String value) {
        if (value == null) {
            return "";
        }
        return value.length() > 1000 ? value.substring(0, 1000) : value;
    }

    private boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private record ReceiptRow(
            long id,
            String receiptReference,
            String merchantNumber,
            String transactionReference,
            String merchantTransactionRef,
            BigDecimal amount,
            String currency,
            int attemptCount,
            String endpointUrl) {}
}
