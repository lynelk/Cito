package net.citotech.cito.communication.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/** Opt-in, bounded connection/authentication probe. Never sends an email or logs credentials. */
@Component
@ConditionalOnProperty(name = "cito.smtp.check-on-startup", havingValue = "true")
public class SmtpConnectionCheck implements ApplicationRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(SmtpConnectionCheck.class);
    private final SmtpMailSenderFactory factory;

    public SmtpConnectionCheck(NamedParameterJdbcTemplate jdbcTemplate) {
        this.factory = new SmtpMailSenderFactory(jdbcTemplate);
    }

    @Override
    public void run(ApplicationArguments arguments) {
        long started = System.nanoTime();
        try {
            var sender = factory.build().sender();
            LOGGER.info(
                    "Cito SMTP probe started: host={}, port={}, implicit_tls={}, starttls={}",
                    sender.getHost(),
                    sender.getPort(),
                    sender.getJavaMailProperties().getProperty("mail.smtp.ssl.enable"),
                    sender.getJavaMailProperties().getProperty("mail.smtp.starttls.enable"));
            sender.testConnection();
            LOGGER.info(
                    "Cito SMTP probe succeeded: connection and configured authentication verified; no email sent; elapsed_ms={}",
                    (System.nanoTime() - started) / 1_000_000);
        } catch (Exception exception) {
            // Mail failure must not take payments or the rest of the application offline.
            LOGGER.error(
                    "Cito SMTP probe failed: category={}, elapsed_ms={}",
                    SmtpMailSenderFactory.failureCategory(exception),
                    (System.nanoTime() - started) / 1_000_000);
        }
    }
}
