package net.citotech.cito.gateway;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.citotech.cito.Common;
import net.citotech.cito.Model.GateWayResponse;
import net.citotech.cito.Model.Merchant;
import net.citotech.cito.Model.Transaction;
import net.citotech.cito.callback.CallbackTaskRepository;
import net.citotech.cito.gateway.AirtelRecoveryStore.Entry;
import net.citotech.cito.gateway.AirtelRecoveryStore.Ticket;
import net.citotech.cito.ledger.PaymentLedgerSettlementService;
import net.citotech.cito.money.MoneyAmount;
import net.citotech.cito.treasury.ProviderTreasuryService;
import net.citotech.cito.treasury.ProviderTreasuryService.Reservation;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** One immutable request, no resubmission on uncertainty, and one atomic financial finalisation. */
@Service
public class AirtelRecoveryService {
    private static final Logger LOG = LoggerFactory.getLogger(AirtelRecoveryService.class);
    private final NamedParameterJdbcTemplate jdbc;
    private final PlatformTransactionManager manager;
    private final AirtelRecoveryStore store;
    private final AirtelRecoveryCredentials credentials;
    private final AirtelStatusClient statusClient;
    private final ProviderEndpointExecutionService execution;
    private final PaymentLedgerSettlementService ledger;
    private final ObjectProvider<ProviderTreasuryService> treasury;
    private final String runtimeEnvironment;

    public AirtelRecoveryService(
            NamedParameterJdbcTemplate jdbc,
            PlatformTransactionManager manager,
            AirtelRecoveryStore store,
            AirtelRecoveryCredentials credentials,
            AirtelStatusClient statusClient,
            ProviderEndpointExecutionService execution,
            PaymentLedgerSettlementService ledger,
            ObjectProvider<ProviderTreasuryService> treasury,
            @Value("${custom.gatewaystate:SANDBOX}") String runtimeEnvironment) {
        this.jdbc = jdbc;
        this.manager = manager;
        this.store = store;
        this.credentials = credentials;
        this.statusClient = statusClient;
        this.execution = execution;
        this.ledger = ledger;
        this.treasury = treasury;
        this.runtimeEnvironment = runtimeEnvironment.toUpperCase(java.util.Locale.ROOT);
    }

    public GateWayResponse submitLegacy(
            long merchantId,
            Double amount,
            String account,
            String transactionReference,
            String description,
            String operation) {
        List<Transaction> txs =
                jdbc.query(
                        "SELECT * FROM "
                                + Common.DB_TABLE_MERCHANT_TRANSACTION_LOG
                                + " WHERE merchant_id=:merchant AND tx_unique_id=:reference AND gateway_id=:gateway LIMIT 2",
                        new MapSqlParameterSource("merchant", merchantId)
                                .addValue("reference", transactionReference)
                                .addValue("gateway", LegacyGatewayIds.AIRTEL_OPEN_API),
                        Common.getTransactionRowMapper());
        if (txs.size() != 1)
            throw new PaymentGatewayException("AIRTEL_CANONICAL_TRANSACTION_REQUIRED");
        Transaction tx = txs.get(0);
        Merchant merchant = Common.getMerchantById(Long.toString(merchantId), jdbc);
        if (merchant == null) throw new PaymentGatewayException("AIRTEL_MERCHANT_NOT_FOUND");
        String source =
                Common.useMerchantProviderCredentials(jdbc) ? "LEGACY_MERCHANT" : "LEGACY_PLATFORM";
        String currency = required(tx.getCurrency(), "currency").toUpperCase(java.util.Locale.ROOT);
        Map<String, String> values =
                credentials.load(merchantId, source, runtimeEnvironment, "UG", currency);
        Map<String, String> metadata = new LinkedHashMap<>(values);
        metadata.put("gatewayState", runtimeEnvironment);
        metadata.put("credentialSource", source);
        return submit(
                new PaymentGatewayRequest(
                        merchant.getAccount_number(),
                        account,
                        amount,
                        tx.getTx_merchant_ref(),
                        description,
                        tx.getCallback_url(),
                        metadata),
                operation,
                tx,
                transactionReference);
    }

    public GateWayResponse submit(PaymentGatewayRequest request, String operation) {
        if (request == null) throw new PaymentGatewayException("AIRTEL_REQUEST_REQUIRED");
        String environment = request.getMetadata().get("gatewayState");
        // Production routes must retain the existing risk, funding and legacy transaction controls.
        if (!"SANDBOX".equalsIgnoreCase(environment))
            throw new PaymentGatewayException("AIRTEL_PRODUCTION_REQUIRES_CANONICAL_ORCHESTRATION");
        return submit(request, operation, null, UUID.randomUUID().toString());
    }

    private GateWayResponse submit(
            PaymentGatewayRequest request, String operation, Transaction tx, String provider) {
        if (!"COLLECT".equals(operation) && !"PAYOUT".equals(operation))
            throw new PaymentGatewayException("AIRTEL_OPERATION_INVALID");
        Merchant merchant = Common.getMerchantByAccountNumber(request.getMerchantNumber(), jdbc);
        if (merchant == null) throw new PaymentGatewayException("AIRTEL_MERCHANT_NOT_FOUND");
        Map<String, String> meta = request.getMetadata();
        String environment =
                required(meta.get("gatewayState"), "environment")
                        .toUpperCase(java.util.Locale.ROOT);
        String country =
                required(meta.get("country"), "country").toUpperCase(java.util.Locale.ROOT);
        String currency =
                required(meta.get("currency"), "currency").toUpperCase(java.util.Locale.ROOT);
        String source = required(meta.get("credentialSource"), "credentialSource");
        if (!List.of("MERCHANT", "PLATFORM_SHARED", "LEGACY_MERCHANT", "LEGACY_PLATFORM")
                .contains(source))
            throw new PaymentGatewayException("AIRTEL_CREDENTIAL_SOURCE_INVALID");
        if (tx == null && source.startsWith("LEGACY_"))
            throw new PaymentGatewayException("AIRTEL_CREDENTIAL_SOURCE_INVALID");
        Map<String, String> approved =
                credentials.load(merchant.getId(), source, environment, country, currency);
        // Always use the approved store, never a caller-supplied replacement credential.
        AirtelOpenApiCredentialSchema.validateForOperation(
                approved, environment, country, currency, operation);
        if (request.getAmount() == null || !Double.isFinite(request.getAmount()))
            throw new PaymentGatewayException("AIRTEL_AMOUNT_INVALID");
        BigDecimal amount = MoneyAmount.of(BigDecimal.valueOf(request.getAmount())).asBigDecimal();
        if (amount.signum() <= 0) throw new PaymentGatewayException("AIRTEL_AMOUNT_INVALID");
        String identity =
                AirtelRecoveryCredentials.identity(approved, environment, country, currency);
        String hash =
                ProviderTokenScope.segment(
                        "AIRTEL_REQUEST",
                        operation,
                        amount.toPlainString(),
                        currency,
                        country,
                        request.getAccountIdentifier(),
                        source,
                        identity,
                        request.getCallbackUrl(),
                        request.getDescription());
        Entry candidate =
                new Entry(
                        0,
                        provider,
                        merchant.getId(),
                        merchant.getAccount_number(),
                        required(request.getReference(), "reference"),
                        tx == null ? null : tx.getId(),
                        operation,
                        environment,
                        country,
                        currency,
                        source,
                        identity,
                        amount,
                        hash,
                        request.getCallbackUrl(),
                        0,
                        null,
                        null);
        if (tx != null) verifyTransaction(candidate, tx);
        TransactionTemplate prepare = new TransactionTemplate(manager);
        prepare.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        Ticket ticket =
                prepare.execute(
                        ignored -> {
                            Ticket saved = store.prepare(candidate);
                            if (saved.created() && "PLATFORM_SHARED".equals(source))
                                bindShared(saved.entry());
                            return saved;
                        });
        if (ticket == null) throw new PaymentGatewayException("AIRTEL_RECOVERY_PERSISTENCE_FAILED");
        Entry entry = ticket.entry();
        if (!ticket.created()) return pending(entry, "202");
        Map<String, String> outboundMetadata = new LinkedHashMap<>(approved);
        outboundMetadata.put("gatewayState", environment);
        outboundMetadata.put("country", country);
        outboundMetadata.put("currency", currency);
        PaymentGatewayRequest outbound =
                new PaymentGatewayRequest(
                        request.getMerchantNumber(),
                        request.getAccountIdentifier(),
                        amount.doubleValue(),
                        entry.provider(),
                        request.getDescription(),
                        request.getCallbackUrl(),
                        outboundMetadata);
        GateWayResponse response = null;
        try {
            response =
                    execution.execute(
                            AirtelOpenApiAdapter.CHANNEL_CODE,
                            "Airtel OpenAPI",
                            operation,
                            outbound);
        } catch (RuntimeException ignored) {
            LOG.warn("Airtel submission needs status verification; recoveryId={}", entry.id());
        }
        String rejection = rejectionProof(response);
        // Failure to record the receipt leaves PREPARED durable and eligible after its grace
        // period.
        try {
            store.submitted(entry.provider(), rejection);
        } catch (RuntimeException ignored) {
            LOG.warn("Airtel receipt persistence deferred; recoveryId={}", entry.id());
        }
        return pending(entry, response == null ? "0" : response.getHttpStatus());
    }

    static String rejectionProof(GateWayResponse response) {
        if (response == null || !"FAILED".equals(response.getTransactionStatus())) return null;
        try {
            int status = Integer.parseInt(response.getHttpStatus());
            return status >= 400 && status < 500 && status != 408 && status != 409 && status != 429
                    ? "SUBMISSION_REJECTED_" + status
                    : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private GateWayResponse pending(Entry entry, String httpStatus) {
        GateWayResponse response = new GateWayResponse();
        response.setHttpStatus(httpStatus == null ? "0" : httpStatus);
        response.setStatus("OK");
        response.setNetworkId(entry.provider());
        response.setOurUniqueTxId(entry.merchantReference());
        // Return a non-terminal acceptance even on replay; the status resource carries final
        // results.
        // This prevents a duplicate synchronous caller from posting settlement a second time.
        response.setTransactionStatus("PENDING");
        response.setMessage(
                "Payment is recorded; check status using the original reference. Do not resubmit.");
        response.setRequestTrace("AIRTEL_DURABLE_RECOVERY;recoveryId=" + entry.id());
        return response;
    }

    public int reconcile(int limit) {
        int resolved = 0;
        for (Entry entry : store.due(limit)) {
            String claim = store.claim(entry.id());
            if (claim == null) continue;
            try {
                Map<String, String> values =
                        credentials.load(
                                entry.merchantId(),
                                entry.source(),
                                entry.environment(),
                                entry.country(),
                                entry.currency());
                if (!entry.identity()
                        .equals(
                                AirtelRecoveryCredentials.identity(
                                        values,
                                        entry.environment(),
                                        entry.country(),
                                        entry.currency())))
                    throw new PaymentGatewayException("AIRTEL_CREDENTIAL_IDENTITY_CHANGED");
                AirtelOpenApiCredentialSchema.validate(
                        values, entry.environment(), entry.country(), entry.currency());
                AirtelStatusClient.Verified verified =
                        entry.submissionRejection() != null
                                        && entry.submissionRejection()
                                                .matches("SUBMISSION_REJECTED_4[0-9]{2}")
                                ? new AirtelStatusClient.Verified("FAILED", "")
                                : statusClient.verify(
                                        entry.operation(),
                                        entry.provider(),
                                        entry.environment(),
                                        entry.country(),
                                        entry.currency(),
                                        entry.amount(),
                                        values);
                if (!verified.terminal()) {
                    store.retry(entry, claim, "PROVIDER_PENDING");
                    continue;
                }
                Boolean done =
                        new TransactionTemplate(manager)
                                .execute(ignored -> finalizeLocked(entry, claim, verified));
                if (Boolean.TRUE.equals(done)) resolved++;
            } catch (Exception failure) {
                try {
                    store.retry(entry, claim, "VERIFICATION_OR_FINALISATION_REQUIRED");
                } catch (RuntimeException ignored) {
                    /* Expiring DB leases recover the deferred item. */
                }
                LOG.warn(
                        "Airtel recovery deferred; recoveryId={}, reasonType={}",
                        entry.id(),
                        failure.getClass().getSimpleName());
            }
        }
        return resolved;
    }

    boolean finalizeLocked(Entry original, String claim, AirtelStatusClient.Verified verified) {
        Entry entry = store.locked(original.id(), claim);
        if (entry == null) return false;
        if (!verified.terminal())
            throw new PaymentGatewayException("AIRTEL_TERMINAL_EVIDENCE_REQUIRED");
        if (entry.transactionId() != null) {
            if (entry.source().startsWith("LEGACY_")
                    && ("LEGACY_MERCHANT".equals(entry.source())
                            != Common.useMerchantProviderCredentials(jdbc)))
                throw new PaymentGatewayException("AIRTEL_LEGACY_ACCOUNTING_SCOPE_CHANGED");
            Transaction tx = lockedTransaction(entry);
            verifyTransaction(entry, tx);
            String current = tx.getStatus();
            if (("SUCCESSFUL".equals(current) || "FAILED".equals(current))
                    && !current.equals(verified.status()))
                throw new PaymentGatewayException("AIRTEL_TERMINAL_CONFLICT");
            // REQUIRED propagation joins this transaction: ledger, legacy statements, status and
            // outbox commit together.
            ledger.applyTerminalProviderOutcome(tx, verified.status());
            if (!verified.status().equals(current)) {
                tx.setStatus(verified.status());
                tx.setFinalStatusSet(true);
                tx.setTx_gateway_ref(
                        verified.financialReference().isBlank()
                                ? entry.provider()
                                : verified.financialReference());
                tx.setTx_update_trace("AIRTEL_AUTHENTICATED_RECOVERY;recoveryId=" + entry.id());
                tx.setResolved_by("AIRTEL_VERIFIED_RECOVERY");
                String result = Common.updateTx(tx, jdbc, manager);
                if (!"success".equals(result))
                    throw new PaymentGatewayException("AIRTEL_FINALISATION_RETRY");
                Transaction updated = lockedTransaction(entry);
                if (!verified.status().equals(updated.getStatus()))
                    throw new PaymentGatewayException("AIRTEL_FINALISATION_NOT_APPLIED");
                Common.enqueueMerchantCallback(
                        updated, Common.getMerchantById(updated.getMerchant_id(), jdbc), jdbc);
                jdbc.update(
                        "UPDATE "
                                + Common.DB_TABLE_MERCHANT_TRANSACTION_LOG
                                + " SET resolved_by='AIRTEL_VERIFIED_RECOVERY' WHERE id=:id",
                        new MapSqlParameterSource("id", entry.transactionId()));
                if (updated.getCallback_url() != null && !updated.getCallback_url().isBlank()) {
                    Integer count =
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM callback_tasks WHERE merchant_id=:merchant AND transaction_id=:id AND reference_value=:reference",
                                    new MapSqlParameterSource("merchant", entry.merchantId())
                                            .addValue("id", entry.transactionId().toString())
                                            .addValue("reference", entry.merchantReference()),
                                    Integer.class);
                    if (count == null || count < 1)
                        throw new PaymentGatewayException("AIRTEL_CALLBACK_ENQUEUE_RETRY");
                }
            }
        } else if (!"SANDBOX".equals(entry.environment())) {
            throw new PaymentGatewayException("AIRTEL_PRODUCTION_TRANSACTION_MISSING");
        }
        if ("PLATFORM_SHARED".equals(entry.source())) {
            Map<String, Object> shared = sharedRow(entry);
            if (!entry.provider().equals(shared.get("provider_reference")))
                throw new PaymentGatewayException("AIRTEL_SHARED_REFERENCE_MISMATCH");
            String state = String.valueOf(shared.get("status"));
            if ("PENDING".equals(state))
                treasury.getObject()
                        .resolvePending(
                                ((Number) shared.get("id")).longValue(),
                                "SUCCESSFUL".equals(verified.status()),
                                entry.provider(),
                                "AIRTEL_VERIFIED_RECOVERY");
            else if (!("SUCCESSFUL".equals(verified.status())
                    ? "SETTLED".equals(state)
                    : List.of("FAILED", "RELEASED").contains(state)))
                throw new PaymentGatewayException("AIRTEL_SHARED_TERMINAL_CONFLICT");
        }
        if (entry.transactionId() == null
                && entry.callbackUrl() != null
                && !entry.callbackUrl().isBlank()) {
            String body =
                    new JSONObject()
                            .put("reference", entry.merchantReference())
                            .put("transactionId", entry.provider())
                            .put("status", verified.status())
                            .put("currency", entry.currency())
                            .put("amount", entry.amount())
                            .put("environment", entry.environment())
                            .put("channel", AirtelOpenApiAdapter.CHANNEL_CODE)
                            .toString();
            new CallbackTaskRepository(jdbc)
                    .enqueue(
                            entry.merchantId(),
                            entry.provider(),
                            entry.merchantReference(),
                            entry.callbackUrl(),
                            body);
        }
        store.resolved(entry, claim, verified.status(), verified.financialReference());
        return true;
    }

    private Transaction lockedTransaction(Entry entry) {
        List<Transaction> rows =
                jdbc.query(
                        "SELECT * FROM "
                                + Common.DB_TABLE_MERCHANT_TRANSACTION_LOG
                                + " WHERE id=:id FOR UPDATE",
                        new MapSqlParameterSource("id", entry.transactionId()),
                        Common.getTransactionRowMapper());
        if (rows.size() != 1) throw new PaymentGatewayException("AIRTEL_TRANSACTION_MISSING");
        return rows.get(0);
    }

    static void verifyTransaction(Entry entry, Transaction tx) {
        if (tx == null
                || !Long.toString(entry.merchantId()).equals(tx.getMerchant_id())
                || !LegacyGatewayIds.AIRTEL_OPEN_API.equals(tx.getGateway_id())
                || !entry.merchantReference().equals(tx.getTx_merchant_ref())
                || !entry.provider().equals(tx.getTx_unique_id())
                || entry.amount().compareTo(tx.getOriginalAmountDecimal()) != 0
                || !entry.currency().equalsIgnoreCase(tx.getCurrency())
                || !("PAYOUT".equals(entry.operation())
                                ? Transaction.TX_TYPE_PAYOUT
                                : Transaction.TX_TYPE_PAYIN)
                        .equals(tx.getTx_type()))
            throw new PaymentGatewayException("AIRTEL_IMMUTABLE_TRANSACTION_MISMATCH");
    }

    private Map<String, Object> sharedRow(Entry entry) {
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT r.* FROM provider_treasury_reservations r JOIN provider_treasury_accounts a ON a.id=r.treasury_account_id"
                                + " WHERE r.merchant_id=:merchant AND r.merchant_reference=:reference AND r.operation=:operation"
                                + " AND a.channel_code=:channel AND a.environment=:environment AND a.country_code=:country AND a.currency_code=:currency FOR UPDATE",
                        new MapSqlParameterSource("merchant", entry.merchantId())
                                .addValue("reference", entry.merchantReference())
                                .addValue("operation", entry.operation())
                                .addValue("channel", AirtelOpenApiAdapter.CHANNEL_CODE)
                                .addValue("environment", entry.environment())
                                .addValue("country", entry.country())
                                .addValue("currency", entry.currency()));
        if (rows.size() != 1
                || entry.amount().compareTo(new BigDecimal(rows.get(0).get("amount").toString()))
                        != 0)
            throw new PaymentGatewayException("AIRTEL_SHARED_RESERVATION_MISMATCH");
        return rows.get(0);
    }

    private void bindShared(Entry entry) {
        Map<String, Object> row = sharedRow(entry);
        Object existing = row.get("provider_reference");
        if (existing != null
                && !existing.toString().isBlank()
                && !entry.provider().equals(existing))
            throw new PaymentGatewayException("AIRTEL_SHARED_REFERENCE_CONFLICT");
        if (!List.of("INITIATED", "RESERVED", "PENDING").contains(row.get("status")))
            throw new PaymentGatewayException("AIRTEL_SHARED_ALREADY_FINAL");
        treasury.getObject()
                .completeShared(
                        new Reservation(
                                ((Number) row.get("id")).longValue(),
                                0,
                                null,
                                entry.merchantId(),
                                entry.merchantNumber(),
                                entry.operation(),
                                "PAYOUT".equals(entry.operation()) ? "OUTGOING" : "INCOMING",
                                entry.amount(),
                                entry.currency(),
                                entry.merchantReference(),
                                String.valueOf(row.get("status"))),
                        "PENDING",
                        entry.provider());
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank())
            throw new PaymentGatewayException("Airtel " + name + " is required");
        return value.trim();
    }
}
