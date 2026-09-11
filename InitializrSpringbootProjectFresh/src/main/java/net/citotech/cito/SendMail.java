package net.citotech.cito;

import net.citotech.cito.communication.email.SmtpMailSenderFactory;
import net.citotech.cito.security.PiiMasking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
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
            var configuration = new SmtpMailSenderFactory(template).build();
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(configuration.from());
            message.setTo(to);
            message.setSubject(subject);
            message.setText(text);
            configuration.sender().send(message);
            LOGGER.info("Cito email accepted by SMTP: recipient={}", PiiMasking.maskEmail(to));
        } catch (MailException | IllegalStateException ex) {
            LOGGER.error(
                    "Cito email delivery failed: recipient={}, category={}",
                    PiiMasking.maskEmail(to),
                    SmtpMailSenderFactory.failureCategory(ex));
        }
    }

    private NamedParameterJdbcTemplate effectiveJdbcTemplate() {
        return jdbcTemplate != null ? jdbcTemplate : applicationJdbcTemplate;
    }
}
