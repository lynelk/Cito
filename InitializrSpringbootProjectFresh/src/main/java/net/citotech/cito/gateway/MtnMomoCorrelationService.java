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
import net.citotech.cito.treasury.ProviderTreasuryService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Persists MTN's provider UUID with merchant ownership and resolves asynchronous provider results.
 *
 * <p>The older provider_conversation_references table only stores a transaction reference. Merchant
 * references are unique per merchant, not globally, so that table alone cannot safely identify a
 * merchant-owned MTN transaction. New submissions use mtn_momo_correlations; the legacy mapping is
 * retained only as a backwards-compatible fallback when it resolves to exactly one MTN transaction.
 */
@Service
public class MtnMomoCorrelationService {
    private final NamedParameterJdbcTemplate jdbc;
    private final ProviderTreasuryService treasuryService;
    private final PlatformTransactionManager transactionManager;

    public MtnMomoCorrelationService(
            NamedParameterJdbcTemplate jdbc,
            ProviderTreasuryService treasuryService,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.treasuryService = treasuryService;
        this.transactionManager = transactionManager;
    }

    public void capture(
            PaymentGatewayRequest request, String operation, GateWayResponse providerResponse) {
        if (request == null || providerResponse == null) return;
        String providerReference = text(providerResponse.getNetworkId());
        if (providerReference.isEmpty() || !isUuid(providerReference)) return;
        String merchantNumber = required(request.getMerchantNumber(), "merchantNumber");
        String merchantReference = required(request.getReference(), "merchantReference");
        String normalizedOperation = normalizedOperation(operation);

        jdbc.update(
                "INSERT INTO mtn_momo_correlations"
                        + " (provider_reference, merchant_number, merchant_reference, operation)"
                        + " VALUES (:provider_reference,:merchant_number,:merchant_reference,:operation)"
                        + " ON DUPLICATE KEY UPDATE merchant_number=:merchant_number,"
                        + " merchant_reference=:merchant_reference, operation=:operation",
                new MapSqlParameterSource()
                        .addValue("provider_reference", providerReference)
                        .addValue("merchant_number", merchantNumber)
                        .addValue("merchant_reference", merchantReference)
                        .addValue("operation", normalizedOperation));
    }

    public Map<String, Object> resolve(
            String providerReference,
            String externalId,
            String providerStatus,
            String financialTransactionId) {
        requireUuid(providerReference);
        String merchantReference = required(externalId, "externalId");

        Map<String, Object> treasuryReservation = null;
        boolean treasuryResolved = false;
        try {
            treasuryReservation =
                    treasuryService.resolveProviderCallback(
                            MtnMomoCredentialSchema.CHANNEL_CODE,
                            providerReference,
                            merchantReference,
                            providerStatus,
                            financialTransactionId);
            treasuryResolved = true;
        } catch (PaymentGatewayException e) {
            if (!"Provider callback reference was not found".equals(e.getMessage())) {
                throw e;
            }
        }

        Correlation correlation = findCorrelation(providerReference);
        if (correlation == null) {
            correlation = findLegacyCorrelation(providerReference, merchantReference);
        }
        if (correlation == null) {
            if (treasuryResolved) {
                return result(
                        merchantReference,
                        normalizedProviderStatus(providerStatus),
                        false,
                        treasuryReservation,
                        "Shared-provider treasury state resolved; merchant transaction correlation was unavailable");
            }
            throw new PaymentGatewayException("MTN callback correlation was not found");
        }
        if (!merchantReference.equals(correlation.merchantReference())) {
            throw new PaymentGatewayException("MTN callback externalId does not match correlation");
        }

        String finalStatus = finalTransactionStatus(providerStatus);
        if (finalStatus == null) {
            return result(
                    merchantReference,
                    normalizedProviderStatus(providerStatus),
                    false,
                    treasuryReservation,
                    "MTN callback accepted with non-final provider status");
        }

        Transaction transaction =
                findTransaction(correlation.merchantNumber(), correlation.merchantReference());
        if (transaction == null) {
            if (treasuryResolved) {
                return result(
                        merchantReference,
                        finalStatus,
                        false,
                        treasuryReservation,
                        "Shared-provider treasury state resolved; merchant transaction was not found");
            }
            throw new PaymentGatewayException("MTN merchant transaction was not found");
        }

        String currentStatus = text(transaction.getStatus()).toUpperCase(Locale.ROOT);
        boolean updated = false;
        if (!"SUCCESSFUL".equals(currentStatus) && !"FAILED".equals(currentStatus)) {
            String networkReference =
                    text(financialTransactionId).isEmpty()
                            ? providerReference
                            : text(financialTransactionId);
            String trace =
                    "MTN_CALLBACK providerReference="
                            + providerReference
                            + "; providerStatus="
                            + normalizedProviderStatus(providerStatus);
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
                                    .addValue("resolved_by", "PROVIDER_CALLBACK:MTN"));
            updated = changed > 0;
            if (updated) {
                Transaction refreshed = findTransactionById(transaction.getId());
                if (refreshed != null) dispatchMerchantCallback(refreshed);
            }
        }

        return result(
                merchantReference,
                finalStatus,
                updated,
                treasuryReservation,
                updated ? "MTN callback resolved" : "MTN callback was already resolved");
    }

    private Correlation findCorrelation(String providerReference) {
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT merchant_number, merchant_reference, operation"
                                + " FROM mtn_momo_correlations"
                                + " WHERE provider_reference=:provider_reference LIMIT 1",
                        new MapSqlParameterSource()
                                .addValue("provider_reference", providerReference));
        if (rows.isEmpty()) return null;
        Map<String, Object> row = rows.get(0);
        return new Correlation(
                text(row.get("merchant_number")),
                text(row.get("merchant_reference")),
                text(row.get("operation")));
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
            throw new PaymentGatewayException("MTN callback externalId does not match legacy correlation");
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
        String operation =
                "PAYOUT".equalsIgnoreCase(text(tx.getTx_type())) ? "PAYOUT" : "COLLECT";
        return new Correlation(merchant.getAccount_number(), mappedReference, operation);
    }

    private Transaction findTransaction(String merchantNumber, String merchantReference) {
        if (text(merchantNumber).isEmpty()) return null;
        Merchant merchant = Common.getMerchantByAccountNumber(merchantNumber, jdbc);
        if (merchant == null) return null;
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

    private Transaction findTransactionById(Long id) {
        if (id == null) return null;
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
            // Provider acknowledgement must not fail because merchant callback delivery is async.
        }
    }

    private Map<String, Object> result(
            String merchantReference,
            String status,
            boolean transactionUpdated,
            Map<String, Object> treasuryReservation,
            String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accepted", true);
        result.put("merchantReference", merchantReference);
        result.put("status", status);
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

    private String required(String value, String field) {
        String safe = text(value);
        if (safe.isEmpty()) throw new PaymentGatewayException(field + " is required");
        return safe;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private record Correlation(String merchantNumber, String merchantReference, String operation) {}
}
