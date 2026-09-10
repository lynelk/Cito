package net.citotech.cito.developer.reference;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/admin/api-reference")
@PreAuthorize("hasRole('ADMIN')")
public class AdminApiReferenceController {
    private final ApiReferenceService reference;
    private final ApiAccessBillingService billing;

    public AdminApiReferenceController(
            ApiReferenceService reference, ApiAccessBillingService billing) {
        this.reference = reference;
        this.billing = billing;
    }

    @GetMapping("/commercial")
    public ResponseEntity<Map<String, Object>> commercial(
            jakarta.servlet.http.HttpServletRequest request) {
        var csrf =
                (org.springframework.security.web.csrf.CsrfToken)
                        request.getAttribute(
                                org.springframework.security.web.csrf.CsrfToken.class.getName());
        if (csrf != null) csrf.getToken();
        return ResponseEntity.ok()
                .header("Cache-Control", "private, no-store")
                .body(reference.document());
    }

    @GetMapping(value = "/guide", produces = "text/plain")
    public String guide() throws IOException {
        return reference.guide();
    }

    @GetMapping("/rates")
    public List<Map<String, Object>> rates() {
        return billing.rates();
    }

    public record RateRequest(
            String method, String path, String amount, String currency, long expectedVersion) {}

    @PostMapping("/rates")
    public ResponseEntity<?> publish(@RequestBody RateRequest rate) {
        try {
            boolean registered =
                    billing.rates().stream()
                            .anyMatch(
                                    row ->
                                            row.get("http_method").equals(rate.method())
                                                    && row.get("route_template")
                                                            .equals(rate.path()));
            if (!registered) throw new IllegalArgumentException("Unknown commercial endpoint");
            return ResponseEntity.ok(
                    billing.publish(
                            rate.method(),
                            rate.path(),
                            rate.amount(),
                            rate.currency(),
                            rate.expectedVersion()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("code", "INVALID_API_RATE", "message", e.getMessage()));
        }
    }
}
