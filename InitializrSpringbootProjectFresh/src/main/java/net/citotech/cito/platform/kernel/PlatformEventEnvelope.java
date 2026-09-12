package net.citotech.cito.platform.kernel;

import java.time.Instant;
import java.util.Map;

/** Immutable metadata envelope for Cito cross-domain events. */
public record PlatformEventEnvelope(
        String eventId,
        String eventType,
        int eventVersion,
        Long organizationId,
        Long merchantId,
        String serviceCode,
        String sourceDomain,
        String sourceId,
        String environment,
        String correlationId,
        String causationId,
        Instant occurredAt,
        String actorType,
        String actorId,
        Map<String, Object> payload) {

    public PlatformEventEnvelope {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
}
