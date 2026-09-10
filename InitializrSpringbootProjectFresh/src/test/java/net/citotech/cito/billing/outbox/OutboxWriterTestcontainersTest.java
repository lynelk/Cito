package net.citotech.cito.billing.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/**
 * Proves {@link OutboxWriter}'s core contract - a write only survives if the caller's own
 * transaction commits - against a real MySQL schema and a real Spring transaction manager, matching
 * the {@code DoubleEntryLedgerServiceTestcontainersTest} (audit K1) precedent: the
 * mocked-JdbcTemplate {@code OutboxWriterTest} proves the SQL/logic shape, this proves the actual
 * rollback semantics that no mock can.
 *
 * <p>Requires a running Docker daemon, so it is tagged {@code "docker"} and excluded from the
 * default {@code mvn test}/{@code mvn verify} run. Run explicitly with: {@code mvn test
 * -Ddocker.tests.excludedGroups=}
 */
@Tag("docker")
@Testcontainers
class OutboxWriterTestcontainersTest {

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
    void aWriteInsideARolledBackTransactionNeverAppearsInTheOutbox() {
        NamedParameterJdbcTemplate jdbcTemplate = new NamedParameterJdbcTemplate(dataSource);
        OutboxWriter writer = new OutboxWriter(jdbcTemplate, new ObjectMapper());
        TransactionTemplate txTemplate =
                new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        txTemplate.execute(
                status -> {
                    writer.write(
                            "USAGE_EVENT",
                            "rollback-case",
                            "USAGE_EVENT_RECORDED",
                            Map.of("k", "v"));
                    status.setRollbackOnly();
                    return null;
                });

        Integer count =
                jdbcTemplate
                        .getJdbcTemplate()
                        .queryForObject(
                                "SELECT COUNT(*) FROM billing_outbox WHERE aggregate_id = 'rollback-case'",
                                Integer.class);
        assertThat(count).isZero();
    }

    @Test
    void aWriteInsideACommittedTransactionIsVisibleInTheOutbox() {
        NamedParameterJdbcTemplate jdbcTemplate = new NamedParameterJdbcTemplate(dataSource);
        OutboxWriter writer = new OutboxWriter(jdbcTemplate, new ObjectMapper());
        TransactionTemplate txTemplate =
                new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        txTemplate.execute(
                status -> {
                    writer.write(
                            "USAGE_EVENT", "commit-case", "USAGE_EVENT_RECORDED", Map.of("k", "v"));
                    return null;
                });

        Integer count =
                jdbcTemplate
                        .getJdbcTemplate()
                        .queryForObject(
                                "SELECT COUNT(*) FROM billing_outbox WHERE aggregate_id = 'commit-case'",
                                Integer.class);
        assertThat(count).isEqualTo(1);
    }
}
