package net.citotech.cito.billing.invoicing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import javax.sql.DataSource;
import net.citotech.cito.billing.integration.cpay.BillingLedgerAccountTemplateService;
import net.citotech.cito.billing.integration.cpay.BillingLedgerLinkWriter;
import net.citotech.cito.billing.reconciliation.BillingCompletenessGateService;
import net.citotech.cito.gateway.PaymentGatewayException;
import net.citotech.cito.ledger.DoubleEntryLedgerService;
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
 * Proves {@link BillingInvoiceService#finalizeInvoice} end to end against a real MySQL schema: the
 * happy path posts to the ledger and flips the invoice to FINALIZED, a post-finalize {@link
 * BillingInvoiceService#stageCharges} attempt is rejected (the immutability half of the Phase 3
 * exit criterion), finalize is blocked while the completeness gate is unapproved, and a
 * zero-subtotal approved draft fails at {@code DoubleEntryLedgerService}'s own entry validation
 * rather than a bespoke invoice-side check (see {@link BillingInvoiceService#finalizeInvoice}'s
 * javadoc for why that is deliberate).
 *
 * <p>Requires a running Docker daemon, so it is tagged {@code "docker"} and excluded from the
 * default {@code mvn test}/{@code mvn verify} run. Run explicitly with: {@code mvn test
 * -Ddocker.tests.excludedGroups=}
 */
@Tag("docker")
@Testcontainers
class BillingInvoiceFinalizeWorkflowTestcontainersTest {

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
    void finalizeInvoicePostsToTheLedgerAndImmutablyFinalizesTheInvoice() {
        NamedParameterJdbcTemplate jdbcTemplate = new NamedParameterJdbcTemplate(dataSource);
        BillingInvoiceRepository invoiceRepository = new BillingInvoiceRepository(jdbcTemplate);
        BillingInvoiceService invoiceService = invoiceService(jdbcTemplate, invoiceRepository);
        BillingCompletenessGateService gateService =
                new BillingCompletenessGateService(jdbcTemplate, invoiceRepository);
        insertRatedCharge(jdbcTemplate, 20L, "2026-08-10");

        long invoiceId =
                invoiceService.createDraft(
                        20L, "UGX", LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));
        assertThat(invoiceService.stageCharges(invoiceId)).isEqualTo(1);
        gateService.submit(invoiceId, "billing-maker");
        gateService.approve(invoiceId, "billing-checker", null);

        long ledgerTransactionId = invoiceService.finalizeInvoice(invoiceId, "billing-finalizer");

        BillingInvoiceRecord finalized = invoiceRepository.find(invoiceId).orElseThrow();
        assertThat(finalized.status()).isEqualTo("FINALIZED");
        assertThat(finalized.ledgerTransactionId()).isEqualTo(ledgerTransactionId);

        assertThatThrownBy(() -> invoiceService.stageCharges(invoiceId))
                .isInstanceOf(PaymentGatewayException.class)
                .hasMessageContaining("not DRAFT");

        Integer linkCount =
                jdbcTemplate
                        .getJdbcTemplate()
                        .queryForObject(
                                "SELECT COUNT(*) FROM billing_ledger_links WHERE ledger_transaction_id = "
                                        + ledgerTransactionId
                                        + " AND link_type = 'CHARGE' AND billing_reference = '"
                                        + finalized.invoiceNumber()
                                        + "'",
                                Integer.class);
        assertThat(linkCount).isEqualTo(1);
    }

    @Test
    void finalizeInvoiceThrowsWhenTheCompletenessGateIsNotApproved() {
        NamedParameterJdbcTemplate jdbcTemplate = new NamedParameterJdbcTemplate(dataSource);
        BillingInvoiceRepository invoiceRepository = new BillingInvoiceRepository(jdbcTemplate);
        BillingInvoiceService invoiceService = invoiceService(jdbcTemplate, invoiceRepository);
        insertRatedCharge(jdbcTemplate, 21L, "2026-09-10");

        long invoiceId =
                invoiceService.createDraft(
                        21L, "UGX", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));
        invoiceService.stageCharges(invoiceId);

        assertThatThrownBy(() -> invoiceService.finalizeInvoice(invoiceId, "billing-finalizer"))
                .isInstanceOf(PaymentGatewayException.class)
                .hasMessageContaining("completeness gate is not approved");
        assertThat(invoiceRepository.find(invoiceId).orElseThrow().status()).isEqualTo("DRAFT");
    }

    @Test
    void finalizeInvoiceOfAZeroSubtotalApprovedDraftFailsAtTheLedgerAndLeavesTheInvoiceDraft() {
        NamedParameterJdbcTemplate jdbcTemplate = new NamedParameterJdbcTemplate(dataSource);
        BillingInvoiceRepository invoiceRepository = new BillingInvoiceRepository(jdbcTemplate);
        BillingInvoiceService invoiceService = invoiceService(jdbcTemplate, invoiceRepository);
        BillingCompletenessGateService gateService =
                new BillingCompletenessGateService(jdbcTemplate, invoiceRepository);

        long invoiceId =
                invoiceService.createDraft(
                        22L, "UGX", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        assertThat(invoiceService.stageCharges(invoiceId)).isZero();
        gateService.submit(invoiceId, "billing-maker");
        gateService.approve(invoiceId, "billing-checker", null);

        assertThatThrownBy(() -> invoiceService.finalizeInvoice(invoiceId, "billing-finalizer"))
                .isInstanceOf(PaymentGatewayException.class)
                .hasMessageContaining("greater than zero");
        assertThat(invoiceRepository.find(invoiceId).orElseThrow().status()).isEqualTo("DRAFT");
    }

    private BillingInvoiceService invoiceService(
            NamedParameterJdbcTemplate jdbcTemplate, BillingInvoiceRepository invoiceRepository) {
        DoubleEntryLedgerService ledgerService = new DoubleEntryLedgerService(jdbcTemplate);
        BillingLedgerLinkWriter linkWriter = new BillingLedgerLinkWriter(jdbcTemplate);
        BillingLedgerAccountTemplateService ledgerAccountTemplateService =
                new BillingLedgerAccountTemplateService(ledgerService, linkWriter);
        BillingCompletenessGateService gateService =
                new BillingCompletenessGateService(jdbcTemplate, invoiceRepository);
        return new BillingInvoiceService(
                invoiceRepository,
                gateService,
                ledgerAccountTemplateService,
                new net.citotech.cito.billing.integration.cpay.BillingPaymentFundingService(
                        jdbcTemplate));
    }

    private void insertRatedCharge(
            NamedParameterJdbcTemplate jdbcTemplate, long billingTenantId, String computedAtDate) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("billing_tenant_id", billingTenantId);
        p.addValue("price_book_version_id", 1L);
        p.addValue("service_code", "PAYMENT");
        p.addValue("meter_code", "collection_count");
        p.addValue("charge_type", "CUSTOMER_CHARGE");
        p.addValue("source_reference", "TX-" + java.util.UUID.randomUUID());
        p.addValue("base_amount", new BigDecimal("1000"));
        p.addValue("rated_amount", new BigDecimal("1000"));
        p.addValue("currency", "UGX");
        p.addValue("rounding_policy", "HALF_UP_SCALE_2");
        p.addValue("idempotency_key", "rated:" + java.util.UUID.randomUUID());
        p.addValue("computed_at", computedAtDate + " 12:00:00");
        jdbcTemplate.update(
                "INSERT INTO billing_rated_charges (billing_tenant_id, price_book_version_id, "
                        + "service_code, meter_code, charge_type, source_reference, base_amount, "
                        + "rated_amount, currency, rounding_policy, idempotency_key, computed_at) "
                        + "VALUES (:billing_tenant_id, :price_book_version_id, :service_code, :meter_code, "
                        + ":charge_type, :source_reference, :base_amount, :rated_amount, :currency, "
                        + ":rounding_policy, :idempotency_key, :computed_at)",
                p);
    }
}
