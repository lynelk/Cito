package net.citotech.cito.billing.integration.cpay;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import net.citotech.cito.Model.Transaction;
import net.citotech.cito.Model.TransactionStatus;
import net.citotech.cito.gateway.PaymentGatewayException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BillingPaymentFundingService {
    private final NamedParameterJdbcTemplate jdbcTemplate;

    public BillingPaymentFundingService(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public FundingClaim claim(
            long billingTenantId,
            String suppliedReference,
            String expectedCurrency,
            BigDecimal amount,
            boolean exactAmount,
            String purpose,
            String targetReference) {
        if (suppliedReference == null
                || suppliedReference.isBlank()
                || amount == null
                || amount.signum() <= 0) {
            throw new PaymentGatewayException(
                    "A positive amount and settled CPay payment reference are required");
        }
        List<PaymentProof> proofs =
                jdbcTemplate.query(
                        "SELECT t.id,t.merchant_id,t.tx_unique_id,t.status,t.tx_type,t.original_amount,t.currency "
                                + "FROM merchant_production_transactions t JOIN billing_tenants bt ON bt.merchant_id=t.merchant_id "
                                + "WHERE bt.id=:tenant AND (t.tx_unique_id=:reference OR t.tx_merchant_ref=:reference "
                                + "OR t.tx_gateway_ref=:reference) ORDER BY t.id DESC LIMIT 2 FOR UPDATE",
                        new MapSqlParameterSource()
                                .addValue("tenant", billingTenantId)
                                .addValue("reference", suppliedReference.trim()),
                        (rs, rowNum) ->
                                new PaymentProof(
                                        rs.getLong("id"),
                                        rs.getLong("merchant_id"),
                                        rs.getString("tx_unique_id"),
                                        rs.getString("status"),
                                        rs.getString("tx_type"),
                                        rs.getBigDecimal("original_amount"),
                                        rs.getString("currency")));
        if (proofs.size() != 1) {
            throw new PaymentGatewayException(
                    "Payment reference must resolve to exactly one CPay transaction for this tenant");
        }
        PaymentProof proof = proofs.get(0);
        if (TransactionStatus.fromString(proof.status()) != TransactionStatus.SUCCESSFUL) {
            throw new PaymentGatewayException("Funding requires a SUCCESSFUL CPay payment");
        }
        if (!Transaction.TX_TYPE_PAYIN.equals(proof.transactionType())) {
            throw new PaymentGatewayException("Funding requires a CPay PAYIN transaction");
        }
        String currency = expectedCurrency == null ? "" : expectedCurrency.trim().toUpperCase();
        if (proof.currency() == null || !proof.currency().equalsIgnoreCase(currency)) {
            throw new PaymentGatewayException(
                    "Funding payment currency does not match the billing account currency");
        }
        if (proof.amount() == null || (exactAmount && proof.amount().compareTo(amount) != 0)) {
            throw new PaymentGatewayException(
                    "Funding amount does not match the settled CPay payment amount");
        }
        String allocationKey = purpose + ":" + targetReference + ":" + proof.transactionId();
        List<Map<String, Object>> existing =
                jdbcTemplate.queryForList(
                        "SELECT source_transaction_id,amount,currency FROM billing_payment_funding_allocations "
                                + "WHERE billing_tenant_id=:tenant AND allocation_key=:allocation FOR UPDATE",
                        new MapSqlParameterSource()
                                .addValue("tenant", billingTenantId)
                                .addValue("allocation", allocationKey));
        if (!existing.isEmpty()) {
            Map<String, Object> row = existing.get(0);
            BigDecimal existingAmount = (BigDecimal) row.get("amount");
            if (((Number) row.get("source_transaction_id")).longValue() != proof.transactionId()
                    || existingAmount.compareTo(amount) != 0
                    || !String.valueOf(row.get("currency")).equalsIgnoreCase(currency)) {
                throw new PaymentGatewayException("Funding allocation idempotency conflict");
            }
            return new FundingClaim(
                    proof.transactionId(),
                    proof.merchantId(),
                    proof.canonicalReference(),
                    currency,
                    proof.amount(),
                    true);
        }
        BigDecimal allocated =
                jdbcTemplate.queryForObject(
                        "SELECT COALESCE(SUM(amount),0) FROM billing_payment_funding_allocations "
                                + "WHERE billing_tenant_id=:tenant AND source_transaction_id=:source",
                        new MapSqlParameterSource()
                                .addValue("tenant", billingTenantId)
                                .addValue("source", proof.transactionId()),
                        BigDecimal.class);
        BigDecimal used = allocated == null ? BigDecimal.ZERO : allocated;
        if (used.add(amount).compareTo(proof.amount()) > 0) {
            throw new PaymentGatewayException(
                    "CPay payment has insufficient unallocated funding balance");
        }
        jdbcTemplate.update(
                "INSERT INTO billing_payment_funding_allocations "
                        + "(billing_tenant_id,source_transaction_id,allocation_key,purpose,target_reference,amount,currency) "
                        + "VALUES (:tenant,:source,:allocation,:purpose,:target,:amount,:currency)",
                new MapSqlParameterSource()
                        .addValue("tenant", billingTenantId)
                        .addValue("source", proof.transactionId())
                        .addValue("allocation", allocationKey)
                        .addValue("purpose", purpose)
                        .addValue("target", targetReference)
                        .addValue("amount", amount)
                        .addValue("currency", currency));
        return new FundingClaim(
                proof.transactionId(),
                proof.merchantId(),
                proof.canonicalReference(),
                currency,
                proof.amount(),
                false);
    }

    private record PaymentProof(
            long transactionId,
            long merchantId,
            String canonicalReference,
            String status,
            String transactionType,
            BigDecimal amount,
            String currency) {}

    public record FundingClaim(
            long sourceTransactionId,
            long merchantId,
            String sourceTransactionReference,
            String currency,
            BigDecimal settledAmount,
            boolean alreadyClaimed) {}
}
