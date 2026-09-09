package net.citotech.cito.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

/** Covers per-gateway timeout resolution and MTN's provider-verification exception. */
@SuppressWarnings({"rawtypes", "unchecked"})
class TransactionTimeoutSchedulerTest {

    @Test
    void skipsMtnAndUsesDefaultTimeoutForOtherGateways() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);

        when(jdbcTemplate.queryForList(
                        contains("DISTINCT gateway_id"),
                        any(MapSqlParameterSource.class),
                        eq(String.class)))
                .thenReturn(List.of("MTNMoMoPaymentGateway", "SafariComPaymentGateway"));

        when(jdbcTemplate.query(
                        contains("FROM settings"),
                        argThat(
                                (MapSqlParameterSource p) ->
                                        p != null
                                                && "transaction_timeout_minutes_SafariComPaymentGateway"
                                                        .equals(p.getValue("name"))),
                        any(RowMapper.class)))
                .thenReturn(List.of());

        List<Integer> timeoutMinutesUsed = new ArrayList<>();
        List<String> gatewaysQueried = new ArrayList<>();
        when(jdbcTemplate.query(
                        contains("merchant_transactions_log"),
                        any(MapSqlParameterSource.class),
                        any(RowMapper.class)))
                .thenAnswer(
                        invocation -> {
                            MapSqlParameterSource params = invocation.getArgument(1);
                            timeoutMinutesUsed.add((Integer) params.getValue("timeout_minutes"));
                            gatewaysQueried.add((String) params.getValue("gateway_id"));
                            return List.of();
                        });

        TransactionTimeoutScheduler scheduler = new TransactionTimeoutScheduler();
        ReflectionTestUtils.setField(scheduler, "jdbcTemplate", jdbcTemplate);
        ReflectionTestUtils.setField(scheduler, "transactionManager", transactionManager);
        ReflectionTestUtils.setField(scheduler, "defaultTimeoutMinutes", 30);

        scheduler.timeoutStalePendingTransactions();

        assertThat(timeoutMinutesUsed).containsExactly(30);
        assertThat(gatewaysQueried).containsExactly("SafariComPaymentGateway");
        verify(jdbcTemplate, never())
                .query(
                        contains("FROM settings"),
                        argThat(
                                (MapSqlParameterSource p) ->
                                        p != null
                                                && "transaction_timeout_minutes_MTNMoMoPaymentGateway"
                                                        .equals(p.getValue("name"))),
                        any(RowMapper.class));
    }
}
