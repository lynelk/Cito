package net.citotech.cito.communication.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.stereotype.Service;

/** SMTP delivery with explicit results and the same secure transport as password recovery. */
@Service
public class EmailDeliveryService {
    private static final Logger LOGGER = LoggerFactory.getLogger(EmailDeliveryService.class);
    private final SmtpMailSenderFactory factory;

    public EmailDeliveryService(NamedParameterJdbcTemplate jdbcTemplate) {
        this.factory = new SmtpMailSenderFactory(jdbcTemplate);
    }

    public EmailSendResult send(EmailSendRequest request) {
        if (request == null || blank(request.to()) || blank(request.body())) {
            return EmailSendResult.failed("to/body must not be blank", "");
        }
        try {
            var configuration = configuration();
            var sender = configuration.sender();
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(configuration.from());
            message.setTo(request.to());
            message.setSubject(request.subject());
            message.setText(request.body());
            sender.send(message);
            // SENT means accepted by SMTP, not independently verified inbox delivery.
            return EmailSendResult.sent(sender.getHost() + ":" + sender.getPort(), "");
        } catch (Exception exception) {
            String category = SmtpMailSenderFactory.failureCategory(exception);
            LOGGER.warn("Cito Communications email failed: category={}", category);
            return EmailSendResult.failed(
                    "CONFIGURATION".equals(category)
                            ? "SMTP configuration invalid: check mail.smtp.host, credentials and TLS settings"
                            : "SMTP failure: " + category,
                    "");
        }
    }

    SmtpMailSenderFactory.Configuration configuration() {
        return factory.build();
    }

    private boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
