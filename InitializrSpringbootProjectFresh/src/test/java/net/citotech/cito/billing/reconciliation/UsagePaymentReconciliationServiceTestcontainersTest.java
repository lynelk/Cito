package net.citotech.cito.billing.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/**
 * Proves {@link UsagePaymentReconciliationService}'s {@code NOT EXISTS} queries actually flag a
 * seeded divergent row against a real MySQL schema - matching the {@code
 * DoubleEntryLedgerServiceTestcontainersTest} (audit K1) precedent: the mocked-JdbcTemplate {@code
 * UsagePaymentReconciliationServiceTest} proves the query composition, this proves the SQL actually
 * detects divergence against real data, which a mock cannot.
 *
 * <p>Requires a running Docker daemon, so it is tagged {@code "docker"} and excluded from the
 * default {@code mvn test}/{@code mvn verify} run. Run explicitly with: {@code mvn test
 * -Ddocker.tests.excludedGroups=}
 */
@Tag("docker")
@Testcontainers
class UsagePaymentReconciliationServiceTestcontainersTest {

    @Container
    private static final MySQLContainer MYSQL =
            new MySQLContainer("mysql:8.0.36")
                    .withCommand("--log-bin-trust-function-creators=1")
                    .withDatabaseName("cpay_test")
                    .withUsername("cpay")
                    .withPassword("cpay");

    private static DataSource dataSource;
    private static NamedParameterJdbcTemplate jdbcTemplate;

    @BeforeAll
    static void migrateSchema() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(MYSQL.getJdbcUrl());
        config.setUsername(MYSQL.getUsername());
        config.setPassword(MYSQL.getPassword());
        dataSource = new HikariDataSource(config);

        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbcTemplate = new NamedParameterJdbcTemplate(dataSource);
    }

    @AfterAll
    static void closeDataSource() {
        if (dataSource instanceof HikariDataSource hikari) {
            hikari.close();
        }
    }

    @Test
    void reconcilePaymentsFlagsATransactionLogRowWithNoMatchingUsageEvent() {
        Instant now = Instant.now();
        Instant windowStart = now.minus(1, ChronoUnit.HOURS);
        Instant windowEnd = now.plus(1, ChronoUnit.HOURS);

        insertMerchant(9001L);
        insertTransactionLog(9001L, "TX-RECON-RECONCILED", now);
        insertTransactionLog(9001L, "TX-RECON-DIVERGENT", now);
        insertUsageEvent("PAYMENT", "TX-RECON-RECONCILED", now);

        UsageReconciliationResult result =
                new UsagePaymentReconciliationService(jdbcTemplate)
                        .reconcilePayments(windowStart, windowEnd);

        assertThat(result.missingFromUsageEvents()).contains("TX-RECON-DIVERGENT");
        assertThat(result.missingFromUsageEvents()).doesNotContain("TX-RECON-RECONCILED");
        assertThat(result.isFullyReconciled()).isFalse();
    }

    private void insertMerchant(long id) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("id", id);
        p.addValue("account_number", "ACC-" + id);
        jdbcTemplate.update(
                "INSERT INTO merchants (id, name, account_number) VALUES (:id, 'Recon Test Merchant', :account_number)",
                p);
    }

    private void insertTransactionLog(long merchantId, String txUniqueId, Instant createdOn) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("merchant_id", merchantId);
        p.addValue("tx_unique_id", txUniqueId);
        p.addValue("tx_gateway_ref", txUniqueId + "-gw");
        p.addValue("created_on", Timestamp.from(createdOn));
        jdbcTemplate.update(
                "INSERT INTO merchant_transactions_log "
                        + "(merchant_id, gateway_id, tx_type, status, tx_unique_id, tx_gateway_ref, created_on) "
                        + "VALUES (:merchant_id, 'mtn_momo', 'PAYIN', 'SUCCESSFUL', :tx_unique_id, :tx_gateway_ref, :created_on)",
                p);
    }

    private void insertUsageEvent(String serviceCode, String sourceReference, Instant eventTime) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("billing_tenant_id", 1L);
        p.addValue("service_code", serviceCode);
        p.addValue("meter_code", "payment_event_count");
        p.addValue("event_time", Timestamp.from(eventTime));
        p.addValue("source_reference", sourceReference);
        p.addValue("idempotency_key", "recon-" + sourceReference);
        jdbcTemplate.update(
                "INSERT INTO billing_usage_events "
                        + "(billing_tenant_id, service_code, meter_code, event_time, source_reference, idempotency_key) "
                        + "VALUES (:billing_tenant_id, :service_code, :meter_code, :event_time, :source_reference, :idempotency_key)",
                p);
    }
}
