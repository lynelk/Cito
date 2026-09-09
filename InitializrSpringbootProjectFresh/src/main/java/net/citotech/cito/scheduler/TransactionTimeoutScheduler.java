package net.citotech.cito.scheduler;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.citotech.cito.Common;
import net.citotech.cito.Model.Merchant;
import net.citotech.cito.Model.Setting;
import net.citotech.cito.Model.Transaction;
import net.citotech.cito.Model.TxCallback;
import net.citotech.cito.gateway.LegacyGatewayIds;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Scheduler that auto-resolves PENDING transactions that have exceeded the timeout threshold.
 * Default timeout: 30 minutes, overridable per gateway via a {@code
 * transaction_timeout_minutes_<gateway_id>} row in the settings table.
 *
 * <p>MTN MoMo is excluded from blind timeouts because MTN callbacks are single-attempt. Its
 * dedicated status poller verifies final provider state before resolving a transaction.
 */
@Component
public class TransactionTimeoutScheduler {

    private static final Logger logger =
            Logger.getLogger(TransactionTimeoutScheduler.class.getName());
    private static final String TIMEOUT_SETTING_PREFIX = "transaction_timeout_minutes_";

    @Autowired private NamedParameterJdbcTemplate jdbcTemplate;

    @Autowired private PlatformTransactionManager transactionManager;

    @Value("${cpay.transactions.timeout.default-minutes:30}")
    private int defaultTimeoutMinutes;

    /** Runs every 5 minutes to find and timeout stuck PENDING transactions, per gateway. */
    @Scheduled(fixedDelayString = "${cpay.transactions.timeout.scan-delay-ms:300000}")
    @SchedulerLock(
            name = "transactionTimeoutScan",
            lockAtMostFor = "PT10M",
            lockAtLeastFor = "PT30S")
    public void timeoutStalePendingTransactions() {
        try {
            List<String> gatewayIds =
                    jdbcTemplate.queryForList(
                            "SELECT DISTINCT gateway_id FROM "
                                    + Common.DB_TABLE_MERCHANT_TRANSACTION_LOG
                                    + " WHERE status = 'PENDING'",
                            new MapSqlParameterSource(),
                            String.class);

            Map<String, Integer> resolvedTimeouts = new HashMap<>();
            for (String gatewayId : gatewayIds) {
                if (LegacyGatewayIds.MTN_MOMO.equals(gatewayId)) {
                    // MTN has a dedicated authenticated status poller. Never manufacture a FAILED
                    // result merely because the provider's one-shot callback was missed.
                    continue;
                }
                int timeoutMinutes = timeoutMinutesForGateway(gatewayId, resolvedTimeouts);
                for (Transaction tx : fetchStalePendingTransactions(gatewayId, timeoutMinutes)) {
                    timeoutTransaction(tx, timeoutMinutes);
                }
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "TransactionTimeoutScheduler error: " + e.getMessage(), e);
        }
    }

    private int timeoutMinutesForGateway(String gatewayId, Map<String, Integer> resolvedTimeouts) {
        return resolvedTimeouts.computeIfAbsent(
                gatewayId,
                id -> {
                    if (id == null || id.isBlank()) {
                        return defaultTimeoutMinutes;
                    }
                    Setting setting = Common.getSettings(TIMEOUT_SETTING_PREFIX + id, jdbcTemplate);
                    if (setting == null
                            || setting.getSetting_value() == null
                            || setting.getSetting_value().isBlank()) {
                        return defaultTimeoutMinutes;
                    }
                    try {
                        int configured = Integer.parseInt(setting.getSetting_value().trim());
                        return configured > 0 ? configured : defaultTimeoutMinutes;
                    } catch (NumberFormatException ex) {
                        logger.log(
                                Level.WARNING,
                                "Invalid "
                                        + TIMEOUT_SETTING_PREFIX
                                        + id
                                        + " setting value, falling back to default: "
                                        + setting.getSetting_value());
                        return defaultTimeoutMinutes;
                    }
                });
    }

    private List<Transaction> fetchStalePendingTransactions(String gatewayId, int timeoutMinutes) {
        String sql =
                "SELECT * FROM "
                        + Common.DB_TABLE_MERCHANT_TRANSACTION_LOG
                        + " WHERE status = 'PENDING'"
                        + " AND gateway_id = :gateway_id"
                        + " AND created_on <= DATE_SUB(NOW(), INTERVAL :timeout_minutes MINUTE)"
                        + " LIMIT 100";

        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("gateway_id", gatewayId);
        params.addValue("timeout_minutes", timeoutMinutes);

        return jdbcTemplate.query(sql, params, Common.getTransactionRowMapper());
    }

    private void timeoutTransaction(Transaction tx, int timeoutMinutes) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.execute(
                (TransactionStatus status) -> {
                    try {
                        tx.setStatus("FAILED");
                        tx.setTx_update_trace(
                                "AUTO_TIMEOUT: Transaction exceeded "
                                        + timeoutMinutes
                                        + " minute pending limit");
                        tx.setResolved_by("SYSTEM_TIMEOUT");

                        String sql =
                                "UPDATE "
                                        + Common.DB_TABLE_MERCHANT_TRANSACTION_LOG
                                        + " SET status=:status, tx_update_trace=:trace, resolved_by=:resolved_by"
                                        + " WHERE id=:id AND status='PENDING'";
                        MapSqlParameterSource p = new MapSqlParameterSource();
                        p.addValue("id", tx.getId());
                        p.addValue("status", "FAILED");
                        p.addValue("trace", tx.getTx_update_trace());
                        p.addValue("resolved_by", "SYSTEM_TIMEOUT");
                        int updated = jdbcTemplate.update(sql, p);

                        if (updated > 0) {
                            logger.log(
                                    Level.INFO,
                                    "Timed out PENDING transaction: " + tx.getTx_unique_id());
                            Merchant merchant =
                                    Common.getMerchantById(tx.getMerchant_id(), jdbcTemplate);
                            if (merchant != null
                                    && tx.getCallback_url() != null
                                    && !tx.getCallback_url().isEmpty()) {
                                TxCallback txCallback = new TxCallback(tx, merchant);
                                txCallback.start(jdbcTemplate, transactionManager);
                            }
                        }
                    } catch (Exception e) {
                        status.setRollbackOnly();
                        logger.log(
                                Level.SEVERE,
                                "Error timing out transaction "
                                        + tx.getId()
                                        + ": "
                                        + e.getMessage(),
                                e);
                    }
                    return null;
                });
    }
}
