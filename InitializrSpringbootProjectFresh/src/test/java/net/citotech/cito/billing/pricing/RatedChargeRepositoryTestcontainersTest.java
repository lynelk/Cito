package net.citotech.cito.billing.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
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
 * Proves {@link RatedChargeRepository#insertIfAbsent} is genuinely idempotent against a real MySQL
 * schema (unique constraint on {@code idempotency_key}), matching the {@code
 * DoubleEntryLedgerServiceTestcontainersTest} (audit K1) precedent.
 *
 * <p>Requires a running Docker daemon, so it is tagged {@code "docker"} and excluded from the
 * default {@code mvn test}/{@code mvn verify} run. Run explicitly with: {@code mvn test
 * -Ddocker.tests.excludedGroups=}
 */
@Tag("docker")
@Testcontainers
class RatedChargeRepositoryTestcontainersTest {

    @Container
    private static final MySQLContainer MYSQL =
            new MySQLContainer("mysql:8.0.36")
                    .withCommand("--log-bin-trust-function-creators=1")
                    .withDatabaseName("cpay_test")
                    .withUsername("cpay")
                    .withPassword("cpay");

    private static DataSource dataSource;

    @BeforeAll
    static void migrateSchema() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(MYSQL.getJdbcUrl());
        config.setUsername(MYSQL.getUsername());
        config.setPassword(MYSQL.getPassword());
        dataSource = new HikariDataSource(config);

        Flyway.configure().dataSource(dataSource).load().migrate();
    }

    @AfterAll
    static void closeDataSource() {
        if (dataSource instanceof HikariDataSource hikari) {
            hikari.close();
        }
    }

    @Test
    void insertIfAbsentNeverDuplicatesARatedChargeForTheSameIdempotencyKey() {
        NamedParameterJdbcTemplate jdbcTemplate = new NamedParameterJdbcTemplate(dataSource);
        RatedChargeRepository repository = new RatedChargeRepository(jdbcTemplate);
        RatedCharge ratedCharge =
                new RatedCharge(1L, new BigDecimal("120.00"), "UGX", "HALF_UP_SCALE_2", "[]", "{}");

        long firstId =
                repository.insertIfAbsent(
                        7L,
                        "PAYMENT",
                        "payment_event_count",
                        "CUSTOMER_CHARGE",
                        "TX-RATED-1",
                        new BigDecimal("1000"),
                        ratedCharge,
                        "rated:TX-RATED-1");
        long secondId =
                repository.insertIfAbsent(
                        7L,
                        "PAYMENT",
                        "payment_event_count",
                        "CUSTOMER_CHARGE",
                        "TX-RATED-1",
                        new BigDecimal("1000"),
                        ratedCharge,
                        "rated:TX-RATED-1");

        assertThat(secondId).isEqualTo(firstId);
        Integer count =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM billing_rated_charges WHERE idempotency_key = 'rated:TX-RATED-1'",
                        new MapSqlParameterSource(),
                        Integer.class);
        assertThat(count).isEqualTo(1);
    }
}
