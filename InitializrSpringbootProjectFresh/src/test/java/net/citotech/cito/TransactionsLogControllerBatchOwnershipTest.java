package net.citotech.cito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import net.citotech.cito.Model.Payment;
import net.citotech.cito.ledger.DoubleEntryLedgerService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

class TransactionsLogControllerBatchOwnershipTest {

    @Test
    void merchantPaymentLookupIsTenantScoped() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.query(
                        anyString(),
                        any(MapSqlParameterSource.class),
                        ArgumentMatchers.<RowMapper<Payment>>any()))
                .thenReturn(List.of());
        TransactionsLogController controller = new TransactionsLogController();
        ReflectionTestUtils.setField(controller, "jdbcTemplate", jdbcTemplate);

        assertThat(controller.getPaymentById(17L, 23L)).isNull();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> parameters =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbcTemplate)
                .query(
                        sql.capture(),
                        parameters.capture(),
                        ArgumentMatchers.<RowMapper<Payment>>any());
        assertThat(sql.getValue()).contains("id = :id AND merchant_id = :merchant_id");
        assertThat(parameters.getValue().getValue("id")).isEqualTo(17L);
        assertThat(parameters.getValue().getValue("merchant_id")).isEqualTo(23L);
    }

    @Test
    void stoppedBatchReleasesOnlyItsMerchantScopedReservationNamespace() {
        DoubleEntryLedgerService ledgerService = mock(DoubleEntryLedgerService.class);
        when(ledgerService.releaseReservationsBySourcePrefix(23L, "batch-payout:17:"))
                .thenReturn(3);
        TransactionsLogController controller = new TransactionsLogController();
        ReflectionTestUtils.setField(controller, "ledgerService", ledgerService);
        Payment payment = new Payment();
        payment.setId(17L);
        payment.setMerchant_id(23L);

        assertThat(controller.releaseStoppedBatchReservations(payment)).isEqualTo(3);
        verify(ledgerService).releaseReservationsBySourcePrefix(23L, "batch-payout:17:");
    }
}
