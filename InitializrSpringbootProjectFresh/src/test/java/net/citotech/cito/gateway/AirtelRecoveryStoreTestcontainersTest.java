package net.citotech.cito.gateway;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;

@Tag("docker")
@Testcontainers
class AirtelRecoveryStoreTestcontainersTest {
    @Container
    static final MySQLContainer MYSQL =
            new MySQLContainer("mysql:8.0.36")
                    .withDatabaseName("airtel_recovery_test")
                    .withUsername("test")
                    .withPassword("test");

    static NamedParameterJdbcTemplate jdbc;
    static AirtelRecoveryStore store;
    static TransactionTemplate tx;

    @BeforeAll
    static void schema() {
        DriverManagerDataSource ds =
                new DriverManagerDataSource(
                        MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        new ResourceDatabasePopulator(
                        new ClassPathResource("db/migration/V123__airtel_durable_recovery.sql"))
                .execute(ds);
        jdbc = new NamedParameterJdbcTemplate(ds);
        store = new AirtelRecoveryStore(jdbc);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        jdbc.getJdbcTemplate()
                .execute(
                        "CREATE TABLE test_financial_effects (reference_value VARCHAR(128) PRIMARY KEY, amount DECIMAL(19,4))");
    }

    static AirtelRecoveryStore.Entry candidate() {
        String ref = UUID.randomUUID().toString();
        return new AirtelRecoveryStore.Entry(
                0,
                ref,
                42,
                "M42",
                ref,
                null,
                "COLLECT",
                "SANDBOX",
                "UG",
                "UGX",
                "MERCHANT",
                "identity",
                new BigDecimal("100.0000"),
                "request-hash",
                null,
                0,
                null,
                null);
    }

    static AirtelRecoveryStore.Entry prepare() {
        return tx.execute(x -> store.prepare(candidate()).entry());
    }

    static void makeDue(long id) {
        jdbc.update(
                "UPDATE airtel_recovery SET next_poll_at='2000-01-01' WHERE id=:id",
                Map.of("id", id));
    }

    @Test
    void replayReturnsSameOriginalReferenceWithoutCreatingNewRequest() {
        var c = candidate();
        var first = tx.execute(x -> store.prepare(c));
        var second = tx.execute(x -> store.prepare(c));
        assertThat(first.created()).isTrue();
        assertThat(second.created()).isFalse();
        assertThat(second.entry().id()).isEqualTo(first.entry().id());
    }

    @Test
    void preparedRequestSurvivesRestartAndCanBeClaimed() {
        var e = prepare();
        makeDue(e.id());
        var restarted = new AirtelRecoveryStore(jdbc);
        assertThat(restarted.due(100)).anyMatch(x -> x.id() == e.id());
        assertThat(restarted.claim(e.id())).isNotBlank();
    }

    @Test
    void onlyOneReplicaClaimsAnItem() throws Exception {
        var e = prepare();
        makeDue(e.id());
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            Callable<String> claim =
                    () -> {
                        start.await();
                        return store.claim(e.id());
                    };
            Future<String> a = pool.submit(claim), b = pool.submit(claim);
            start.countDown();
            assertThat(Arrays.asList(a.get(), b.get()).stream().filter(Objects::nonNull).count())
                    .isEqualTo(1);
        }
    }

    @Test
    void expiredLeaseCanBeReclaimedAndOldOwnerCannotFinalize() {
        var e = prepare();
        makeDue(e.id());
        String old = store.claim(e.id());
        jdbc.update(
                "UPDATE airtel_recovery SET claim_until='2000-01-01' WHERE id=:id",
                Map.of("id", e.id()));
        String replacement = store.claim(e.id());
        assertThat(replacement).isNotEqualTo(old);
        assertThat(tx.execute(x -> store.locked(e.id(), old))).isNull();
        assertThatThrownBy(
                        () ->
                                tx.execute(
                                        x -> {
                                            store.resolved(e, old, "SUCCESSFUL", "receipt");
                                            return null;
                                        }))
                .isInstanceOf(PaymentGatewayException.class);
    }

    @Test
    void retryPreservesPersistedRejectionProof() {
        var e = prepare();
        store.submitted(e.provider(), "SUBMISSION_REJECTED_400");
        makeDue(e.id());
        String claim = store.claim(e.id());
        store.retry(e, claim, "DB_RETRY");
        assertThat(
                        store.findByMerchantReference(e.merchantId(), e.merchantReference())
                                .get(0)
                                .submissionRejection())
                .isEqualTo("SUBMISSION_REJECTED_400");
    }

    @Test
    void financialEffectAndResolutionRollbackTogether() {
        var e = prepare();
        makeDue(e.id());
        String claim = store.claim(e.id());
        assertThatThrownBy(
                        () ->
                                tx.execute(
                                        x -> {
                                            store.locked(e.id(), claim);
                                            jdbc.update(
                                                    "INSERT INTO test_financial_effects VALUES (:ref,100)",
                                                    Map.of("ref", e.provider()));
                                            store.resolved(e, claim, "SUCCESSFUL", "receipt");
                                            throw new IllegalStateException(
                                                    "simulated outbox failure");
                                        }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM test_financial_effects WHERE reference_value=:ref",
                                Map.of("ref", e.provider()),
                                Integer.class))
                .isZero();
        assertThat(
                        store.findByMerchantReference(e.merchantId(), e.merchantReference())
                                .get(0)
                                .terminalStatus())
                .isNull();
    }

    @Test
    void completionIsNoLongerPollableOrClaimable() {
        var e = prepare();
        makeDue(e.id());
        String claim = store.claim(e.id());
        tx.execute(
                x -> {
                    store.resolved(e, claim, "SUCCESSFUL", "receipt");
                    return null;
                });
        assertThat(store.claim(e.id())).isNull();
        assertThat(store.due(100)).noneMatch(x -> x.id() == e.id());
        store.signal(e.provider());
        assertThat(
                        store.findByMerchantReference(e.merchantId(), e.merchantReference())
                                .get(0)
                                .terminalStatus())
                .isEqualTo("SUCCESSFUL");
    }

    @Test
    void merchantScopeCannotReadAnotherMerchantsStatus() {
        var e = prepare();
        assertThat(store.findByMerchantReference(43, e.merchantReference())).isEmpty();
    }
}
