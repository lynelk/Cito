package net.citotech.cito.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Covers audit B7: the scheduler's javadoc claimed a per-gateway configurable timeout, but the code
 * used one hardcoded 30-minute constant for every gateway. It now looks up a {@code
 * transaction_timeout_minutes_<gateway_id>} setting per gateway, falling back to the default when
 * unset. MTN MoMo is intentionally excluded because its asynchronous transactions are reconciled
 * against MTN's status endpoint instead of being failed by elapsed time alone.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
class TransactionTimeoutSchedulerTest {

    @Test
    void skipsMtnAndUsesDefaultTimeoutForOtherGateways() throws Exception {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);

        when(jdbcTemplate.queryForList(
                        contains("DISTINCT gateway_id"),
                        any(MapSqlParameterSource.class),
                        eq(String.class)))
                .thenReturn(List.of("AirtelMoneyOpenApiPaymentGateway", "SafariComPaymentGateway"));

        ResultSet airtelSettingRow =
                settingRow(
                        "transaction_timeout_minutes_AirtelMoneyOpenApiPaymentGateway",
                        "Airtel timeout override",
                        "45");
        when(jdbcTemplate.query(
                        contains("FROM settings"),
                        argThat(
                                (MapSqlParameterSource p) ->
                                        p != null
                                                && "transaction_timeout_minutes_AirtelMoneyOpenApiPaymentGateway"
                                                        .equals(p.getValue("name"))),
                        any(RowMapper.class)))
                .thenAnswer(
                        invocation -> {
                            RowMapper mapper = invocation.getArgument(2);
                            return List.of(mapper.mapRow(airtelSettingRow, 1));
                        });
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
        when(jdbcTemplate.query(
                        contains("merchant_transactions_log"),
                        any(MapSqlParameterSource.class),
                        any(RowMapper.class)))
                .thenAnswer(
                        invocation -> {
                            MapSqlParameterSource params = invocation.getArgument(1);
                            timeoutMinutesUsed.add((Integer) params.getValue("timeout_minutes"));
                            return List.of();
                        });

        TransactionTimeoutScheduler scheduler = scheduler(jdbcTemplate, transactionManager);
        scheduler.timeoutStalePendingTransactions();

        assertThat(timeoutMinutesUsed).containsExactlyInAnyOrder(45, 30);
    }

    @Test
    void doesNotTimeoutMtnPendingTransactionsWithoutProviderEvidence() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);

        when(jdbcTemplate.queryForList(
                        contains("DISTINCT gateway_id"),
                        any(MapSqlParameterSource.class),
                        eq(String.class)))
                .thenReturn(List.of("MTNMoMoPaymentGateway"));

        List<Integer> timeoutMinutesUsed = new ArrayList<>();
        when(jdbcTemplate.query(
                        contains("merchant_transactions_log"),
                        any(MapSqlParameterSource.class),
                        any(RowMapper.class)))
                .thenAnswer(
                        invocation -> {
                            MapSqlParameterSource params = invocation.getArgument(1);
                            Object timeout = params.getValue("timeout_minutes");
                            if (timeout instanceof Integer value) timeoutMinutesUsed.add(value);
                            return List.of();
                        });

        TransactionTimeoutScheduler scheduler = scheduler(jdbcTemplate, transactionManager);
        scheduler.timeoutStalePendingTransactions();

        assertThat(timeoutMinutesUsed).isEmpty();
    }

    private TransactionTimeoutScheduler scheduler(
            NamedParameterJdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager) {
        TransactionTimeoutScheduler scheduler = new TransactionTimeoutScheduler();
        ReflectionTestUtils.setField(scheduler, "jdbcTemplate", jdbcTemplate);
        ReflectionTestUtils.setField(scheduler, "transactionManager", transactionManager);
        ReflectionTestUtils.setField(scheduler, "defaultTimeoutMinutes", 30);
        return scheduler;
    }

    private ResultSet settingRow(String name, String label, String value) throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getString("name")).thenReturn(name);
        when(row.getString("label")).thenReturn(label);
        when(row.getString("setting_value")).thenReturn(value);
        when(row.getLong("id")).thenReturn(1L);
        when(row.getString("setting_group")).thenReturn("transactions");
        when(row.getString("description")).thenReturn("");
        return row;
    }
}
