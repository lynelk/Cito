package net.citotech.cito.gateway;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Tag("docker")
@Testcontainers
class MobileMoneyRecoveryLeaseStoreTestcontainersTest {
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");
    private NamedParameterJdbcTemplate jdbc;
    private MobileMoneyRecoveryLeaseStore store;

    @BeforeEach
    void setup() throws Exception {
        var ds =
                new DriverManagerDataSource(
                        MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new NamedParameterJdbcTemplate(ds);
        jdbc.getJdbcTemplate().execute("DROP TABLE IF EXISTS mobile_money_executions");
        jdbc.getJdbcTemplate().execute("DROP TABLE IF EXISTS merchant_transactions_log");
        jdbc.getJdbcTemplate()
                .execute(
                        "CREATE TABLE mobile_money_executions (transaction_id VARCHAR(64) PRIMARY KEY, environment VARCHAR(16), next_poll_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, last_polled_at TIMESTAMP NULL)");
        jdbc.getJdbcTemplate()
                .execute(
                        "CREATE TABLE merchant_transactions_log (tx_unique_id VARCHAR(64) PRIMARY KEY, status VARCHAR(32))");
        jdbc.getJdbcTemplate()
                .execute(
                        Files.readString(
                                Path.of(
                                        "src/main/resources/db/migration/V129__canonical_mobile_money_recovery_leases.sql")));
        jdbc.update(
                "INSERT INTO mobile_money_executions(transaction_id,environment) VALUES ('sandbox','SANDBOX'),('production','PRODUCTION')",
                Map.of());
        jdbc.update(
                "INSERT INTO merchant_transactions_log VALUES ('sandbox','PENDING'),('production','PENDING')",
                Map.of());
        store = new MobileMoneyRecoveryLeaseStore(jdbc);
    }

    @Test
    void competingReplicasAndRestartShareOneDurableClaim() throws Exception {
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            var first =
                    pool.submit(
                            () -> {
                                start.await();
                                return store.claim("sandbox", "SANDBOX");
                            });
            var second =
                    pool.submit(
                            () -> {
                                start.await();
                                return new MobileMoneyRecoveryLeaseStore(jdbc)
                                        .claim("sandbox", "SANDBOX");
                            });
            start.countDown();
            String a = first.get(5, java.util.concurrent.TimeUnit.SECONDS);
            String b = second.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertThat((a == null) != (b == null)).isTrue();
            assertThat(new MobileMoneyRecoveryLeaseStore(jdbc).due("SANDBOX", 10)).isEmpty();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void expiredWorkerCannotEraseNewerLeaseOrAlterItsSchedule() {
        String first = store.claim("sandbox", "SANDBOX");
        jdbc.update(
                "UPDATE mobile_money_executions SET recovery_claim_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE transaction_id='sandbox'",
                Map.of());
        String second = store.claim("sandbox", "SANDBOX");
        assertThat(second).isNotNull().isNotEqualTo(first);
        store.retry("sandbox", first, "STALE_WORKER");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT recovery_claim_token FROM mobile_money_executions WHERE transaction_id='sandbox'",
                                Map.of(),
                                String.class))
                .isEqualTo(second);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT recovery_last_code FROM mobile_money_executions WHERE transaction_id='sandbox'",
                                Map.of(),
                                String.class))
                .isNull();
    }

    @Test
    void sandboxCannotClaimProductionAndUnknownRuntimeFailsClosed() {
        assertThat(store.claim("production", "SANDBOX")).isNull();
        assertThat(store.due("SANDBOX", 20)).hasSize(1);
        assertThatThrownBy(() -> store.claim("sandbox", "TYPO"))
                .isInstanceOf(PaymentGatewayException.class);
    }

    @Test
    void callbackFloodCannotContinuouslyAdvanceOrFinalizeWork() {
        store.signal("sandbox");
        Object first =
                jdbc.queryForMap(
                                "SELECT recovery_last_signal_at FROM mobile_money_executions WHERE transaction_id='sandbox'",
                                Map.of())
                        .get("recovery_last_signal_at");
        store.signal("sandbox");
        assertThat(
                        jdbc.queryForMap(
                                        "SELECT recovery_last_signal_at FROM mobile_money_executions WHERE transaction_id='sandbox'",
                                        Map.of())
                                .get("recovery_last_signal_at"))
                .isEqualTo(first);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM merchant_transactions_log WHERE tx_unique_id='sandbox'",
                                Map.of(),
                                String.class))
                .isEqualTo("PENDING");
        jdbc.update(
                "UPDATE merchant_transactions_log SET status='SUCCESSFUL' WHERE tx_unique_id='sandbox'",
                Map.of());
        assertThat(store.claim("sandbox", "SANDBOX")).isNull();
        assertThat(store.due("SANDBOX", 20)).isEmpty();
    }
}
