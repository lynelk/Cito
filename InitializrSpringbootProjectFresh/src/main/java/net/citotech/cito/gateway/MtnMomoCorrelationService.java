package net.citotech.cito.gateway;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.citotech.cito.Common;
import net.citotech.cito.Model.GateWayResponse;
import net.citotech.cito.Model.Merchant;
import net.citotech.cito.Model.Transaction;
import net.citotech.cito.Model.TxCallback;
import net.citotech.cito.merchant.MerchantChannelCredentialService;
import net.citotech.cito.sharedprovider.SharedProviderAccessService;
import net.citotech.cito.treasury.ProviderTreasuryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Persists MTN's provider UUID with merchant ownership and resolves asynchronous provider signals.
 *
 * <p>MTN callbacks are not treated as authoritative financial state. The callback UUID is first
 * correlated to the exact merchant transaction and then Cito performs an authenticated MTN status
 * lookup with the same credential source used for execution. The same verification path is used by
 * the missed-callback poller. Only the verified provider result may move merchant or shared-provider
 * treasury state to a final status.
 */
@Service
public class MtnMomoCorrelationService {
    private static final Logger logger = LoggerFactory.getLogger(MtnMomoCorrelationService.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final ProviderTreasuryService treasuryService;
    private final PlatformTransactionManager transactionManager;
    private final MerchantChannelCredentialService merchantCredentials;
    private final SharedProviderAccessService sharedProviderAccessService;
    private final MtnMomoStatusClient statusClient;

    public MtnMomoCorrelationService(
            NamedParameterJdbcTemplate jdbc,
            ProviderTreasuryService treasuryService,
            PlatformTransactionManager transactionManager,
            MerchantChannelCredentialService merchantCredentials,
            SharedProviderAccessService sharedProviderAccessService,
            MtnMomoStatusClient statusClient) {
        this.jdbc = jdbc;
        this.treasuryService = treasuryService;
        this.transactionManager = transactionManager;
        this.merchantCredentials = merchantCredentials;
        this.sharedProviderAccessService = sharedProviderAccessService;
        this.statusClient = statusClient;
    }

    public void capture(
            PaymentGatewayRequest request, String operation, GateWayResponse providerResponse) {
        if (request == null || providerResponse == null) return;
        if (!"202".equals(text(providerResponse.getHttpStatus()))) return;
        String providerReference = text(providerResponse.getNetworkId());
        if (providerReference.isEmpty() || !isUuid(providerReference)) return;

        String merchantNumber = required(request.getMerchantNumber(), "merchantNumber");
        String merchantReference = required(request.getReference(), "merchantReference");
        String normalizedOperation = normalizedOperation(operation);
        Map<String, String> metadata =
                request.getMetadata() == null ? Map.of() : request.getMetadata();
        String environment =
                normalizedEnvironment(
                        firstNonBlank(
                                metadata.get("credentialEnvironment"),
                                metadata.get("gatewayState"),
                                "SANDBOX"));
        String country = required(metadata.get("country"), "country").toUpperCase(Locale.ROOT);
        String currency = required(metadata.get("currency"), "currency").toUpperCase(Locale.ROOT);
        String credentialSource =
                firstNonBlank(metadata.get("credentialSource"), SharedProviderAccessService.MERCHANT)
                        .toUpperCase(Locale.ROOT);
        if (!credentialSource.equals(SharedProviderAccessService.MERCHANT)
                && !credentialSource.equals(SharedProviderAccessService.PLATFORM_SHARED)) {
            throw new PaymentGatewayException("Invalid MTN credential source");
        }

        jdbc.update(
                "INSERT INTO mtn_momo_correlations"
                        + " (provider_reference, merchant_number, merchant_reference, operation,"
                        + " environment, country_code, currency_code, credential_source)"
                        + " VALUES (:provider_reference,:merchant_number,:merchant_reference,:operation,"
                        + " :environment,:country,:currency,:credential_source)"
                        + " ON DUPLICATE KEY UPDATE merchant_number=:merchant_number,"
                        + " merchant_reference=:merchant_reference, operation=:operation,"
                        + " environment=:environment, country_code=:country, currency_code=:currency,"
                        + " credential_source=:credential_source",
                new MapSqlParameterSource()
                        .addValue("provider_reference", providerReference)
                        .addValue("merchant_number", merchantNumber)
                        .addValue("merchant_reference", merchantReference)
                        .addValue("operation", normalizedOperation)
                        .addValue("environment", environment)
                        .addValue("country", country)
                        .addValue("currency", currency)
                        .addValue("credential_source", credentialSource));
    }

    public Map<String, Object> resolve(
            String providerReference,
            String externalId,
            String callbackStatus,
            String callbackFinancialTransactionId) {
        requireUuid(providerReference);
        String merchantReference = required(externalId, "externalId");

        Correlation correlation = findCorrelation(providerReference);
        if (correlation == null) {
            correlation = findLegacyCorrelation(providerReference, merchantReference);
        }
        if (correlation == null) {
            throw new PaymentGatewayException("MTN callback correlation was not found");
        }
        if (!merchantReference.equals(correlation.merchantReference())) {
            throw new PaymentGatewayException("MTN callback externalId does not match correlation");
        }
        return verifyAndApply(
                correlation,
                providerReference,
                callbackStatus,
                callbackFinancialTransactionId,
                true);
    }

    /**
     * Polls provider state for exact, unresolved MTN correlations. This is the recovery path for
     * MTN's single-attempt callbacks and intentionally uses the same authenticated verification as
     * the callback handler.
     */
    public int reconcilePending(int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, 500));
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT c.provider_reference, c.merchant_number, c.merchant_reference,"
                                + " c.operation, c.environment, c.country_code, c.currency_code,"
                                + " c.credential_source"
                                + " FROM mtn_momo_correlations c"
                                + " JOIN merchants m ON m.account_number=c.merchant_number"
                                + " JOIN "
                                + Common.DB_TABLE_MERCHANT_TRANSACTION_LOG
                                + " t ON t.merchant_id=m.id"
                                + " AND t.tx_merchant_ref=c.merchant_reference"
                                + " AND t.gateway_id=:gateway_id"
                                + " WHERE t.status IN ('PENDING','UNDETERMINED')"
                                + " ORDER BY c.updated_at ASC LIMIT :limit",
                        new MapSqlParameterSource()
                                .addValue("gateway_id", LegacyGatewayIds.MTN_MOMO)
                                .addValue("limit", limit));
        int finalized = 0;
        for (Map<String, Object> row : rows) {
            String providerReference = text(row.get("provider_reference"));
            try {
                Map<String, Object> outcome =
                        verifyAndApply(
                                correlation(row), providerReference, "", "", false);
                if (Boolean.TRUE.equals(outcome.get("transactionUpdated"))) finalized++;
            } catch (Exception e) {
                logger.warn(
                        "MTN pending-status verification failed for provider reference {}: {}",
                        providerReference,
                        e.getMessage());
            }
        }
        return finalized;
    }

    private Map<String, Object> verifyAndApply(
            Correlation correlation,
            String providerReference,
            String callbackStatus,
            String callbackFinancialTransactionId,
            boolean callbackDriven) {
        Merchant merchant = Common.getMerchantByAccountNumber(correlation.merchantNumber(), jdbc);
        if (merchant == null) {
            throw new PaymentGatewayException("MTN correlation merchant was not found");
        }
        Transaction transaction =
                findTransaction(merchant, correlation.merchantReference());
        if (transaction == null) {
            throw new PaymentGatewayException("MTN merchant transaction was not found");
        }

        Map<String, Object> credentials = credentialsFor(correlation, merchant);
        MtnMomoStatusClient.VerifiedStatus verified =
                statusClient.verify(
                        correlation.operation(),
                        providerReference,
                        correlation.environment(),
                        correlation.country(),
                        correlation.currency(),
                        credentials);
        if (text(verified.externalId()).isEmpty()
                || !correlation.merchantReference().equals(text(verified.externalId()))) {
            throw new PaymentGatewayException(
                    "MTN verified status externalId does not match the callback correlation");
        }

        Map<String, Object> treasuryReservation = null;
        try {
            treasuryReservation =
                    treasuryService.resolveProviderCallback(
                            MtnMomoCredentialSchema.CHANNEL_CODE,
                            providerReference,
                            correlation.merchantReference(),
                            verified.status(),
                            verified.financialTransactionId());
        } catch (PaymentGatewayException e) {
            if (!"Provider callback reference was not found".equals(e.getMessage())) {
                throw e;
            }
            // Merchant-owned MTN execution has no CPay shared-provider treasury reservation.
        }

        String finalStatus = finalTransactionStatus(verified.status());
        if (finalStatus == null) {
            return result(
                    correlation.merchantReference(),
                    verified.status(),
                    callbackStatus,
                    false,
                    true,
                    treasuryReservation,
                    callbackDriven
                            ? "MTN callback correlated and provider status verified; transaction remains pending"
                            : "MTN status poll verified that the transaction remains pending");
        }

        String currentStatus = text(transaction.getStatus()).toUpperCase(Locale.ROOT);
        boolean updated = false;
        if (!"SUCCESSFUL".equals(currentStatus) && !"FAILED".equals(currentStatus)) {
            String networkReference =
                    text(verified.financialTransactionId()).isEmpty()
                            ? providerReference
                            : text(verified.financialTransactionId());
            String trace =
                    (callbackDriven ? "MTN_VERIFIED_CALLBACK" : "MTN_VERIFIED_POLL")
                            + " providerReference="
                            + providerReference
                            + "; callbackStatus="
                            + normalizedProviderStatus(callbackStatus)
                            + "; verifiedStatus="
                            + normalizedProviderStatus(verified.status())
                            + "; callbackFinancialTransactionIdPresent="
                            + !text(callbackFinancialTransactionId).isEmpty();
            int changed =
                    jdbc.update(
                            "UPDATE "
                                    + Common.DB_TABLE_MERCHANT_TRANSACTION_LOG
                                    + " SET status=:status, tx_gateway_ref=:gateway_ref,"
                                    + " tx_update_trace=:trace, resolved_by=:resolved_by"
                                    + " WHERE id=:id AND status IN ('PENDING','UNDETERMINED')",
                            new MapSqlParameterSource()
                                    .addValue("id", transaction.getId())
                                    .addValue("status", finalStatus)
                                    .addValue("gateway_ref", networkReference)
                                    .addValue("trace", trace)
                                    .addValue(
                                            "resolved_by",
                                            callbackDriven
                                                    ? "MTN_STATUS_VERIFIED_CALLBACK"
                                                    : "MTN_STATUS_VERIFIED_POLL"));
            updated = changed > 0;
            if (updated) {
                Transaction refreshed = findTransactionById(transaction.getId());
                if (refreshed != null) dispatchMerchantCallback(refreshed);
            }
        }

        return result(
                correlation.merchantReference(),
                finalStatus,
                callbackStatus,
                updated,
                true,
                treasuryReservation,
                updated
                        ? (callbackDriven
                                ? "MTN callback resolved from authenticated provider status"
                                : "MTN pending transaction resolved from authenticated provider poll")
                        : "MTN transaction was already resolved");
    }

    private Map<String, Object> credentialsFor(Correlation correlation, Merchant merchant) {
        if (SharedProviderAccessService.PLATFORM_SHARED.equals(correlation.credentialSource())) {
            return sharedProviderAccessService.loadActivePlatformCredential(
                    MtnMomoCredentialSchema.CHANNEL_CODE,
                    correlation.environment(),
                    correlation.country(),
                    correlation.currency());
        }
        return merchantCredentials.loadDecrypted(
                merchant, MtnMomoCredentialSchema.CHANNEL_CODE, correlation.environment());
    }

    private Correlation findCorrelation(String providerReference) {
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT merchant_number, merchant_reference, operation, environment,"
                                + " country_code, currency_code, credential_source"
                                + " FROM mtn_momo_correlations"
                                + " WHERE provider_reference=:provider_reference LIMIT 1",
                        new MapSqlParameterSource()
                                .addValue("provider_reference", providerReference));
        return rows.isEmpty() ? null : correlation(rows.get(0));
    }

    private Correlation correlation(Map<String, Object> row) {
        return new Correlation(
                text(row.get("merchant_number")),
                text(row.get("merchant_reference")),
                normalizedOperation(text(row.get("operation"))),
                normalizedEnvironment(text(row.get("environment"))),
                required(text(row.get("country_code")), "country").toUpperCase(Locale.ROOT),
                required(text(row.get("currency_code")), "currency").toUpperCase(Locale.ROOT),
                required(text(row.get("credential_source")), "credentialSource")
                        .toUpperCase(Locale.ROOT));
    }

    private Correlation findLegacyCorrelation(String providerReference, String externalId) {
        List<Map<String, Object>> mappings =
                jdbc.queryForList(
                        "SELECT tx_reference FROM provider_conversation_references"
                                + " WHERE provider_code=:provider_code"
                                + " AND conversation_id=:conversation_id LIMIT 1",
                        new MapSqlParameterSource()
                                .addValue("provider_code", MtnMomoCredentialSchema.CHANNEL_CODE)
                                .addValue("conversation_id", providerReference));
        if (mappings.isEmpty()) return null;
        String mappedReference = text(mappings.get(0).get("tx_reference"));
        if (!externalId.equals(mappedReference)) {
            throw new PaymentGatewayException(
                    "MTN callback externalId does not match legacy correlation");
        }

        List<Transaction> candidates =
                jdbc.query(
                        "SELECT * FROM "
                                + Common.DB_TABLE_MERCHANT_TRANSACTION_LOG
                                + " WHERE tx_merchant_ref=:merchant_reference"
                                + " AND gateway_id=:gateway_id LIMIT 2",
                        new MapSqlParameterSource()
                                .addValue("merchant_reference", mappedReference)
                                .addValue("gateway_id", LegacyGatewayIds.MTN_MOMO),
                        Common.getTransactionRowMapper());
        if (candidates.size() != 1) {
            if (candidates.size() > 1) {
                throw new PaymentGatewayException(
                        "Legacy MTN callback correlation is ambiguous across merchants");
            }
            return null;
        }
        Transaction tx = candidates.get(0);
        Merchant merchant = Common.getMerchantById(tx.getMerchant_id(), jdbc);
        if (merchant == null) return null;

        List<Map<String, Object>> environments =
                jdbc.queryForList(
                        "SELECT environment FROM merchant_channel_credentials"
                                + " WHERE merchant_id=:merchant_id AND channel_code=:channel_code"
                                + " AND status IN ('ACTIVE','SANDBOX_TESTED')"
                                + " ORDER BY FIELD(status,'ACTIVE','SANDBOX_TESTED') LIMIT 2",
                        new MapSqlParameterSource()
                                .addValue("merchant_id", merchant.getId())
                                .addValue("channel_code", MtnMomoCredentialSchema.CHANNEL_CODE));
        if (environments.size() != 1) return null;
        String environment = normalizedEnvironment(text(environments.get(0).get("environment")));
        String currency = text(tx.getCurrency()).toUpperCase(Locale.ROOT);
        if (currency.isEmpty()) currency = "SANDBOX".equals(environment) ? "EUR" : "UGX";
        String operation =
                "PAYOUT".equalsIgnoreCase(text(tx.getTx_type())) ? "PAYOUT" : "COLLECT";
        return new Correlation(
                merchant.getAccount_number(),
                mappedReference,
                operation,
                environment,
                "UG",
                currency,
                SharedProviderAccessService.MERCHANT);
    }

    private Transaction findTransaction(Merchant merchant, String merchantReference) {
        List<Transaction> rows =
                jdbc.query(
                        "SELECT * FROM "
                                + Common.DB_TABLE_MERCHANT_TRANSACTION_LOG
                                + " WHERE merchant_id=:merchant_id"
                                + " AND tx_merchant_ref=:merchant_reference"
                                + " AND gateway_id=:gateway_id LIMIT 1",
                        new MapSqlParameterSource()
                                .addValue("merchant_id", merchant.getId())
                                .addValue("merchant_reference", merchantReference)
                                .addValue("gateway_id", LegacyGatewayIds.MTN_MOMO),
                        Common.getTransactionRowMapper());
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Transaction findTransactionById(long id) {
        List<Transaction> rows =
                jdbc.query(
                        "SELECT * FROM "
                                + Common.DB_TABLE_MERCHANT_TRANSACTION_LOG
                                + " WHERE id=:id LIMIT 1",
                        new MapSqlParameterSource().addValue("id", id),
                        Common.getTransactionRowMapper());
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void dispatchMerchantCallback(Transaction transaction) {
        if (text(transaction.getCallback_url()).isEmpty()) return;
        Merchant merchant = Common.getMerchantById(transaction.getMerchant_id(), jdbc);
        if (merchant == null) return;
        try {
            new TxCallback(transaction, merchant).start(jdbc, transactionManager);
        } catch (Exception ignored) {
            // MTN acknowledgement/polling must not fail because merchant callback delivery is async.
        }
    }

    private Map<String, Object> result(
            String merchantReference,
            String status,
            String callbackStatus,
            boolean transactionUpdated,
            boolean verified,
            Map<String, Object> treasuryReservation,
            String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accepted", true);
        result.put("verified", verified);
        result.put("merchantReference", merchantReference);
        result.put("status", status);
        result.put("callbackSignalStatus", normalizedProviderStatus(callbackStatus));
        result.put("transactionUpdated", transactionUpdated);
        result.put("message", message);
        if (treasuryReservation != null) {
            result.put("reservationId", treasuryReservation.get("id"));
            result.put("reservationStatus", treasuryReservation.get("status"));
        }
        return result;
    }

    private String finalTransactionStatus(String providerStatus) {
        String status = normalizedProviderStatus(providerStatus);
        if (status.equals("SUCCESSFUL") || status.equals("SUCCESS") || status.equals("COMPLETED")) {
            return "SUCCESSFUL";
        }
        if (status.equals("FAILED") || status.equals("FAILURE") || status.equals("REJECTED")) {
            return "FAILED";
        }
        return null;
    }

    private String normalizedProviderStatus(String providerStatus) {
        return text(providerStatus).toUpperCase(Locale.ROOT);
    }

    private String normalizedOperation(String operation) {
        String value = text(operation).toUpperCase(Locale.ROOT);
        if (!value.equals("COLLECT") && !value.equals("PAYOUT")) {
            throw new PaymentGatewayException("MTN operation must be COLLECT or PAYOUT");
        }
        return value;
    }

    private String normalizedEnvironment(String environment) {
        String value = text(environment).toUpperCase(Locale.ROOT);
        if (!value.equals("SANDBOX") && !value.equals("PRODUCTION")) {
            throw new PaymentGatewayException("MTN environment must be SANDBOX or PRODUCTION");
        }
        return value;
    }

    private void requireUuid(String value) {
        if (!isUuid(value)) {
            throw new PaymentGatewayException("Invalid MTN callback reference");
        }
    }

    private boolean isUuid(String value) {
        try {
            UUID.fromString(text(value));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (!text(value).isEmpty()) return text(value);
        }
        return "";
    }

    private String required(String value, String field) {
        String safe = text(value);
        if (safe.isEmpty()) throw new PaymentGatewayException(field + " is required");
        return safe;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private record Correlation(
            String merchantNumber,
            String merchantReference,
            String operation,
            String environment,
            String country,
            String currency,
            String credentialSource) {}
}
