package net.citotech.cito.scheduler;

import java.util.logging.Level;
import java.util.logging.Logger;
import net.citotech.cito.analytics.GrowthMilestoneProjectionService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Keeps the immutable growth milestone projection aligned with durable operational state. */
@Component
public class GrowthMilestoneProjectionScheduler {
    private static final Logger logger =
            Logger.getLogger(GrowthMilestoneProjectionScheduler.class.getName());

    private final GrowthMilestoneProjectionService projectionService;

    @Value("${cpay.growth.milestone-projection.enabled:true}")
    private boolean enabled;

    public GrowthMilestoneProjectionScheduler(GrowthMilestoneProjectionService projectionService) {
        this.projectionService = projectionService;
    }

    @Scheduled(
            fixedDelayString = "${cpay.growth.milestone-projection.delay-ms:300000}",
            initialDelayString = "${cpay.growth.milestone-projection.initial-delay-ms:60000}")
    @SchedulerLock(
            name = "growthMilestoneProjection",
            lockAtMostFor = "PT5M",
            lockAtLeastFor = "PT5S")
    public void reconcile() {
        if (!enabled) {
            return;
        }
        try {
            projectionService.reconcile();
        } catch (Exception ex) {
            logger.log(Level.WARNING, "Growth milestone projection failed: " + ex.getMessage(), ex);
        }
    }
}
