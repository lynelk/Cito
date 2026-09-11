package net.citotech.cito.gateway;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import net.citotech.cito.Model.GateWayResponse;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class MobileMoneyRecoveryOrchestrationTest {
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final MobileMoneyExecutionService executions = mock(MobileMoneyExecutionService.class);
    private final MobileMoneyRecoveryLeaseStore leases = mock(MobileMoneyRecoveryLeaseStore.class);
    private final BoundedProviderVerification bounded = mock(BoundedProviderVerification.class);
    private final MobileMoneyRecoveryService recovery = spy(new MobileMoneyRecoveryService(
            jdbc, executions, mock(MtnMomoStatusClient.class), leases, bounded, "SANDBOX"));
    private final Map<String, Object> row = Map.of("transaction_id", "original-reference");

    @Test
    void verifiedProofUsesTheExactClaimAndOnlyTheCanonicalFinalizer() throws Exception {
        when(leases.due("SANDBOX", 10)).thenReturn(List.of(row));
        when(leases.claim("original-reference", "SANDBOX")).thenReturn("current-claim");
        GateWayResponse proof = new GateWayResponse();
        proof.setTransactionStatus("SUCCESSFUL");
        doReturn(proof).when(recovery).readOnlyOutcome(row);
        when(bounded.execute(any())).thenAnswer(call -> ((Callable<?>) call.getArgument(0)).call());
        recovery.reconcilePending();
        verify(executions).applyVerified("original-reference", proof, "current-claim");
        verify(executions, never()).apply(anyString(), any());
        verify(leases, never()).retry(anyString(), anyString(), anyString());
    }

    @Test
    void losingClaimCannotStartProviderWorkOrApplyAnOutcome() {
        when(leases.due("SANDBOX", 10)).thenReturn(List.of(row));
        when(leases.claim("original-reference", "SANDBOX")).thenReturn(null);
        recovery.reconcilePending();
        verifyNoInteractions(bounded, executions);
        verify(recovery, never()).readOnlyOutcome(any());
    }

    @Test
    void timeoutKeepsFundsUntouchedAndDefersOnlyTheOwnedClaim() {
        when(leases.due("SANDBOX", 10)).thenReturn(List.of(row));
        when(leases.claim("original-reference", "SANDBOX")).thenReturn("current-claim");
        when(bounded.execute(any())).thenThrow(new PaymentGatewayException("synthetic timeout"));
        recovery.reconcilePending();
        verifyNoInteractions(executions);
        verify(leases).retry("original-reference", "current-claim", "VERIFICATION_DEFERRED");
    }

    @Test
    void aFailedFinancialCommitNeverFallsBackToUnfencedFinalization() {
        when(leases.due("SANDBOX", 10)).thenReturn(List.of(row));
        when(leases.claim("original-reference", "SANDBOX")).thenReturn("current-claim");
        GateWayResponse proof = new GateWayResponse();
        when(bounded.execute(any())).thenReturn(proof);
        doThrow(new PaymentGatewayException("expired or failed commit"))
                .when(executions).applyVerified("original-reference", proof, "current-claim");
        recovery.reconcilePending();
        verify(leases).retry("original-reference", "current-claim", "VERIFICATION_DEFERRED");
        verify(executions, never()).apply(anyString(), any());
    }

    @Test
    void aCallbackIsOnlyARateLimitedWakeUpHint() {
        when(jdbc.queryForList(anyString(), any(org.springframework.jdbc.core.namedparam.MapSqlParameterSource.class)))
                .thenReturn(List.of(row));
        recovery.signal("airtel_open_api", "original-reference", "original-reference");
        verify(leases).signal("original-reference");
        verifyNoInteractions(bounded, executions);
    }
}
