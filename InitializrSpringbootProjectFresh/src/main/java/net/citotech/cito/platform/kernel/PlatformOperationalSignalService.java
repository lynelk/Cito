package net.citotech.cito.platform.kernel;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** One cross-domain operational signal contract; its event and audit commit or roll back together. */
@Service
public class PlatformOperationalSignalService {
    private final PlatformEventContract events;
    private final PlatformAuditContract audit;

    public PlatformOperationalSignalService(
            PlatformEventContract events, PlatformAuditContract audit) {
        this.events = events;
        this.audit = audit;
    }

    @Transactional
    public String record(
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
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("category", required(category));
        payload.put("severity", required(severity));
        payload.put("state", required(state));
        payload.put("title", required(title));
        payload.put("summary", summary == null ? "" : summary.trim());
        payload.put("actionRoute", actionRoute == null ? "" : actionRoute.trim());
        String eventId = events.publish(context, serviceCode, sourceDomain, "operational.signal",
                sourceId, null, payload);
        audit.record(context, "OPERATIONAL_SIGNAL_RECORDED", sourceDomain, sourceId,
                Map.of("eventId", eventId, "category", category, "severity", severity, "state", state));
        return eventId;
    }

    private String required(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Operational signal value is required");
        }
        return value.trim();
    }
}
