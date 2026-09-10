package net.citotech.cito.communication.notification;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.citotech.cito.webhook.WebhookEventCatalog;

/**
 * Notification policy metadata over existing platform event names; never a second webhook schema.
 */
public final class NotificationEventCatalog {
    public enum Classification {
        MANDATORY,
        OPERATIONAL,
        MARKETING
    }

    public record Definition(
            String type,
            Classification classification,
            String group,
            String severity,
            String templateKey,
            int templateVersion,
            String description) {}

    private static final Map<String, Definition> EVENTS = new LinkedHashMap<>();

    static {
        for (var event : WebhookEventCatalog.all()) {
            add(
                    event.type(),
                    event.type().endsWith(".completed")
                            ? Classification.MANDATORY
                            : Classification.OPERATIONAL,
                    null,
                    "INFO",
                    event.description());
        }
        add("account.created", Classification.MANDATORY, null, "INFO", "Account created");
        add(
                "account.suspended",
                Classification.MANDATORY,
                "COMPLIANCE",
                "HIGH",
                "Account suspended");
        add(
                "security.password.changed",
                Classification.MANDATORY,
                "SECURITY",
                "HIGH",
                "Password changed");
        add(
                "security.mfa.changed",
                Classification.MANDATORY,
                "SECURITY",
                "HIGH",
                "Authentication settings changed");
        add(
                "security.authentication.suspicious",
                Classification.MANDATORY,
                "SECURITY",
                "CRITICAL",
                "Suspicious authentication activity");
        add(
                "api.credentials.changed",
                Classification.MANDATORY,
                "SECURITY",
                "HIGH",
                "API credentials changed");
        add(
                "production.configuration.changed",
                Classification.MANDATORY,
                "SECURITY",
                "HIGH",
                "Production configuration changed");
        add(
                "settlement.completed",
                Classification.OPERATIONAL,
                "FINANCE",
                "INFO",
                "Settlement completed");
        add(
                "reconciliation.break",
                Classification.OPERATIONAL,
                "FINANCE",
                "HIGH",
                "Reconciliation requires attention");
        add(
                "ledger.imbalance",
                Classification.MANDATORY,
                "FINANCE",
                "CRITICAL",
                "Ledger imbalance detected");
        add(
                "compliance.critical",
                Classification.MANDATORY,
                "COMPLIANCE",
                "CRITICAL",
                "Critical compliance event");
        add(
                "provider.outage",
                Classification.OPERATIONAL,
                "PLATFORM_OPERATIONS",
                "HIGH",
                "Provider outage");
        add(
                "sms.failure_rate.high",
                Classification.OPERATIONAL,
                "PLATFORM_OPERATIONS",
                "HIGH",
                "SMS failure rate exceeded threshold");
        add(
                "sms.queue.backlog",
                Classification.OPERATIONAL,
                "PLATFORM_OPERATIONS",
                "HIGH",
                "SMS queue backlog exceeded threshold");
        add(
                "sms.callback.failure",
                Classification.OPERATIONAL,
                "PLATFORM_OPERATIONS",
                "HIGH",
                "SMS callback or delivery report failure");
        add(
                "api.degradation.sustained",
                Classification.OPERATIONAL,
                "PLATFORM_OPERATIONS",
                "HIGH",
                "Sustained API degradation");
        add(
                "balance.threshold",
                Classification.OPERATIONAL,
                "FINANCE",
                "WARNING",
                "Balance threshold reached");
        add("report.scheduled", Classification.OPERATIONAL, null, "INFO", "Scheduled report ready");
        add(
                "marketing.campaign",
                Classification.MARKETING,
                null,
                "INFO",
                "Optional marketing communication");
    }

    private static void add(
            String type,
            Classification classification,
            String group,
            String severity,
            String description) {
        EVENTS.put(
                type,
                new Definition(
                        type,
                        classification,
                        group,
                        severity,
                        "notification." + type + ".v1",
                        1,
                        description));
    }

    public static List<Definition> all() {
        return List.copyOf(EVENTS.values());
    }

    public static boolean isKnown(String type) {
        return type != null && EVENTS.containsKey(type.trim().toLowerCase(Locale.ROOT));
    }

    public static Definition require(String type) {
        if (!isKnown(type)) throw new IllegalArgumentException("Unknown notification event");
        return EVENTS.get(type.trim().toLowerCase(Locale.ROOT));
    }

    private NotificationEventCatalog() {}
}
