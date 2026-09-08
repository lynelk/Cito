package net.citotech.cito;

import java.util.Properties;
import net.citotech.cito.Model.Setting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

/**
 * SMTP mail sender used by legacy and current Cito flows.
 *
 * <p>Some legacy callers instantiate this class directly instead of obtaining the Spring bean. The
 * application-level JDBC reference keeps those callers functional without copying credentials into
 * code. Production SMTP values can be injected through CITO_SMTP_* variables; existing database
 * settings remain supported for backwards compatibility.
 */
@Component
public class SendMail implements EmailService {

    private static final Logger LOGGER = LoggerFactory.getLogger(SendMail.class);
    private static volatile NamedParameterJdbcTemplate applicationJdbcTemplate;

    private NamedParameterJdbcTemplate jdbcTemplate;

    @Autowired
    public void setJdbcTemplate(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        applicationJdbcTemplate = jdbcTemplate;
    }

    public void sendSimpleMessage(String to, String subject, String text) {
        sendWithTemplate(to, subject, text, effectiveJdbcTemplate());
    }

    public void sendSimpleMessage(
            String to, String subject, String text, NamedParameterJdbcTemplate jdbcTemplate) {
        if (jdbcTemplate != null) {
            this.jdbcTemplate = jdbcTemplate;
            applicationJdbcTemplate = jdbcTemplate;
        }
        sendWithTemplate(to, subject, text, effectiveJdbcTemplate());
    }

    private void sendWithTemplate(
            String to, String subject, String text, NamedParameterJdbcTemplate template) {
        try {
            JavaMailSenderImpl mailSender = buildMailSender(template);
            String username = setting("mail.smtp.username", "CITO_SMTP_USERNAME", "", template);
            String from = setting("mail.smtp.from", "CITO_SMTP_FROM", username, template);
            if (isBlank(from)) {
                from = "noreply@cito.coresynergi.es";
            }

            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(to);
            message.setSubject(subject);
            message.setText(text);
            mailSender.send(message);
        } catch (MailException | IllegalStateException ex) {
            LOGGER.error(
                    "Cito email delivery failed for subject '{}': {}", subject, ex.getMessage());
        }
    }

    private JavaMailSenderImpl buildMailSender(NamedParameterJdbcTemplate template) {
        String host = setting("mail.smtp.host", "CITO_SMTP_HOST", "", template);
        String portValue = setting("mail.smtp.port", "CITO_SMTP_PORT", "587", template);
        String username = setting("mail.smtp.username", "CITO_SMTP_USERNAME", "", template);
        String password = setting("mail.smtp.password", "CITO_SMTP_PASSWORD", "", template);
        String auth =
                setting(
                        "mail.smtp.auth",
                        "CITO_SMTP_AUTH",
                        isBlank(username) ? "false" : "true",
                        template);
        String starttls =
                setting("mail.smtp.starttls.enable", "CITO_SMTP_STARTTLS", "true", template);

        if (isBlank(host)) {
            throw new IllegalStateException(
                    "SMTP is not configured; set CITO_SMTP_HOST or the mail.smtp.host setting");
        }

        int port;
        try {
            port = Integer.parseInt(portValue.trim());
        } catch (NumberFormatException ex) {
            throw new IllegalStateException("SMTP port is invalid");
        }

        JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
        mailSender.setHost(host);
        mailSender.setPort(port);
        mailSender.setUsername(username);
        mailSender.setPassword(password);

        Properties props = mailSender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", auth);
        props.put("mail.smtp.starttls.enable", starttls);
        props.put("mail.debug", "false");

        return mailSender;
    }

    private String setting(
            String databaseKey,
            String environmentKey,
            String defaultValue,
            NamedParameterJdbcTemplate template) {
        String environmentValue = System.getenv(environmentKey);
        if (!isBlank(environmentValue)) {
            return environmentValue.trim();
        }

        if (template != null) {
            try {
                Setting configured = Common.getSettings(databaseKey, template);
                if (configured != null && !isBlank(configured.getSetting_value())) {
                    return configured.getSetting_value().trim();
                }
            } catch (RuntimeException ex) {
                LOGGER.warn("Unable to read SMTP setting '{}' from the database", databaseKey);
            }
        }
        return defaultValue == null ? "" : defaultValue;
    }

    private NamedParameterJdbcTemplate effectiveJdbcTemplate() {
        return jdbcTemplate != null ? jdbcTemplate : applicationJdbcTemplate;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
