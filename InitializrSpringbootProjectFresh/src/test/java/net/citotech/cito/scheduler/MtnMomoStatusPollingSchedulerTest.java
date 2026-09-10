package net.citotech.cito.scheduler;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import net.citotech.cito.gateway.MtnMomoCorrelationService;
import org.junit.jupiter.api.Test;

class MtnMomoStatusPollingSchedulerTest {

    @Test
    void delegatesPendingReconciliationWithBoundedBatchSize() {
        MtnMomoCorrelationService correlationService = mock(MtnMomoCorrelationService.class);
        when(correlationService.reconcilePending(100)).thenReturn(2);
        MtnMomoStatusPollingScheduler scheduler =
                new MtnMomoStatusPollingScheduler(correlationService, 100);

        scheduler.reconcilePending();

        verify(correlationService).reconcilePending(100);
    }

    @Test
    void capsConfiguredBatchSizeAtFiveHundred() {
        MtnMomoCorrelationService correlationService = mock(MtnMomoCorrelationService.class);
        MtnMomoStatusPollingScheduler scheduler =
                new MtnMomoStatusPollingScheduler(correlationService, 5000);

        scheduler.reconcilePending();

        verify(correlationService).reconcilePending(500);
    }
}
