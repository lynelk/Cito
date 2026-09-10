package net.citotech.cito.gateway;

import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** A callback is only a bounded wake-up hint. No callback-provided status ever settles money. */
@RestController
@RequestMapping("/api/v2/provider-callbacks/airtel")
public class AirtelRecoveryCallbackController {
    private final AirtelRecoveryStore store;

    public AirtelRecoveryCallbackController(AirtelRecoveryStore store) {
        this.store = store;
    }

    @RequestMapping(
            path = "/{providerReference}",
            method = {RequestMethod.POST, RequestMethod.PUT})
    public ResponseEntity<Map<String, Boolean>> receive(@PathVariable String providerReference) {
        signal(providerReference);
        return ResponseEntity.ok(Map.of("accepted", true));
    }

    @RequestMapping(method = {RequestMethod.POST, RequestMethod.PUT})
    public ResponseEntity<Map<String, Boolean>> receiveBody(@RequestBody Map<String, Object> body) {
        Object data = body.get("data");
        Object tx = data instanceof Map<?, ?> m ? m.get("transaction") : body.get("transaction");
        if (tx instanceof Map<?, ?> transaction && transaction.get("id") instanceof String ref)
            signal(ref);
        return ResponseEntity.ok(Map.of("accepted", true));
    }

    private void signal(String reference) {
        try {
            if (reference == null
                    || !UUID.fromString(reference).toString().equalsIgnoreCase(reference)) return;
            store.signal(reference);
        } catch (IllegalArgumentException ignored) {
            /* Opaque malformed/unknown signals reveal no tenant data. */
        }
    }
}
