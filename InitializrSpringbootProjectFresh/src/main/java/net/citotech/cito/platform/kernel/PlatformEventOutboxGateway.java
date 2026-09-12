package net.citotech.cito.platform.kernel;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.citotech.cito.billing.outbox.OutboxWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publishes cross-domain Cito events through the existing transactional outbox. The caller must
 * already own a business transaction; MANDATORY rejects accidental autocommit publication. This
 * boundary never opens a separate transaction for an event whose business work can still roll back.
 */
@Service
public class PlatformEventOutboxGateway implements PlatformEventContract {
    public static final String OUTBOX_EVENT_TYPE = "CITO_PLATFORM_EVENT";
    private final OutboxWriter outbox;

    public PlatformEventOutboxGateway(OutboxWriter outbox) {
        this.outbox = outbox;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public String publish(
            PlatformTenantContext context,
            String serviceCode,
            String sourceDomain,
            String eventType,
            String sourceId,
            String causationId,
            Map<String, Object> payload) {
        if (context == null) throw new IllegalArgumentException("Platform tenant context is required");
        String environment = normalize(context.environment());
        if (!Set.of("SANDBOX", "PRODUCTION").contains(environment)) {
            throw new IllegalArgumentException("Platform event environment is invalid");
        }
        String id = UUID.randomUUID().toString();
        PlatformEventEnvelope envelope = new PlatformEventEnvelope(
                id, required(eventType), 1, context.organizationId(), context.merchantId(),
                normalize(serviceCode), normalize(sourceDomain), required(sourceId), environment,
                context.correlationId(), causationId, Instant.now(), context.actorType(),
                context.actorId(), payload);
        outbox.write("CITO_PLATFORM", envelope.sourceId(), OUTBOX_EVENT_TYPE, toPayload(envelope));
        return id;
    }

    private Map<String, Object> toPayload(PlatformEventEnvelope envelope) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("eventId", envelope.eventId());
        values.put("eventType", envelope.eventType());
        values.put("eventVersion", envelope.eventVersion());
        values.put("organizationId", envelope.organizationId());
        values.put("merchantId", envelope.merchantId());
        values.put("serviceCode", envelope.serviceCode());
        values.put("sourceDomain", envelope.sourceDomain());
        values.put("sourceId", envelope.sourceId());
        values.put("environment", envelope.environment());
        values.put("correlationId", envelope.correlationId());
        values.put("causationId", envelope.causationId());
        values.put("occurredAt", envelope.occurredAt().toString());
        values.put("actorType", envelope.actorType());
        values.put("actorId", envelope.actorId());
        values.put("payload", envelope.payload());
        return values;
    }

    private String required(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Platform event value is required");
        }
        return value.trim();
    }

    private String normalize(String value) {
        return required(value).toUpperCase(Locale.ROOT);
    }
}
