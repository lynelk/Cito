package net.citotech.cito.gateway;

import static org.assertj.core.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class BoundedProviderVerificationTest {
    @Test
    void returnsReadOnlyResultWithoutInventingFinalization() {
        var executor = new BoundedProviderVerification();
        try {
            assertThat(executor.execute(() -> "PENDING", 1000)).isEqualTo("PENDING");
        } finally {
            executor.close();
        }
    }

    @Test
    void slowProviderCannotHoldTheRecoveryCallerIndefinitely() throws Exception {
        var executor = new BoundedProviderVerification();
        var started = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        try {
            assertThatThrownBy(
                            () ->
                                    executor.execute(
                                            () -> {
                                                started.countDown();
                                                try {
                                                    new CountDownLatch(1).await();
                                                } catch (InterruptedException error) {
                                                    interrupted.countDown();
                                                    throw error;
                                                }
                                                return "SUCCESSFUL";
                                            },
                                            100))
                    .isInstanceOf(PaymentGatewayException.class)
                    .hasMessageContaining("deadline");
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.close();
        }
    }

    @Test
    void providerErrorsAreNotConvertedToSuccessOrFailureOutcomes() {
        var executor = new BoundedProviderVerification();
        try {
            assertThatThrownBy(
                            () ->
                                    executor.execute(
                                            () -> {
                                                throw new PaymentGatewayException(
                                                        "synthetic provider unavailable");
                                            },
                                            1000))
                    .hasMessage("synthetic provider unavailable");
        } finally {
            executor.close();
        }
    }
}
