package net.citotech.cito.platform.kernel;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import net.citotech.cito.billing.usage.UsageEvent;

/** Canonical metering entry point for every billable Cito domain. */
public interface PlatformUsageContract {
    UsageEvent recordUsage(
            long merchantId,
            String serviceCode,
            String metricCode,
            Instant occurredAt,
            BigDecimal quantity,
            String currency,
            Map<String, String> dimensions,
            String sourceReference,
            String idempotencyKey);
}
