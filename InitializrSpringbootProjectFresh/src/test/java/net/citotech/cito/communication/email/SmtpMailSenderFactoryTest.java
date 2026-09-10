package net.citotech.cito.communication.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.citotech.cito.Model.Setting;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@Timeout(10)
class SmtpMailSenderFactoryTest {
    private Map<String, String> environment(int port) {
        Map<String, String> values = new HashMap<>();
        values.put("CITO_SMTP_HOST", "localhost");
        values.put("CITO_SMTP_PORT", Integer.toString(port));
        values.put("CITO_SMTP_CONNECTION_TIMEOUT_MS", "500");
        values.put("CITO_SMTP_READ_TIMEOUT_MS", "500");
        values.put("CITO_SMTP_WRITE_TIMEOUT_MS", "500");
        return values;
    }

    @Test
    void port465UsesImplicitTlsEvenWhenLegacyStarttlsIsEnabled() {
        var values = environment(465);
        values.put("CITO_SMTP_STARTTLS", "true");
        var sender = new SmtpMailSenderFactory(null, values::get).build().sender();
        assertThat(sender.getJavaMailProperties())
                .containsEntry("mail.smtp.ssl.enable", "true")
                .containsEntry("mail.smtp.starttls.enable", "false")
                .containsEntry("mail.smtp.ssl.checkserveridentity", "true");
    }

    @Test
    void environmentOverridesDatabaseWithoutLosingMailboxCredentials() {
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        Map<String, String> database =
                Map.of(
                        "mail.smtp.host", "mail.example.test",
                        "mail.smtp.port", "465",
                        "mail.smtp.username", "mailer@example.test",
                        "mail.smtp.password", " significant whitespace ",
                        "mail.smtp.from", "noreply@example.test");
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenAnswer(
                        invocation -> {
                            MapSqlParameterSource parameters = invocation.getArgument(1);
                            String value = database.get(parameters.getValue("name"));
                            if (value == null) {
                                return List.of();
                            }
                            Setting setting = new Setting();
                            setting.setSetting_value(value);
                            return List.of(setting);
                        });
        var configuration =
                new SmtpMailSenderFactory(jdbc, Map.of("CITO_SMTP_PORT", "587")::get).build();
        assertThat(configuration.sender().getPort()).isEqualTo(587);
        assertThat(configuration.sender().getHost()).isEqualTo("mail.example.test");
        assertThat(configuration.sender().getPassword()).isEqualTo(" significant whitespace ");
        assertThat(configuration.from()).isEqualTo("noreply@example.test");
        assertThat(configuration.sender().getJavaMailProperties())
                .containsEntry("mail.smtp.starttls.required", "true")
                .containsEntry("mail.smtp.auth", "true");
    }

    @Test
    void refusesPlaintextAndInfiniteTimeoutConfiguration() {
        var values = environment(587);
        values.put("CITO_SMTP_STARTTLS", "false");
        assertThatThrownBy(() -> new SmtpMailSenderFactory(null, values::get).build())
                .isInstanceOf(IllegalStateException.class);
        values.put("CITO_SMTP_STARTTLS", "true");
        values.put("CITO_SMTP_READ_TIMEOUT_MS", "0");
        assertThatThrownBy(() -> new SmtpMailSenderFactory(null, values::get).build())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void implicitTlsSendsClientHelloBeforeWaitingForSmtpGreeting() throws Exception {
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            server.setSoTimeout(3000);
            var firstBytes =
                    executor.submit(
                            () -> {
                                try (Socket socket = server.accept()) {
                                    socket.setSoTimeout(3000);
                                    return socket.getInputStream().readNBytes(5);
                                }
                            });
            var values = environment(server.getLocalPort());
            values.put("CITO_SMTP_SSL", "true");
            var sender = new SmtpMailSenderFactory(null, values::get).build().sender();
            // The peer deliberately closes after the TLS record header, before any credentials.
            assertThat(catchThrowable(sender::testConnection)).isNotNull();
            byte[] header = firstBytes.get(3, TimeUnit.SECONDS);
            assertThat(header).hasSize(5);
            assertThat(header[0]).isEqualTo((byte) 0x16);
            assertThat(header[1]).isEqualTo((byte) 0x03);
        }
    }

    @Test
    void unresponsiveSmtpGreetingTimesOutInsteadOfHanging() throws Exception {
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            server.setSoTimeout(3000);
            var observed =
                    executor.submit(
                            () -> {
                                try (Socket socket = server.accept()) {
                                    socket.setSoTimeout(3000);
                                    return socket.getInputStream().read();
                                }
                            });
            var sender =
                    new SmtpMailSenderFactory(null, environment(server.getLocalPort())::get)
                            .build()
                            .sender();
            long started = System.nanoTime();
            Throwable failure = catchThrowable(sender::testConnection);
            assertThat(SmtpMailSenderFactory.failureCategory(failure)).isEqualTo("TIMEOUT");
            assertThat(Duration.ofNanos(System.nanoTime() - started))
                    .isLessThan(Duration.ofSeconds(3));
            assertThat(observed.get(3, TimeUnit.SECONDS)).isEqualTo(-1);
        }
    }

    @Test
    void serverWithoutStarttlsCannotReceiveAuthenticationCredentials() throws Exception {
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            server.setSoTimeout(3000);
            var nextCommand =
                    executor.submit(
                            () -> {
                                try (Socket socket = server.accept()) {
                                    socket.setSoTimeout(3000);
                                    var output = socket.getOutputStream();
                                    var input =
                                            new BufferedReader(
                                                    new InputStreamReader(
                                                            socket.getInputStream(),
                                                            StandardCharsets.US_ASCII));
                                    output.write(
                                            "220 test SMTP\r\n"
                                                    .getBytes(StandardCharsets.US_ASCII));
                                    output.flush();
                                    assertThat(input.readLine()).startsWith("EHLO ");
                                    output.write(
                                            "250-test\r\n250 AUTH LOGIN PLAIN\r\n"
                                                    .getBytes(StandardCharsets.US_ASCII));
                                    output.flush();
                                    return input.readLine();
                                }
                            });
            var values = environment(server.getLocalPort());
            values.put("CITO_SMTP_USERNAME", "test@example.test");
            values.put("CITO_SMTP_PASSWORD", "test-only-password");
            var sender = new SmtpMailSenderFactory(null, values::get).build().sender();
            assertThat(catchThrowable(sender::testConnection)).isNotNull();
            String command = nextCommand.get(3, TimeUnit.SECONDS);
            assertThat(command == null || "QUIT".equals(command)).isTrue();
        }
    }
}
