package net.citotech.cito.billing.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import net.citotech.cito.billing.tenancy.BillingTenantRepository;
import net.citotech.cito.billing.tenancy.BillingTenantResolver;
import net.citotech.cito.billing.usage.UsageEventOutboxHandler;
import net.citotech.cito.billing.usage.UsageEventRepository;
import net.citotech.cito.billing.usage.UsageGatewayService;
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
 * Proves the full Phase 2 pipeline against a real MySQL schema: {@link OutboxWriter} writes a
 * {@code PAYMENT_COLLECTION_SUBMITTED} entry, {@link OutboxRelay} (wired with the real {@link
 * UsageEventOutboxHandler}, not a fake) claims and processes it, and a real {@code
 * billing_usage_events} row results - closing the gap {@code OutboxRelayTest}'s own javadoc left
 * open ("a relay-level Testcontainers test is left to a later slice once a real OutboxEventHandler
 * is wired (Phase 2)").
 *
 * <p>Requires a running Docker daemon, so it is tagged {@code "docker"} and excluded from the
 * default {@code mvn test}/{@code mvn verify} run. Run explicitly with: {@code mvn test
 * -Ddocker.tests.excludedGroups=}
 */
@Tag("docker")
@Testcontainers
class OutboxRelayTestcontainersTest {

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
    void processBatchDeliversAPaymentCollectionEntryIntoARealUsageEvent() {
        ObjectMapper objectMapper = new ObjectMapper();
        OutboxWriter outboxWriter = new OutboxWriter(jdbcTemplate, objectMapper);
        BillingTenantResolver tenantResolver =
                new BillingTenantResolver(new BillingTenantRepository(jdbcTemplate));
        UsageGatewayService usageGatewayService =
                new UsageGatewayService(
                        tenantResolver, new UsageEventRepository(jdbcTemplate, objectMapper));
        UsageEventOutboxHandler handler = new UsageEventOutboxHandler(usageGatewayService);
        OutboxRelay relay = new OutboxRelay(jdbcTemplate, objectMapper, List.of(handler));

        long merchantId = 8001L;
        insertMerchant(merchantId);
        long billingTenantId = insertBillingTenant(merchantId);

        outboxWriter.write(
                "PAYMENT",
                "TX-RELAY-1",
                "PAYMENT_COLLECTION_SUBMITTED",
                Map.of(
                        "billingTenantId", billingTenantId,
                        "merchantId", merchantId,
                        "transactionReference", "TX-RELAY-1",
                        "amount", "500",
                        "currency", "UGX"));

        int processed = relay.processBatch(10);

        assertThat(processed).isEqualTo(1);
        String status =
                jdbcTemplate.queryForObject(
                        "SELECT status FROM billing_outbox WHERE aggregate_id = 'TX-RELAY-1'",
                        new MapSqlParameterSource(),
                        String.class);
        assertThat(status).isEqualTo("DELIVERED");

        Integer usageEventCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM billing_usage_events WHERE source_reference = 'TX-RELAY-1'",
                        new MapSqlParameterSource(),
                        Integer.class);
        assertThat(usageEventCount).isEqualTo(1);
    }

    private void insertMerchant(long id) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("id", id);
        p.addValue("account_number", "ACC-" + id);
        jdbcTemplate.update(
                "INSERT INTO merchants (id, name, account_number) VALUES (:id, 'Relay Test Merchant', :account_number)",
                p);
    }

    private long insertBillingTenant(long merchantId) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("merchant_id", merchantId);
        jdbcTemplate.update(
                "INSERT INTO billing_tenants (merchant_id, tenant_type, tenant_status) "
                        + "VALUES (:merchant_id, 'CPAY_MERCHANT', 'ACTIVE')",
                p);
        Long id =
                jdbcTemplate.queryForObject(
                        "SELECT id FROM billing_tenants WHERE merchant_id = :merchant_id",
                        p,
                        Long.class);
        return id == null ? 0L : id;
    }
}
