package net.citotech.cito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Release evidence only: local disposable MySQL, synthetic rows, no application/provider calls. */
class PopulatedV125UpgradeTest {
    @Test
    void upgradesPopulated125Through128WithoutChangingHistoricalPayment() {
        String url = System.getenv("DB_URL");
        assertTrue(url != null && url.startsWith("jdbc:mysql://127.0.0.1:3306/cpayadmin_qa?"),
                "This evidence test is restricted to its disposable loopback CI database");
        String user = System.getenv("DB_USERNAME");
        String password = System.getenv("DB_PASSWORD");
        var initial = Flyway.configure().dataSource(url, user, password)
                .locations("classpath:db/migration").baselineOnMigrate(false)
                .target("125").load().migrate();
        assertTrue(initial.success);
        var jdbc = new NamedParameterJdbcTemplate(new DriverManagerDataSource(url, user, password));
        Map<String, Object> key = Map.of("reference", "release-qa-populated-125");
        assertEquals(1, jdbc.update("INSERT INTO merchant_transactions_log"
                + " (merchant_id,gateway_id,original_amount,charges,status,tx_unique_id,tx_gateway_ref,tx_merchant_ref,tx_type,tx_cost,currency)"
                + " SELECT id,'MTNMoMoPaymentGateway',37.1250,0.1250,'PENDING',:reference,"
                + " '00000000-0000-4000-8000-000000000125',:reference,'PAYIN',0.0500,'UGX'"
                + " FROM merchants WHERE account_number='CITO-FLOAT-STOCK'", key));
        var before = jdbc.queryForMap("SELECT * FROM merchant_transactions_log WHERE tx_unique_id=:reference", key);
        var flyway = Flyway.configure().dataSource(url, user, password)
                .locations("classpath:db/migration").baselineOnMigrate(false).load();
        var result = flyway.migrate();
        assertTrue(result.success);
        assertEquals(3, result.migrationsExecuted, "Must apply V126, V127 and V128");
        flyway.validate();
        var after = new LinkedHashMap<>(jdbc.queryForMap(
                "SELECT * FROM merchant_transactions_log WHERE tx_unique_id=:reference", key));
        assertEquals("PRODUCTION", after.remove("execution_environment"));
        assertEquals(before, after, "Every historical amount, state, party/reference and timestamp must be preserved");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM merchant_production_transactions WHERE tx_unique_id=:reference", key, Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM merchant_sandbox_transactions WHERE tx_unique_id=:reference", key, Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM mobile_money_executions WHERE transaction_id=:reference", key, Integer.class));
        assertEquals("128", jdbc.queryForObject("SELECT version FROM flyway_schema_history WHERE success=1 AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1", Map.of(), String.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=0", Map.of(), Integer.class));
        System.out.println("POPULATED_V125_TO_V128_PASS: three migrations, unchanged historical payment, correct environment views, no invented execution and Flyway validation passed");
    }
}
