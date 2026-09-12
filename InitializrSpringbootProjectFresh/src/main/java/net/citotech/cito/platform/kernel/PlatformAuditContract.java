package net.citotech.cito.platform.kernel;

import java.util.Map;

/** Append-only audit boundary shared by all platform domains. */
public interface PlatformAuditContract {
    void record(
            PlatformTenantContext context,
            String action,
            String resourceType,
            String resourceId,
            Map<String, Object> summary);
}
