package net.citotech.cito.platform.kernel;

import java.util.Map;

/** Transactional platform-event boundary shared by every Cito domain. */
public interface PlatformEventContract {
    String publish(
            PlatformTenantContext context,
            String serviceCode,
            String sourceDomain,
            String eventType,
            String sourceId,
            String causationId,
            Map<String, Object> payload);
}
