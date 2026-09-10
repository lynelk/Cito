package net.citotech.cito.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import net.citotech.cito.gateway.PaymentGatewayException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/**
 * Covers audit K1: Testcontainers-based DB integration testing. Everything else covering {@link
 * DoubleEntryLedgerService} ({@code DoubleEntryLedgerServiceTest}) mocks {@code
 * NamedParameterJdbcTemplate}, which proves the service's own logic but never proves the SQL it
 * emits is actually correct against a real MySQL schema (correct column names, working {@code ON
 * DUPLICATE KEY UPDATE} semantics, a real unique constraint enforcing idempotency). This runs the
 * full Flyway migration history (V1..current head) against a real MySQL 8 container and exercises
 * the ledger's core documented invariant (see {@code Docs/Testing-strategy.md}: "balanced ledger
 * debits/credits"), idempotent-post guarantee, and serialized funds-reservation behavior end to
 * end.
 *
 * <p>Requires a running Docker daemon, so it is tagged {@code "docker"} and excluded from the
 * default {@code mvn test}/{@code mvn verify} run (see the {@code docker.tests.excludedGroups}
 * property and surefire {@code excludedGroups} binding in {@code pom.xml}). Run explicitly in a
 * Docker-capable environment with: {@code mvn test -Ddocker.tests.excludedGroups=}
 */
@Tag("docker")
@Testcontainers
class DoubleEntryLedgerServiceTestcontainersTest {

    @Container
    private static final MySQLContainer MYSQL =
            new MySQLContainer("mysql:8.0.36")
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
    void postsBalancedEntriesAndTheyAreVisibleInTheRealSchema() {
        NamedParameterJdbcTemplate jdbcTemplate = new NamedParameterJdbcTemplate(dataSource);
        DoubleEntryLedgerService service = new DoubleEntryLedgerService(jdbcTemplate);

        long txId =
                service.post(
                        "TX-K1-REAL-DB-1",
                        "PAYMENT",
                        "PAY-K1-1",
                        "testcontainers real-db post",
                        List.of(
                                entry("merchant:1001:UGX:collections_payable", "CR", "5000"),
                                entry("provider:mtn_momo:UGX:float", "DR", "5000")));

        assertThat(txId).isPositive();

        List<Map<String, Object>> entries =
                jdbcTemplate
                        .getJdbcTemplate()
                        .queryForList(
                                "SELECT entry_direction, amount FROM ledger_entries WHERE ledger_transaction_id = "
                                        + txId
                                        + " ORDER BY entry_direction");
        assertThat(entries).hasSize(2);

        BigDecimal debitTotal =
                entries.stream()
                        .filter(row -> "DR".equals(row.get("entry_direction")))
                        .map(row -> (BigDecimal) row.get("amount"))
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal creditTotal =
                entries.stream()
                        .filter(row -> "CR".equals(row.get("entry_direction")))
                        .map(row -> (BigDecimal) row.get("amount"))
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(debitTotal).isEqualByComparingTo(creditTotal);
    }

    @Test
    void repostingTheSameTransactionReferenceDoesNotDuplicateEntriesInTheRealSchema() {
        NamedParameterJdbcTemplate jdbcTemplate = new NamedParameterJdbcTemplate(dataSource);
        DoubleEntryLedgerService service = new DoubleEntryLedgerService(jdbcTemplate);
        List<LedgerEntryCommand> entries =
                List.of(
                        entry("merchant:1002:UGX:collections_payable", "CR", "1000"),
                        entry("provider:mtn_momo:UGX:float", "DR", "1000"));

        long firstPost =
                service.post("TX-K1-IDEMPOTENT-1", "PAYMENT", "PAY-K1-2", "first attempt", entries);
        long secondPost =
                service.post(
                        "TX-K1-IDEMPOTENT-1", "PAYMENT", "PAY-K1-2", "retried delivery", entries);

        assertThat(secondPost).isEqualTo(firstPost);
        Integer entryCount =
                jdbcTemplate
                        .getJdbcTemplate()
                        .queryForObject(
                                "SELECT COUNT(*) FROM ledger_entries WHERE ledger_transaction_id = "
                                        + firstPost,
                                Integer.class);
        assertThat(entryCount).isEqualTo(2);
    }

    @Test
    void reversePostsFlippedEntriesAndTheWholeSetStaysBalanced() {
        NamedParameterJdbcTemplate jdbcTemplate = new NamedParameterJdbcTemplate(dataSource);
        DoubleEntryLedgerService service = new DoubleEntryLedgerService(jdbcTemplate);
        List<LedgerEntryCommand> entries =
                List.of(
                        entry("merchant:1003:UGX:collections_payable", "CR", "2500"),
                        entry("provider:mtn_momo:UGX:float", "DR", "2500"));

        long originalTxId =
                service.post(
                        "TX-K1-REVERSAL-ORIGINAL",
                        "PAYMENT",
                        "PAY-K1-3",
                        "original posting",
                        entries);
        long reversalTxId =
                service.reverse(
                        "TX-K1-REVERSAL-ORIGINAL", "TX-K1-REVERSAL-NEW", "correcting error");

        assertThat(reversalTxId).isNotEqualTo(originalTxId);

        List<Map<String, Object>> reversalEntries =
                jdbcTemplate
                        .getJdbcTemplate()
                        .queryForList(
                                "SELECT entry_direction, amount FROM ledger_entries WHERE ledger_transaction_id = "
                                        + reversalTxId
                                        + " ORDER BY entry_direction");
        assertThat(reversalEntries).hasSize(2);
        // The reversal flips each original entry's direction: the original CR merchant entry
        // becomes a DR reversal entry, and vice versa for the provider entry.
        BigDecimal reversalDebitTotal =
                reversalEntries.stream()
                        .filter(row -> "DR".equals(row.get("entry_direction")))
                        .map(row -> (BigDecimal) row.get("amount"))
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal reversalCreditTotal =
                reversalEntries.stream()
                        .filter(row -> "CR".equals(row.get("entry_direction")))
                        .map(row -> (BigDecimal) row.get("amount"))
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(reversalDebitTotal).isEqualByComparingTo("2500");
        assertThat(reversalCreditTotal).isEqualByComparingTo("2500");

        // Reversing is idempotent, same as post().
        long reversalReplay =
                service.reverse(
                        "TX-K1-REVERSAL-ORIGINAL", "TX-K1-REVERSAL-NEW", "retried delivery");
        assertThat(reversalReplay).isEqualTo(reversalTxId);
        Integer reversalEntryCount =
                jdbcTemplate
                        .getJdbcTemplate()
                        .queryForObject(
                                "SELECT COUNT(*) FROM ledger_entries WHERE ledger_transaction_id = "
                                        + reversalTxId,
                                Integer.class);
        assertThat(reversalEntryCount).isEqualTo(2);
    }

    @Test
    void postThrowsWhenARealLockRowCoversTodayForTheCurrency() {
        NamedParameterJdbcTemplate jdbcTemplate = new NamedParameterJdbcTemplate(dataSource);
        DoubleEntryLedgerService service = new DoubleEntryLedgerService(jdbcTemplate);
        jdbcTemplate
                .getJdbcTemplate()
                .update(
                        "INSERT INTO ledger_period_locks (currency, period_start, period_end, locked_by, reason) "
                                + "VALUES ('KES', CURRENT_DATE, CURRENT_DATE, 'finance-ops', 'testcontainers lock test')");

        assertThatThrownBy(
                        () ->
                                service.post(
                                        "TX-K1-LOCKED",
                                        "PAYMENT",
                                        "PAY-K1-LOCKED",
                                        "should be rejected",
                                        List.of(
                                                entry(
                                                        "merchant:1004:KES:collections_payable",
                                                        "CR",
                                                        "500"),
                                                entry("provider:mtn_momo:KES:float", "DR", "500"))))
                .isInstanceOf(PaymentGatewayException.class)
                .hasMessageContaining("KES")
                .hasMessageContaining("locked");

        Integer transactionCount =
                jdbcTemplate
                        .getJdbcTemplate()
                        .queryForObject(
                                "SELECT COUNT(*) FROM ledger_transactions WHERE transaction_reference = 'TX-K1-LOCKED'",
                                Integer.class);
        assertThat(transactionCount).isZero();
    }

    @Test
    void postStillSucceedsForAnUnrelatedCurrencyWhileAnotherCurrencyIsLocked() {
        NamedParameterJdbcTemplate jdbcTemplate = new NamedParameterJdbcTemplate(dataSource);
        DoubleEntryLedgerService service = new DoubleEntryLedgerService(jdbcTemplate);
        // Reuses the KES lock inserted by the test above (same class-level dataSource); UGX has
        // no lock row at all, proving the fail-open default holds per-currency, not globally.
        long txId =
                service.post(
                        "TX-K1-UNLOCKED-CURRENCY",
                        "PAYMENT",
                        "PAY-K1-UNLOCKED-CURRENCY",
                        "different currency, should succeed",
                        List.of(
                                entry("merchant:1005:UGX:collections_payable", "CR", "750"),
                                entry("provider:mtn_momo:UGX:float", "DR", "750")));

        assertThat(txId).isPositive();
    }

    @Test
    void concurrentReservationsCannotOverspendTheSameMerchantCurrencyBalance() throws Exception {
        NamedParameterJdbcTemplate jdbcTemplate = new NamedParameterJdbcTemplate(dataSource);
        DoubleEntryLedgerService service = new DoubleEntryLedgerService(jdbcTemplate);
        DataSourceTransactionManager transactionManager =
                new DataSourceTransactionManager(dataSource);

        service.post(
                "TX-K1-CONCURRENT-SEED",
                "PAYMENT",
                "PAY-K1-CONCURRENT-SEED",
                "seed merchant liability for reservation concurrency",
                List.of(
                        entry(
                                "merchant:2001:UGX:merchant_liability",
                                "MERCHANT_LIABILITY",
                                "MERCHANT",
                                2001L,
                                "CR",
                                "100000",
                                "UGX"),
                        entry(
                                "provider:mtn_momo:UGX:float:reservation_seed",
                                "CONTROL",
                                "PROVIDER",
                                9001L,
                                "DR",
                                "100000",
                                "UGX")));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first =
                    executor.submit(
                            reserveWhenReleased(
                                    service,
                                    transactionManager,
                                    ready,
                                    start,
                                    "RES-K1-CONCURRENT-A"));
            Future<Boolean> second =
                    executor.submit(
                            reserveWhenReleased(
                                    service,
                                    transactionManager,
                                    ready,
                                    start,
                                    "RES-K1-CONCURRENT-B"));

            ready.await();
            start.countDown();

            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(true, false);
        } finally {
            executor.shutdownNow();
        }

        Integer reservedCount =
                jdbcTemplate
                        .getJdbcTemplate()
                        .queryForObject(
                                "SELECT COUNT(*) FROM ledger_reservations "
                                        + "WHERE merchant_id = 2001 "
                                        + "AND currency = 'UGX' "
                                        + "AND reservation_status = 'RESERVED'",
                                Integer.class);
        BigDecimal reservedAmount =
                jdbcTemplate
                        .getJdbcTemplate()
                        .queryForObject(
                                "SELECT COALESCE(SUM(amount), 0) FROM ledger_reservations "
                                        + "WHERE merchant_id = 2001 "
                                        + "AND currency = 'UGX' "
                                        + "AND reservation_status = 'RESERVED'",
                                BigDecimal.class);

        assertThat(reservedCount).isEqualTo(1);
        assertThat(reservedAmount).isEqualByComparingTo("80000.0000");
        assertThat(service.availableMerchantBalance(2001L, "UGX"))
                .isEqualByComparingTo("20000.0000");
    }

    @Test
    void reservationsUseCurrentBalancesEvenWhenOuterTransactionHasAnOlderSnapshot()
            throws Exception {
        LedgerReservationMysqlScenario.run(dataSource);
    }

    private Callable<Boolean> reserveWhenReleased(
            DoubleEntryLedgerService service,
            DataSourceTransactionManager transactionManager,
            CountDownLatch ready,
            CountDownLatch start,
            String reservationReference) {
        return () -> {
            ready.countDown();
            start.await();
            TransactionTemplate template = new TransactionTemplate(transactionManager);
            template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            try {
                template.executeWithoutResult(
                        status ->
                                service.reserve(
                                        reservationReference,
                                        2001L,
                                        reservationReference.replace("RES", "PAY"),
                                        new BigDecimal("80000"),
                                        "UGX"));
                return true;
            } catch (PaymentGatewayException ex) {
                assertThat(ex)
                        .hasMessageContaining("Insufficient ledger-derived available balance");
                return false;
            }
        };
    }

    private LedgerEntryCommand entry(
            String account,
            String accountType,
            String ownerType,
            Long ownerId,
            String direction,
            String amount,
            String currency) {
        return new LedgerEntryCommand(
                account,
                account,
                accountType,
                ownerType,
                ownerId,
                direction,
                new BigDecimal(amount),
                currency,
                "testcontainers K1 test");
    }

    private LedgerEntryCommand entry(String account, String direction, String amount) {
        return new LedgerEntryCommand(
                account,
                account,
                "CONTROL",
                "SYSTEM",
                null,
                direction,
                new BigDecimal(amount),
                account.split(":")[2],
                "testcontainers K1 test");
    }
}
