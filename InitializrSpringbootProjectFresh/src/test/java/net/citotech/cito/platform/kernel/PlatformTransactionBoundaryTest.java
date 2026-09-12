package net.citotech.cito.platform.kernel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import net.citotech.cito.billing.outbox.OutboxWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

/** Real Spring proxies and JDBC transactions; no provider calls and no production database. */
class PlatformTransactionBoundaryTest {
    private AnnotationConfigApplicationContext application;
    private JdbcTemplate jdbc;
    private PlatformEventContract events;
    private PlatformOperationalSignalService signals;
    private final PlatformTenantContext tenant = new PlatformTenantContext(9L, 42L, "worker", "SYSTEM",
            Set.of(), "PRODUCTION", "CITO_WORKER", "request-1");

    @BeforeEach
    void openContext() {
        application = new AnnotationConfigApplicationContext(TestConfiguration.class);
        jdbc = application.getBean(JdbcTemplate.class);
        events = application.getBean(PlatformEventContract.class);
        signals = application.getBean(PlatformOperationalSignalService.class);
    }

    @AfterEach
    void closeContext() {
        application.close();
    }

    @Test
    void publishingWithoutBusinessTransactionIsRejectedBeforeWritingOutbox() {
        assertThatThrownBy(this::publish).isInstanceOf(IllegalTransactionStateException.class);
        verifyNoInteractions(application.getBean(OutboxWriter.class));
        assertThat(effectCount()).isZero();
    }

    @Test
    void ownerRollbackAlsoRollsBackItsPlatformEvent() {
        TransactionTemplate transaction = new TransactionTemplate(application.getBean(PlatformTransactionManager.class));
        assertThatThrownBy(() -> transaction.execute(status -> {
            publish();
            throw new IllegalStateException("Business command failed");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(effectCount()).isZero();
    }

    @Test
    void signalAuditFailureRollsBackBothEffects() {
        application.getBean(AtomicBoolean.class).set(true);
        assertThatThrownBy(this::signal).isInstanceOf(IllegalStateException.class);
        assertThat(effectCount()).isZero();
    }

    @Test
    void successfulSignalCommitsEventAndAuditTogether() {
        assertThat(signal()).matches("[0-9a-f-]{36}");
        assertThat(effectCount()).isEqualTo(2);
    }

    private String publish() {
        return events.publish(tenant, "COMMUNICATION_SMS", "COMMUNICATION", "message.sent", "message-1", null, Map.of());
    }

    private String signal() {
        return signals.record(tenant, "COMMUNICATION_SMS", "COMMUNICATION", "message-1",
                "DELIVERY", "WARNING", "DEGRADED", "Delivery needs review", "", "/operations");
    }

    private int effectCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM effects", Integer.class);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class TestConfiguration {
        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource("jdbc:h2:mem:platform-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        }

        @Bean
        JdbcTemplate jdbc(DataSource dataSource) {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            jdbc.execute("CREATE TABLE effects (kind VARCHAR(20) NOT NULL)");
            return jdbc;
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        AtomicBoolean failAudit() {
            return new AtomicBoolean();
        }

        @Bean
        OutboxWriter outbox(JdbcTemplate jdbc) {
            OutboxWriter writer = mock(OutboxWriter.class);
            when(writer.write(anyString(), anyString(), anyString(), any())).thenAnswer(invocation -> {
                jdbc.update("INSERT INTO effects(kind) VALUES ('event')");
                return 1L;
            });
            return writer;
        }

        @Bean
        PlatformEventContract events(OutboxWriter writer) {
            return new PlatformEventOutboxGateway(writer);
        }

        @Bean
        PlatformAuditContract audit(JdbcTemplate jdbc, AtomicBoolean failAudit) {
            PlatformAuditContract audit = mock(PlatformAuditContract.class);
            doAnswer(invocation -> {
                jdbc.update("INSERT INTO effects(kind) VALUES ('audit')");
                if (failAudit.get()) throw new IllegalStateException("Audit unavailable");
                return null;
            }).when(audit).record(any(), anyString(), anyString(), anyString(), any());
            return audit;
        }

        @Bean
        PlatformOperationalSignalService signals(PlatformEventContract events, PlatformAuditContract audit) {
            return new PlatformOperationalSignalService(events, audit);
        }
    }
}
