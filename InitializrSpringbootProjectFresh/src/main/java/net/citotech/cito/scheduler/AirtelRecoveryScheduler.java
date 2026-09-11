package net.citotech.cito.scheduler;

import net.citotech.cito.gateway.AirtelRecoveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Each item has its own fenced DB lease; two replicas cannot finalise the same payment. */
@Component
public class AirtelRecoveryScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(AirtelRecoveryScheduler.class);
    private final AirtelRecoveryService service;
    private final boolean enabled;
    private final int batchSize;

    public AirtelRecoveryScheduler(
            AirtelRecoveryService service,
            @Value("${cpay.airtel.recovery.enabled:true}") boolean enabled,
            @Value("${cpay.airtel.recovery.batch-size:20}") int batchSize) {
        this.service = service;
        this.enabled = enabled;
        this.batchSize = Math.max(1, Math.min(batchSize, 100));
    }

    @Scheduled(
            fixedDelayString = "${cpay.airtel.recovery.delay-ms:30000}",
            initialDelayString = "${cpay.airtel.recovery.initial-delay-ms:30000}")
    public void recover() {
        if (!enabled) return;
        try {
            int count = service.reconcile(batchSize);
            if (count > 0) LOG.info("Airtel recoveries finalised: {}", count);
        } catch (Exception ignored) {
            LOG.warn("Airtel recovery scan deferred; unresolved payments retain their holds");
        }
    }
}
