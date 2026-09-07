package net.citotech.cito;

import net.citotech.cito.Model.AirtelMoneyOpenApiPaymentGateway;
import net.citotech.cito.Model.AirtelMoneyPaymentGateway;
import net.citotech.cito.Model.Balance;
import net.citotech.cito.Model.GatewayChargeDetails;
import net.citotech.cito.Model.Merchant;
import net.citotech.cito.Model.Transaction;
import net.citotech.cito.ledger.DoubleEntryLedgerService;
import net.citotech.cito.ledger.DoubleEntryLedgerService.BatchReservationResult;
import net.citotech.cito.ledger.DoubleEntryLedgerService.ReservationCommand;
import net.citotech.cito.money.MoneyAmount;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Fail-closed funding and reservation guard for legacy batch payouts.
 *
 * <p>The legacy scheduler used to validate every beneficiary against the same balance snapshot and
 * reserve immediately before each provider call. A later reservation failure therefore rolled the
 * whole database transaction back after an earlier external payout had already happened. This guard
 * locks the batch and atomically reserves the aggregate amount for the next payout slice before the
 * first provider call, using stable idempotent reservation references.
 */
@Component
public class BatchPayoutFundingGuard {
    private static final Logger LOG = Logger.getLogger(BatchPayoutFundingGuard.class.getName());
    private static final String DEFAULT_CURRENCY = "UGX";
    private static final int MAX_PAYOUTS_PER_SLICE = 31;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final DoubleEntryLedgerService ledgerService;

    public BatchPayoutFundingGuard(
            NamedParameterJdbcTemplate jdbcTemplate, DoubleEntryLedgerService ledgerService) {
        this.jdbcTemplate = jdbcTemplate;
        this.ledgerService = ledgerService;
    }

    /**
     * Locks and reserves the next provider-call slice. Must be called from the payout transaction
     * before any external payout is attempted.
     */
    public boolean reserveProcessingSlice(long batchId, long merchantId) {
        if (!lockProcessingBatch(batchId, merchantId)) {
            return false;
        }

        Merchant merchant = Common.getMerchantById(Long.toString(merchantId), jdbcTemplate);
        if (merchant == null) {
            throw new IllegalStateException("Payout merchant not found for batch " + batchId);
        }

        List<PreparedCandidate> prepared = prepareNextSlice(batchId, merchantId);
        if (prepared.isEmpty()) {
            return true;
        }

        Map<String, BigDecimal> requiredByGateway = new LinkedHashMap<>();
        List<ReservationCommand> reservations = new ArrayList<>();
        for (PreparedCandidate candidate : prepared) {
            requiredByGateway.merge(candidate.gatewayId(), candidate.required(), BigDecimal::add);
            reservations.add(
                    new ReservationCommand(
                            reservationReference(batchId, candidate.beneficiaryId()),
                            sourceReference(batchId, candidate.beneficiaryId()),
                            candidate.required()));
        }

        BatchReservationResult result =
                ledgerService.reserveAll(merchant.getId(), DEFAULT_CURRENCY, reservations);
        if (!result.reserved()) {
            pause(
                    batchId,
                    prepared.get(0).beneficiaryId(),
                    "Batch paused before provider calls: aggregate ledger availability is "
                            + result.available().toPlainString()
                            + " but "
                            + result.required().toPlainString()
                            + " "
                            + DEFAULT_CURRENCY
                            + " is required. Fund/reconcile and explicitly resume the batch.");
            return false;
        }

        // reserveAll keeps the merchant/currency control row locked until this surrounding payout
        // transaction completes. Check the legacy gateway buckets only after that lock is held so
        // concurrent batches cannot both proceed from the same pre-payout snapshot.
        Map<String, BigDecimal> legacyAvailable = legacyBalances(merchantId);
        for (Map.Entry<String, BigDecimal> requirement : requiredByGateway.entrySet()) {
            BigDecimal available = legacyAvailable.get(requirement.getKey());
            if (available == null || available.compareTo(requirement.getValue()) < 0) {
                pause(
                        batchId,
                        prepared.get(0).beneficiaryId(),
                        "Batch paused before provider calls: aggregate gateway balance is "
                                + (available == null ? "unavailable" : available.toPlainString())
                                + " but "
                                + requirement.getValue().toPlainString()
                                + " "
                                + DEFAULT_CURRENCY
                                + " is required for gateway "
                                + requirement.getKey()
                                + ". Ledger funds remain reserved for this batch; fund/reconcile "
                                + "the gateway and explicitly resume it.");
                return false;
            }
        }
        return true;
    }

    static String reservationReference(long batchId, long beneficiaryId) {
        return "batch-payout-reserve:" + batchId + ":" + beneficiaryId;
    }

    static String sourceReference(long batchId, long beneficiaryId) {
        return "batch-payout:" + batchId + ":" + beneficiaryId;
    }

    static String legacyBalanceGatewayId(String providerGatewayId) {
        if (AirtelMoneyOpenApiPaymentGateway.gateway_id.equals(providerGatewayId)) {
            return AirtelMoneyPaymentGateway.gateway_id;
        }
        return providerGatewayId;
    }

    static BigDecimal requiredAmount(BigDecimal payoutAmount, double legacyCharges) {
        BigDecimal normalizedPayout =
                MoneyAmount.of(payoutAmount == null ? null : payoutAmount.toPlainString())
                        .asBigDecimal();
        BigDecimal normalizedCharges = MoneyAmount.normalize(BigDecimal.valueOf(legacyCharges));
        if (normalizedCharges.signum() < 0) {
            throw new IllegalArgumentException("payout charges cannot be negative");
        }
        return MoneyAmount.normalize(normalizedPayout.add(normalizedCharges));
    }

    private boolean lockProcessingBatch(long batchId, long merchantId) {
        String sql =
                "SELECT id FROM "
                        + Common.DB_TABLE_MERCHANT_BATCH_TRANSACTION_LOG
                        + " WHERE id=:batch_id AND merchant_id=:merchant_id AND status=:processing"
                        + " FOR UPDATE";
        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("batch_id", batchId);
        params.addValue("merchant_id", merchantId);
        params.addValue("processing", Transaction.BATCH_PAYMENTS_PROCESSING);
        return !jdbcTemplate.query(sql, params, (rs, rowNum) -> rs.getLong("id")).isEmpty();
    }

    private List<Candidate> candidates(long batchId) {
        String sql =
                "SELECT b.id AS beneficiary_id, b.account, b.amount "
                        + "FROM "
                        + Common.DB_TABLE_MERCHANT_BATCH_TRANSACTION_BENEFICIARIES
                        + " b WHERE b.batch_id=:batch_id AND b.status=:unpaid "
                        + "AND NOT EXISTS (SELECT 1 FROM "
                        + Common.DB_TABLE_MERCHANT_TRANSACTION_LOG
                        + " t WHERE t.merchant_batch_transactions_log_id=:batch_id "
                        + "AND t.beneficiary_id=b.id) ORDER BY b.id";
        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("batch_id", batchId);
        params.addValue("unpaid", Transaction.BATCH_PAYMENT_UNPAID);
        return jdbcTemplate.query(
                sql,
                params,
                (rs, rowNum) ->
                        new Candidate(
                                rs.getLong("beneficiary_id"),
                                rs.getString("account"),
                                rs.getBigDecimal("amount")));
    }

    private List<PreparedCandidate> prepareNextSlice(long batchId, long merchantId) {
        List<PreparedCandidate> prepared = new ArrayList<>();
        for (Candidate candidate : candidates(batchId)) {
            String gatewayId = DoPayGateway.getGatewayIdByMsisdn(candidate.account(), jdbcTemplate);
            if (gatewayId == null || gatewayId.isBlank()) {
                // The payout scheduler terminalizes unsupported beneficiaries without a provider
                // call. They do not consume one of its 31 external-payout slots.
                continue;
            }
            GatewayChargeDetails chargeDetails =
                    DoPayGateway.getGatewayChargeDetailsById(jdbcTemplate, gatewayId, merchantId);
            double charges =
                    DoPayGateway.getCustomerOutboundCharges(
                            candidate.amount().doubleValue(), chargeDetails);
            BigDecimal required = requiredAmount(candidate.amount(), charges);
            prepared.add(
                    new PreparedCandidate(
                            candidate.beneficiaryId(),
                            legacyBalanceGatewayId(gatewayId),
                            required));
            if (prepared.size() == MAX_PAYOUTS_PER_SLICE) {
                break;
            }
        }
        return prepared;
    }

    private Map<String, BigDecimal> legacyBalances(long merchantId) {
        Map<String, BigDecimal> available = new LinkedHashMap<>();
        ArrayList<Balance> balances =
                Common.getMerchantBalances(Long.toString(merchantId), jdbcTemplate);
        for (Balance balance : balances) {
            available.put(balance.getGateway_id(), BigDecimal.valueOf(balance.getAmount()));
        }
        return available;
    }

    private void pause(long batchId, long beneficiaryId, String reason) {
        MapSqlParameterSource batch = new MapSqlParameterSource();
        batch.addValue("batch_id", batchId);
        batch.addValue("processing", Transaction.BATCH_PAYMENTS_PROCESSING);
        batch.addValue("paused", Transaction.BATCH_PAYMENTS_PAUSED);
        int changed =
                jdbcTemplate.update(
                        "UPDATE "
                                + Common.DB_TABLE_MERCHANT_BATCH_TRANSACTION_LOG
                                + " SET status=:paused WHERE id=:batch_id AND status=:processing",
                        batch);
        if (changed == 0) {
            return;
        }

        MapSqlParameterSource beneficiary = new MapSqlParameterSource();
        beneficiary.addValue("beneficiary_id", beneficiaryId);
        beneficiary.addValue("reason", reason);
        jdbcTemplate.update(
                "UPDATE "
                        + Common.DB_TABLE_MERCHANT_BATCH_TRANSACTION_BENEFICIARIES
                        + " SET reason=:reason WHERE id=:beneficiary_id",
                beneficiary);
        LOG.log(Level.WARNING, "Paused payout batch " + batchId + ": " + reason);
    }

    private record Candidate(long beneficiaryId, String account, BigDecimal amount) {}

    private record PreparedCandidate(long beneficiaryId, String gatewayId, BigDecimal required) {}
}
