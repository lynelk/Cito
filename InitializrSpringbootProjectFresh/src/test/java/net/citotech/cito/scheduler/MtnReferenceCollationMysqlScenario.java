package net.citotech.cito.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import net.citotech.cito.ledger.PaymentLedgerSettlementService;
import net.citotech.cito.sharedprovider.SharedProviderAccessService;
import net.citotech.cito.treasury.ProviderTreasuryService;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

/** Disposable MySQL-only upgrade regression. No provider or settlement method is invoked. */
public final class MtnReferenceCollationMysqlScenario {
    private static final String KEY = "staging-recovery-collation-ci";

    public static Fixture beforeUpgrade(String url, String username, String password) {
        var jdbc = jdbc(url, username, password);
        // Reproduce the observed V126 state independently of the CI server's default collation.
        jdbc.update(
                "ALTER TABLE provider_treasury_reservations MODIFY COLUMN provider_reference"
                        + " VARCHAR(191) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL",
                Map.of());
        int inserted =
                jdbc.update(
                        "INSERT INTO provider_treasury_reservations"
                                + " (idempotency_key,treasury_account_id,merchant_id,merchant_number,"
                                + "operation,direction,amount,currency_code,merchant_reference,"
                                + "provider_reference,status,updated_at)"
                                + " SELECT :key,a.id,m.id,m.account_number,'COLLECTION','INCOMING',"
                                + "10.1234,'EUR',:key,'Aa100000-0000-4000-8000-000000000001',"
                                + "'PENDING',DATE_SUB(NOW(),INTERVAL 60 SECOND)"
                                + " FROM provider_treasury_accounts a CROSS JOIN merchants m"
                                + " WHERE a.channel_code='mtn_momo' AND a.environment='SANDBOX'"
                                + " AND a.account_role='COLLECTION' AND a.currency_code='EUR'"
                                + " AND m.account_number='CITO-FLOAT-STOCK' LIMIT 1",
                        Map.of("key", KEY));
        assertEquals(1, inserted, "The isolated upgrade fixture must exist");
        var row =
                jdbc.queryForMap(
                        "SELECT * FROM provider_treasury_reservations WHERE idempotency_key=:key",
                        Map.of("key", KEY));
        DataAccessException failure =
                assertThrows(
                        DataAccessException.class,
                        () ->
                                ReflectionTestUtils.invokeMethod(
                                        scheduler(jdbc), "pendingSharedAfter", 0L));
        assertTrue(failure.getMostSpecificCause() instanceof SQLException);
        assertEquals(1267, ((SQLException) failure.getMostSpecificCause()).getErrorCode());
        return new Fixture(((Number) row.get("id")).longValue(), row);
    }

    public static void afterUpgrade(String url, String username, String password, Fixture fixture) {
        var jdbc = jdbc(url, username, password);
        assertEquals(
                fixture.row(),
                jdbc.queryForMap(
                        "SELECT * FROM provider_treasury_reservations WHERE id=:id",
                        Map.of("id", fixture.id())),
                "Migration must preserve every reservation value, including reference and timestamps");
        assertEquals(new BigDecimal("10.1234"), fixture.row().get("amount"));
        assertEquals(
                2,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.columns"
                                + " WHERE table_schema=DATABASE() AND column_name='provider_reference'"
                                + " AND table_name IN ('mtn_momo_correlations','provider_treasury_reservations')"
                                + " AND collation_name='utf8mb4_unicode_ci'",
                        Map.of(),
                        Integer.class));
        List<Map<String, Object>> pending =
                ReflectionTestUtils.invokeMethod(scheduler(jdbc), "pendingSharedAfter", 0L);
        assertNotNull(pending);
        assertTrue(
                pending.stream()
                        .anyMatch(row -> ((Number) row.get("id")).longValue() == fixture.id()),
                "The actual scheduler selector must recover the pending fixture after V127");
        assertNotNull(ReflectionTestUtils.invokeMethod(scheduler(jdbc), "pendingLegacyAfter", 0L));
        assertEquals(
                1,
                jdbc.update(
                        "DELETE FROM provider_treasury_reservations WHERE id=:id AND idempotency_key=:key",
                        Map.of("id", fixture.id(), "key", KEY)));
    }

    private static NamedParameterJdbcTemplate jdbc(String url, String username, String password) {
        return new NamedParameterJdbcTemplate(new DriverManagerDataSource(url, username, password));
    }

    private static MtnMomoStatusPollScheduler scheduler(NamedParameterJdbcTemplate jdbc) {
        return new MtnMomoStatusPollScheduler(
                jdbc,
                mock(PlatformTransactionManager.class),
                mock(PaymentLedgerSettlementService.class),
                mock(SharedProviderAccessService.class),
                mock(ProviderTreasuryService.class));
    }

    public record Fixture(long id, Map<String, Object> row) {}

    private MtnReferenceCollationMysqlScenario() {}
}
