package net.citotech.cito.gateway;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/** Receives MTN's single-attempt asynchronous result for a transaction-specific callback URL. */
@RestController
@RequestMapping("/api/v2/provider-callbacks/mtn")
public class MtnMomoCallbackController {
    private final MtnMomoCorrelationService correlationService;

    public MtnMomoCallbackController(MtnMomoCorrelationService correlationService) {
        this.correlationService = correlationService;
    }

    @RequestMapping(
            path = "/{providerReference}",
            method = {RequestMethod.POST, RequestMethod.PUT})
    public ResponseEntity<Map<String, Object>> receive(
            @PathVariable String providerReference, @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(
                correlationService.resolve(
                        providerReference,
                        text(body.get("externalId")),
                        text(body.get("status")),
                        text(body.get("financialTransactionId"))));
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
