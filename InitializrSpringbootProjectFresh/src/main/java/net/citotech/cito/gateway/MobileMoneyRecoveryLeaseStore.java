package net.citotech.cito.gateway;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/** Fenced leases on the existing canonical journal. No network calls or financial writes. */
@Component
public class MobileMoneyRecoveryLeaseStore {
    private final NamedParameterJdbcTemplate jdbc;

    public MobileMoneyRecoveryLeaseStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> due(String runtimeEnvironment, int limit) {
        requireRuntime(runtimeEnvironment);
        return jdbc.queryForList(
                "SELECT e.* FROM mobile_money_executions e JOIN merchant_transactions_log t ON t.tx_unique_id=e.transaction_id "
                        + "WHERE t.status IN ('PENDING','UNDETERMINED') AND e.next_poll_at<=CURRENT_TIMESTAMP "
                        + "AND (e.recovery_claim_until IS NULL OR e.recovery_claim_until<CURRENT_TIMESTAMP(6)) "
                        + "AND (:runtime='PRODUCTION' OR e.environment='SANDBOX') "
                        + "ORDER BY e.next_poll_at,e.transaction_id LIMIT "
                        + Math.max(1, Math.min(limit, 20)),
                new MapSqlParameterSource("runtime", runtimeEnvironment));
    }

    public String claim(String id, String runtimeEnvironment) {
        requireRuntime(runtimeEnvironment);
        String token = UUID.randomUUID().toString();
        int changed =
                jdbc.update(
                        "UPDATE mobile_money_executions e SET recovery_claim_token=:claim,"
                                + "recovery_claim_until=TIMESTAMPADD(SECOND,90,CURRENT_TIMESTAMP(6)),"
                                + "recovery_attempt_count=recovery_attempt_count+1 "
                                + "WHERE transaction_id=:tx AND next_poll_at<=CURRENT_TIMESTAMP "
                                + "AND (recovery_claim_until IS NULL OR recovery_claim_until<CURRENT_TIMESTAMP(6)) "
                                + "AND (:runtime='PRODUCTION' OR environment='SANDBOX') "
                                + "AND EXISTS (SELECT 1 FROM merchant_transactions_log t WHERE t.tx_unique_id=e.transaction_id "
                                + "AND t.status IN ('PENDING','UNDETERMINED'))",
                        new MapSqlParameterSource("tx", id)
                                .addValue("claim", token)
                                .addValue("runtime", runtimeEnvironment));
        return changed == 1 ? token : null;
    }

    public void retry(String id, String claim, String code) {
        // Only the current owner can defer the row; an expired worker cannot erase a newer lease.
        jdbc.update(
                "UPDATE mobile_money_executions SET recovery_claim_token=NULL,recovery_claim_until=NULL,"
                        + "recovery_last_code=:code,last_polled_at=CURRENT_TIMESTAMP,"
                        + "next_poll_at=TIMESTAMPADD(SECOND,LEAST(1800,60*POW(2,LEAST(recovery_attempt_count,5))),CURRENT_TIMESTAMP) "
                        + "WHERE transaction_id=:tx AND recovery_claim_token=:claim "
                        + "AND recovery_claim_until>CURRENT_TIMESTAMP(6)",
                new MapSqlParameterSource("tx", id)
                        .addValue("claim", claim)
                        .addValue("code", code));
    }

    public void signal(String id) {
        jdbc.update(
                "UPDATE mobile_money_executions e SET next_poll_at=LEAST(next_poll_at,CURRENT_TIMESTAMP),"
                        + "recovery_last_signal_at=CURRENT_TIMESTAMP(6) WHERE transaction_id=:tx "
                        + "AND (recovery_last_signal_at IS NULL OR recovery_last_signal_at<TIMESTAMPADD(SECOND,-30,CURRENT_TIMESTAMP(6))) "
                        + "AND EXISTS (SELECT 1 FROM merchant_transactions_log t WHERE t.tx_unique_id=e.transaction_id "
                        + "AND t.status IN ('PENDING','UNDETERMINED'))",
                new MapSqlParameterSource("tx", id));
    }

    public static void requireRuntime(String environment) {
        if (!"SANDBOX".equals(environment) && !"PRODUCTION".equals(environment)) {
            throw new PaymentGatewayException("Mobile-money recovery environment is invalid");
        }
    }
}
