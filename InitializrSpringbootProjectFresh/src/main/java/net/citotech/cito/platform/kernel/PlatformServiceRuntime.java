package net.citotech.cito.platform.kernel;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.citotech.cito.billing.usage.UsageEvent;
import org.springframework.stereotype.Service;

/**
 * Shared application boundary for Cito domains. Domain operations retain their typed adapters,
 * transactional/idempotent commands and resource-level authorization. This facade does not replace
 * those controls. Canonical billing currently stores production usage only; sandbox simulation must
 * not cross that boundary. Usage callers supply the original occurrence time, including on retries.
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
        requireMerchant(context);
        if (!Set.of("SANDBOX", "PRODUCTION").contains(context.environment())) {
            throw new IllegalArgumentException("Service environment is invalid");
        }
        entitlements.requireEntitlement(context.merchantId(), serviceCode, context.environment());
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
                context, serviceCode, sourceDomain, eventType, sourceId, causationId, payload);
    }

    public UsageEvent usage(
            PlatformTenantContext context,
            String serviceCode,
            String metricCode,
            Instant occurredAt,
            BigDecimal quantity,
            String currency,
            Map<String, String> dimensions,
            String sourceReference,
            String idempotencyKey) {
        requireMerchant(context);
        if (!"PRODUCTION".equals(context.environment())) {
            throw new IllegalArgumentException("Sandbox usage cannot enter production billing");
        }
        return usage.recordUsage(
                context.merchantId(),
                serviceCode,
                metricCode,
                Objects.requireNonNull(occurredAt, "Original usage occurrence time is required"),
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

    private void requireMerchant(PlatformTenantContext context) {
        if (context == null || context.merchantId() == null || context.merchantId() <= 0) {
            throw new IllegalArgumentException(
                    "Merchant-scoped execution requires a tenant context");
        }
    }
}
