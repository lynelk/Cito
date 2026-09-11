package net.citotech.cito.billing.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import net.citotech.cito.billing.reconciliation.UsagePaymentReconciliationService;
import net.citotech.cito.billing.reconciliation.UsageReconciliationResult;
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
 * Billing Slice 26 - the Phase 2 capstone: proves the gap {@link
 * UsagePaymentReconciliationService}'s own javadoc named ("nothing currently consumes {@code
 * billing_outbox} into {@code billing_usage_events} yet... expected until Phase 2 lands") is
 * actually closed. Drives a real {@code merchant_transactions_log} row through the exact same
 * {@link OutboxWriter} -&gt; {@link OutboxRelay} -&gt; {@link UsageEventOutboxHandler} -&gt; {@code
 * billing_usage_events} pipeline {@code PaymentUsageOutboxHook} uses in production, then re-runs
 * the Phase 1 reconciliation report and proves it now reports a clean match for the wired
 * transaction while still correctly flagging an unwired one - the concrete evidence the Phase 1
 * exit criterion ("payments are 1:1 events, zero tolerance") is achievable once the {@code
 * billing-usage-outbox} flag is on and the relay is running. Lives in this package (not {@code
 * billing.reconciliation}) because it needs package-private access to {@link
 * OutboxRelay#processBatch}, matching {@code OutboxRelayTestcontainersTest}'s precedent.
 *
 * <p>Requires a running Docker daemon, so it is tagged {@code "docker"} and excluded from the
 * default {@code mvn test}/{@code mvn verify} run. Run explicitly with: {@code mvn test
 * -Ddocker.tests.excludedGroups=}
 */
@Tag("docker")
@Testcontainers
class PaymentPipelineReconciliationTestcontainersTest {

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
    void theFullOutboxPipelineClosesTheGapTheReconciliationReportFlags() {
        ObjectMapper objectMapper = new ObjectMapper();
        OutboxWriter outboxWriter = new OutboxWriter(jdbcTemplate, objectMapper);
        BillingTenantResolver tenantResolver =
                new BillingTenantResolver(new BillingTenantRepository(jdbcTemplate));
        UsageGatewayService usageGatewayService =
                new UsageGatewayService(
                        tenantResolver, new UsageEventRepository(jdbcTemplate, objectMapper));
        OutboxRelay relay =
                new OutboxRelay(
                        jdbcTemplate,
                        objectMapper,
                        List.of(new UsageEventOutboxHandler(usageGatewayService)));
        UsagePaymentReconciliationService reconciliationService =
                new UsagePaymentReconciliationService(jdbcTemplate);

        Instant now = Instant.now();
        Instant windowStart = now.minus(1, ChronoUnit.HOURS);
        Instant windowEnd = now.plus(1, ChronoUnit.HOURS);

        long merchantId = 8101L;
        insertMerchant(merchantId);
        long billingTenantId = insertBillingTenant(merchantId);

        // A payment CPay actually processed: a real merchant_transactions_log row (what
        // Common.doPayIn produces) plus the outbox entry PaymentUsageOutboxHook would have
        // written right after it, with the flag on.
        insertTransactionLog(merchantId, "TX-PIPELINE-WIRED", now);
        outboxWriter.write(
                "PAYMENT",
                "TX-PIPELINE-WIRED",
                "PAYMENT_COLLECTION_SUBMITTED",
                Map.of(
                        "billingTenantId", billingTenantId,
                        "merchantId", merchantId,
                        "transactionReference", "TX-PIPELINE-WIRED",
                        "amount", "500",
                        "currency", "UGX"));

        // A second payment as if the flag were still off for it (or the relay hadn't run yet) -
        // no outbox entry, so it must still show up as a real, unreconciled gap.
        insertTransactionLog(merchantId, "TX-PIPELINE-UNWIRED", now);

        UsageReconciliationResult beforeRelay =
                reconciliationService.reconcilePayments(windowStart, windowEnd);
        assertThat(beforeRelay.missingFromUsageEvents())
                .contains("TX-PIPELINE-WIRED", "TX-PIPELINE-UNWIRED");

        int processed = relay.processBatch(10);
        assertThat(processed).isEqualTo(1);

        UsageReconciliationResult afterRelay =
                reconciliationService.reconcilePayments(windowStart, windowEnd);
        assertThat(afterRelay.missingFromUsageEvents()).doesNotContain("TX-PIPELINE-WIRED");
        assertThat(afterRelay.missingFromUsageEvents()).contains("TX-PIPELINE-UNWIRED");
    }

    private void insertMerchant(long id) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("id", id);
        p.addValue("account_number", "ACC-" + id);
        jdbcTemplate.update(
                "INSERT INTO merchants (id, name, account_number) VALUES (:id, 'Pipeline Test Merchant', :account_number)",
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
}
