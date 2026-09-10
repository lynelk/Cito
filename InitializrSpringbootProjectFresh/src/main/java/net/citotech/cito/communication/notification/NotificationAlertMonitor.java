package net.citotech.cito.communication.notification;

import java.time.Instant;
import java.util.Map;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Reads canonical operational evidence; never derives financial truth from message delivery. */
@Component
public class NotificationAlertMonitor {
    private final NamedParameterJdbcTemplate jdbc;
    private final NotificationOrchestrator notifications;

    public NotificationAlertMonitor(
            NamedParameterJdbcTemplate jdbc, NotificationOrchestrator notifications) {
        this.jdbc = jdbc;
        this.notifications = notifications;
    }

    @Scheduled(fixedDelayString = "${cpay.notification.monitor-delay-ms:60000}")
    @SchedulerLock(
            name = "notificationAlertMonitor",
            lockAtMostFor = "PT2M",
            lockAtLeastFor = "PT5S")
    public void monitor() {
        long bucket = Instant.now().getEpochSecond() / 300;
        alert(
                "provider.outage",
                bucket,
                "SELECT COUNT(*) FROM communication_provider_health WHERE channel='SMS' AND (state='UNAVAILABLE' OR circuit_open_until>CURRENT_TIMESTAMP)",
                1);
        alert(
                "sms.queue.backlog",
                bucket,
                "SELECT COUNT(*) FROM communication_outbox WHERE status='PENDING' AND next_attempt_at<DATE_SUB(NOW(),INTERVAL 5 MINUTE)",
                100);
        alert(
                "sms.failure_rate.high",
                bucket,
                "SELECT COUNT(*) FROM (SELECT 1 FROM communication_message_deliveries WHERE channel='SMS' AND created_at>DATE_SUB(NOW(),INTERVAL 5 MINUTE) HAVING COUNT(*)>=20 AND SUM(status IN ('FAILED','REJECTED'))/COUNT(*)>=0.20) x",
                1);
        alert(
                "sms.callback.failure",
                bucket,
                "SELECT COUNT(*) FROM communication_messages WHERE selected_channel='SMS' AND status='SENT' AND updated_at<DATE_SUB(NOW(),INTERVAL 1 HOUR) AND JSON_EXTRACT(metadata_json,'$.requireDeliveryReceipts')=true",
                10);
        alert(
                "reconciliation.break",
                bucket,
                "SELECT COUNT(*) FROM reconciliation_exceptions WHERE status='OPEN' AND severity IN ('HIGH','CRITICAL')",
                1);
        alert(
                "compliance.critical",
                bucket,
                "SELECT COUNT(*) FROM compliance_cases WHERE case_status='OPEN' AND severity='CRITICAL'",
                1);
        alert(
                "security.authentication.suspicious",
                bucket,
                "SELECT COUNT(*) FROM admin_audit_events WHERE request_summary='denied' AND created_at>DATE_SUB(NOW(),INTERVAL 5 MINUTE)",
                10);
        alert(
                "ledger.imbalance",
                bucket,
                "SELECT COUNT(*) FROM ledger_trial_balance_runs WHERE run_date=CURRENT_DATE AND total_debits<>total_credits",
                1);
        // Each five-minute subwindow must independently exceed the degradation threshold.
        alert(
                "api.degradation.sustained",
                bucket,
                "SELECT COUNT(*) FROM (SELECT FLOOR(UNIX_TIMESTAMP(created_at)/300) window_id FROM developer_api_request_log WHERE environment='production' AND created_at>DATE_SUB(NOW(),INTERVAL 15 MINUTE) GROUP BY window_id HAVING COUNT(*)>=20 AND SUM(response_status>=500)/COUNT(*)>=0.10) x",
                3);
    }

    private void alert(String type, long bucket, String query, int minimum) {
        Integer count = jdbc.queryForObject(query, Map.of(), Integer.class);
        if (count != null && count >= minimum)
            notifications.record(0, type + ":" + bucket, type, "monitor-" + bucket);
    }
}
