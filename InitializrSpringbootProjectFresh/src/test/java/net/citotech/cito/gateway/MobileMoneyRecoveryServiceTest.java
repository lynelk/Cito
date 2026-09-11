package net.citotech.cito.gateway;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.*;
import net.citotech.cito.Model.Transaction;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class MobileMoneyRecoveryServiceTest {
    @Test
    void mtnCallbackCannotFinalizeWithoutMatchingVerifiedCommercialAttributes() {
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        var executions = mock(MobileMoneyExecutionService.class);
        var mtn = mock(MtnMomoStatusClient.class);
        var recovery =
                new MobileMoneyRecoveryService(
                        jdbc,
                        executions,
                        mtn,
                        mock(MobileMoneyRecoveryLeaseStore.class),
                        mock(BoundedProviderVerification.class),
                        "PRODUCTION");
        Transaction tx = new Transaction();
        tx.setCurrency("UGX");
        tx.setOriginalAmountDecimal(new BigDecimal("10.1234"));
        tx.setPayer_number("256770000000");
        when(executions.load("transaction-id")).thenReturn(tx);
        when(executions.snapshot(anyMap())).thenReturn(Map.of());
        Map<String, Object> row =
                Map.of(
                        "channel_code",
                        "mtn_momo",
                        "transaction_id",
                        "transaction-id",
                        "provider_reference",
                        "provider-id",
                        "operation",
                        "COLLECT",
                        "environment",
                        "PRODUCTION",
                        "country_code",
                        "UG",
                        "currency_code",
                        "UGX");
        for (var status :
                List.of(
                        new MtnMomoStatusClient.VerifiedStatus(
                                "SUCCESSFUL",
                                "other-id",
                                "finance",
                                "10.1234",
                                "UGX",
                                "256770000000"),
                        new MtnMomoStatusClient.VerifiedStatus(
                                "SUCCESSFUL",
                                "transaction-id",
                                "finance",
                                "10.1235",
                                "UGX",
                                "256770000000"),
                        new MtnMomoStatusClient.VerifiedStatus(
                                "SUCCESSFUL",
                                "transaction-id",
                                "finance",
                                "10.1234",
                                "EUR",
                                "256770000000"),
                        new MtnMomoStatusClient.VerifiedStatus(
                                "SUCCESSFUL",
                                "transaction-id",
                                "finance",
                                "10.1234",
                                "UGX",
                                "256770000001"))) {
            when(mtn.verify(
                            anyString(),
                            anyString(),
                            anyString(),
                            anyString(),
                            anyString(),
                            anyMap()))
                    .thenReturn(status);
            assertThatThrownBy(() -> recovery.readOnlyOutcome(row))
                    .isInstanceOf(PaymentGatewayException.class);
        }
        verify(executions, never()).apply(anyString(), any());
        when(mtn.verify(anyString(), anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(
                        new MtnMomoStatusClient.VerifiedStatus(
                                "SUCCESSFUL",
                                "transaction-id",
                                "finance",
                                "10.1234",
                                "UGX",
                                "256770000000"));
        recovery.readOnlyOutcome(row);
        verify(executions, never()).apply(anyString(), any());
        verify(executions, never()).applyVerified(anyString(), any(), anyString());
    }
}
