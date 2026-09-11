package net.citotech.cito.billing.invoicing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/**
 * Proves {@link BillingInvoiceRepository} against a real MySQL schema: the draft round-trip, the
 * {@code CUSTOMER_CHARGE}-only/period-scoped/not-yet-staged filter on {@code
 * findUnstagedCustomerCharges}, and the real unique constraint on {@code
 * billing_invoice_lines.billing_rated_charge_id} that backstops {@link
 * BillingInvoiceService#stageCharges} against double-staging under a race.
 *
 * <p>Requires a running Docker daemon, so it is tagged {@code "docker"} and excluded from the
 * default {@code mvn test}/{@code mvn verify} run. Run explicitly with: {@code mvn test
 * -Ddocker.tests.excludedGroups=}
 */
@Tag("docker")
@Testcontainers
class BillingInvoiceRepositoryTestcontainersTest {

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
    void insertDraftAndFindRoundTripEveryField() {
        NamedParameterJdbcTemplate jdbcTemplate = new NamedParameterJdbcTemplate(dataSource);
        BillingInvoiceRepository repository = new BillingInvoiceRepository(jdbcTemplate);

        long id =
                repository.insertDraft(
                        7L,
                        "BINV-K1-1",
                        "UGX",
                        LocalDate.of(2026, 1, 1),
                        LocalDate.of(2026, 1, 31));

        Optional<BillingInvoiceRecord> found = repository.find(id);
        assertThat(found).isPresent();
        BillingInvoiceRecord record = found.get();
        assertThat(record.billingTenantId()).isEqualTo(7L);
        assertThat(record.invoiceNumber()).isEqualTo("BINV-K1-1");
        assertThat(record.currency()).isEqualTo("UGX");
        assertThat(record.periodStart()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(record.periodEnd()).isEqualTo(LocalDate.of(2026, 1, 31));
        assertThat(record.isDraft()).isTrue();
        assertThat(record.subtotalAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(record.totalAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(record.finalizedAt()).isNull();
        assertThat(record.ledgerTransactionId()).isNull();
    }

    @Test
    void findUnstagedCustomerChargesExcludesProviderCostOutOfPeriodAndAlreadyStagedRows() {
        NamedParameterJdbcTemplate jdbcTemplate = new NamedParameterJdbcTemplate(dataSource);
        BillingInvoiceRepository repository = new BillingInvoiceRepository(jdbcTemplate);
        long inPeriodCustomerCharge =
                insertRatedCharge(jdbcTemplate, 8L, "CUSTOMER_CHARGE", "UGX", "2026-02-10");
        insertRatedCharge(jdbcTemplate, 8L, "PROVIDER_COST", "UGX", "2026-02-10");
        insertRatedCharge(jdbcTemplate, 8L, "CUSTOMER_CHARGE", "UGX", "2026-03-15");
        long alreadyStaged =
                insertRatedCharge(jdbcTemplate, 8L, "CUSTOMER_CHARGE", "UGX", "2026-02-20");
        long invoiceId =
                repository.insertDraft(
                        8L,
                        "BINV-K1-2",
                        "UGX",
                        LocalDate.of(2026, 2, 1),
                        LocalDate.of(2026, 2, 28));
        repository.insertLine(
                invoiceId, alreadyStaged, "already staged", new BigDecimal("10"), "UGX");

        List<UnstagedRatedCharge> unstaged =
                repository.findUnstagedCustomerCharges(
                        8L, "UGX", LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28));

        assertThat(unstaged).hasSize(1);
        assertThat(unstaged.get(0).ratedChargeId()).isEqualTo(inPeriodCustomerCharge);
    }

    @Test
    void insertLineRejectsStagingTheSameRatedChargeTwice() {
        NamedParameterJdbcTemplate jdbcTemplate = new NamedParameterJdbcTemplate(dataSource);
        BillingInvoiceRepository repository = new BillingInvoiceRepository(jdbcTemplate);
        long ratedChargeId =
                insertRatedCharge(jdbcTemplate, 9L, "CUSTOMER_CHARGE", "UGX", "2026-04-05");
        long invoiceId =
                repository.insertDraft(
                        9L,
                        "BINV-K1-3",
                        "UGX",
                        LocalDate.of(2026, 4, 1),
                        LocalDate.of(2026, 4, 30));
        repository.insertLine(invoiceId, ratedChargeId, "first stage", new BigDecimal("75"), "UGX");

        assertThatThrownBy(
                        () ->
                                repository.insertLine(
                                        invoiceId,
                                        ratedChargeId,
                                        "second stage",
                                        new BigDecimal("75"),
                                        "UGX"))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void sumLineAmountsAndUpdateTotalsReflectInsertedLines() {
        NamedParameterJdbcTemplate jdbcTemplate = new NamedParameterJdbcTemplate(dataSource);
        BillingInvoiceRepository repository = new BillingInvoiceRepository(jdbcTemplate);
        long invoiceId =
                repository.insertDraft(
                        10L,
                        "BINV-K1-4",
                        "UGX",
                        LocalDate.of(2026, 5, 1),
                        LocalDate.of(2026, 5, 31));
        repository.insertLine(
                invoiceId, null, "manual adjustment one", new BigDecimal("40"), "UGX");
        repository.insertLine(
                invoiceId, null, "manual adjustment two", new BigDecimal("60"), "UGX");

        BigDecimal sum = repository.sumLineAmounts(invoiceId);
        assertThat(sum).isEqualByComparingTo("100");

        repository.updateTotals(invoiceId, sum, sum);
        BillingInvoiceRecord updated = repository.find(invoiceId).orElseThrow();
        assertThat(updated.subtotalAmount()).isEqualByComparingTo("100");
        assertThat(updated.totalAmount()).isEqualByComparingTo("100");
    }

    @Test
    void finalizeInvoiceFlipsStatusAndRecordsTheLedgerTransaction() {
        NamedParameterJdbcTemplate jdbcTemplate = new NamedParameterJdbcTemplate(dataSource);
        BillingInvoiceRepository repository = new BillingInvoiceRepository(jdbcTemplate);
        long invoiceId =
                repository.insertDraft(
                        11L,
                        "BINV-K1-5",
                        "UGX",
                        LocalDate.of(2026, 6, 1),
                        LocalDate.of(2026, 6, 30));

        int updated = repository.finalizeInvoice(invoiceId, "billing-finalizer", 777L);

        assertThat(updated).isEqualTo(1);
        BillingInvoiceRecord finalized = repository.find(invoiceId).orElseThrow();
        assertThat(finalized.status()).isEqualTo("FINALIZED");
        assertThat(finalized.finalizedBy()).isEqualTo("billing-finalizer");
        assertThat(finalized.finalizedAt()).isNotNull();
        assertThat(finalized.ledgerTransactionId()).isEqualTo(777L);
    }

    @Test
    void finalizeInvoiceReturnsZeroAndLeavesTheRowUntouchedWhenAlreadyFinalized() {
        NamedParameterJdbcTemplate jdbcTemplate = new NamedParameterJdbcTemplate(dataSource);
        BillingInvoiceRepository repository = new BillingInvoiceRepository(jdbcTemplate);
        long invoiceId =
                repository.insertDraft(
                        12L,
                        "BINV-K1-6",
                        "UGX",
                        LocalDate.of(2026, 7, 1),
                        LocalDate.of(2026, 7, 31));
        repository.finalizeInvoice(invoiceId, "billing-finalizer", 888L);

        int secondAttempt = repository.finalizeInvoice(invoiceId, "someone-else", 999L);

        assertThat(secondAttempt).isZero();
        BillingInvoiceRecord finalized = repository.find(invoiceId).orElseThrow();
        assertThat(finalized.finalizedBy()).isEqualTo("billing-finalizer");
        assertThat(finalized.ledgerTransactionId()).isEqualTo(888L);
    }

    private long insertRatedCharge(
            NamedParameterJdbcTemplate jdbcTemplate,
            long billingTenantId,
            String chargeType,
            String currency,
            String computedAtDate) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("billing_tenant_id", billingTenantId);
        p.addValue("price_book_version_id", 1L);
        p.addValue("service_code", "PAYMENT");
        p.addValue("meter_code", "collection_count");
        p.addValue("charge_type", chargeType);
        p.addValue("source_reference", "TX-" + java.util.UUID.randomUUID());
        p.addValue("base_amount", new BigDecimal("1000"));
        p.addValue("rated_amount", new BigDecimal("25"));
        p.addValue("currency", currency);
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
        Long id =
                jdbcTemplate.queryForObject(
                        "SELECT LAST_INSERT_ID()", new MapSqlParameterSource(), Long.class);
        return id == null ? 0L : id;
    }
}
