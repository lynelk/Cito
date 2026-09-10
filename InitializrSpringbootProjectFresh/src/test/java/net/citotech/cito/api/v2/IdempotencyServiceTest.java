package net.citotech.cito.api.v2;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.citotech.cito.gateway.PaymentGatewayException;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class IdempotencyServiceTest {
    private IdempotencyService service;
    private NamedParameterJdbcTemplate jdbc;

    @BeforeEach
    void database() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL(
                "jdbc:h2:mem:"
                        + UUID.randomUUID()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc = new NamedParameterJdbcTemplate(source);
        jdbc.getJdbcTemplate()
                .execute(
                        "CREATE TABLE cpay_idempotency_keys(id BIGINT AUTO_INCREMENT PRIMARY KEY,merchant_number VARCHAR(100),idempotency_key VARCHAR(255),request_hash VARCHAR(64),response_body TEXT,status VARCHAR(50),created_at TIMESTAMP,UNIQUE(merchant_number,idempotency_key))");
        service = new IdempotencyService(jdbc, new ObjectMapper());
    }

    @Test
    void concurrentClaimsPermitExactlyOneSubmission() throws Exception {
        AtomicInteger outbound = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService workers = Executors.newFixedThreadPool(8)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++)
                futures.add(
                        workers.submit(
                                () -> {
                                    try {
                                        start.await();
                                        if (service.findExistingBody("merchant", "key", "body")
                                                .isEmpty()) outbound.incrementAndGet();
                                    } catch (PaymentGatewayException expected) {
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                    }
                                }));
            start.countDown();
            for (Future<?> future : futures) future.get(10, TimeUnit.SECONDS);
        }
        assertThat(outbound.get()).isEqualTo(1);
        assertThat(
                        jdbc.getJdbcTemplate()
                                .queryForObject(
                                        "SELECT COUNT(*) FROM cpay_idempotency_keys",
                                        Integer.class))
                .isEqualTo(1);
    }

    @Test
    void committedResultReplaysAndChangedBodyConflicts() {
        assertThat(service.findExistingBody("merchant", "key", "body")).isEmpty();
        service.recordBody("merchant", "key", "body", "{\"status\":\"PENDING\"}");
        assertThat(service.findExistingBody("merchant", "key", "body"))
                .contains("{\"status\":\"PENDING\"}");
        assertThatThrownBy(() -> service.findExistingBody("merchant", "key", "changed"))
                .isInstanceOf(PaymentGatewayException.class);
    }

    @Test
    void failedDatabaseDoesNotAuthorizeSubmission() {
        jdbc.getJdbcTemplate().execute("DROP TABLE cpay_idempotency_keys");
        assertThatThrownBy(() -> service.findExistingBody("merchant", "key", "body"))
                .isInstanceOf(PaymentGatewayException.class)
                .hasMessageContaining("no submission");
    }

    @Test
    void independentMerchantsMayUseTheSameKey() {
        assertThat(service.findExistingBody("one", "key", "body")).isEmpty();
        assertThat(service.findExistingBody("two", "key", "body")).isEmpty();
    }

    @Test
    void responseCannotBeRecordedWithoutAClaim() {
        assertThatThrownBy(() -> service.recordBody("merchant", "unclaimed", "body", "{}"))
                .isInstanceOf(PaymentGatewayException.class);
    }
}
