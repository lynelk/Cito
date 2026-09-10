package net.citotech.cito.billing.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import javax.sql.DataSource;
import net.citotech.cito.billing.integration.cpay.BillingLedgerAccountTemplateService;
import net.citotech.cito.billing.integration.cpay.BillingLedgerLinkWriter;
import net.citotech.cito.billing.invoicing.BillingInvoiceRepository;
import net.citotech.cito.billing.invoicing.BillingInvoiceService;
import net.citotech.cito.billing.reconciliation.BillingCompletenessGateService;
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
 * Proves {@link BillingTraceChainService} against a real MySQL schema: pre-finalize rows have null
 * ledger fields, post-finalize rows fan out one-per-ledger-entry with the finalize's ledger
 * transaction id, both entry points ({@code traceBySourceReference}/{@code traceByInvoice}) return
 * the same rows for a finalized invoice, a manual-adjustment line is excluded from {@code
 * traceByInvoice}, and an unknown source reference returns nothing.
 *
 * <p>Requires a running Docker daemon, so it is tagged {@code "docker"} and excluded from the
 * default {@code mvn test}/{@code mvn verify} run. Run explicitly with: {@code mvn test
 * -Ddocker.tests.excludedGroups=}
 */
@Tag("docker")
@Testcontainers
class BillingTraceChainServiceTestcontainersTest {

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
    void traceBySourceReferenceReturnsARowWithNullLedgerFieldsBeforeFinalize() {
        NamedParameterJdbcTemplate jdbcTemplate = new NamedParameterJdbcTemplate(dataSource);
        BillingInvoiceRepository invoiceRepository = new BillingInvoiceRepository(jdbcTemplate);
        BillingTraceChainService traceChainService = new BillingTraceChainService(jdbcTemplate);
        String sourceReference = insertUsageEventAndRatedCharge(jdbcTemplate, 30L);
        BillingInvoiceService invoiceService = invoiceService(jdbcTemplate, invoiceRepository);
        long invoiceId =
                invoiceService.createDraft(
                        30L, "UGX", LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));
        invoiceService.stageCharges(invoiceId);

        List<BillingTraceChainRow> rows =
                traceChainService.traceBySourceReference(30L, sourceReference);

        assertThat(rows).hasSize(1);
        BillingTraceChainRow row = rows.get(0);
        assertThat(row.billingInvoiceId()).isEqualTo(invoiceId);
        assertThat(row.invoiceStatus()).isEqualTo("DRAFT");
        assertThat(row.ledgerTransactionId()).isNull();
        assertThat(row.ledgerEntryId()).isNull();
        assertThat(row.ledgerEntryDirection()).isNull();
        assertThat(row.ledgerEntryAmount()).isNull();
    }

    @Test
    void traceBySourceReferenceReturnsOneRowPerLedgerEntryOnceFinalized() {
        NamedParameterJdbcTemplate jdbcTemplate = new NamedParameterJdbcTemplate(dataSource);
        BillingInvoiceRepository invoiceRepository = new BillingInvoiceRepository(jdbcTemplate);
        BillingTraceChainService traceChainService = new BillingTraceChainService(jdbcTemplate);
        BillingInvoiceService invoiceService = invoiceService(jdbcTemplate, invoiceRepository);
        BillingCompletenessGateService gateService =
                new BillingCompletenessGateService(jdbcTemplate, invoiceRepository);
        String sourceReference = insertUsageEventAndRatedCharge(jdbcTemplate, 31L);
        long invoiceId =
                invoiceService.createDraft(
                        31L, "UGX", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));
        invoiceService.stageCharges(invoiceId);
        net.citotech.cito.billing.SyntheticBillingEvidence.recordCompleteSource(
                jdbcTemplate, invoiceRepository, invoiceId);
        gateService.submit(invoiceId, "billing-maker");
        gateService.approve(invoiceId, "billing-checker", null);
        long ledgerTransactionId = invoiceService.finalizeInvoice(invoiceId, "billing-finalizer");

        List<BillingTraceChainRow> rows =
                traceChainService.traceBySourceReference(31L, sourceReference);

        assertThat(rows).hasSize(2);
        assertThat(rows)
                .allSatisfy(
                        row -> {
                            assertThat(row.billingInvoiceId()).isEqualTo(invoiceId);
                            assertThat(row.invoiceStatus()).isEqualTo("FINALIZED");
                            assertThat(row.ledgerTransactionId()).isEqualTo(ledgerTransactionId);
                        });
        assertThat(rows)
                .extracting(
                        row ->
                                tuple(
                                        row.ledgerEntryDirection(),
                                        row.ledgerEntryAmount().stripTrailingZeros()))
                .containsExactlyInAnyOrder(
                        tuple("DR", new BigDecimal("1000").stripTrailingZeros()),
                        tuple("CR", new BigDecimal("1000").stripTrailingZeros()));
    }

    @Test
    void traceByInvoiceReturnsTheSameRowsAsTraceBySourceReferenceForAFinalizedInvoice() {
        NamedParameterJdbcTemplate jdbcTemplate = new NamedParameterJdbcTemplate(dataSource);
        BillingInvoiceRepository invoiceRepository = new BillingInvoiceRepository(jdbcTemplate);
        BillingTraceChainService traceChainService = new BillingTraceChainService(jdbcTemplate);
        BillingInvoiceService invoiceService = invoiceService(jdbcTemplate, invoiceRepository);
        BillingCompletenessGateService gateService =
                new BillingCompletenessGateService(jdbcTemplate, invoiceRepository);
        String sourceReference = insertUsageEventAndRatedCharge(jdbcTemplate, 32L);
        long invoiceId =
                invoiceService.createDraft(
                        32L, "UGX", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        invoiceService.stageCharges(invoiceId);
        net.citotech.cito.billing.SyntheticBillingEvidence.recordCompleteSource(
                jdbcTemplate, invoiceRepository, invoiceId);
        gateService.submit(invoiceId, "billing-maker");
        gateService.approve(invoiceId, "billing-checker", null);
        invoiceService.finalizeInvoice(invoiceId, "billing-finalizer");

        List<BillingTraceChainRow> bySourceReference =
                traceChainService.traceBySourceReference(32L, sourceReference);
        List<BillingTraceChainRow> byInvoice = traceChainService.traceByInvoice(invoiceId);

        assertThat(byInvoice).containsExactlyInAnyOrderElementsOf(bySourceReference);
    }

    @Test
    void traceByInvoiceOmitsManualAdjustmentLinesThatHaveNoRatedCharge() {
        NamedParameterJdbcTemplate jdbcTemplate = new NamedParameterJdbcTemplate(dataSource);
        BillingInvoiceRepository invoiceRepository = new BillingInvoiceRepository(jdbcTemplate);
        BillingTraceChainService traceChainService = new BillingTraceChainService(jdbcTemplate);
        long invoiceId =
                invoiceRepository.insertDraft(
                        33L,
                        "BINV-K1-TRACE-4",
                        "UGX",
                        LocalDate.of(2026, 11, 1),
                        LocalDate.of(2026, 11, 30));
        invoiceRepository.insertLine(
                invoiceId, null, "manual adjustment", new BigDecimal("25"), "UGX");

        List<BillingTraceChainRow> rows = traceChainService.traceByInvoice(invoiceId);

        assertThat(rows).isEmpty();
    }

    @Test
    void traceBySourceReferenceReturnsNothingForAnUnknownSourceReference() {
        NamedParameterJdbcTemplate jdbcTemplate = new NamedParameterJdbcTemplate(dataSource);
        BillingTraceChainService traceChainService = new BillingTraceChainService(jdbcTemplate);

        List<BillingTraceChainRow> rows =
                traceChainService.traceBySourceReference(34L, "TX-DOES-NOT-EXIST");

        assertThat(rows).isEmpty();
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

    private String insertUsageEventAndRatedCharge(
            NamedParameterJdbcTemplate jdbcTemplate, long billingTenantId) {
        String sourceReference = "TX-TRACE-" + java.util.UUID.randomUUID();
        String fixtureDate =
                billingTenantId == 31L
                        ? "2026-09-15 12:00:00"
                        : billingTenantId == 32L ? "2026-10-15 12:00:00" : "2026-08-15 12:00:00";

        MapSqlParameterSource usageEvent = new MapSqlParameterSource();
        usageEvent.addValue("billing_tenant_id", billingTenantId);
        usageEvent.addValue("service_code", "PAYMENT");
        usageEvent.addValue("meter_code", "collection_count");
        usageEvent.addValue("event_time", fixtureDate);
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
        ratedCharge.addValue("computed_at", fixtureDate);
        jdbcTemplate.update(
                "INSERT INTO billing_rated_charges (billing_tenant_id, price_book_version_id, "
                        + "service_code, meter_code, charge_type, source_reference, base_amount, "
                        + "rated_amount, currency, rounding_policy, idempotency_key, computed_at) "
                        + "VALUES (:billing_tenant_id, :price_book_version_id, :service_code, :meter_code, "
                        + ":charge_type, :source_reference, :base_amount, :rated_amount, :currency, "
                        + ":rounding_policy, :idempotency_key, :computed_at)",
                ratedCharge);

        return sourceReference;
    }
}
