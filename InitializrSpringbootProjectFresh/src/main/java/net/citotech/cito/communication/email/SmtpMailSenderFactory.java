package net.citotech.cito.communication.email;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Properties;
import java.util.function.Function;
import javax.net.ssl.SSLException;
import net.citotech.cito.Common;
import net.citotech.cito.Model.Setting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/** Shared SMTP configuration for password recovery and Communications email delivery. */
public final class SmtpMailSenderFactory {
    private static final Logger LOGGER = LoggerFactory.getLogger(SmtpMailSenderFactory.class);
    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final Function<String, String> environment;

    public SmtpMailSenderFactory(NamedParameterJdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, System::getenv);
    }

    SmtpMailSenderFactory(
            NamedParameterJdbcTemplate jdbcTemplate, Function<String, String> environment) {
        this.jdbcTemplate = jdbcTemplate;
        this.environment = environment;
    }

    public Configuration build() {
        String host = setting("host", "HOST", "");
        if (host.isBlank()) {
            throw new IllegalStateException("mail.smtp.host is not configured");
        }
        int port = positiveInteger(setting("port", "PORT", "587"), 65535);
        String username = setting("username", "USERNAME", "");
        String password = setting("password", "PASSWORD", "");
        boolean auth = flag(setting("auth", "AUTH", Boolean.toString(!username.isBlank())));
        boolean ssl = flag(setting("ssl.enable", "SSL", Boolean.toString(port == 465)));
        // Implicit TLS and STARTTLS are different handshakes. Port 465 must start with TLS.
        boolean starttls = !ssl && flag(setting("starttls.enable", "STARTTLS", "true"));
        if ((port == 465 && !ssl) || (!ssl && !starttls)) {
            throw new IllegalStateException("SMTP requires implicit TLS or STARTTLS");
        }
        if (auth && (username.isBlank() || password.isBlank())) {
            throw new IllegalStateException("SMTP authentication credentials are incomplete");
        }

        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(host);
        sender.setPort(port);
        sender.setUsername(username);
        sender.setPassword(password);
        Properties properties = sender.getJavaMailProperties();
        properties.setProperty("mail.transport.protocol", "smtp");
        properties.setProperty("mail.smtp.auth", Boolean.toString(auth));
        properties.setProperty("mail.smtp.ssl.enable", Boolean.toString(ssl));
        properties.setProperty("mail.smtp.ssl.checkserveridentity", "true");
        properties.setProperty("mail.smtp.starttls.enable", Boolean.toString(starttls));
        properties.setProperty("mail.smtp.starttls.required", Boolean.toString(starttls));
        properties.setProperty(
                "mail.smtp.connectiontimeout",
                timeout("connectiontimeout", "CONNECTION_TIMEOUT_MS"));
        properties.setProperty("mail.smtp.timeout", timeout("timeout", "READ_TIMEOUT_MS"));
        properties.setProperty(
                "mail.smtp.writetimeout", timeout("writetimeout", "WRITE_TIMEOUT_MS"));
        properties.setProperty("mail.debug", "false");
        String from = setting("from", "FROM", username);
        if (from.isBlank()) {
            from = "noreply@cito.coresynergi.es";
        }
        return new Configuration(sender, from);
    }

    private String timeout(String databaseSuffix, String environmentSuffix) {
        return Integer.toString(
                positiveInteger(setting(databaseSuffix, environmentSuffix, "10000"), 60000));
    }

    private static int positiveInteger(String value, int maximum) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed > 0 && parsed <= maximum) {
                return parsed;
            }
        } catch (NumberFormatException ignored) {
            // Report a non-sensitive configuration failure, not the supplied value.
        }
        throw new IllegalStateException("SMTP port or timeout is invalid");
    }

    private static boolean flag(String value) {
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
            throw new IllegalStateException("SMTP boolean setting is invalid");
        }
        return Boolean.parseBoolean(value);
    }

    private String setting(String databaseSuffix, String environmentSuffix, String defaultValue) {
        String value = environment.apply("CITO_SMTP_" + environmentSuffix);
        if (value == null || value.isBlank()) {
            if (jdbcTemplate != null) {
                try {
                    Setting configured =
                            Common.getSettings("mail.smtp." + databaseSuffix, jdbcTemplate);
                    value = configured == null ? null : configured.getSetting_value();
                } catch (RuntimeException exception) {
                    LOGGER.warn("Unable to read SMTP setting mail.smtp.{}", databaseSuffix);
                }
            }
        }
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        // Passwords may contain significant leading/trailing whitespace.
        return "password".equals(databaseSuffix) ? value : value.trim();
    }

    /** Stable diagnostics only: provider text can include credentials or message content. */
    public static String failureCategory(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 12; depth++) {
            if (current instanceof MailAuthenticationException
                    || current instanceof AuthenticationFailedException) {
                return "AUTHENTICATION";
            }
            if (current instanceof UnknownHostException) {
                return "DNS";
            }
            if (current instanceof SocketTimeoutException) {
                return "TIMEOUT";
            }
            if (current instanceof SSLException) {
                return "TLS";
            }
            if (current instanceof ConnectException) {
                return "CONNECTION";
            }
            if (current instanceof IllegalStateException) {
                return "CONFIGURATION";
            }
            Throwable next = current.getCause();
            if (next == null && current instanceof MessagingException messaging) {
                next = messaging.getNextException();
            }
            current = next;
        }
        return "SMTP_REJECTED";
    }

    public record Configuration(JavaMailSenderImpl sender, String from) {}
}
