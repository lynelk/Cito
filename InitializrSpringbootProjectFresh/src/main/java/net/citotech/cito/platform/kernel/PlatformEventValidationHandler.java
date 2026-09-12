package net.citotech.cito.platform.kernel;

import java.util.Map;
import net.citotech.cito.billing.outbox.BillingOutboxEntry;
import net.citotech.cito.billing.outbox.OutboxEventHandler;
import org.springframework.stereotype.Component;

/**
 * Terminal consumer for canonical platform events.
 *
 * <p>The outbox row itself is the durable event journal. This handler validates the common envelope
 * so the relay can mark it delivered without inventing a second event table or domain-specific
 * publication path. Future independent consumers can subscribe to the same event type and remain
 * idempotent by {@code eventId}.
 */
@Component
public class PlatformEventValidationHandler implements OutboxEventHandler {
    @Override
    public boolean supports(String eventType) {
        return PlatformEventOutboxGateway.OUTBOX_EVENT_TYPE.equals(eventType);
    }

    @Override
    public void handle(BillingOutboxEntry entry) {
        Map<String, Object> payload = entry.payload();
        require(payload, "eventId");
        require(payload, "eventType");
        require(payload, "serviceCode");
        require(payload, "sourceDomain");
        require(payload, "sourceId");
        require(payload, "environment");
        require(payload, "occurredAt");
        Object version = payload.get("eventVersion");
        if (!(version instanceof Number number) || number.intValue() < 1) {
            throw new IllegalStateException("Cito platform event version is invalid");
        }
    }

    private void require(Map<String, Object> payload, String field) {
        Object value = payload.get(field);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalStateException("Cito platform event is missing " + field);
        }
    }
}
