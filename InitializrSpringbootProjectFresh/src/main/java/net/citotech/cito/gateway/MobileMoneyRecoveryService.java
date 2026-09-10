package net.citotech.cito.gateway;

import java.util.*;
import net.citotech.cito.Model.*;
import net.citotech.cito.money.MoneyAmount;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Callbacks are wake-up signals; authenticated provider lookup is the settlement evidence. */
@Service
public class MobileMoneyRecoveryService {
    private final NamedParameterJdbcTemplate jdbc;
    private final MobileMoneyExecutionService executions;
    private final MtnMomoStatusClient mtn;
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(MobileMoneyRecoveryService.class);

    public MobileMoneyRecoveryService(
            NamedParameterJdbcTemplate jdbc,
            MobileMoneyExecutionService executions,
            MtnMomoStatusClient mtn) {
        this.jdbc = jdbc;
        this.executions = executions;
        this.mtn = mtn;
    }

    public boolean signal(String channel, String reference, String externalId) {
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT * FROM mobile_money_executions WHERE channel_code=:channel AND provider_reference=:reference",
                        new MapSqlParameterSource("channel", channel)
                                .addValue("reference", reference));
        if (rows.isEmpty()) return false;
        if (rows.size() != 1) throw new PaymentGatewayException("Provider reference is ambiguous");
        Map<String, Object> row = rows.get(0);
        if (externalId != null
                && !externalId.isBlank()
                && !externalId.equals(row.get("transaction_id")))
            throw new PaymentGatewayException("Provider external reference does not match payment");
        // Persist a wake-up. The worker rate-limits network verification under its shared lock.
        jdbc.update(
                "UPDATE mobile_money_executions SET next_poll_at=LEAST(next_poll_at,CURRENT_TIMESTAMP) WHERE transaction_id=:tx",
                new MapSqlParameterSource("tx", row.get("transaction_id")));
        return true;
    }

    @Scheduled(fixedDelayString = "${cpay.mobile-money.status-poll.delay-ms:15000}")
    @SchedulerLock(name = "mobileMoneyRecovery", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1S")
    public void reconcilePending() {
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT e.* FROM mobile_money_executions e JOIN merchant_transactions_log t ON t.tx_unique_id=e.transaction_id WHERE t.status IN ('PENDING','UNDETERMINED') AND e.next_poll_at<=CURRENT_TIMESTAMP ORDER BY e.next_poll_at,e.transaction_id LIMIT 100",
                        new MapSqlParameterSource());
        for (Map<String, Object> row : rows) {
            // Advance even failed lookups so a broken credential cannot starve later payments.
            jdbc.update(
                    "UPDATE mobile_money_executions SET next_poll_at=DATE_ADD(CURRENT_TIMESTAMP,INTERVAL 60 SECOND),last_polled_at=CURRENT_TIMESTAMP WHERE transaction_id=:tx",
                    new MapSqlParameterSource("tx", row.get("transaction_id")));
            try {
                verify(row);
            } catch (RuntimeException e) {
                log.warn(
                        "Mobile-money verification deferred for transaction {} ({})",
                        row.get("transaction_id"),
                        e.getClass().getSimpleName());
            }
        }
    }

    public void verify(Map<String, Object> row) {
        String id = text(row, "transaction_id"), operation = text(row, "operation");
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
        executions.apply(id, response);
    }

    private static String text(Map<String, Object> row, String key) {
        return Objects.toString(row.get(key), "");
    }
}
