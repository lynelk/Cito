package net.citotech.cito.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Disabled-by-default bootstrap for recovering a deployment whose admin table is completely empty.
 *
 * <p>The bootstrap is deliberately narrow: it runs only when an explicit administrator email is
 * configured and the {@code admins} table contains zero rows. The created account has an unusable
 * placeholder password and is marked to require a password change, so the operator must establish
 * the real credential through the normal password-reset flow.
 */
@Component
public class InitialAdminBootstrap implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(InitialAdminBootstrap.class);
    private static final String PLACEHOLDER_PASSWORD_HASH = "0".repeat(64);
    private static final List<String> ADMIN_PRIVILEGES =
            List.of(
                    "ACCESS_REPORTS",
                    "UPDATE_SETTINGS",
                    "ACCESS_SETTINGS",
                    "UPDATED_MERCHANT",
                    "UPDATE_MERCHANT",
                    "CREATE_MERCHANT",
                    "ACTIVATE_MERCHANT",
                    "DEBIT_MERCHANT",
                    "CREDIT_MERCHANT",
                    "ACCESS_AUDITTRAIL",
                    "ACCESS_ADMIN",
                    "CREATE_ADMIN",
                    "UPDATE_ADMIN",
                    "DELETE_ADMIN",
                    "ACCESS_TRANSACTION_LOG");

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final String configuredEmail;

    public InitialAdminBootstrap(
            NamedParameterJdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate,
            @Value("${cpay.bootstrap-admin.email:}") String configuredEmail) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.configuredEmail = configuredEmail == null ? "" : configuredEmail.trim();
    }

    @Override
    public void run(ApplicationArguments args) {
        if (configuredEmail.isBlank()) {
            return;
        }

        String email = configuredEmail.toLowerCase(Locale.ROOT);
        if (!isPlausibleEmail(email)) {
            throw new IllegalStateException("Configured first-admin email is invalid");
        }

        BootstrapResult result =
                transactionTemplate.execute(
                        status -> {
                            long adminCount = adminCount();
                            if (adminCount != 0L) {
                                return BootstrapResult.SKIPPED_EXISTING_ADMINS;
                            }
                            return createFirstAdmin(email);
                        });

        if (result == BootstrapResult.CREATED) {
            LOGGER.warn(
                    "Created the first Cito administrator {} with password reset required; remove "
                            + "CPAY_BOOTSTRAP_ADMIN_EMAIL after verification",
                    PiiMasking.maskEmail(email));
        } else {
            LOGGER.info(
                    "First-admin bootstrap skipped because administrator records already exist; "
                            + "configured target was {}",
                    PiiMasking.maskEmail(email));
        }
    }

    private BootstrapResult createFirstAdmin(String email) {
        String emailHash = sha256(email);
        String operationId = "first-admin-v1-" + emailHash.substring(0, 16);

        MapSqlParameterSource operationParameters =
                new MapSqlParameterSource()
                        .addValue("operation_id", operationId)
                        .addValue("target_email_sha256", emailHash);
        jdbcTemplate.update(
                "INSERT INTO admin_bootstrap_operations "
                        + "(operation_id, target_email_sha256, removed_admin_count, granted_privilege_count) "
                        + "VALUES (:operation_id, :target_email_sha256, 0, 0)",
                operationParameters);

        MapSqlParameterSource adminParameters =
                new MapSqlParameterSource()
                        .addValue("name", "Cito Super Admin")
                        .addValue("email", email)
                        .addValue("phone", "")
                        .addValue("password", PLACEHOLDER_PASSWORD_HASH)
                        .addValue("status", "ACTIVE");
        int inserted =
                jdbcTemplate.update(
                        "INSERT INTO admins "
                                + "(name, email, phone, password, must_change_password, status) "
                                + "VALUES (:name, :email, :phone, :password, 1, :status)",
                        adminParameters);
        if (inserted != 1) {
            throw new IllegalStateException("First administrator was not inserted");
        }

        Long adminId =
                jdbcTemplate.queryForObject(
                        "SELECT id FROM admins WHERE LOWER(email)=:email",
                        new MapSqlParameterSource("email", email),
                        Long.class);
        if (adminId == null) {
            throw new IllegalStateException("First administrator id could not be resolved");
        }

        for (String privilege : ADMIN_PRIVILEGES) {
            jdbcTemplate.update(
                    "INSERT INTO admin_privileges (admin_id, privilege) "
                            + "VALUES (:admin_id, :privilege)",
                    new MapSqlParameterSource()
                            .addValue("admin_id", adminId)
                            .addValue("privilege", privilege));
        }

        jdbcTemplate.update(
                "UPDATE admin_bootstrap_operations SET target_admin_id=:admin_id, "
                        + "granted_privilege_count=:privilege_count, completed_at=CURRENT_TIMESTAMP "
                        + "WHERE operation_id=:operation_id",
                new MapSqlParameterSource()
                        .addValue("admin_id", adminId)
                        .addValue("privilege_count", ADMIN_PRIVILEGES.size())
                        .addValue("operation_id", operationId));

        return BootstrapResult.CREATED;
    }

    private long adminCount() {
        Long count =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM admins", new MapSqlParameterSource(), Long.class);
        return count == null ? 0L : count;
    }

    private static boolean isPlausibleEmail(String email) {
        int at = email.indexOf('@');
        return at > 0 && at < email.length() - 1 && email.indexOf('.', at) > at + 1;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of()
                    .formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private enum BootstrapResult {
        CREATED,
        SKIPPED_EXISTING_ADMINS
    }
}
