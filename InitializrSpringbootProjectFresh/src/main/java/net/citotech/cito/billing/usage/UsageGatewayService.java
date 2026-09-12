package net.citotech.cito.billing.usage;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import net.citotech.cito.billing.tenancy.BillingTenantResolver;
import net.citotech.cito.platform.kernel.PlatformUsageContract;
import org.springframework.stereotype.Service;

/**
 * Canonical metering entry point for every billable Cito domain.
 *
 * <p>Resolves the caller's merchant to the existing Billing/BaaS tenant, then deduplicates by the
 * immutable idempotency key. Payments, communications, identity/risk, vending and API access use
 * this same contract instead of maintaining parallel usage stores.
 */
@Service
public class UsageGatewayService implements PlatformUsageContract {
    private final BillingTenantResolver tenantResolver;
    private final UsageEventRepository repository;

    public UsageGatewayService(
            BillingTenantResolver tenantResolver, UsageEventRepository repository) {
        this.tenantResolver = tenantResolver;
        this.repository = repository;
    }

    @Override
    public UsageEvent recordUsage(
            long merchantId,
            String serviceCode,
            String meterCode,
            Instant eventTime,
            BigDecimal quantity,
            String currency,
            Map<String, String> dimensions,
            String sourceReference,
            String idempotencyKey) {
        long billingTenantId = tenantResolver.resolveTenantId(merchantId);
        UsageEvent event =
                new UsageEvent(
                        0L,
                        billingTenantId,
                        serviceCode,
                        meterCode,
                        eventTime,
                        quantity,
                        currency,
                        dimensions,
                        sourceReference,
                        idempotencyKey,
                        null);
        return repository.insertIfAbsent(event);
    }
}
