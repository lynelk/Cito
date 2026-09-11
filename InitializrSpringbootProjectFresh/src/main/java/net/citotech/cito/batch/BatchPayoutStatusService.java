package net.citotech.cito.batch;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import net.citotech.cito.Common;
import net.citotech.cito.DoPayGateway;
import net.citotech.cito.Model.GatewayChargeDetails;
import net.citotech.cito.Model.Merchant;
import net.citotech.cito.Model.Transaction;
import net.citotech.cito.gateway.PaymentGatewayException;
import net.citotech.cito.ledger.DoubleEntryLedgerService;
import net.citotech.cito.money.MoneyAmount;
import org.json.JSONObject;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Batch-level status aggregation and idempotent per-row retry (audit B8). The existing batch payout
 * loop in TransactionsLogController only ever looks at a beneficiary's *first* linked transaction
 * (now the *most recent*, per the ORDER BY fix in {@code Common.getTxByBatchIdBeneficiaryId}) -
 * once that transaction is FAILED, nothing in the codebase previously created a new attempt. This
 * service adds that: it only re-runs rows currently in the FAILED state (a no-op, safely
 * re-callable, for anything else), creating a fresh transaction attempt per retried beneficiary
 * rather than mutating the failed one.
 */
@Service
public class BatchPayoutStatusService {
    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final PlatformTransactionManager transactionManager;
    private final DoubleEntryLedgerService ledgerService;

    public BatchPayoutStatusService(
            NamedParameterJdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            DoubleEntryLedgerService ledgerService) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionManager = transactionManager;
        this.ledgerService = ledgerService;
    }

    public BatchStatusSummary status(long batchId, long merchantId) {
        requireBatchOwnedByMerchant(batchId, merchantId);
        List<Map<String, Object>> rows =
                jdbcTemplate.queryForList(
                        "SELECT status, COUNT(*) AS row_count, COALESCE(SUM(amount), 0) AS row_amount "
                                + "FROM beneficiaries WHERE batch_id=:batch_id GROUP BY status",
                        new MapSqlParameterSource("batch_id", batchId));
        int paid = 0;
        int failed = 0;
        int inProgress = 0;
        int unpaid = 0;
        int total = 0;
        BigDecimal paidAmount = BigDecimal.ZERO;
        for (Map<String, Object> row : rows) {
            String status = String.valueOf(row.get("status"));
            int count = ((Number) row.get("row_count")).intValue();
            BigDecimal amount = new BigDecimal(String.valueOf(row.get("row_amount")));
            total += count;
            if (Transaction.BATCH_PAYMENT_PAID.equals(status)) {
                paid = count;
                paidAmount = amount;
            } else if (Transaction.BATCH_PAYMENT_FAILED.equals(status)) {
                failed = count;
            } else if (Transaction.BATCH_PAYMENT_INPROGRESS.equals(status)) {
                inProgress = count;
            } else {
                unpaid += count;
            }
        }
        return new BatchStatusSummary(batchId, total, paid, failed, inProgress, unpaid, paidAmount);
    }

    /**
     * Retries only the beneficiaries currently FAILED in this batch. Returns how many were retried.
     */
    public int retryFailed(long batchId, long merchantId) {
        Merchant merchant = requireBatchOwnedByMerchant(batchId, merchantId);
        List<BeneficiaryRow> failedRows =
                jdbcTemplate.query(
                        "SELECT id, account, amount, account_type FROM beneficiaries WHERE batch_id=:batch_id AND status=:status",
                        new MapSqlParameterSource()
                                .addValue("batch_id", batchId)
                                .addValue("status", Transaction.BATCH_PAYMENT_FAILED),
                        (rs, rowNum) ->
                                new BeneficiaryRow(
                                        rs.getLong("id"),
                                        rs.getString("account"),
                                        rs.getDouble("amount")));

        int retried = 0;
        for (BeneficiaryRow row : failedRows) {
            if (retryBeneficiary(merchant, batchId, row)) {
                retried++;
            }
        }
        return retried;
    }

    private boolean retryBeneficiary(Merchant merchant, long batchId, BeneficiaryRow row) {
        String gatewayId = DoPayGateway.getGatewayIdByMsisdn(row.account(), jdbcTemplate);
        if (gatewayId == null) {
            return false;
        }
        GatewayChargeDetails chargeDetails =
                DoPayGateway.getGatewayChargeDetailsById(jdbcTemplate, gatewayId, merchant.getId());
        Double charges =
                chargeDetails == null
                        ? 0.0
                        : DoPayGateway.getCustomerOutboundCharges(row.amount(), chargeDetails);
        Double txCost =
                chargeDetails == null
                        ? 0.0
                        : DoPayGateway.getCostOfOutboundCharges(row.amount(), chargeDetails);

        Transaction retryTx = new Transaction();
        retryTx.setGateway_id(gatewayId);
        retryTx.setOriginal_amount(row.amount());
        retryTx.setPayer_number(row.account());
        retryTx.setStatus("PENDING");
        retryTx.setMerchant_id(merchant.getId() + "");
        retryTx.setTx_description(merchant.getShort_name());
        retryTx.setTx_merchant_description("Batch retry " + batchId + ": " + row.account());
        retryTx.setTx_type(Transaction.TX_TYPE_PAYOUT);
        retryTx.setTx_unique_id(Common.generateUuid());
        retryTx.setTx_merchant_ref(Common.generateUuid());
        retryTx.setCallback_url("");
        retryTx.setOriginate_ip("localhost");
        retryTx.setCharging_method(
                chargeDetails == null ? "" : chargeDetails.getCustomerOutboundChargeMethod());
        retryTx.setCharges(charges == null ? 0.0 : charges);
        retryTx.setTx_cost(txCost == null ? 0.0 : txCost);
        retryTx.setTx_request_trace("");
        retryTx.setTx_update_trace("");
        retryTx.setTx_gateway_ref("");
        retryTx.setBeneficiary_id(row.id());
        retryTx.setMerchant_batch_transactions_log_id(batchId);

        if (net.citotech.cito.gateway.MobileMoneyCompatibilityBridge.manages(retryTx)) {
            String reference =
                    "batch-payout:"
                            + batchId
                            + ":"
                            + row.id()
                            + ":retry:"
                            + java.util.UUID.randomUUID();
            retryTx.setTx_merchant_ref(reference);
            var claim =
                    new org.springframework.transaction.support.TransactionTemplate(
                            transactionManager);
            claim.setPropagationBehavior(
                    org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            Boolean claimed =
                    claim.execute(
                            ignored -> {
                                String batchStatus =
                                        jdbcTemplate.queryForObject(
                                                "SELECT status FROM merchant_batch_transactions_log WHERE id=:batch AND merchant_id=:merchant FOR UPDATE",
                                                new MapSqlParameterSource("batch", batchId)
                                                        .addValue("merchant", merchant.getId()),
                                                String.class);
                                if (!"PROCESSING".equals(batchStatus)
                                        && !"DONE".equals(batchStatus)) return false;
                                int changed =
                                        jdbcTemplate.update(
                                                "UPDATE beneficiaries SET status='INPROGRESS',reason='',active_payment_reference=:reference WHERE id=:beneficiary AND batch_id=:batch AND status='FAILED'",
                                                new MapSqlParameterSource("reference", reference)
                                                        .addValue("beneficiary", row.id())
                                                        .addValue("batch", batchId));
                                if (changed != 1) return false;
                                jdbcTemplate.update(
                                        "UPDATE merchant_batch_transactions_log SET status='PROCESSING' WHERE id=:batch AND status='DONE'",
                                        new MapSqlParameterSource("batch", batchId));
                                return true;
                            });
            if (!Boolean.TRUE.equals(claimed)) return false;
            try {
                Common.doPayOut(retryTx, merchant, jdbcTemplate, transactionManager);
                if ("FAILED".equals(retryTx.getStatus()))
                    markBeneficiary(
                            row.id(),
                            Transaction.BATCH_PAYMENT_FAILED,
                            "Provider confirmed failure");
                else if ("SUCCESSFUL".equals(retryTx.getStatus()))
                    markBeneficiary(row.id(), Transaction.BATCH_PAYMENT_PAID, "");
            } catch (RuntimeException ex) {
                Transaction durable =
                        claim.execute(
                                ignored ->
                                        Common.getMerchantTxByTheirRef(
                                                reference,
                                                merchant.getId().toString(),
                                                jdbcTemplate));
                Integer queued =
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM payout_approval_queue WHERE merchant_id=:merchant AND payout_reference=:reference AND queue_status NOT IN ('REJECTED','CANCELLED')",
                                new MapSqlParameterSource("merchant", merchant.getId())
                                        .addValue("reference", reference),
                                Integer.class);
                if (durable == null && (queued == null || queued == 0))
                    markBeneficiary(
                            row.id(),
                            Transaction.BATCH_PAYMENT_FAILED,
                            "Retry could not be submitted");
            }
            return true;
        }

        String reservationReference =
                "batch-retry-reserve:" + batchId + ":" + row.id() + ":" + retryTx.getTx_unique_id();
        BigDecimal reservedAmount =
                MoneyAmount.of(String.valueOf(row.amount()))
                        .asBigDecimal()
                        .add(charges == null ? BigDecimal.ZERO : BigDecimal.valueOf(charges));
        ledgerService.reserve(
                reservationReference,
                merchant.getId(),
                retryTx.getTx_merchant_ref(),
                reservedAmount,
                "UGX");

        String resultJson;
        try {
            resultJson = Common.doPayOut(retryTx, merchant, jdbcTemplate, transactionManager);
        } catch (RuntimeException ex) {
            ledgerService.releaseReservation(reservationReference);
            markBeneficiary(row.id(), Transaction.BATCH_PAYMENT_FAILED, ex.getMessage());
            return true;
        }

        JSONObject result = new JSONObject(resultJson);
        boolean succeeded =
                "OK".equals(result.optString("state")) && "000".equals(result.optString("code"));
        if (succeeded) {
            ledgerService.captureReservation(reservationReference);
            markBeneficiary(row.id(), Transaction.BATCH_PAYMENT_INPROGRESS, "");
        } else {
            ledgerService.releaseReservation(reservationReference);
            markBeneficiary(
                    row.id(),
                    Transaction.BATCH_PAYMENT_FAILED,
                    result.optString("message", resultJson));
        }
        return true;
    }

    private void markBeneficiary(long beneficiaryId, String status, String reason) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("id", beneficiaryId);
        p.addValue("status", status);
        p.addValue("reason", reason == null ? "" : reason);
        jdbcTemplate.update(
                "UPDATE beneficiaries SET status=:status, reason=:reason WHERE id=:id", p);
    }

    private Merchant requireBatchOwnedByMerchant(long batchId, long merchantId) {
        List<Long> owners =
                jdbcTemplate.query(
                        "SELECT merchant_id FROM merchant_batch_transactions_log WHERE id=:id",
                        new MapSqlParameterSource("id", batchId),
                        (rs, rowNum) -> rs.getLong("merchant_id"));
        if (owners.isEmpty() || owners.get(0) != merchantId) {
            throw new PaymentGatewayException(
                    "Batch " + batchId + " was not found for this merchant");
        }
        Merchant merchant = Common.getMerchantById(String.valueOf(merchantId), jdbcTemplate);
        if (merchant == null) {
            throw new PaymentGatewayException("Merchant was not found");
        }
        return merchant;
    }

    private record BeneficiaryRow(long id, String account, double amount) {}

    public record BatchStatusSummary(
            long batchId,
            int totalBeneficiaries,
            int paid,
            int failed,
            int inProgress,
            int unpaid,
            BigDecimal paidAmount) {}
}
