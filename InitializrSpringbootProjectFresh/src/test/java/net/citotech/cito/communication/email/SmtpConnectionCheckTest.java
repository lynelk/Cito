package net.citotech.cito.communication.email;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import jakarta.mail.AuthenticationFailedException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.mail.javamail.JavaMailSenderImpl;

class SmtpConnectionCheckTest {
    @Test
    void authenticationProbeSendsNoEmailAndFailureDoesNotStopApplication() throws Exception {
        var sender = mock(JavaMailSenderImpl.class);
        when(sender.getJavaMailProperties()).thenReturn(new java.util.Properties());
        doThrow(new AuthenticationFailedException("test failure")).when(sender).testConnection();
        try (var construction =
                mockConstruction(
                        SmtpMailSenderFactory.class,
                        (factory, context) ->
                                when(factory.build())
                                        .thenReturn(
                                                new SmtpMailSenderFactory.Configuration(
                                                        sender, "test@example.test")))) {
            var check = new SmtpConnectionCheck(mock(NamedParameterJdbcTemplate.class));
            assertThatCode(() -> check.run(new DefaultApplicationArguments()))
                    .doesNotThrowAnyException();
            verify(sender).testConnection();
            verify(sender).getHost();
            verify(sender).getPort();
            verify(sender, org.mockito.Mockito.times(2)).getJavaMailProperties();
            verifyNoMoreInteractions(sender);
        }
    }
}
