package net.citotech.cito.scheduler;

import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.citotech.cito.Common;
import net.citotech.cito.DoPayGateway;
import net.citotech.cito.Model.GateWayResponse;
import net.citotech.cito.Model.Merchant;
import net.citotech.cito.Model.Transaction;
import net.citotech.cito.Model.TxCallback;
import net.citotech.cito.gateway.LegacyGatewayIds;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Reconciles asynchronous MTN MoMo RequestToPay and Transfer transactions against MTN's GET status
 * endpoints. MTN sends a provider callback only once, so status polling is the authoritative fallback
 * when that callback is missed.
 */
@Component
public class MtnMomoStatusPollScheduler {
    private static final Logger logger =
            Logger.getLogger(MtnMomoStatusPollScheduler.class.getName());
    private static final int BATCH_SIZE = 100;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final PlatformTransactionManager transactionManager;

    public MtnMomoStatusPollScheduler(
            NamedParameterJdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionManager = transactionManager;
    }

    /**
     * Poll once per minute by default. A short grace period avoids querying MTN immediately after a
     * 202 Accepted response while the resource is still being processed.
     */
    @Scheduled(fixedDelayString = "${cpay.mtn.status-poll.delay-ms:60000}")
    @SchedulerLock(
            name = "mtnMomoStatusPoll",
            lockAtMostFor = "PT55S",
            lockAtLeastFor = "PT5S")
    public void reconcilePendingMtnTransactions() {
        try {
            for (Transaction tx : pendingTransactions()) {
                reconcile(tx);
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "MTN MoMo status reconciliation failed: " + e.getMessage(), e);
        }
    }

    private List<Transaction> pendingTransactions() {
        String sql =
                "SELECT * FROM "
                        + Common.DB_TABLE_MERCHANT_TRANSACTION_LOG
                        + " WHERE status='PENDING'"
                        + " AND gateway_id=:gateway_id"
                        + " AND created_on <= DATE_SUB(NOW(), INTERVAL 15 SECOND)"
                        + " ORDER BY created_on ASC LIMIT "
                        + BATCH_SIZE;
        return jdbcTemplate.query(
                sql,
                new MapSqlParameterSource().addValue("gateway_id", LegacyGatewayIds.MTN_MOMO),
                Common.getTransactionRowMapper());
    }

    private void reconcile(Transaction tx) {
        if (tx == null || tx.getTx_unique_id() == null || tx.getTx_unique_id().isBlank()) {
            return;
        }
        try {
            String segment =
                    Transaction.TX_TYPE_PAYOUT.equalsIgnoreCase(tx.getTx_type())
                            ? "disbursement"
                            : "collection";
            Long merchantId = parseMerchantId(tx.getMerchant_id());
            if (merchantId == null) {
                logger.warning("Skipping MTN status poll: transaction has no merchant id");
                return;
            }

            GateWayResponse provider =
                    new DoPayGateway()
                            .runPayGatewayDoCheckStatus(
                                    jdbcTemplate,
                                    LegacyGatewayIds.MTN_MOMO,
                                    tx.getTx_unique_id(),
                                    segment,
                                    merchantId);
            if (provider == null || provider.getTransactionStatus() == null) {
                return;
            }

            String providerStatus = provider.getTransactionStatus().trim().toUpperCase(Locale.ROOT);
            if (!"SUCCESSFUL".equals(providerStatus) && !"FAILED".equals(providerStatus)) {
                return;
            }

            String networkReference = safe(provider.getNetworkId());
            if (networkReference.isBlank()) {
                networkReference = safe(tx.getTx_gateway_ref());
            }
            String trace =
                    "MTN_STATUS_POLL: providerStatus="
                            + providerStatus
                            + ";httpStatus="
                            + safe(provider.getHttpStatus())
                            + ";reference="
                            + tx.getTx_unique_id();

            MapSqlParameterSource p =
                    new MapSqlParameterSource()
                            .addValue("id", tx.getId())
                            .addValue("status", providerStatus)
                            .addValue("network_ref", networkReference)
                            .addValue("trace", trace)
                            .addValue("resolved_by", "MTN_STATUS_POLL");
            int updated =
                    jdbcTemplate.update(
                            "UPDATE "
                                    + Common.DB_TABLE_MERCHANT_TRANSACTION_LOG
                                    + " SET status=:status, tx_gateway_ref=:network_ref,"
                                    + " tx_update_trace=:trace, resolved_by=:resolved_by"
                                    + " WHERE id=:id AND status='PENDING'",
                            p);
            if (updated < 1) {
                return;
            }

            Transaction resolved = refreshedTransaction(tx.getId());
            if (resolved == null) {
                return;
            }
            Merchant merchant = Common.getMerchantById(resolved.getMerchant_id(), jdbcTemplate);
            if (merchant != null
                    && resolved.getCallback_url() != null
                    && !resolved.getCallback_url().isBlank()) {
                new TxCallback(resolved, merchant).start(jdbcTemplate, transactionManager);
            }
        } catch (Exception e) {
            // A failed poll is not evidence that the payment failed. Leave it PENDING for the next
            // polling cycle or reconciliation process.
            logger.log(
                    Level.WARNING,
                    "MTN status poll failed for transaction " + tx.getTx_unique_id(),
                    e);
        }
    }

    private Transaction refreshedTransaction(Long id) {
        List<Transaction> rows =
                jdbcTemplate.query(
                        "SELECT * FROM "
                                + Common.DB_TABLE_MERCHANT_TRANSACTION_LOG
                                + " WHERE id=:id LIMIT 1",
                        new MapSqlParameterSource().addValue("id", id),
                        Common.getTransactionRowMapper());
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Long parseMerchantId(String value) {
        try {
            return value == null || value.isBlank() ? null : Long.valueOf(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
