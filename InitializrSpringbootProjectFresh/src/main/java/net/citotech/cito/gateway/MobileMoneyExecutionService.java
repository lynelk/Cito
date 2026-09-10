package net.citotech.cito.gateway;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.*;
import net.citotech.cito.*;
import net.citotech.cito.Model.*;
import net.citotech.cito.api.v2.dto.*;
import net.citotech.cito.billing.integration.cpay.PaymentUsageOutboxHook;
import net.citotech.cito.compliance.RiskDecisionService;
import net.citotech.cito.ledger.PaymentLedgerSettlementService;
import net.citotech.cito.merchant.MerchantChannelCryptoService;
import net.citotech.cito.money.MoneyAmount;
import net.citotech.cito.payout.PayoutControlService;
import net.citotech.cito.security.CanonicalRequestSigner;
import net.citotech.cito.sharedprovider.SharedProviderAccessService;
import net.citotech.cito.treasury.ProviderTreasuryService;
import net.citotech.cito.webhook.MerchantWebhookService;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Durable submission boundary for both credential owners. No network I/O occurs under a DB lock.
 */
@Service
public class MobileMoneyExecutionService {
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final PlatformTransactionManager transactionManager;
    private final SharedProviderAccessService access;
    private final ProviderTreasuryService treasury;
    private final PaymentLedgerSettlementService ledger;
    private final RiskDecisionService risk;
    private final PayoutControlService payoutControls;
    private final MerchantChannelCryptoService crypto;
    private final ObjectMapper json;
    private final GatewayExecutionService gateways;
    private final PaymentUsageOutboxHook usage;
    private final MerchantWebhookService webhooks;

    public MobileMoneyExecutionService(
            NamedParameterJdbcTemplate jdbc,
            PlatformTransactionManager tm,
            SharedProviderAccessService access,
            ProviderTreasuryService treasury,
            PaymentLedgerSettlementService ledger,
            RiskDecisionService risk,
            PayoutControlService payoutControls,
            MerchantChannelCryptoService crypto,
            ObjectMapper json,
            GatewayExecutionService gateways,
            PaymentUsageOutboxHook usage,
            MerchantWebhookService webhooks) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(tm);
        this.transactions.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transactionManager = tm;
        this.access = access;
        this.treasury = treasury;
        this.ledger = ledger;
        this.risk = risk;
        this.payoutControls = payoutControls;
        this.crypto = crypto;
        this.json = json;
        this.gateways = gateways;
        this.usage = usage;
        this.webhooks = webhooks;
    }

    @org.springframework.beans.factory.annotation.Autowired
    private net.citotech.cito.sandbox.SandboxProductionGuardService productionGuard;

    @org.springframework.beans.factory.annotation.Autowired
    private IntelligentPaymentRoutingService routing;

    public static boolean managed(String channel) {
        return "mtn_momo".equalsIgnoreCase(channel) || "airtel_open_api".equalsIgnoreCase(channel);
    }

    public PaymentResult submit(
            PaymentRequest request,
            Merchant merchant,
            String environment,
            String operation,
            PaymentChannelAdapter adapter) {
        Prepared prepared =
                transactions.execute(
                        ignored -> prepare(request, merchant, environment, operation, adapter));
        if (prepared.replay() != null) return prepared.replay();
        GateWayResponse response;
        try {
            response =
                    gateways.execute(
                            () ->
                                    "PAYOUT".equals(operation)
                                            ? adapter.payout(prepared.gatewayRequest())
                                            : adapter.collect(prepared.gatewayRequest()));
        } catch (RuntimeException ex) {
            // The durable provider reference survives even a crash/timeout after the provider
            // accepted.
            response = new GateWayResponse();
            response.setTransactionStatus("UNDETERMINED");
            response.setHttpStatus("0");
            response.setMessage("Provider outcome pending verification");
        }
        apply(prepared.transaction().getTx_unique_id(), response);
        // The caller may hold an older MySQL REPEATABLE READ snapshot (refunds/batches).
        return transactions.execute(
                ignored ->
                        result(
                                request,
                                load(prepared.transaction().getTx_unique_id()),
                                adapter.channelCode(),
                                environment));
    }

    private Prepared prepare(
            PaymentRequest request,
            Merchant merchant,
            String environment,
            String operation,
            PaymentChannelAdapter adapter) {
        require(request.getReference(), "reference");
        require(request.getCountry(), "country");
        require(request.getCurrency(), "currency");
        if (!adapter.countryCode().equalsIgnoreCase(request.getCountry()))
            throw new PaymentGatewayException(
                    "Country is not supported by the selected payment channel");
        BigDecimal amount = MoneyAmount.of(request.getAmount()).asBigDecimal();
        // Legacy merchant balance projections still expose Double. Reject an amount that cannot
        // round-trip exactly instead of sending one amount and projecting another.
        if (java.math.BigDecimal.valueOf(amount.doubleValue()).compareTo(amount) != 0)
            throw new PaymentGatewayException(
                    "Amount exceeds the supported merchant balance precision");

        if (amount.signum() <= 0) throw new PaymentGatewayException("Amount must be positive");
        String account =
                "PAYOUT".equals(operation)
                        ? request.getPayee().getValue()
                        : request.getPayer().getValue();
        String hash = fingerprint(request, operation, environment, adapter.channelCode());
        MapSqlParameterSource p =
                new MapSqlParameterSource()
                        .addValue("merchant", merchant.getId())
                        .addValue("reference", request.getReference());
        // Serializes reference claims, risk limits and reservations across all application
        // replicas.
        jdbc.queryForObject(
                "SELECT id FROM merchants WHERE id=:merchant FOR UPDATE", p, Long.class);
        List<Map<String, Object>> previous =
                jdbc.queryForList(
                        "SELECT * FROM mobile_money_executions WHERE merchant_id=:merchant AND merchant_reference=:reference",
                        p);
        if (!previous.isEmpty()) {
            Map<String, Object> row = previous.get(0);
            if (!hash.equals(row.get("request_hash")))
                throw new PaymentGatewayException(
                        "Payment reference conflicts with the original commercial attributes");
            return new Prepared(
                    null,
                    null,
                    result(
                            request,
                            load(String.valueOf(row.get("transaction_id"))),
                            adapter.channelCode(),
                            environment));
        }
        if (Common.getMerchantTxByTheirRef(
                        request.getReference(), merchant.getId().toString(), jdbc)
                != null)
            throw new PaymentGatewayException(
                    "Reference already belongs to a historical transaction; use its status endpoint");
        Integer historical =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM provider_endpoint_runs WHERE merchant_number=:merchant_number AND reference_value=:reference",
                        new MapSqlParameterSource("merchant_number", merchant.getAccount_number())
                                .addValue("reference", request.getReference()),
                        Integer.class);
        if (historical != null && historical > 0)
            throw new PaymentGatewayException(
                    "Reference belongs to a historical provider submission; reconcile its provider outcome before creating a new payment");
        Long batchId = null, beneficiaryId = null;
        if (request.getReference().matches("batch-payout:[0-9]+:[0-9]+(:retry:[0-9a-f-]{36})?")) {
            String[] parts = request.getReference().split(":");
            batchId = Long.valueOf(parts[1]);
            beneficiaryId = Long.valueOf(parts[2]);
            Map<String, Object> batch =
                    jdbc.queryForMap(
                            "SELECT b.amount,b.account,b.active_payment_reference,p.status FROM merchant_batch_transactions_log p JOIN beneficiaries b ON b.batch_id=p.id WHERE p.id=:batch AND b.id=:beneficiary AND p.merchant_id=:merchant FOR UPDATE",
                            new MapSqlParameterSource("batch", batchId)
                                    .addValue("beneficiary", beneficiaryId)
                                    .addValue("merchant", merchant.getId()));
            if (!"PROCESSING".equals(batch.get("status"))
                    || !account.equals(batch.get("account"))
                    || amount.compareTo(new BigDecimal(batch.get("amount").toString())) != 0
                    || (batch.get("active_payment_reference") != null
                            && !request.getReference()
                                    .equals(batch.get("active_payment_reference"))))
                throw new PaymentGatewayException(
                        "Batch is stopped or its beneficiary attributes do not match");
        }
        // Read the cap under the same merchant lock as durable claims. Replays returned above
        // do not consume another slot, and competing replicas cannot pass on a stale count.
        new net.citotech.cito.merchant.MerchantEnvironmentService(jdbc)
                .enforceProductionLimit(merchant, environment);
        productionGuard.reserveProductionExecution(
                merchant, environment, operation, request.getReference());
        risk.authorizePayment(merchant, request, operation);
        if ("PRODUCTION".equals(environment) && "PAYOUT".equals(operation)) {
            PayoutControlService.PayoutEvaluation evaluation =
                    payoutControls.evaluate(request, merchant, "merchant:" + merchant.getId());
            if (!"EXECUTE".equals(evaluation.decision())) {
                if (!evaluation.isApprovalRequired())
                    throw new PaymentGatewayException("Payout blocked by controls");
                PaymentResult pending = new PaymentResult();
                pending.setReference(request.getReference());
                pending.setTransactionId("approval:" + evaluation.queueId());
                pending.setStatus("APPROVAL_PENDING");
                pending.setChannel(adapter.channelCode());
                pending.setEnvironment(environment);
                pending.setCurrency(request.getCurrency());
                pending.setMessage("Payout awaits independent approval");
                return new Prepared(null, null, pending);
            }
        }
        String source =
                request.getMetadata() == null
                        ? null
                        : request.getMetadata().get("credentialSource");
        SharedProviderAccessService.CredentialContext context =
                access.resolve(
                        merchant,
                        adapter.channelCode(),
                        environment,
                        request.getCountry(),
                        request.getCurrency(),
                        operation,
                        amount,
                        source);
        if ("mtn_momo".equals(adapter.channelCode()))
            MtnMomoCredentialSchema.validateForOperation(
                    context.credentials(),
                    environment,
                    request.getCountry(),
                    request.getCurrency(),
                    operation);
        else
            AirtelOpenApiCredentialSchema.validateForOperation(
                    context.credentials(),
                    environment,
                    request.getCountry(),
                    request.getCurrency(),
                    operation);
        String gatewayId = ((LegacyGatewayAdapter) adapter).legacyGatewayId();
        GatewayChargeDetails fees =
                DoPayGateway.getGatewayChargeDetailsById(jdbc, gatewayId, merchant.getId());
        if (fees == null)
            throw new PaymentGatewayException("Payment channel fees are not configured");
        boolean payout = "PAYOUT".equals(operation);
        Transaction tx = new Transaction();
        tx.setTx_unique_id(UUID.randomUUID().toString());
        tx.setTx_merchant_ref(request.getReference());
        tx.setMerchant_id(merchant.getId().toString());
        tx.setGateway_id(gatewayId);
        tx.setOriginalAmountDecimal(amount);
        tx.setEnvironment(environment);
        tx.setCurrency(request.getCurrency().toUpperCase(Locale.ROOT));
        tx.setTx_type(payout ? Transaction.TX_TYPE_PAYOUT : Transaction.TX_TYPE_PAYIN);
        tx.setStatus("PENDING");
        tx.setPayer_number(account);
        tx.setCallback_url(Objects.toString(request.getCallbackUrl(), ""));
        tx.setTx_description(request.getDescription());
        tx.setTx_merchant_description(request.getDescription());
        tx.setCharging_method(
                payout
                        ? fees.getCustomerOutboundChargeMethod()
                        : fees.getCustomerInboundChargeMethod());
        tx.setChargesDecimal(
                payout
                        ? DoPayGateway.getCustomerOutboundChargesDecimal(amount, fees)
                        : DoPayGateway.getCustomerInboundChargesDecimal(amount, fees));
        tx.setTxCostDecimal(
                payout
                        ? DoPayGateway.getCostOfOutboundChargesDecimal(amount, fees)
                        : DoPayGateway.getCostOfInboundChargesDecimal(amount, fees));
        for (BigDecimal monetaryValue : List.of(tx.getChargesDecimal(), tx.getTxCostDecimal())) {
            if (BigDecimal.valueOf(monetaryValue.doubleValue()).compareTo(monetaryValue) != 0)
                throw new PaymentGatewayException(
                        "Fees exceed the supported merchant balance precision");
        }
        String providerReference =
                "mtn_momo".equals(adapter.channelCode())
                        ? UUID.randomUUID().toString()
                        : tx.getTx_unique_id();
        tx.setTx_gateway_ref(providerReference);
        p.addValue("tx", tx.getTx_unique_id())
                .addValue("batch", batchId)
                .addValue("beneficiary", beneficiaryId)
                .addValue("gateway", gatewayId)
                .addValue("amount", amount)
                .addValue("environment", environment)
                .addValue("currency", tx.getCurrency())
                .addValue("type", tx.getTx_type())
                .addValue("account", account)
                .addValue("description", request.getDescription())
                .addValue("callback", tx.getCallback_url())
                .addValue("charges", tx.getChargesDecimal())
                .addValue("cost", tx.getTxCostDecimal())
                .addValue("method", tx.getCharging_method())
                .addValue("provider", providerReference);
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(
                "INSERT INTO merchant_transactions_log (merchant_batch_transactions_log_id,beneficiary_id,merchant_id,gateway_id,original_amount,currency,execution_environment,tx_type,charges,tx_cost,charging_method,tx_unique_id,tx_gateway_ref,tx_merchant_ref,payer_number,tx_description,tx_merchant_description,callback_url,status,callback_status,tx_request_trace,tx_update_trace,originate_ip,safaricom_request_reference) VALUES (:batch,:beneficiary,:merchant,:gateway,:amount,:currency,:environment,:type,:charges,:cost,:method,:tx,:provider,:reference,:account,:description,:description,:callback,'PENDING','PENDING','','','','')",
                p,
                key,
                new String[] {"id"});
        tx.setId(Objects.requireNonNull(key.getKey()).longValue());
        if (payout && "PRODUCTION".equals(environment)) ledger.reservePayout(tx, merchant);
        ProviderTreasuryService.Reservation reservation =
                treasury.beginShared(
                        context,
                        merchant,
                        adapter.channelCode(),
                        environment,
                        amount,
                        request.getReference());
        Map<String, String> metadata = new LinkedHashMap<>();
        context.credentials()
                .forEach(
                        (k, v) -> {
                            if (v != null) metadata.put(k, String.valueOf(v));
                        });
        metadata.put("currency", tx.getCurrency());
        metadata.put("country", request.getCountry());
        metadata.put("gatewayState", environment);
        metadata.put("credentialEnvironment", environment);
        metadata.put("credentialSource", context.source());
        metadata.put("providerReference", providerReference);
        p.addValue("channel", adapter.channelCode())
                .addValue("environment", environment)
                .addValue("operation", operation)
                .addValue("country", request.getCountry())
                .addValue("hash", hash)
                .addValue("source", context.source())
                .addValue("credentials", crypto.encrypt(encode(metadata)))
                .addValue("treasury", reservation == null ? null : reservation.id());
        jdbc.update(
                "INSERT INTO mobile_money_executions (transaction_id,merchant_id,merchant_reference,channel_code,environment,operation,country_code,currency_code,request_hash,provider_reference,credential_source,credential_snapshot,treasury_reservation_id) VALUES (:tx,:merchant,:reference,:channel,:environment,:operation,:country,:currency,:hash,:provider,:source,:credentials,:treasury)",
                p);
        if (reservation != null) treasury.completeShared(reservation, "PENDING", providerReference);
        return new Prepared(
                tx,
                new PaymentGatewayRequest(
                        merchant.getAccount_number(),
                        account,
                        amount,
                        tx.getTx_unique_id(),
                        request.getDescription(),
                        request.getCallbackUrl(),
                        metadata),
                null);
    }

    /**
     * Status, canonical ledger, compatibility projection, treasury and outboxes commit together.
     */
    public void apply(String transactionId, GateWayResponse response) {
        transactions.executeWithoutResult(
                ignored -> {
                    MapSqlParameterSource p = new MapSqlParameterSource("tx", transactionId);
                    List<Map<String, Object>> rows =
                            jdbc.queryForList(
                                    "SELECT * FROM mobile_money_executions WHERE transaction_id=:tx FOR UPDATE",
                                    p);
                    if (rows.size() != 1)
                        throw new PaymentGatewayException("Payment execution was not found");
                    Map<String, Object> execution = rows.get(0);
                    Transaction tx = load(transactionId);
                    if (terminal(tx.getStatus())) return;
                    String status =
                            normalize(response == null ? null : response.getTransactionStatus());
                    String financialReference =
                            response == null ? "" : Objects.toString(response.getNetworkId(), "");
                    if (!financialReference.isBlank()
                            && !financialReference.equals(execution.get("provider_reference"))) {
                        p.addValue("financial", financialReference);
                        jdbc.update(
                                "UPDATE mobile_money_executions SET financial_reference=:financial WHERE transaction_id=:tx",
                                p);
                        if (terminal(status))
                            jdbc.update(
                                    "UPDATE merchant_transactions_log SET tx_gateway_ref=:financial WHERE tx_unique_id=:tx",
                                    p);
                    }
                    Merchant merchant =
                            terminal(status)
                                    ? Common.getMerchantById(tx.getMerchant_id(), jdbc)
                                    : null;
                    if (terminal(status) && "PRODUCTION".equals(execution.get("environment"))) {
                        ledger.applyTerminalProviderOutcome(tx, status, merchant);
                        if ("SUCCESSFUL".equals(status)) projectStatement(tx);
                    }
                    Object reservation = execution.get("treasury_reservation_id");
                    if (terminal(status) && reservation instanceof Number number)
                        treasury.resolvePending(
                                number.longValue(),
                                "SUCCESSFUL".equals(status),
                                String.valueOf(execution.get("provider_reference")),
                                "VERIFIED_PROVIDER");
                    p.addValue("status", status)
                            .addValue(
                                    "trace",
                                    response == null
                                            ? "httpStatus=0"
                                            : "httpStatus=" + response.getHttpStatus());
                    jdbc.update(
                            "UPDATE merchant_transactions_log SET status=:status,tx_update_trace=:trace WHERE tx_unique_id=:tx AND status NOT IN ('SUCCESSFUL','FAILED')",
                            p);
                    jdbc.update(
                            "UPDATE mobile_money_executions SET last_polled_at=CURRENT_TIMESTAMP,next_poll_at=TIMESTAMPADD(SECOND,60,CURRENT_TIMESTAMP) WHERE transaction_id=:tx",
                            p);
                    tx.setStatus(status);
                    if (terminal(status)) {
                        if (routing != null) {
                            Object createdAt = execution.get("created_at");
                            long elapsed =
                                    createdAt instanceof java.sql.Timestamp timestamp
                                            ? Math.max(
                                                    0,
                                                    System.currentTimeMillis()
                                                            - timestamp.getTime())
                                            : 0;
                            routing.recordMobileMoneyOutcome(
                                    merchant.getId(),
                                    tx.getTx_merchant_ref(),
                                    String.valueOf(execution.get("operation")),
                                    String.valueOf(execution.get("environment")),
                                    String.valueOf(execution.get("channel_code")),
                                    String.valueOf(execution.get("country_code")),
                                    tx.getCurrency(),
                                    "SUCCESSFUL".equals(status),
                                    elapsed);
                        }
                        PaymentRequest request = new PaymentRequest();
                        request.setMerchantNumber(merchant.getAccount_number());
                        request.setReference(tx.getTx_merchant_ref());
                        request.setAmount(tx.getOriginalAmountDecimal().toPlainString());
                        request.setCurrency(tx.getCurrency());
                        request.setChannel(String.valueOf(execution.get("channel_code")));
                        if ("PRODUCTION".equals(execution.get("environment"))) {
                            usage.recordPaymentSettled(merchant, request, tx);
                        }
                        webhooks.enqueue(
                                merchant.getId(),
                                ("PAYOUT".equals(execution.get("operation"))
                                                ? "payout."
                                                : "payment.")
                                        + ("SUCCESSFUL".equals(status) ? "completed" : "failed"),
                                transactionId,
                                encode(
                                        Map.of(
                                                "reference",
                                                tx.getTx_merchant_ref(),
                                                "transactionId",
                                                transactionId,
                                                "status",
                                                status,
                                                "amount",
                                                request.getAmount(),
                                                "currency",
                                                tx.getCurrency(),
                                                "environment",
                                                execution.get("environment"))));
                        // The established callback worker owns delivery/retries for this canonical
                        // row.
                        jdbc.update(
                                "UPDATE merchant_transactions_log SET callback_status='PENDING' WHERE tx_unique_id=:tx",
                                p);
                    }
                });
    }

    private void projectStatement(Transaction tx) {
        boolean payout = Transaction.TX_TYPE_PAYOUT.equals(tx.getTx_type());
        statement(tx, tx.getOriginalAmountDecimal(), payout ? "DR" : "CR", tx.getTx_type());
        if (tx.getChargesDecimal().signum() > 0)
            statement(
                    tx,
                    tx.getChargesDecimal(),
                    "DR",
                    payout ? Transaction.TX_TYPE_PAYOUT_CHARGE : Transaction.TX_TYPE_PAYIN_CHARGE);
    }

    private void statement(Transaction tx, BigDecimal amount, String direction, String narrative) {
        Statement statement = new Statement();
        statement.setTransactions_log_id(tx.getId());
        statement.setAmount(amount.doubleValue());
        statement.setGateway_id(tx.getGateway_id());
        statement.setNarritive(narrative);
        statement.setMerchant_id(Long.valueOf(tx.getMerchant_id()));
        statement.setDescription(tx.getTx_description());
        statement.setRecorded_by("SYSTEM");
        statement.setTx_type(direction);
        String outcome =
                Common.recordStatementTx(
                        statement,
                        Balance.getBalanceTypeByGatewayId(tx.getGateway_id())[0],
                        jdbc,
                        transactionManager);
        if (!"success".equals(outcome))
            throw new PaymentGatewayException("Merchant balance projection failed");
    }

    public Transaction load(String id) {
        Transaction tx = Common.getTxByRef(id, jdbc);
        if (tx == null) throw new PaymentGatewayException("Payment transaction was not found");
        return tx;
    }

    public Map<String, Object> snapshot(Map<String, Object> execution) {
        try {
            return json.readValue(
                    crypto.decrypt(String.valueOf(execution.get("credential_snapshot"))),
                    new TypeReference<Map<String, Object>>() {});
        } catch (Exception ex) {
            throw new PaymentGatewayException("Payment credential snapshot is unavailable");
        }
    }

    public static String fingerprint(
            PaymentRequest request, String operation, String environment, String channel) {
        String party =
                "PAYOUT".equals(operation)
                        ? request.getPayee().getValue()
                        : request.getPayer().getValue();
        return CanonicalRequestSigner.sha256Hex(
                String.join(
                        "\n",
                        request.getMerchantNumber(),
                        request.getReference(),
                        operation,
                        environment,
                        channel,
                        MoneyAmount.of(request.getAmount()).asBigDecimal().toPlainString(),
                        request.getCurrency().toUpperCase(Locale.ROOT),
                        request.getCountry().toUpperCase(Locale.ROOT),
                        party,
                        Objects.toString(request.getCallbackUrl(), ""),
                        Objects.toString(request.getDescription(), ""),
                        request.getMetadata() == null
                                ? "AUTO"
                                : request.getMetadata().getOrDefault("credentialSource", "AUTO")));
    }

    private PaymentResult result(
            PaymentRequest request, Transaction tx, String channel, String environment) {
        PaymentResult result = new PaymentResult();
        result.setReference(request.getReference());
        result.setTransactionId(tx.getTx_unique_id());
        result.setStatus(tx.getStatus());
        result.setChannel(channel);
        result.setEnvironment(environment);
        result.setCurrency(tx.getCurrency());
        result.setMessage(
                terminal(tx.getStatus())
                        ? "Provider-confirmed payment outcome"
                        : "Payment pending provider verification");
        return result;
    }

    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new PaymentGatewayException("Unable to persist payment evidence");
        }
    }

    private static boolean terminal(String status) {
        return "SUCCESSFUL".equals(status) || "FAILED".equals(status);
    }

    private static String normalize(String status) {
        return terminal(status) ? status : "UNDETERMINED".equals(status) ? status : "PENDING";
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank())
            throw new PaymentGatewayException(field + " is required");
    }

    private record Prepared(
            Transaction transaction, PaymentGatewayRequest gatewayRequest, PaymentResult replay) {}
}
