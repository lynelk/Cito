package net.citotech.cito.treasury;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.citotech.cito.Common;
import net.citotech.cito.Model.Merchant;
import net.citotech.cito.SettingsRegistry;
import net.citotech.cito.admin.AdminPermissionService;
import net.citotech.cito.api.v2.AdapterNativePaymentService;
import net.citotech.cito.api.v2.dto.PaymentPartyRequest;
import net.citotech.cito.api.v2.dto.PaymentRequest;
import net.citotech.cito.api.v2.dto.PaymentResult;
import net.citotech.cito.gateway.PaymentGatewayException;
import net.citotech.cito.merchant.MerchantChannelCryptoService;
import net.citotech.cito.security.AdminMfaService;
import net.citotech.cito.sharedprovider.SharedProviderAccessService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Audited, explicitly shared-credential provider tests initiated from the admin console. */
@Service
public class ProviderLiveTestService {
    private static final String PRODUCTION = "PRODUCTION";

    private final NamedParameterJdbcTemplate jdbc;
    private final AdapterNativePaymentService payments;
    private final AdminPermissionService permissions;
    private final AdminMfaService mfa;
    private final MerchantChannelCryptoService crypto;

    public ProviderLiveTestService(
            NamedParameterJdbcTemplate jdbc,
            AdapterNativePaymentService payments,
            AdminPermissionService permissions,
            AdminMfaService mfa,
            MerchantChannelCryptoService crypto) {
        this.jdbc = jdbc;
        this.payments = payments;
        this.permissions = permissions;
        this.mfa = mfa;
        this.crypto = crypto;
    }

    public List<Map<String, Object>> merchants() {
        return jdbc.queryForList(
                "SELECT id, name, account_number AS merchantNumber, status FROM merchants WHERE"
                        + " status='ACTIVE' AND account_number NOT LIKE 'CITO-%' ORDER BY name,"
                        + " account_number",
                Map.of());
    }

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT t.id, t.test_reference AS testReference, t.idempotency_key AS"
                                + " idempotencyKey, t.merchant_id AS merchantId, m.name AS"
                                + " merchantName, m.account_number AS merchantNumber, t.channel_code AS"
                                + " channelCode, t.credential_source AS credentialSource,"
                                + " t.environment, t.country_code AS countryCode, t.currency_code AS"
                                + " currencyCode, t.operation, t.amount, t.party_mask AS partyMask,"
                                + " t.status, t.provider_reference AS providerReference,"
                                + " t.result_message AS resultMessage, t.requested_by AS requestedBy,"
                                + " t.requested_at AS requestedAt, t.approved_by AS approvedBy,"
                                + " t.approved_at AS approvedAt, t.executed_by AS executedBy,"
                                + " t.executed_at AS executedAt, t.completed_at AS completedAt,"
                                + " r.status AS treasuryStatus FROM provider_live_tests t JOIN"
                                + " merchants m ON m.id=t.merchant_id LEFT JOIN"
                                + " provider_treasury_reservations r ON"
                                + " r.merchant_reference=t.test_reference ORDER BY t.requested_at DESC"
                                + " LIMIT 200",
                        Map.of());
        for (Map<String, Object> row : rows) {
            row.put("events", events(number(row.get("id"))));
        }
        return rows;
    }

    public Map<String, Object> request(Map<String, Object> body, String actor) {
        String operation = operation(body.get("operation"));
        permissions.require(
                "COLLECT".equals(operation) ? "LIVE_COLLECTION_TEST" : "LIVE_DISBURSEMENT_TEST",
                "provider-live-test-request",
                operation);
        String who = required(actor, "actor");
        String environment = upper(body.get("environment"), "environment");
        if (!List.of("SANDBOX", "PRODUCTION").contains(environment))
            throw new PaymentGatewayException("environment must be SANDBOX or PRODUCTION");
        requireProductionControls(environment, body, who);
        String idempotency = required(body.get("idempotencyKey"), "idempotencyKey");
        long merchantId = number(body.get("merchantId"));
        String channel = upper(body.get("channelCode"), "channelCode").toLowerCase(Locale.ROOT);
        if (!channel.equals("mtn_momo") && !channel.equals("airtel_open_api")) {
            throw new PaymentGatewayException("channelCode must be mtn_momo or airtel_open_api");
        }
        String country = upper(body.get("countryCode"), "countryCode");
        String currency = upper(body.get("currencyCode"), "currencyCode");
        if ("mtn_momo".equals(channel)
                && (!"UG".equals(country)
                        || !("SANDBOX".equals(environment) ? "EUR" : "UGX").equals(currency)))
            throw new PaymentGatewayException(
                    "MTN portal tests require UG/EUR in sandbox or UG/UGX in production");
        Merchant merchant = merchant(merchantId);
        BigDecimal amount = amount(body.get("amount"));
        BigDecimal maximum = SettingsRegistry.getDecimal("provider_live_test_max_amount", jdbc);
        if (amount.compareTo(maximum) > 0) {
            throw new PaymentGatewayException(
                    "Live test amount exceeds the configured maximum of "
                            + maximum.toPlainString());
        }
        String party = normalizeParty(body.get("party"));
        List<Map<String, Object>> prior =
                jdbc.queryForList(
                        "SELECT * FROM provider_live_tests WHERE idempotency_key=:key",
                        new MapSqlParameterSource("key", idempotency));
        if (!prior.isEmpty()) {
            Map<String, Object> old = prior.get(0);
            if (number(old.get("merchant_id")) != merchantId
                    || !channel.equals(old.get("channel_code"))
                    || !environment.equals(old.get("environment"))
                    || !country.equals(old.get("country_code"))
                    || !currency.equals(old.get("currency_code"))
                    || !operation.equals(old.get("operation"))
                    || amount.compareTo(new BigDecimal(String.valueOf(old.get("amount")))) != 0
                    || !party.equals(crypto.decrypt(String.valueOf(old.get("party_payload")))))
                throw new PaymentGatewayException(
                        "Live-test idempotency key conflicts with the original test");
            return byId(number(old.get("id")));
        }

        String reference = "LIVE-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        String initial = "PAYOUT".equals(operation) ? "PENDING_APPROVAL" : "QUEUED";
        MapSqlParameterSource parameters =
                new MapSqlParameterSource()
                        .addValue("reference", reference)
                        .addValue("idempotency", idempotency)
                        .addValue("merchant", merchantId)
                        .addValue("channel", channel)
                        .addValue("environment", environment)
                        .addValue("country", country)
                        .addValue("currency", currency)
                        .addValue("operation", operation)
                        .addValue("amount", amount)
                        .addValue("party", crypto.encrypt(party))
                        .addValue("mask", maskParty(party))
                        .addValue("status", initial)
                        .addValue("actor", who);
        jdbc.update(
                "INSERT INTO provider_live_tests"
                        + " (test_reference,idempotency_key,merchant_id,channel_code,credential_source,"
                        + " environment,country_code,currency_code,operation,amount,party_payload,party_mask,status,requested_by)"
                        + " VALUES (:reference,:idempotency,:merchant,:channel,'PLATFORM_SHARED',"
                        + " :environment,:country,:currency,:operation,:amount,:party,:mask,:status,:actor)",
                parameters);
        long id =
                jdbc.queryForObject(
                        "SELECT id FROM provider_live_tests WHERE test_reference=:reference",
                        parameters,
                        Long.class);
        event(id, "REQUESTED", initial, "Live provider test requested", who);
        if ("COLLECT".equals(operation)) execute(id, merchant, party, who);
        return byId(id);
    }

    @Transactional
    public Map<String, Object> approve(long id, Map<String, Object> body, String actor) {
        permissions.require(
                "LIVE_DISBURSEMENT_APPROVE", "provider-live-test-approve", "live-test:" + id);
        String who = required(actor, "actor");
        Map<String, Object> row = locked(id);
        if (!"PAYOUT".equals(row.get("operation"))
                || !"PENDING_APPROVAL".equals(row.get("status"))) {
            throw new PaymentGatewayException("Only pending payout tests can be approved");
        }
        if (who.equalsIgnoreCase(String.valueOf(row.get("requested_by")))) {
            throw new PaymentGatewayException(
                    "Maker-checker violation: requester cannot approve the same payout test");
        }
        requireProductionControls(String.valueOf(row.get("environment")), body, who);
        jdbc.update(
                "UPDATE provider_live_tests SET status='APPROVED', approved_by=:actor,"
                        + " approved_at=CURRENT_TIMESTAMP(6) WHERE id=:id",
                new MapSqlParameterSource().addValue("id", id).addValue("actor", who));
        event(id, "APPROVED", "APPROVED", "Payout test approved by a different operator", who);
        Merchant merchant = merchant(number(row.get("merchant_id")));
        String party = crypto.decrypt(String.valueOf(row.get("party_payload")));
        try {
            execute(id, merchant, party, who);
        } catch (RuntimeException ignored) {
            // execute() has persisted the sanitized failure and event. Return that audited state
            // so the outer maker-checker transaction commits rather than erasing the evidence.
        }
        return byId(id);
    }

    private void execute(long id, Merchant merchant, String party, String actor) {
        Map<String, Object> row = locked(id);
        jdbc.update(
                "UPDATE provider_live_tests SET status='PROCESSING', executed_by=:actor,"
                        + " executed_at=CURRENT_TIMESTAMP(6) WHERE id=:id",
                new MapSqlParameterSource().addValue("id", id).addValue("actor", actor));
        event(id, "EXECUTION_STARTED", "PROCESSING", "Provider request submitted", actor);
        try {
            PaymentRequest request = paymentRequest(row, merchant, party);
            PaymentResult result =
                    "COLLECT".equals(row.get("operation"))
                            ? payments.collect(
                                    request, merchant, String.valueOf(row.get("environment")))
                            : payments.payout(
                                    request, merchant, String.valueOf(row.get("environment")));
            String status = finalStatus(result == null ? null : result.getStatus());
            jdbc.update(
                    "UPDATE provider_live_tests SET status=:status, provider_reference=:provider,"
                            + " result_message=:message, completed_at=CASE WHEN :terminal=1 THEN"
                            + " CURRENT_TIMESTAMP(6) ELSE NULL END WHERE id=:id",
                    new MapSqlParameterSource()
                            .addValue("id", id)
                            .addValue("status", status)
                            .addValue("provider", result == null ? null : result.getTransactionId())
                            .addValue(
                                    "message",
                                    result == null ? "No provider response" : result.getMessage())
                            .addValue("terminal", "PENDING_PROVIDER".equals(status) ? 0 : 1));
            event(id, "PROVIDER_RESPONSE", status, safeMessage(result), actor);
        } catch (RuntimeException e) {
            boolean awaitingProvider = true;
            if (net.citotech.cito.gateway.MobileMoneyExecutionService.managed(
                    String.valueOf(row.get("channel_code")))) {
                java.util.List<String> claims =
                        jdbc.queryForList(
                                "SELECT transaction_id FROM mobile_money_executions WHERE"
                                        + " merchant_id=:merchant AND merchant_reference=:reference FOR"
                                        + " UPDATE",
                                new MapSqlParameterSource("merchant", merchant.getId())
                                        .addValue("reference", row.get("test_reference")),
                                String.class);
                awaitingProvider = !claims.isEmpty();
            }
            String status = awaitingProvider ? "PENDING_PROVIDER" : "FAILED";
            String message =
                    awaitingProvider
                            ? "Execution outcome awaits canonical recovery"
                            : "Payment rejected before provider submission; check credential"
                                    + " readiness, limits and funds";
            jdbc.update(
                    "UPDATE provider_live_tests SET status=:status, result_message=:message,"
                            + " completed_at=CASE WHEN :pending=1 THEN NULL ELSE CURRENT_TIMESTAMP(6)"
                            + " END WHERE id=:id",
                    new MapSqlParameterSource()
                            .addValue("id", id)
                            .addValue("status", status)
                            .addValue("message", message)
                            .addValue("pending", awaitingProvider ? 1 : 0));
            event(
                    id,
                    awaitingProvider ? "VERIFICATION_DEFERRED" : "EXECUTION_REJECTED",
                    status,
                    message,
                    actor);
        }
    }

    private PaymentRequest paymentRequest(
            Map<String, Object> row, Merchant merchant, String party) {
        PaymentRequest request = new PaymentRequest();
        request.setMerchantNumber(merchant.getAccount_number());
        request.setAmount(String.valueOf(row.get("amount")));
        request.setCurrency(String.valueOf(row.get("currency_code")));
        request.setCountry(String.valueOf(row.get("country_code")));
        request.setChannel(String.valueOf(row.get("channel_code")));
        request.setReference(String.valueOf(row.get("test_reference")));
        request.setDescription("CPay admin live " + row.get("operation") + " test");
        request.setMetadata(
                Map.of(
                        "credentialSource",
                        SharedProviderAccessService.PLATFORM_SHARED,
                        "environment",
                        String.valueOf(row.get("environment"))));
        PaymentPartyRequest paymentParty = new PaymentPartyRequest();
        paymentParty.setType("MSISDN");
        paymentParty.setValue(party);
        if ("COLLECT".equals(row.get("operation"))) request.setPayer(paymentParty);
        else request.setPayee(paymentParty);
        return request;
    }

    private Map<String, Object> byId(long id) {
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT t.id, t.test_reference AS testReference, t.idempotency_key AS"
                                + " idempotencyKey, t.merchant_id AS merchantId, m.name AS"
                                + " merchantName, m.account_number AS merchantNumber, t.channel_code AS"
                                + " channelCode, t.credential_source AS credentialSource,"
                                + " t.environment, t.country_code AS countryCode, t.currency_code AS"
                                + " currencyCode, t.operation, t.amount, t.party_mask AS partyMask,"
                                + " t.status, t.provider_reference AS providerReference,"
                                + " t.result_message AS resultMessage, t.requested_by AS requestedBy,"
                                + " t.requested_at AS requestedAt, t.approved_by AS approvedBy,"
                                + " t.approved_at AS approvedAt, t.executed_by AS executedBy,"
                                + " t.executed_at AS executedAt, t.completed_at AS completedAt,"
                                + " r.status AS treasuryStatus FROM provider_live_tests t JOIN"
                                + " merchants m ON m.id=t.merchant_id LEFT JOIN"
                                + " provider_treasury_reservations r ON"
                                + " r.merchant_reference=t.test_reference WHERE t.id=:id",
                        new MapSqlParameterSource("id", id));
        if (rows.isEmpty()) throw new PaymentGatewayException("Live provider test was not found");
        Map<String, Object> result = new LinkedHashMap<>(rows.get(0));
        result.put("events", events(id));
        return result;
    }

    private List<Map<String, Object>> events(long id) {
        return jdbc.queryForList(
                "SELECT sequence_no AS sequenceNumber, event_type AS eventType, status, message,"
                        + " actor, created_at AS createdAt FROM provider_live_test_events"
                        + " WHERE live_test_id=:id ORDER BY sequence_no",
                new MapSqlParameterSource("id", id));
    }

    private Map<String, Object> locked(long id) {
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT * FROM provider_live_tests WHERE id=:id FOR UPDATE",
                        new MapSqlParameterSource("id", id));
        if (rows.isEmpty()) throw new PaymentGatewayException("Live provider test was not found");
        return rows.get(0);
    }

    @org.springframework.scheduling.annotation.Scheduled(
            fixedDelayString = "${cpay.provider-live-test.sync-ms:30000}")
    @net.javacrumbs.shedlock.spring.annotation.SchedulerLock(
            name = "providerLiveTestOutcomes",
            lockAtMostFor = "PT2M")
    @Transactional
    public void synchronizeOutcomes() {
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT l.id,t.status FROM provider_live_tests l JOIN"
                                + " mobile_money_executions e ON e.merchant_id=l.merchant_id AND"
                                + " CAST(e.merchant_reference AS BINARY)=CAST(l.test_reference AS"
                                + " BINARY) JOIN merchant_transactions_log t ON"
                                + " t.tx_unique_id=e.transaction_id WHERE l.status IN"
                                + " ('PENDING_PROVIDER','PROCESSING') AND t.status IN"
                                + " ('SUCCESSFUL','FAILED') ORDER BY l.id LIMIT 100 FOR UPDATE",
                        Map.of());
        rows.addAll(
                jdbc.queryForList(
                        "SELECT l.id,'FAILED' AS status FROM provider_live_tests l JOIN"
                                + " payout_approval_queue q ON q.merchant_id=l.merchant_id AND"
                                + " CAST(q.payout_reference AS BINARY)=CAST(l.test_reference AS BINARY)"
                                + " WHERE l.status IN ('PENDING_PROVIDER','PROCESSING') AND"
                                + " q.queue_status IN ('REJECTED','CANCELLED') AND NOT EXISTS (SELECT 1"
                                + " FROM mobile_money_executions e WHERE e.merchant_id=l.merchant_id"
                                + " AND CAST(e.merchant_reference AS BINARY)=CAST(l.test_reference AS"
                                + " BINARY)) ORDER BY l.id LIMIT 100 FOR UPDATE",
                        Map.of()));
        for (Map<String, Object> row : rows) {
            long id = number(row.get("id"));
            String status = "SUCCESSFUL".equals(row.get("status")) ? "SUCCEEDED" : "FAILED";
            jdbc.update(
                    "UPDATE provider_live_tests SET"
                            + " status=:status,completed_at=CURRENT_TIMESTAMP,result_message='Outcome"
                            + " resolved through payment or approval evidence' WHERE id=:id",
                    new MapSqlParameterSource("status", status).addValue("id", id));
            event(
                    id,
                    "OUTCOME_VERIFIED",
                    status,
                    "Canonical payment or approval evidence resolved",
                    "SYSTEM");
        }
    }

    private void event(long id, String type, String status, String message, String actor) {
        jdbc.update(
                "INSERT INTO provider_live_test_events"
                        + " (live_test_id,sequence_no,event_type,status,message,actor)"
                        + " SELECT :id,COALESCE(MAX(sequence_no),0)+1,:type,:status,:message,:actor"
                        + " FROM provider_live_test_events WHERE live_test_id=:id",
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("type", type)
                        .addValue("status", status)
                        .addValue("message", message)
                        .addValue("actor", actor));
    }

    private Merchant merchant(long id) {
        Merchant merchant = Common.getMerchantById(String.valueOf(id), jdbc);
        if (merchant == null || !"ACTIVE".equalsIgnoreCase(merchant.getStatus())) {
            throw new PaymentGatewayException("An active merchant is required for a live test");
        }
        return merchant;
    }

    private void requireProductionControls(
            String environment, Map<String, Object> body, String actor) {
        if (!PRODUCTION.equalsIgnoreCase(environment)) return;
        if (!Boolean.TRUE.equals(body.get("confirmProduction"))) {
            throw new PaymentGatewayException(
                    "Production confirmation is required because this test can move real money");
        }
        mfa.requireCode(actor, required(body.get("mfaCode"), "mfaCode"));
    }

    private String operation(Object value) {
        String operation = upper(value, "operation");
        if (!operation.equals("COLLECT") && !operation.equals("PAYOUT")) {
            throw new PaymentGatewayException("operation must be COLLECT or PAYOUT");
        }
        return operation;
    }

    private String finalStatus(String providerStatus) {
        String status = providerStatus == null ? "" : providerStatus.toUpperCase(Locale.ROOT);
        if (status.contains("SUCCESS") || status.equals("COMPLETED")) return "SUCCEEDED";
        if (status.contains("FAIL") || status.contains("REJECT")) return "FAILED";
        return "PENDING_PROVIDER";
    }

    private BigDecimal amount(Object value) {
        try {
            BigDecimal amount =
                    new BigDecimal(required(value, "amount")).setScale(4, RoundingMode.HALF_UP);
            if (amount.signum() <= 0) throw new NumberFormatException();
            return amount;
        } catch (NumberFormatException e) {
            throw new PaymentGatewayException("amount must be a positive monetary value");
        }
    }

    private String normalizeParty(Object value) {
        String party = required(value, "party").replace(" ", "").replace("-", "");
        if (!party.matches("^\\+?[0-9]{9,15}$")) {
            throw new PaymentGatewayException("party must be a valid international MSISDN");
        }
        return party;
    }

    private String maskParty(String party) {
        if (party.length() <= 6) return "****";
        return party.substring(0, 3) + "****" + party.substring(party.length() - 3);
    }

    private String safeMessage(PaymentResult result) {
        if (result == null) return "No provider response";
        String message = result.getMessage();
        return message == null || message.isBlank()
                ? "Provider status: " + result.getStatus()
                : message;
    }

    private long number(Object value) {
        if (value instanceof Number number) return number.longValue();
        try {
            return Long.parseLong(required(value, "merchantId"));
        } catch (NumberFormatException e) {
            throw new PaymentGatewayException("merchantId must be a number");
        }
    }

    private String upper(Object value, String field) {
        return required(value, field).toUpperCase(Locale.ROOT);
    }

    private String required(Object value, String field) {
        String text = value == null ? "" : String.valueOf(value).trim();
        if (text.isEmpty()) throw new PaymentGatewayException(field + " is required");
        return text;
    }
}
