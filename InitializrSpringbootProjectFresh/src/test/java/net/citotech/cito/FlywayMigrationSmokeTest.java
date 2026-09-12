package net.citotech.cito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Verifies the complete migration history on pristine MySQL, then a populated V126-to-latest upgrade,
 * while retaining database-level audit and financial protections.
 */
class FlywayMigrationSmokeTest {

    @Test
    void appliesAllMigrationsToCleanMysqlSchema() throws Exception {
        String url = System.getenv("DB_URL");
        Assumptions.assumeTrue(
                url != null && url.startsWith("jdbc:mysql:"),
                "Clean migration smoke test requires the dedicated MySQL integration environment");

        String username = requireEnvironment("DB_USERNAME");
        String password = requireEnvironment("DB_PASSWORD");

        MigrateResult baseline =
                Flyway.configure()
                        .dataSource(url, username, password)
                        .locations("classpath:db/migration")
                        .baselineOnMigrate(false)
                        .target("126")
                        .load()
                        .migrate();
        assertTrue(baseline.success);
        assertTrue(baseline.migrationsExecuted > 0, "A clean schema must execute migrations");
        var fixture =
                net.citotech.cito.scheduler.MtnReferenceCollationMysqlScenario.beforeUpgrade(
                        url, username, password);

        MigrateResult result =
                Flyway.configure()
                        .dataSource(url, username, password)
                        .locations("classpath:db/migration")
                        .baselineOnMigrate(false)
                        .load()
                        .migrate();

        assertTrue(result.success, "Flyway migration must succeed");
        assertTrue(result.migrationsExecuted > 0, "The V126 upgrade must execute later migrations");
        net.citotech.cito.scheduler.MtnReferenceCollationMysqlScenario.afterUpgrade(
                url, username, password, fixture);
        Flyway.configure()
                .dataSource(url, username, password)
                .locations("classpath:db/migration")
                .load()
                .validate();

        try (Connection connection = DriverManager.getConnection(url, username, password)) {
            assertEquals("130", latestSuccessfulVersion(connection));
            assertEquals(
                    1,
                    scalarCount(
                            connection,
                            "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() "
                                    + "AND table_name='api_endpoint_rates' AND column_name='amount' "
                                    + "AND numeric_precision=19 AND numeric_scale=4 "
                                    + "AND CAST(column_default AS DECIMAL(19,4))=0"));
            assertEquals(
                    1,
                    scalarCount(
                            connection,
                            "SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() "
                                    + "AND table_name='api_endpoint_rates' AND constraint_name='chk_api_rate_nonnegative' "
                                    + "AND constraint_type='CHECK'"));
            assertEquals(4, auditProtectionTriggerCount(connection));
            assertEquals(6, treasuryAccountRoleCount(connection, "MASTER"));
            assertEquals(6, treasuryAccountRoleCount(connection, "COLLECTION"));
            assertEquals(6, treasuryAccountRoleCount(connection, "DISBURSEMENT"));
            assertEquals(3, mtnScopeAccountCount(connection, "PRODUCTION", "UGX"));
            assertEquals(3, mtnScopeAccountCount(connection, "SANDBOX", "EUR"));
            assertEquals(0, nonZeroSeededTreasuryAccountCount(connection));
            assertEquals(4, defaultOperationalMerchantCount(connection));
            assertEquals(4, configuredOperationalSettingCount(connection));
            assertEquals(20, defaultOperationalChannelBalanceCount(connection));
            assertEquals(0, defaultOperationalMerchantUserCount(connection));
            assertEquals(0, nonZeroDefaultOperationalBalanceCount(connection));
        }
        net.citotech.cito.ledger.LedgerReservationMysqlScenario.run(
                new org.springframework.jdbc.datasource.DriverManagerDataSource(
                        url, username, password));
        net.citotech.cito.gateway.MobileMoneyMysqlScenario.run(url, username, password);
        net.citotech.cito.communication.outbox.NotificationMysqlScenario.run(
                url, username, password);
    }

    private static String latestSuccessfulVersion(Connection connection) throws SQLException {
        try (PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT version FROM flyway_schema_history "
                                        + "WHERE success = 1 AND version IS NOT NULL "
                                        + "ORDER BY installed_rank DESC LIMIT 1");
                ResultSet resultSet = statement.executeQuery()) {
            assertTrue(resultSet.next(), "Flyway history must contain a successful migration");
            return resultSet.getString(1);
        }
    }

    private static int auditProtectionTriggerCount(Connection connection) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "SELECT COUNT(*) FROM information_schema.triggers "
                                + "WHERE trigger_schema = DATABASE() "
                                + "AND trigger_name IN (?, ?, ?, ?)")) {
            statement.setString(1, "audit_trail_no_update");
            statement.setString(2, "audit_trail_no_delete");
            statement.setString(3, "merchants_audit_trail_no_update");
            statement.setString(4, "merchants_audit_trail_no_delete");
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), "Trigger count query must return a row");
                return resultSet.getInt(1);
            }
        }
    }

    private static int treasuryAccountRoleCount(Connection connection, String role)
            throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "SELECT COUNT(*) FROM provider_treasury_accounts WHERE account_role=?")) {
            statement.setString(1, role);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), "Treasury account role count must return a row");
                return resultSet.getInt(1);
            }
        }
    }

    private static int mtnScopeAccountCount(
            Connection connection, String environment, String currency) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "SELECT COUNT(*) FROM provider_treasury_accounts "
                                + "WHERE channel_code='mtn_momo' AND environment=? AND currency_code=?")) {
            statement.setString(1, environment);
            statement.setString(2, currency);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), "MTN treasury scope count must return a row");
                return resultSet.getInt(1);
            }
        }
    }

    private static int nonZeroSeededTreasuryAccountCount(Connection connection)
            throws SQLException {
        try (PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT COUNT(*) FROM provider_treasury_accounts "
                                        + "WHERE book_balance<>0 OR reserved_balance<>0 "
                                        + "OR pending_outgoing_balance<>0 OR pending_incoming_balance<>0");
                ResultSet resultSet = statement.executeQuery()) {
            assertTrue(resultSet.next(), "Seeded treasury balance count must return a row");
            return resultSet.getInt(1);
        }
    }

    private static int defaultOperationalMerchantCount(Connection connection) throws SQLException {
        return scalarCount(
                connection,
                "SELECT COUNT(*) FROM merchants WHERE account_number IN "
                        + "('CITO-FLOAT-STOCK','CITO-GATEWAY-REVENUE','CITO-GATEWAY-SUSPENSE','CITO-SMS-REVENUE')");
    }

    private static int configuredOperationalSettingCount(Connection connection)
            throws SQLException {
        return scalarCount(
                connection,
                "SELECT COUNT(*) FROM settings WHERE "
                        + "(name='float_stock_account' AND setting_value='CITO-FLOAT-STOCK') OR "
                        + "(name='revenue_account' AND setting_value='CITO-GATEWAY-REVENUE') OR "
                        + "(name='suspense_account' AND setting_value='CITO-GATEWAY-SUSPENSE') OR "
                        + "(name='sms_revenue_account' AND setting_value='CITO-SMS-REVENUE')");
    }

    private static int defaultOperationalChannelBalanceCount(Connection connection)
            throws SQLException {
        return scalarCount(
                connection,
                "SELECT COUNT(*) FROM merchant_channel_balances b JOIN merchants m ON m.id=b.merchant_id "
                        + "WHERE m.account_number IN "
                        + "('CITO-FLOAT-STOCK','CITO-GATEWAY-REVENUE','CITO-GATEWAY-SUSPENSE','CITO-SMS-REVENUE')");
    }

    private static int defaultOperationalMerchantUserCount(Connection connection)
            throws SQLException {
        return scalarCount(
                connection,
                "SELECT COUNT(*) FROM merchant_admins ma JOIN merchants m ON m.id=ma.merchant_id "
                        + "WHERE m.account_number IN "
                        + "('CITO-FLOAT-STOCK','CITO-GATEWAY-REVENUE','CITO-GATEWAY-SUSPENSE','CITO-SMS-REVENUE')");
    }

    private static int nonZeroDefaultOperationalBalanceCount(Connection connection)
            throws SQLException {
        return scalarCount(
                connection,
                "SELECT COUNT(*) FROM merchant_channel_balances b JOIN merchants m ON m.id=b.merchant_id "
                        + "WHERE m.account_number IN "
                        + "('CITO-FLOAT-STOCK','CITO-GATEWAY-REVENUE','CITO-GATEWAY-SUSPENSE','CITO-SMS-REVENUE') "
                        + "AND (b.available_balance<>0 OR b.ledger_balance<>0 OR b.pending_balance<>0)");
    }

    private static int scalarCount(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet resultSet = statement.executeQuery()) {
            assertTrue(resultSet.next(), "Count query must return a row");
            return resultSet.getInt(1);
        }
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required for this integration test");
        }
        return value;
    }
}
