package net.citotech.cito.gateway;

import java.util.*;
import net.citotech.cito.Model.*;
import net.citotech.cito.money.MoneyAmount;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Callbacks are hints. Only bounded authenticated lookup plus a current fenced claim can settle.
 */
@Service
public class MobileMoneyRecoveryService {
    private final NamedParameterJdbcTemplate jdbc;
    private final MobileMoneyExecutionService executions;
    private final MtnMomoStatusClient mtn;
    private final MobileMoneyRecoveryLeaseStore leases;
    private final BoundedProviderVerification verification;
    private final String runtimeEnvironment;
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(MobileMoneyRecoveryService.class);

    public MobileMoneyRecoveryService(
            NamedParameterJdbcTemplate jdbc,
            MobileMoneyExecutionService executions,
            MtnMomoStatusClient mtn,
            MobileMoneyRecoveryLeaseStore leases,
            BoundedProviderVerification verification,
            @Value("${custom.gatewaystate:SANDBOX}") String runtimeEnvironment) {
        this.jdbc = jdbc;
        this.executions = executions;
        this.mtn = mtn;
        this.leases = leases;
        this.verification = verification;
        this.runtimeEnvironment = runtimeEnvironment.trim().toUpperCase(Locale.ROOT);
        MobileMoneyRecoveryLeaseStore.requireRuntime(this.runtimeEnvironment);
    }

    public boolean signal(String channel, String reference, String externalId) {
        if (!MobileMoneyExecutionService.managed(channel)
                || reference == null
                || reference.length() > 64) return false;
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT transaction_id FROM mobile_money_executions WHERE channel_code=:channel AND provider_reference=:reference "
                                + "AND (:runtime='PRODUCTION' OR environment='SANDBOX')",
                        new MapSqlParameterSource("channel", channel)
                                .addValue("reference", reference)
                                .addValue("runtime", runtimeEnvironment));
        if (rows.isEmpty()) return false;
        if (rows.size() != 1) throw new PaymentGatewayException("Provider reference is ambiguous");
        Map<String, Object> row = rows.get(0);
        if (externalId != null
                && !externalId.isBlank()
                && !externalId.equals(row.get("transaction_id")))
            throw new PaymentGatewayException("Provider external reference does not match payment");
        leases.signal(String.valueOf(row.get("transaction_id")));
        return true;
    }

    @Scheduled(fixedDelayString = "${cpay.mobile-money.status-poll.delay-ms:15000}")
    @SchedulerLock(name = "mobileMoneyRecovery", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1S")
    public void reconcilePending() {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(90);
        for (Map<String, Object> row : leases.due(runtimeEnvironment, 10)) {
            if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) break;
            String id = text(row, "transaction_id");
            String claim = leases.claim(id, runtimeEnvironment);
            if (claim == null) continue;
            try {
                GateWayResponse outcome = verification.execute(() -> readOnlyOutcome(row));
                executions.applyVerified(id, outcome, claim);
            } catch (RuntimeException error) {
                leases.retry(id, claim, "VERIFICATION_DEFERRED");
                log.warn(
                        "Mobile-money verification deferred for transaction {} ({})",
                        id,
                        error.getClass().getSimpleName());
            }
        }
    }

    GateWayResponse readOnlyOutcome(Map<String, Object> row) {
        String id = text(row, "transaction_id"), operation = text(row, "operation");
        if (!MobileMoneyExecutionService.managed(text(row, "channel_code"))
                || (!"COLLECT".equals(operation) && !"PAYOUT".equals(operation))
                || (!"PRODUCTION".equals(runtimeEnvironment)
                        && !"SANDBOX".equals(text(row, "environment")))) {
            throw new PaymentGatewayException("Recovery scope does not match this runtime");
        }
        Transaction transaction = executions.load(id);
        Map<String, Object> credentials = executions.snapshot(row);
        GateWayResponse response;
        if ("mtn_momo".equals(row.get("channel_code"))) {
            MtnMomoStatusClient.VerifiedStatus status =
                    mtn.verify(
                            operation,
                            text(row, "provider_reference"),
                            text(row, "environment"),
                            text(row, "country_code"),
                            text(row, "currency_code"),
                            credentials);
            if (!id.equals(status.externalId())
                    || !transaction.getCurrency().equalsIgnoreCase(status.currency())
                    || !transaction.getPayer_number().equals(status.account())
                    || status.amount() == null
                    || transaction
                                    .getOriginalAmountDecimal()
                                    .compareTo(MoneyAmount.of(status.amount()).asBigDecimal())
                            != 0)
                throw new PaymentGatewayException(
                        "Verified MTN commercial attributes do not match payment");
            response = new GateWayResponse();
            response.setTransactionStatus(status.status());
            response.setHttpStatus("200");
            response.setNetworkId(status.financialTransactionId());
        } else {
            AirtelOpenApiCredentialSchema.validateForOperation(
                    credentials,
                    text(row, "environment"),
                    text(row, "country_code"),
                    text(row, "currency_code"),
                    operation);
            AirtelMoneyOpenApiPaymentGateway gateway = new AirtelMoneyOpenApiPaymentGateway();
            gateway.setApiDetails(
                    text(credentials, "baseUrl"),
                    text(credentials, "clientId"),
                    text(credentials, "clientSecret"),
                    text(credentials, "apiPin"));
            gateway.setTransactionContext(
                    text(row, "environment"),
                    text(row, "country_code"),
                    text(row, "currency_code"));
            gateway.setEndpointDetails(
                    text(credentials, "tokenPath"),
                    text(credentials, "collectionPath"),
                    text(credentials, "payoutPath"),
                    text(credentials, "balancePath"),
                    text(credentials, "collectionStatusPath"),
                    text(credentials, "payoutStatusPath"));
            gateway.setSegment("PAYOUT".equals(operation) ? "disbursement" : "collection");
            response = gateway.checkStatus(id);
            if (response == null
                    || !"200".equals(response.getHttpStatus())
                    || !id.equals(gateway.getVerifiedTransactionId()))
                throw new PaymentGatewayException(
                        "Airtel status has not verified the submitted transaction id");
            gateway.requireMatchingCommercialAttributes(
                    transaction.getOriginalAmountDecimal(), transaction.getCurrency());
        }
        return response;
    }

    private static String text(Map<String, Object> row, String key) {
        return Objects.toString(row.get(key), "");
    }
}
