package net.citotech.cito.billing.invoicing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import javax.sql.DataSource;
import net.citotech.cito.billing.export.BillingTraceChainRow;
import net.citotech.cito.billing.export.BillingTraceChainService;
import net.citotech.cito.billing.integration.cpay.BillingLedgerAccountTemplateService;
import net.citotech.cito.billing.integration.cpay.BillingLedgerLinkWriter;
import net.citotech.cito.billing.reconciliation.BillingCompletenessGateService;
import net.citotech.cito.gateway.PaymentGatewayException;
import net.citotech.cito.ledger.DoubleEntryLedgerService;
import net.citotech.cito.ledger.TrialBalanceResult;
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
 * The Phase 3 exit-criterion capstone: a single controlled billing invoice, staged and finalized
 * end to end against a real MySQL schema, proving every piece named in the billing plan's Phase 3
 * exit criterion in one place - staged charges, a completeness PASS, finalize, post-finalize
 * immutability, a balanced trial balance, a {@code billing_ledger_links} row, and the full trace
 * chain in one join. Each piece already has its own dedicated coverage elsewhere (the finalize
 * workflow in {@code BillingInvoiceFinalizeWorkflowTestcontainersTest}, the join itself in {@code
 * BillingTraceChainServiceTestcontainersTest}) - this test's value is proving they all actually
 * compose, matching {@code PaymentPipelineReconciliationTestcontainersTest}'s role as the Phase 2
 * capstone.
 *
 * <p>Requires a running Docker daemon, so it is tagged {@code "docker"} and excluded from the
 * default {@code mvn test}/{@code mvn verify} run. Run explicitly with: {@code mvn test
 * -Ddocker.tests.excludedGroups=}
 */
@Tag("docker")
@Testcontainers
class BillingPhase3ExitCriterionTestcontainersTest {

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
    void theFullPhase3WorkflowClosesFromStagedChargeToTraceableLedgerEntry() {
        NamedParameterJdbcTemplate jdbcTemplate = new NamedParameterJdbcTemplate(dataSource);
        BillingInvoiceRepository invoiceRepository = new BillingInvoiceRepository(jdbcTemplate);
        DoubleEntryLedgerService ledgerService = new DoubleEntryLedgerService(jdbcTemplate);
        BillingLedgerAccountTemplateService ledgerAccountTemplateService =
                new BillingLedgerAccountTemplateService(
                        ledgerService, new BillingLedgerLinkWriter(jdbcTemplate));
        BillingCompletenessGateService gateService =
                new BillingCompletenessGateService(jdbcTemplate, invoiceRepository);
        BillingInvoiceService invoiceService =
                new BillingInvoiceService(
                        invoiceRepository,
                        gateService,
                        ledgerAccountTemplateService,
                        new net.citotech.cito.billing.integration.cpay.BillingPaymentFundingService(
                                jdbcTemplate));
        BillingTraceChainService traceChainService = new BillingTraceChainService(jdbcTemplate);
        long billingTenantId = 99L;
        String sourceReference = "TX-PHASE3-CAPSTONE-" + java.util.UUID.randomUUID();
        seedUsageEventAndRatedCharge(jdbcTemplate, billingTenantId, sourceReference);

        long invoiceId =
                invoiceService.createDraft(
                        billingTenantId,
                        "UGX",
                        LocalDate.of(2026, 8, 1),
                        LocalDate.of(2026, 8, 31));
        assertThat(invoiceService.stageCharges(invoiceId)).isEqualTo(1);
        net.citotech.cito.billing.SyntheticBillingEvidence.recordCompleteSource(
                jdbcTemplate, invoiceRepository, invoiceId);
        gateService.submit(invoiceId, "billing-maker");
        gateService.approve(invoiceId, "billing-checker", null);

        long ledgerTransactionId = invoiceService.finalizeInvoice(invoiceId, "billing-finalizer");

        BillingInvoiceRecord finalized = invoiceRepository.find(invoiceId).orElseThrow();
        String invoiceNumber = finalized.invoiceNumber();

        // (a) immutability: a post-finalize mutation attempt fails.
        assertThatThrownBy(() -> invoiceService.stageCharges(invoiceId))
                .isInstanceOf(PaymentGatewayException.class)
                .hasMessageContaining("not DRAFT");

        // (b) the trial balance is exactly balanced for this posting.
        TrialBalanceResult trialBalance = ledgerService.runTrialBalance(LocalDate.now(), "UGX");
        assertThat(trialBalance.isBalanced()).isTrue();
        assertThat(trialBalance.getTotalDebits()).isEqualByComparingTo("1000");
        assertThat(trialBalance.getTotalCredits()).isEqualByComparingTo("1000");

        // (c) a billing_ledger_links row traces the ledger transaction back to this invoice.
        Integer linkCount =
                jdbcTemplate
                        .getJdbcTemplate()
                        .queryForObject(
                                "SELECT COUNT(*) FROM billing_ledger_links WHERE ledger_transaction_id = "
                                        + ledgerTransactionId
                                        + " AND link_type = 'CHARGE' AND billing_reference = '"
                                        + invoiceNumber
                                        + "'",
                                Integer.class);
        assertThat(linkCount).isEqualTo(1);

        // (d) the full trace chain (event -> charge -> invoice line -> invoice -> ledger entry) is
        // queryable in one join.
        List<BillingTraceChainRow> trace =
                traceChainService.traceBySourceReference(billingTenantId, sourceReference);
        assertThat(trace).hasSize(2);
        assertThat(trace)
                .allSatisfy(
                        row -> {
                            assertThat(row.billingInvoiceId()).isEqualTo(invoiceId);
                            assertThat(row.invoiceNumber()).isEqualTo(invoiceNumber);
                            assertThat(row.invoiceStatus()).isEqualTo("FINALIZED");
                            assertThat(row.ledgerTransactionId()).isEqualTo(ledgerTransactionId);
                        });
        assertThat(trace)
                .extracting(
                        row ->
                                tuple(
                                        row.ledgerEntryDirection(),
                                        row.ledgerEntryAmount().stripTrailingZeros()))
                .containsExactlyInAnyOrder(
                        tuple("DR", new BigDecimal("1000").stripTrailingZeros()),
                        tuple("CR", new BigDecimal("1000").stripTrailingZeros()));
    }

    private void seedUsageEventAndRatedCharge(
            NamedParameterJdbcTemplate jdbcTemplate, long billingTenantId, String sourceReference) {
        MapSqlParameterSource usageEvent = new MapSqlParameterSource();
        usageEvent.addValue("billing_tenant_id", billingTenantId);
        usageEvent.addValue("service_code", "PAYMENT");
        usageEvent.addValue("meter_code", "collection_count");
        usageEvent.addValue("event_time", "2026-08-15 12:00:00");
        usageEvent.addValue("currency", "UGX");
        usageEvent.addValue("source_reference", sourceReference);
        usageEvent.addValue("idempotency_key", "usage:" + sourceReference);
        jdbcTemplate.update(
                "INSERT INTO billing_usage_events (billing_tenant_id, service_code, meter_code, "
                        + "event_time, currency, source_reference, idempotency_key) "
                        + "VALUES (:billing_tenant_id, :service_code, :meter_code, :event_time, "
                        + ":currency, :source_reference, :idempotency_key)",
                usageEvent);

        MapSqlParameterSource ratedCharge = new MapSqlParameterSource();
        ratedCharge.addValue("billing_tenant_id", billingTenantId);
        ratedCharge.addValue("price_book_version_id", 1L);
        ratedCharge.addValue("service_code", "PAYMENT");
        ratedCharge.addValue("meter_code", "collection_count");
        ratedCharge.addValue("charge_type", "CUSTOMER_CHARGE");
        ratedCharge.addValue("source_reference", sourceReference);
        ratedCharge.addValue("base_amount", new BigDecimal("1000"));
        ratedCharge.addValue("rated_amount", new BigDecimal("1000"));
        ratedCharge.addValue("currency", "UGX");
        ratedCharge.addValue("rounding_policy", "HALF_UP_SCALE_2");
        ratedCharge.addValue("idempotency_key", "rated:" + sourceReference);
        ratedCharge.addValue("computed_at", "2026-08-15 12:00:00");
        jdbcTemplate.update(
                "INSERT INTO billing_rated_charges (billing_tenant_id, price_book_version_id, "
                        + "service_code, meter_code, charge_type, source_reference, base_amount, "
                        + "rated_amount, currency, rounding_policy, idempotency_key, computed_at) "
                        + "VALUES (:billing_tenant_id, :price_book_version_id, :service_code, :meter_code, "
                        + ":charge_type, :source_reference, :base_amount, :rated_amount, :currency, "
                        + ":rounding_policy, :idempotency_key, :computed_at)",
                ratedCharge);
    }
}
