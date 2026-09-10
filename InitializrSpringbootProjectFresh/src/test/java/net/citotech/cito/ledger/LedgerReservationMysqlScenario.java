package net.citotech.cito.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import net.citotech.cito.gateway.PaymentGatewayException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Deterministic MySQL reservation regression; synthetic funds and no provider calls. */
public final class LedgerReservationMysqlScenario {
    private LedgerReservationMysqlScenario() {}

    public static void run(DataSource dataSource) throws Exception {
        NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(dataSource);
        DoubleEntryLedgerService service = new DoubleEntryLedgerService(jdbc);
        DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource);
        for (boolean postedDebit : List.of(false, true)) {
            for (boolean batch : List.of(false, true)) {
                long merchant = (postedDebit ? 2301L : 2201L) + (batch ? 1 : 0);
                String prefix = "STALE-SNAPSHOT-" + merchant;
                new TransactionTemplate(manager)
                        .executeWithoutResult(
                                seed ->
                                        service.post(
                                                prefix + "-SEED",
                                                "PAYMENT",
                                                prefix + "-SEED",
                                                "synthetic opening liability",
                                                List.of(
                                                        entry(
                                                                "merchant:"
                                                                        + merchant
                                                                        + ":UGX:merchant_liability",
                                                                "MERCHANT_LIABILITY",
                                                                "MERCHANT",
                                                                merchant,
                                                                "CR",
                                                                "100000",
                                                                "UGX"),
                                                        entry(
                                                                "provider:mtn_momo:UGX:stale-snapshot:"
                                                                        + merchant,
                                                                "CONTROL",
                                                                "PROVIDER",
                                                                9001L,
                                                                "DR",
                                                                "100000",
                                                                "UGX"))));
                TransactionTemplate outer = new TransactionTemplate(manager);
                outer.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
                try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
                    outer.executeWithoutResult(
                            status -> {
                                jdbc.getJdbcTemplate()
                                        .queryForObject(
                                                "SELECT COUNT(*) FROM ledger_reservations",
                                                Integer.class);
                                Future<?> committed =
                                        executor.submit(
                                                () ->
                                                        new TransactionTemplate(manager)
                                                                .executeWithoutResult(
                                                                        inner ->
                                                                                reduceAvailableBalance(
                                                                                        service,
                                                                                        prefix,
                                                                                        merchant,
                                                                                        postedDebit)));
                                try {
                                    committed.get(30, java.util.concurrent.TimeUnit.SECONDS);
                                } catch (Exception failure) {
                                    throw new IllegalStateException(failure);
                                }
                                if (batch) {
                                    var result =
                                            service.reserveAll(
                                                    merchant,
                                                    "UGX",
                                                    List.of(
                                                            new DoubleEntryLedgerService
                                                                    .ReservationCommand(
                                                                    prefix + "-SECOND",
                                                                    prefix + "-PAYMENT-SECOND",
                                                                    new BigDecimal("80000"))));
                                    assertThat(result.reserved()).isFalse();
                                    assertThat(result.available())
                                            .isEqualByComparingTo("20000.0000");
                                } else {
                                    assertThatThrownBy(
                                                    () ->
                                                            service.reserve(
                                                                    prefix + "-SECOND",
                                                                    merchant,
                                                                    prefix + "-PAYMENT-SECOND",
                                                                    new BigDecimal("80000"),
                                                                    "UGX"))
                                            .isInstanceOf(PaymentGatewayException.class)
                                            .hasMessageContaining(
                                                    "Insufficient ledger-derived available balance");
                                }
                            });
                }
                assertThat(service.availableMerchantBalance(merchant, "UGX"))
                        .isEqualByComparingTo("20000.0000");
                Integer count =
                        jdbc.getJdbcTemplate()
                                .queryForObject(
                                        "SELECT COUNT(*) FROM ledger_reservations WHERE merchant_id=? AND reservation_status='RESERVED'",
                                        Integer.class,
                                        merchant);
                assertThat(count).isEqualTo(postedDebit ? 0 : 1);
            }
        }
    }

    private static void reduceAvailableBalance(
            DoubleEntryLedgerService service, String prefix, long merchant, boolean postedDebit) {
        if (postedDebit) {
            service.post(
                    prefix + "-DEBIT",
                    "PAYMENT",
                    prefix + "-PAYMENT-DEBIT",
                    "synthetic committed liability reduction",
                    List.of(
                            entry(
                                    "merchant:" + merchant + ":UGX:merchant_liability",
                                    "MERCHANT_LIABILITY",
                                    "MERCHANT",
                                    merchant,
                                    "DR",
                                    "80000",
                                    "UGX"),
                            entry(
                                    "provider:mtn_momo:UGX:stale-snapshot:" + merchant,
                                    "CONTROL",
                                    "PROVIDER",
                                    9001L,
                                    "CR",
                                    "80000",
                                    "UGX")));
        } else {
            service.reserve(
                    prefix + "-FIRST",
                    merchant,
                    prefix + "-PAYMENT-FIRST",
                    new BigDecimal("80000"),
                    "UGX");
        }
    }

    private static LedgerEntryCommand entry(
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
                "synthetic stale-snapshot regression");
    }
}
