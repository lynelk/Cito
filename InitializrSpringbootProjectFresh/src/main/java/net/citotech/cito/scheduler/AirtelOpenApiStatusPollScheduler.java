package net.citotech.cito.scheduler;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import net.citotech.cito.Common;
import net.citotech.cito.DoPayGateway;
import net.citotech.cito.Model.AirtelMoneyOpenApiPaymentGateway;
import net.citotech.cito.Model.GateWayResponse;
import net.citotech.cito.Model.Transaction;
import net.citotech.cito.gateway.AirtelOpenApiCredentialSchema;
import net.citotech.cito.gateway.LegacyGatewayIds;
import net.citotech.cito.gateway.PaymentGatewayException;
import net.citotech.cito.ledger.PaymentLedgerSettlementService;
import net.citotech.cito.sharedprovider.SharedProviderAccessService;
import net.citotech.cito.treasury.ProviderTreasuryService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Authenticated recovery only: a callback body or elapsed time never settles a payment. */
@Component
public class AirtelOpenApiStatusPollScheduler {
    private static final Logger logger =
            Logger.getLogger(AirtelOpenApiStatusPollScheduler.class.getName());
    private static final int BATCH_SIZE = 25;
    private static final long BATCH_BUDGET_NANOS = TimeUnit.SECONDS.toNanos(20);
    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final PlatformTransactionManager transactionManager;
    private final PaymentLedgerSettlementService ledgerSettlementService;
    private final SharedProviderAccessService sharedProviderAccessService;
    private final ProviderTreasuryService treasuryService;

    public AirtelOpenApiStatusPollScheduler(
            NamedParameterJdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            PaymentLedgerSettlementService ledgerSettlementService,
            SharedProviderAccessService sharedProviderAccessService,
            ProviderTreasuryService treasuryService) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionManager = transactionManager;
        this.ledgerSettlementService = ledgerSettlementService;
        this.sharedProviderAccessService = sharedProviderAccessService;
        this.treasuryService = treasuryService;
    }

    @Scheduled(fixedDelayString = "${cpay.airtel.status-poll.delay-ms:60000}")
    @SchedulerLock(
            name = "airtelOpenApiStatusPoll",
            lockAtMostFor = "PT30M",
            lockAtLeastFor = "PT5S")
    public void reconcilePendingAirtelTransactions() {
        // Each batch stops starting new work after its budget. The lease also covers the final
        // in-flight lookup, including the gateway's bounded token refresh/read timeouts.
        // Row-level transactions below, not the scheduler lease alone, protect financial effects.
        try {
            reconcileLegacyBatch();
        } catch (Exception e) {
            warn("legacy batch", e);
        }
        try {
            reconcileSharedProviderBatch();
        } catch (Exception e) {
            warn("shared batch", e);
        }
    }

    private long cursor(String scope) {
        Long value =
                jdbcTemplate.queryForObject(
                        "SELECT last_id FROM airtel_recovery_cursors WHERE scope=:scope",
                        new MapSqlParameterSource("scope", scope),
                        Long.class);
        return value == null ? 0 : value;
    }

    private void advance(String scope, long id) {
        jdbcTemplate.update(
                "UPDATE airtel_recovery_cursors SET last_id=:id, updated_at=CURRENT_TIMESTAMP WHERE scope=:scope",
                new MapSqlParameterSource("scope", scope).addValue("id", id));
    }

    private void reconcileLegacyBatch() {
        long after = cursor("LEGACY");
        List<Transaction> rows = pendingLegacyAfter(after);
        if (rows.isEmpty() && after > 0) {
            advance("LEGACY", 0);
            rows = pendingLegacyAfter(0);
        }
        long deadline = System.nanoTime() + BATCH_BUDGET_NANOS;
        for (Transaction tx : rows) {
            if (System.nanoTime() >= deadline) break;
            try {
                reconcileLegacy(tx);
            } catch (Exception e) {
                warn("legacy item", e);
            }
            advance("LEGACY", tx.getId());
        }
    }

    private List<Transaction> pendingLegacyAfter(long after) {
        return jdbcTemplate.query(
                "SELECT * FROM "
                        + Common.DB_TABLE_MERCHANT_TRANSACTION_LOG
                        + " WHERE status IN ('PENDING','UNDETERMINED') AND gateway_id=:gateway"
                        + " AND id>:cursor AND created_on<=DATE_SUB(NOW(), INTERVAL 15 SECOND) ORDER BY id LIMIT "
                        + BATCH_SIZE,
                new MapSqlParameterSource("gateway", LegacyGatewayIds.AIRTEL_OPEN_API)
                        .addValue("cursor", after),
                Common.getTransactionRowMapper());
    }

    void reconcileLegacy(Transaction observed) {
        if (observed == null || text(observed.getTx_unique_id()).isEmpty()) return;
        var state = Common.getSettings("application_settings_state", jdbcTemplate);
        var simulation = Common.getSettings("simulate_transactions", jdbcTemplate);
        if (state != null
                && "sandbox".equalsIgnoreCase(text(state.getSetting_value()))
                && (simulation == null
                        || "yes".equalsIgnoreCase(text(simulation.getSetting_value())))) return;
        String segment =
                Transaction.TX_TYPE_PAYOUT.equalsIgnoreCase(observed.getTx_type())
                        ? "disbursement"
                        : "collection";
        GateWayResponse provider =
                new DoPayGateway()
                        .runPayGatewayDoCheckStatus(
                                jdbcTemplate,
                                LegacyGatewayIds.AIRTEL_OPEN_API,
                                observed.getTx_unique_id(),
                                segment,
                                Long.valueOf(observed.getMerchant_id()));
        if (!isAuthoritativeTerminal(provider)) return;
        finalizeLegacy(observed, provider);
    }

    void finalizeLegacy(Transaction observed, GateWayResponse provider) {
        if (!isAuthoritativeTerminal(provider)) return;
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(
                        status -> {
                            List<Transaction> locked =
                                    jdbcTemplate.query(
                                            "SELECT * FROM "
                                                    + Common.DB_TABLE_MERCHANT_TRANSACTION_LOG
                                                    + " WHERE id=:id AND gateway_id=:gateway FOR UPDATE",
                                            new MapSqlParameterSource("id", observed.getId())
                                                    .addValue(
                                                            "gateway",
                                                            LegacyGatewayIds.AIRTEL_OPEN_API),
                                            Common.getTransactionRowMapper());
                            if (locked.size() != 1)
                                throw new PaymentGatewayException(
                                        "Airtel recovery transaction not found");
                            Transaction tx = locked.get(0);
                            if (!unresolved(tx.getStatus())) return;
                            if (!text(tx.getTx_unique_id()).equals(text(observed.getTx_unique_id()))
                                    || !text(tx.getMerchant_id())
                                            .equals(text(observed.getMerchant_id()))
                                    || !text(tx.getCurrency())
                                            .equalsIgnoreCase(text(observed.getCurrency()))
                                    || tx.getOriginalAmountDecimal()
                                                    .compareTo(observed.getOriginalAmountDecimal())
                                            != 0)
                                throw new PaymentGatewayException(
                                        "Airtel recovery immutable scope changed");
                            String terminal = normalizedStatus(provider);
                            tx.setStatus(terminal);
                            String network = text(provider.getNetworkId());
                            if (!network.isEmpty()) tx.setTx_gateway_ref(network);
                            tx.setTx_update_trace(
                                    "AIRTEL_AUTHENTICATED_STATUS_POLL;status="
                                            + terminal
                                            + ";http=200");
                            tx.setFinalStatusSet(true);
                            String outcome = Common.updateTx(tx, jdbcTemplate, transactionManager);
                            if (!"success".equalsIgnoreCase(outcome))
                                throw new PaymentGatewayException(
                                        "Airtel canonical transaction finalization failed");
                            String persisted =
                                    jdbcTemplate.queryForObject(
                                            "SELECT status FROM "
                                                    + Common.DB_TABLE_MERCHANT_TRANSACTION_LOG
                                                    + " WHERE id=:id",
                                            new MapSqlParameterSource("id", tx.getId()),
                                            String.class);
                            if (!terminal.equals(persisted))
                                throw new PaymentGatewayException(
                                        "Airtel finalization was rejected; ledger remains unchanged");
                            // These writes join the same transaction as status, statement and
                            // callback-outbox work.
                            ledgerSettlementService.applyTerminalProviderOutcome(tx, terminal);
                            jdbcTemplate.update(
                                    "UPDATE "
                                            + Common.DB_TABLE_MERCHANT_TRANSACTION_LOG
                                            + " SET resolved_by='AIRTEL_STATUS_POLL' WHERE id=:id AND status=:status",
                                    new MapSqlParameterSource("id", tx.getId())
                                            .addValue("status", terminal));
                        });
    }

    private void reconcileSharedProviderBatch() {
        long after = cursor("PLATFORM_SHARED");
        List<Map<String, Object>> rows = pendingSharedAfter(after);
        if (rows.isEmpty() && after > 0) {
            advance("PLATFORM_SHARED", 0);
            rows = pendingSharedAfter(0);
        }
        long deadline = System.nanoTime() + BATCH_BUDGET_NANOS;
        for (Map<String, Object> row : rows) {
            if (System.nanoTime() >= deadline) break;
            try {
                reconcileSharedProvider(row);
            } catch (Exception e) {
                warn("shared item", e);
            }
            advance("PLATFORM_SHARED", number(row.get("id")));
        }
    }

    private List<Map<String, Object>> pendingSharedAfter(long after) {
        return jdbcTemplate.queryForList(
                "SELECT r.id, r.operation, r.provider_reference, r.currency_code,"
                        + " r.merchant_reference, r.treasury_account_id, a.environment, a.country_code"
                        + " FROM provider_treasury_reservations r JOIN provider_treasury_accounts a ON a.id=r.treasury_account_id"
                        + " WHERE r.status='PENDING' AND a.channel_code=:channel AND r.merchant_reference<>''"
                        + " AND r.id>:cursor AND r.updated_at<=DATE_SUB(NOW(), INTERVAL 15 SECOND) ORDER BY r.id LIMIT "
                        + BATCH_SIZE,
                new MapSqlParameterSource("channel", AirtelOpenApiCredentialSchema.CHANNEL_CODE)
                        .addValue("cursor", after));
    }

    void reconcileSharedProvider(Map<String, Object> row) {
        long reservationId = number(row.get("id"));
        String operation = text(row.get("operation")).toUpperCase(Locale.ROOT);
        String submittedReference = statusReference(row);
        if (reservationId <= 0
                || submittedReference.isEmpty()
                || !(operation.equals("COLLECT") || operation.equals("PAYOUT"))) return;
        // Shared provider transaction IDs must be unambiguous across tenants, including terminal
        // rows.
        Integer matches =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM provider_treasury_reservations"
                                + " WHERE treasury_account_id=:account AND operation=:operation AND merchant_reference=:reference",
                        new MapSqlParameterSource("account", row.get("treasury_account_id"))
                                .addValue("operation", operation)
                                .addValue("reference", submittedReference),
                        Integer.class);
        if (matches == null || matches != 1)
            throw new PaymentGatewayException(
                    "Ambiguous Airtel shared reference requires reconciliation");
        String environment = text(row.get("environment"));
        String country = text(row.get("country_code"));
        String currency = text(row.get("currency_code"));
        Map<String, Object> credentials =
                sharedProviderAccessService.loadActivePlatformCredential(
                        AirtelOpenApiCredentialSchema.CHANNEL_CODE, environment, country, currency);
        // A status read does not require a payout PIN or encryption key.
        AirtelOpenApiCredentialSchema.validateForOperation(
                credentials, environment, country, currency, "COLLECT");
        AirtelMoneyOpenApiPaymentGateway gateway = new AirtelMoneyOpenApiPaymentGateway();
        gateway.setApiDetails(
                value(credentials, "baseUrl"),
                value(credentials, "clientId"),
                value(credentials, "clientSecret"),
                "");
        gateway.setTransactionContext(environment, country, currency);
        gateway.setEndpointDetails(
                value(credentials, "tokenPath"),
                value(credentials, "collectionPath"),
                value(credentials, "payoutPath"),
                value(credentials, "balancePath"),
                value(credentials, "collectionStatusPath"),
                value(credentials, "payoutStatusPath"));
        gateway.setSegment(operation.equals("PAYOUT") ? "disbursement" : "collection");
        GateWayResponse provider = gateway.checkStatus(submittedReference);
        if (!isAuthoritativeTerminal(provider)) return;
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(
                        status -> {
                            List<Map<String, Object>> locked =
                                    jdbcTemplate.queryForList(
                                            "SELECT status, merchant_reference, treasury_account_id, operation, currency_code"
                                                    + " FROM provider_treasury_reservations WHERE id=:id FOR UPDATE",
                                            new MapSqlParameterSource("id", reservationId));
                            if (locked.size() != 1)
                                throw new PaymentGatewayException("Airtel reservation not found");
                            Map<String, Object> current = locked.get(0);
                            if (!"PENDING".equals(text(current.get("status")))) return;
                            if (!submittedReference.equals(text(current.get("merchant_reference")))
                                    || !text(row.get("treasury_account_id"))
                                            .equals(text(current.get("treasury_account_id")))
                                    || !operation.equals(text(current.get("operation")))
                                    || !currency.equalsIgnoreCase(
                                            text(current.get("currency_code"))))
                                throw new PaymentGatewayException(
                                        "Airtel reservation scope changed");
                            String network = text(provider.getNetworkId());
                            if (network.isEmpty()) network = text(row.get("provider_reference"));
                            if (network.isEmpty()) network = submittedReference;
                            treasuryService.resolvePending(
                                    reservationId,
                                    "SUCCESSFUL".equals(normalizedStatus(provider)),
                                    network,
                                    "AIRTEL_STATUS_POLL");
                        });
    }

    static String statusReference(Map<String, Object> row) {
        return text(row.get("merchant_reference"));
    }

    static boolean isAuthoritativeTerminal(GateWayResponse provider) {
        if (provider == null
                || !"200".equals(text(provider.getHttpStatus()))
                || !"OK".equalsIgnoreCase(text(provider.getStatus()))) return false;
        String value = normalizedStatus(provider);
        return "SUCCESSFUL".equals(value) || "FAILED".equals(value);
    }

    private static boolean unresolved(String status) {
        return "PENDING".equalsIgnoreCase(status) || "UNDETERMINED".equalsIgnoreCase(status);
    }

    private static String normalizedStatus(GateWayResponse provider) {
        return text(provider.getTransactionStatus()).toUpperCase(Locale.ROOT);
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String value(Map<String, Object> map, String key) {
        return text(map.get(key));
    }

    private static long number(Object value) {
        return value instanceof Number n ? n.longValue() : Long.parseLong(text(value));
    }

    private static void warn(String phase, Exception error) {
        logger.warning(
                "Airtel recovery "
                        + phase
                        + " remains unresolved; errorClass="
                        + error.getClass().getSimpleName());
    }
}
