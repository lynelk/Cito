package net.citotech.cito.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ensures password recovery always has safe, non-secret message templates.
 *
 * <p>Legacy deployments may have the settings table but no password-reset template rows. The
 * authentication controller expects these rows to exist, so a missing row previously caused a
 * 500 response after a valid reset token had already been issued. This bootstrap is idempotent and
 * never overwrites an operator-customized template.
 */
@Component
public class PasswordResetSettingsBootstrap implements ApplicationRunner {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(PasswordResetSettingsBootstrap.class);

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public PasswordResetSettingsBootstrap(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        int inserted = 0;
        inserted +=
                ensureTemplate(
                        "email_tmp_pw_reset",
                        "Password reset",
                        "Hello #name,\n\nYour Cito password reset verification code is #code.\n\n"
                                + "If you did not request this change, ignore this message and contact support if you are concerned.",
                        "Password recovery verification message");
        inserted +=
                ensureTemplate(
                        "email_tmp_on_password_reset_done",
                        "Password reset complete",
                        "Hello #name,\n\nYour Cito password was changed successfully.\n\n"
                                + "If you did not make this change, contact support immediately.",
                        "Password recovery completion message");

        if (inserted > 0) {
            LOGGER.warn("Installed {} missing password-recovery setting template(s)", inserted);
        }
    }

    private int ensureTemplate(String name, String label, String value, String description) {
        MapSqlParameterSource parameters =
                new MapSqlParameterSource()
                        .addValue("name", name)
                        .addValue("label", label)
                        .addValue("setting_value", value)
                        .addValue("setting_group", "communications")
                        .addValue("description", description);

        return jdbcTemplate.update(
                "INSERT INTO settings (name, label, setting_value, setting_group, description) "
                        + "SELECT :name, :label, :setting_value, :setting_group, :description "
                        + "WHERE NOT EXISTS (SELECT 1 FROM settings WHERE name=:name)",
                parameters);
    }
}
