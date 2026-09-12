package net.citotech.cito.platform.kernel;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import net.citotech.cito.billing.usage.UsageEvent;
import org.springframework.stereotype.Service;

/**
 * Shared application boundary for Cito service domains.
 *
 * <p>Payments, communications, identity/risk, vending and developer services should call this
 * runtime rather than creating separate tenant, entitlement, usage, event, audit or operational
 * contracts. Provider-specific execution remains inside domain adapters.
 */
@Service
public class PlatformServiceRuntime {
    private final PlatformEntitlementContract entitlements;
    private final PlatformUsageContract usage;
    private final PlatformEventContract events;
    private final PlatformAuditContract audit;
    private final PlatformOperationalSignalService signals;

    public PlatformServiceRuntime(
            PlatformEntitlementContract entitlements,
            PlatformUsageContract usage,
            PlatformEventContract events,
            PlatformAuditContract audit,
            PlatformOperationalSignalService signals) {
        this.entitlements = entitlements;
        this.usage = usage;
        this.events = events;
        this.audit = audit;
        this.signals = signals;
    }

    public void requireService(PlatformTenantContext context, String serviceCode) {
        if (context == null || context.merchantId() == null) {
            throw new IllegalArgumentException("Merchant-scoped service execution requires a tenant context");
        }
        entitlements.requireEntitlement(
                context.merchantId(), serviceCode, context.environment());
    }

    public String event(
            PlatformTenantContext context,
            String serviceCode,
            String sourceDomain,
            String eventType,
            String sourceId,
            String causationId,
            Map<String, Object> payload) {
        return events.publish(
                context,
                serviceCode,
                sourceDomain,
                eventType,
                sourceId,
                causationId,
                payload);
    }

    public UsageEvent usage(
            PlatformTenantContext context,
            String serviceCode,
            String metricCode,
            BigDecimal quantity,
            String currency,
            Map<String, String> dimensions,
            String sourceReference,
            String idempotencyKey) {
        if (context == null || context.merchantId() == null) {
            throw new IllegalArgumentException("Billable usage requires a merchant-scoped tenant context");
        }
        return usage.recordUsage(
                context.merchantId(),
                serviceCode,
                metricCode,
                Instant.now(),
                quantity,
                currency,
                dimensions,
                sourceReference,
                idempotencyKey);
    }

    public void audit(
            PlatformTenantContext context,
            String action,
            String resourceType,
            String resourceId,
            Map<String, Object> summary) {
        audit.record(context, action, resourceType, resourceId, summary);
    }

    public String signal(
            PlatformTenantContext context,
            String serviceCode,
            String sourceDomain,
            String sourceId,
            String category,
            String severity,
            String state,
            String title,
            String summary,
            String actionRoute) {
        return signals.record(
                context,
                serviceCode,
                sourceDomain,
                sourceId,
                category,
                severity,
                state,
                title,
                summary,
                actionRoute);
    }
}
