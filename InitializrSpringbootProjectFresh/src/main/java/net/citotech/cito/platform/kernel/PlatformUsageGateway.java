package net.citotech.cito.platform.kernel;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import net.citotech.cito.billing.usage.UsageEvent;
import net.citotech.cito.billing.usage.UsageGatewayService;
import org.springframework.stereotype.Service;

/** Uses the existing Billing/BaaS usage pipeline as Cito's single metering implementation. */
@Service
public class PlatformUsageGateway implements PlatformUsageContract {
    private final UsageGatewayService usage;

    public PlatformUsageGateway(UsageGatewayService usage) {
        this.usage = usage;
    }

    @Override
    public UsageEvent recordUsage(
            long merchantId,
            String serviceCode,
            String metricCode,
            Instant occurredAt,
            BigDecimal quantity,
            String currency,
            Map<String, String> dimensions,
            String sourceReference,
            String idempotencyKey) {
        return usage.recordUsage(
                merchantId,
                serviceCode,
                metricCode,
                occurredAt,
                quantity,
                currency,
                dimensions,
                sourceReference,
                idempotencyKey);
    }
}
