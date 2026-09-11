package net.citotech.cito.billing.pricing;

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
 * Proves {@link ChargeShadowComparisonService}'s JOIN across {@code merchant_transactions_log} and
 * {@code billing_rated_charges} actually detects a seeded divergent charge against a real MySQL
 * schema, matching the {@code UsagePaymentReconciliationServiceTestcontainersTest} precedent.
 *
 * <p>Requires a running Docker daemon, so it is tagged {@code "docker"} and excluded from the
 * default {@code mvn test}/{@code mvn verify} run. Run explicitly with: {@code mvn test
 * -Ddocker.tests.excludedGroups=}
 */
@Tag("docker")
@Testcontainers
class ChargeShadowComparisonServiceTestcontainersTest {

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
    void compareFlagsATransactionWhoseRatedChargeDivergesFromTheLegacyCharge() {
        Instant now = Instant.now();
        Instant windowStart = now.minus(1, ChronoUnit.HOURS);
        Instant windowEnd = now.plus(1, ChronoUnit.HOURS);

        insertMerchant(9101L);
        insertTransactionLog(9101L, "TX-SHADOW-MATCH", now, "120.00");
        insertTransactionLog(9101L, "TX-SHADOW-DIVERGENT", now, "100.00");
        insertRatedCharge("TX-SHADOW-MATCH", "120.00");
        insertRatedCharge("TX-SHADOW-DIVERGENT", "105.50");

        ChargeShadowComparisonResult result =
                new ChargeShadowComparisonService(jdbcTemplate).compare(windowStart, windowEnd);

        assertThat(result.comparedCount()).isEqualTo(2);
        assertThat(result.diverging())
                .extracting(ChargeShadowDelta::sourceReference)
                .containsExactly("TX-SHADOW-DIVERGENT");
    }

    private void insertMerchant(long id) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("id", id);
        p.addValue("account_number", "ACC-" + id);
        jdbcTemplate.update(
                "INSERT INTO merchants (id, name, account_number) VALUES (:id, 'Shadow Test Merchant', :account_number)",
                p);
    }

    private void insertTransactionLog(
            long merchantId, String txUniqueId, Instant createdOn, String charges) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("merchant_id", merchantId);
        p.addValue("tx_unique_id", txUniqueId);
        p.addValue("tx_gateway_ref", txUniqueId + "-gw");
        p.addValue("created_on", Timestamp.from(createdOn));
        p.addValue("charges", charges);
        jdbcTemplate.update(
                "INSERT INTO merchant_transactions_log "
                        + "(merchant_id, gateway_id, tx_type, status, tx_unique_id, tx_gateway_ref, created_on, charges) "
                        + "VALUES (:merchant_id, 'mtn_momo', 'PAYIN', 'SUCCESSFUL', :tx_unique_id, :tx_gateway_ref, :created_on, :charges)",
                p);
    }

    private void insertRatedCharge(String sourceReference, String ratedAmount) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("billing_tenant_id", 1L);
        p.addValue("price_book_version_id", 1L);
        p.addValue("source_reference", sourceReference);
        p.addValue("base_amount", "1000.0000");
        p.addValue("rated_amount", ratedAmount);
        p.addValue("idempotency_key", "shadow-" + sourceReference);
        jdbcTemplate.update(
                "INSERT INTO billing_rated_charges (billing_tenant_id, price_book_version_id, "
                        + "service_code, meter_code, charge_type, source_reference, base_amount, "
                        + "rated_amount, currency, rounding_policy, idempotency_key) "
                        + "VALUES (:billing_tenant_id, :price_book_version_id, 'PAYMENT', "
                        + "'payment_event_count', 'CUSTOMER_CHARGE', :source_reference, :base_amount, "
                        + ":rated_amount, 'UGX', 'HALF_UP_SCALE_2', :idempotency_key)",
                p);
    }
}
