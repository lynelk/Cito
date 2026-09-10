package net.citotech.cito.scheduler;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.citotech.cito.Common;
import net.citotech.cito.DoPayGateway;
import net.citotech.cito.Model.AirtelMoneyOpenApiPaymentGateway;
import net.citotech.cito.Model.GateWayResponse;
import net.citotech.cito.Model.Transaction;
import net.citotech.cito.gateway.AirtelOpenApiCredentialSchema;
import net.citotech.cito.gateway.LegacyGatewayIds;
import net.citotech.cito.ledger.PaymentLedgerSettlementService;
import net.citotech.cito.sharedprovider.SharedProviderAccessService;
import net.citotech.cito.treasury.ProviderTreasuryService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Reconciles asynchronous Airtel Money OpenAPI collection and payout transactions against
 * Airtel's authenticated status resources. Provider acceptance, an absent callback, transport
 * failure, 429, or 5xx response is never treated as terminal financial evidence.
 */
@Component
public class AirtelOpenApiStatusPollScheduler {
    private static final Logger logger =
            Logger.getLogger(AirtelOpenApiStatusPollScheduler.class.getName());
    private static final int BATCH_SIZE = 100;
    private static final String COLLECTION_STATUS_PATH = "/standard/v1/payments/{reference}";
    private static final String DISBURSEMENT_STATUS_PATH = "/standard/v1/disbursements/{reference}";

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final PlatformTransactionManager transactionManager;
    private final PaymentLedgerSettlementService ledgerSettlementService;
    private final SharedProviderAccessService sharedProviderAccessService;
    private final ProviderTreasuryService treasuryService;
    private final AtomicLong legacyCursor = new AtomicLong(0);
    private final AtomicLong sharedCursor = new AtomicLong(0);

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
    @SchedulerLock(name = "airtelOpenApiStatusPoll", lockAtMostFor = "PT55S", lockAtLeastFor = "PT5S")
    public void reconcilePendingAirtelTransactions() {
        try {
            reconcileLegacyBatch();
            reconcileSharedProviderBatch();
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Airtel OpenAPI status reconciliation failed: " + e.getMessage(), e);
        }
    }

    private void reconcileLegacyBatch() {
        List<Transaction> rows = pendingLegacyAfter(legacyCursor.get());
        if (rows.isEmpty() && legacyCursor.get() > 0) {
            legacyCursor.set(0);
            rows = pendingLegacyAfter(0);
        }
        for (Transaction tx : rows) {
            legacyCursor.set(Math.max(legacyCursor.get(), tx.getId()));
            reconcileLegacy(tx);
        }
    }

    private List<Transaction> pendingLegacyAfter(long cursor) {
        String sql =
                "SELECT * FROM "
                        + Common.DB_TABLE_MERCHANT_TRANSACTION_LOG
                        + " WHERE status IN ('PENDING','UNDETERMINED')"
                        + " AND gateway_id=:gateway_id"
                        + " AND id>:cursor"
                        + " AND created_on <= DATE_SUB(NOW(), INTERVAL 15 SECOND)"
                        + " ORDER BY id ASC LIMIT "
                        + BATCH_SIZE;
        return jdbcTemplate.query(
                sql,
                new MapSqlParameterSource()
                        .addValue("gateway_id", LegacyGatewayIds.AIRTEL_OPEN_API)
                        .addValue("cursor", cursor),
                Common.getTransactionRowMapper());
    }

    private void reconcileLegacy(Transaction tx) {
        if (tx == null || blank(tx.getTx_unique_id())) return;
        try {
            Long merchantId = parseMerchantId(tx.getMerchant_id());
            if (merchantId == null) return;
            String segment = Transaction.TX_TYPE_PAYOUT.equalsIgnoreCase(tx.getTx_type())
                    ? "disbursement" : "collection";
            GateWayResponse provider =
                    new DoPayGateway().runPayGatewayDoCheckStatus(
                            jdbcTemplate,
                            LegacyGatewayIds.AIRTEL_OPEN_API,
                            tx.getTx_unique_id(),
                            segment,
                            merchantId);
            if (!isAuthoritativeTerminal(provider)) return;

            String providerStatus = normalizedStatus(provider);
            ledgerSettlementService.applyTerminalProviderOutcome(tx, providerStatus);
            String networkReference = safe(provider.getNetworkId());
            if (networkReference.isBlank()) networkReference = safe(tx.getTx_gateway_ref());
            tx.setStatus(providerStatus);
            tx.setTx_gateway_ref(networkReference);
            tx.setTx_update_trace(
                    "AIRTEL_STATUS_POLL: providerStatus=" + providerStatus
                            + ";httpStatus=" + safe(provider.getHttpStatus())
                            + ";reference=" + tx.getTx_unique_id());
            tx.setResolved_by("AIRTEL_STATUS_POLL");
            tx.setFinalStatusSet(true);
            String result = Common.updateTx(tx, jdbcTemplate, transactionManager);
            if (!"success".equalsIgnoreCase(result)) {
                logger.warning("Canonical Airtel transaction finalization did not complete for "
                        + tx.getTx_unique_id() + ": " + result);
            }
        } catch (Exception e) {
            logger.log(Level.WARNING,
                    "Airtel legacy status reconciliation failed for " + tx.getTx_unique_id(), e);
        }
    }

    private void reconcileSharedProviderBatch() {
        List<Map<String, Object>> rows = pendingSharedAfter(sharedCursor.get());
        if (rows.isEmpty() && sharedCursor.get() > 0) {
            sharedCursor.set(0);
            rows = pendingSharedAfter(0);
        }
        for (Map<String, Object> row : rows) {
            long id = number(row.get("id"));
            sharedCursor.set(Math.max(sharedCursor.get(), id));
            reconcileSharedProvider(row);
        }
    }

    private List<Map<String, Object>> pendingSharedAfter(long cursor) {
        return jdbcTemplate.queryForList(
                "SELECT r.id, r.operation, r.provider_reference, r.currency_code,"
                        + " r.merchant_reference, a.environment, a.country_code"
                        + " FROM provider_treasury_reservations r"
                        + " JOIN provider_treasury_accounts a ON a.id=r.treasury_account_id"
                        + " WHERE r.status='PENDING' AND a.channel_code=:channel"
                        + " AND r.provider_reference IS NOT NULL AND r.provider_reference<>''"
                        + " AND r.id>:cursor"
                        + " AND r.updated_at <= DATE_SUB(NOW(), INTERVAL 15 SECOND)"
                        + " ORDER BY r.id ASC LIMIT " + BATCH_SIZE,
                new MapSqlParameterSource()
                        .addValue("channel", AirtelOpenApiCredentialSchema.CHANNEL_CODE)
                        .addValue("cursor", cursor));
    }

    private void reconcileSharedProvider(Map<String, Object> row) {
        long reservationId = number(row.get("id"));
        String operation = text(row.get("operation")).toUpperCase(Locale.ROOT);
        String providerReference = text(row.get("provider_reference"));
        String environment = text(row.get("environment"));
        String country = text(row.get("country_code"));
        String currency = text(row.get("currency_code"));
        if (reservationId <= 0 || blank(providerReference) || blank(operation)) return;
        try {
            Map<String, Object> credentials = sharedProviderAccessService.loadActivePlatformCredential(
                    AirtelOpenApiCredentialSchema.CHANNEL_CODE, environment, country, currency);
            AirtelOpenApiCredentialSchema.validateForOperation(
                    credentials, environment, country, currency, operation);

            AirtelMoneyOpenApiPaymentGateway gateway = new AirtelMoneyOpenApiPaymentGateway();
            gateway.setApiDetails(
                    value(credentials, "baseUrl"), value(credentials, "clientId"),
                    value(credentials, "clientSecret"), value(credentials, "apiPin"));
            gateway.setPublicKey(value(credentials, "publicKey"));
            gateway.setTransactionContext(environment, country, currency);
            gateway.setEndpointDetails(
                    valueOrDefault(credentials, "tokenPath", "/auth/oauth2/token"),
                    valueOrDefault(credentials, "collectionsPath", "/merchant/v2/payments/"),
                    valueOrDefault(credentials, "disbursementsPath", "/standard/v2/disbursements/"),
                    valueOrDefault(credentials, "balancePath", "/standard/v2/users/balance"),
                    valueOrDefault(credentials, "collectionStatusPath", COLLECTION_STATUS_PATH),
                    valueOrDefault(credentials, "disbursementStatusPath", DISBURSEMENT_STATUS_PATH));
            gateway.setSegment("PAYOUT".equals(operation) ? "disbursement" : "collection");

            GateWayResponse provider = gateway.checkStatus(providerReference);
            if (!isAuthoritativeTerminal(provider)) return;
            treasuryService.resolvePending(
                    reservationId,
                    "SUCCESSFUL".equals(normalizedStatus(provider)),
                    providerReference,
                    "AIRTEL_STATUS_POLL");
        } catch (Exception e) {
            logger.log(Level.WARNING,
                    "Airtel shared-provider status reconciliation failed for reservation "
                            + reservationId, e);
        }
    }

    static boolean isAuthoritativeTerminal(GateWayResponse provider) {
        if (provider == null
                || !"200".equals(safe(provider.getHttpStatus()))
                || !"OK".equalsIgnoreCase(safe(provider.getStatus()))) return false;
        String status = normalizedStatus(provider);
        return "SUCCESSFUL".equals(status) || "FAILED".equals(status);
    }

    private static String normalizedStatus(GateWayResponse provider) {
        return provider == null || provider.getTransactionStatus() == null
                ? "" : provider.getTransactionStatus().trim().toUpperCase(Locale.ROOT);
    }

    private Long parseMerchantId(String value) {
        try { return value == null || value.isBlank() ? null : Long.valueOf(value.trim()); }
        catch (NumberFormatException ignored) { return null; }
    }

    private long number(Object value) {
        if (value instanceof Number n) return n.longValue();
        try { return value == null ? 0 : Long.parseLong(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private String value(Map<String, Object> values, String key) {
        Object value = values == null ? null : values.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String valueOrDefault(Map<String, Object> values, String key, String defaultValue) {
        String value = value(values, key);
        return value.isBlank() ? defaultValue : value;
    }

    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private static String safe(String value) { return value == null ? "" : value.trim(); }
    private boolean blank(String value) { return value == null || value.trim().isEmpty(); }
}
