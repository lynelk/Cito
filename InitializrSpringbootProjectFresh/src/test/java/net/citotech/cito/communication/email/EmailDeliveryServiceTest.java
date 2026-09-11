package net.citotech.cito.communication.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * Covers the fail-safe guard of the B2 email delivery service: with no SMTP settings in the store,
 * a blank request, or a broken settings lookup, {@code EmailDeliveryService} returns a refundable
 * FAILED instead of attempting a network call or silently swallowing (the legacy {@code SendMail}
 * {@code printStackTrace} behaviour). The live SMTP send path itself is exercised by the
 * integration / provider-certification lane, mirroring the SMS provider-adapter practice.
 */
class EmailDeliveryServiceTest {

    @Test
    void smtpFailureRemainsRefundableAndDoesNotExposeProviderDetails() {
        var service = spy(new EmailDeliveryService(emptySettingsJdbc()));
        var sender = mock(JavaMailSenderImpl.class);
        doReturn(new SmtpMailSenderFactory.Configuration(sender, "from@example.test"))
                .when(service)
                .configuration();
        doThrow(new MailSendException("private provider response"))
                .when(sender)
                .send(any(SimpleMailMessage.class));

        var result = service.send(request());
        assertThat(result.status()).isEqualTo(EmailSendResult.Status.FAILED);
        assertThat(result.status().isRefundable()).isTrue();
        assertThat(result.trace())
                .contains("SMTP_REJECTED")
                .doesNotContain("private provider response");
    }

    @Test
    void sentResultRequiresSmtpAcceptance() {
        var service = spy(new EmailDeliveryService(emptySettingsJdbc()));
        var sender = mock(JavaMailSenderImpl.class);
        doReturn(new SmtpMailSenderFactory.Configuration(sender, "from@example.test"))
                .when(service)
                .configuration();

        var result = service.send(request());
        assertThat(result.status()).isEqualTo(EmailSendResult.Status.SENT);
        verify(sender).send(any(SimpleMailMessage.class));
    }

    private NamedParameterJdbcTemplate emptySettingsJdbc() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.query(
                        anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());
        return jdbcTemplate;
    }

    private EmailSendRequest request() {
        return new EmailSendRequest("ops@example.com", "Subject", "Body");
    }

    @Test
    void returnsRefundableFailureWhenNoSettingsAreConfigured() {
        EmailDeliveryService service = new EmailDeliveryService(emptySettingsJdbc());

        EmailSendResult result = service.send(request());

        assertThat(result.status()).isEqualTo(EmailSendResult.Status.FAILED);
        assertThat(result.status().isRefundable()).isTrue();
        assertThat(result.trace()).contains("mail.smtp.host");
    }

    @Test
    void returnsFailureForBlankRecipient() {
        EmailDeliveryService service = new EmailDeliveryService(emptySettingsJdbc());

        EmailSendResult result = service.send(new EmailSendRequest("  ", "Subject", "Body"));

        assertThat(result.status()).isEqualTo(EmailSendResult.Status.FAILED);
        assertThat(result.trace()).contains("to/body must not be blank");
    }

    @Test
    void returnsFailureForBlankBody() {
        EmailDeliveryService service = new EmailDeliveryService(emptySettingsJdbc());

        EmailSendResult result =
                service.send(new EmailSendRequest("ops@example.com", "Subject", " "));

        assertThat(result.status()).isEqualTo(EmailSendResult.Status.FAILED);
        assertThat(result.trace()).contains("to/body must not be blank");
    }

    @Test
    void returnsFailureForNullRequest() {
        EmailDeliveryService service = new EmailDeliveryService(emptySettingsJdbc());

        EmailSendResult result = service.send(null);

        assertThat(result.status()).isEqualTo(EmailSendResult.Status.FAILED);
        assertThat(result.trace()).contains("to/body must not be blank");
    }

    @Test
    void returnsRefundableFailureWhenSettingsLookupBreaks() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.query(
                        anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenThrow(new IllegalStateException("db down"));
        EmailDeliveryService service = new EmailDeliveryService(jdbcTemplate);

        EmailSendResult result = service.send(request());

        assertThat(result.status()).isEqualTo(EmailSendResult.Status.FAILED);
        assertThat(result.status().isRefundable()).isTrue();
        assertThat(result.trace()).contains("mail.smtp.host");
    }
}
