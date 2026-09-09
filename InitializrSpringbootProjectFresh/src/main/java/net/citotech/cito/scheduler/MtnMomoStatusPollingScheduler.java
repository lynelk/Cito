package net.citotech.cito.scheduler;

import net.citotech.cito.gateway.MtnMomoCorrelationService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Recovers MTN transactions when the provider's single-attempt callback is missed. */
@Component
public class MtnMomoStatusPollingScheduler {
    private static final Logger logger = LoggerFactory.getLogger(MtnMomoStatusPollingScheduler.class);

    private final MtnMomoCorrelationService correlationService;
    private final int batchSize;

    public MtnMomoStatusPollingScheduler(
            MtnMomoCorrelationService correlationService,
            @Value("${cpay.mtn.status-poll.batch-size:100}") int batchSize) {
        this.correlationService = correlationService;
        this.batchSize = Math.max(1, Math.min(batchSize, 500));
    }

    @Scheduled(
            fixedDelayString = "${cpay.mtn.status-poll.scan-delay-ms:60000}",
            initialDelayString = "${cpay.mtn.status-poll.initial-delay-ms:30000}")
    @SchedulerLock(
            name = "mtnMomoPendingStatusPoll",
            lockAtMostFor = "PT5M",
            lockAtLeastFor = "PT5S")
    public void reconcilePending() {
        try {
            int finalized = correlationService.reconcilePending(batchSize);
            if (finalized > 0) {
                logger.info("Verified and finalized {} pending MTN transaction(s)", finalized);
            }
        } catch (Exception e) {
            logger.error("MTN pending-status poll failed: {}", e.getMessage(), e);
        }
    }
}
