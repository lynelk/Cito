package net.citotech.cito.communication.notification;

import java.util.Map;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Each projection transaction commits recipient evidence and the canonical delivery outbox
 * together.
 */
@Component
public class NotificationEventWorker {
    private final NamedParameterJdbcTemplate jdbc;
    private final NotificationOrchestrator orchestrator;

    public NotificationEventWorker(
            NamedParameterJdbcTemplate jdbc, NotificationOrchestrator orchestrator) {
        this.jdbc = jdbc;
        this.orchestrator = orchestrator;
    }

    @Scheduled(fixedDelayString = "${cpay.notification.event-delay-ms:5000}")
    @SchedulerLock(
            name = "notificationEventWorker",
            lockAtMostFor = "PT2M",
            lockAtLeastFor = "PT1S")
    public void process() {
        for (Long id :
                jdbc.queryForList(
                        "SELECT id FROM notification_events WHERE status='PENDING' ORDER BY id LIMIT 100",
                        Map.of(),
                        Long.class)) {
            try {
                orchestrator.process(id);
            } catch (RuntimeException failure) {
                jdbc.update(
                        "UPDATE notification_events SET last_error_safe='Notification projection failed; retry pending' WHERE id=:id",
                        Map.of("id", id));
            }
        }
    }
}
