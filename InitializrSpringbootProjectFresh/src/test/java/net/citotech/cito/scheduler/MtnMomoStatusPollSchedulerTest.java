package net.citotech.cito.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import net.citotech.cito.Model.GateWayResponse;
import org.junit.jupiter.api.Test;

class MtnMomoStatusPollSchedulerTest {

    @Test
    void non200ResourceFailureIsNotAuthoritativeTerminalEvidence() {
        GateWayResponse response = response("404", "ERROR", "FAILED");

        assertThat(MtnMomoStatusPollScheduler.isAuthoritativeTerminal(response)).isFalse();
    }

    @Test
    void onlyHttp200OkExplicitSuccessOrFailureIsTerminal() {
        assertThat(
                        MtnMomoStatusPollScheduler.isAuthoritativeTerminal(
                                response("200", "OK", "SUCCESSFUL")))
                .isTrue();
        assertThat(
                        MtnMomoStatusPollScheduler.isAuthoritativeTerminal(
                                response("200", "OK", "FAILED")))
                .isTrue();
        assertThat(
                        MtnMomoStatusPollScheduler.isAuthoritativeTerminal(
                                response("200", "OK", "PENDING")))
                .isFalse();
        assertThat(
                        MtnMomoStatusPollScheduler.isAuthoritativeTerminal(
                                response("202", "OK", "SUCCESSFUL")))
                .isFalse();
    }

    @Test
    void reconciliationDoesNotDependBackOnOrchestrationOrAdapterRegistry() {
        var constructor = MtnMomoStatusPollScheduler.class.getDeclaredConstructors()[0];
        var dependencies =
                Arrays.stream(constructor.getParameterTypes()).map(Class::getName).toList();

        assertThat(dependencies)
                .doesNotContain(
                        "net.citotech.cito.PaymentOrchestrationService",
                        "net.citotech.cito.gateway.PaymentChannelRegistry",
                        "net.citotech.cito.gateway.ProviderEndpointExecutionService");
    }

    private GateWayResponse response(String http, String status, String transactionStatus) {
        GateWayResponse response = new GateWayResponse();
        response.setHttpStatus(http);
        response.setStatus(status);
        response.setTransactionStatus(transactionStatus);
        return response;
    }
}
