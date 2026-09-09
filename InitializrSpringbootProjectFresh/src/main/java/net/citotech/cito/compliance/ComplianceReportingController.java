package net.citotech.cito.compliance;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/v2/admin/compliance")
@PreAuthorize("hasRole('ADMIN')")
public class ComplianceReportingController {
    private final ComplianceReportingService service;
    private final ComplianceCaseService caseService;

    public ComplianceReportingController(
            ComplianceReportingService service, ComplianceCaseService caseService) {
        this.service = service;
        this.caseService = caseService;
    }

    @GetMapping(path = "/summary")
    public Map<String, Object> summary() {
        return service.summary();
    }

    @GetMapping(path = "/report")
    public Map<String, Object> report(
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to) {
        return service.report(from, to);
    }

    @PostMapping(path = "/events/{id}/review")
    public ResponseEntity<?> review(
            @PathVariable("id") long id,
            @RequestParam(name = "reviewedBy", required = false) String reviewedBy) {
        int updated = service.markReviewed(id, reviewedBy);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("updated", updated);
        response.put("id", id);
        return ResponseEntity.ok(response);
    }

    @GetMapping(path = "/profiles")
    public List<Map<String, Object>> profiles(
            @RequestParam(name = "status", required = false) String status) {
        return caseService.listProfiles(status);
    }

    /**
     * Returns the currently actionable compliance cases. An empty case queue is a successful empty
     * result, not an exceptional condition, so the admin workspace can render a truthful no-cases
     * state without converting normal operations into an HTTP 500.
     */
    @GetMapping(path = "/cases")
    public List<Map<String, Object>> cases() {
        return caseService.listOpenCases();
    }

    @PostMapping(path = "/cases/{id}/decision")
    public ResponseEntity<?> decideCase(
            @PathVariable("id") long id, @RequestBody Map<String, Object> body) {
        if (id <= 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "id must be positive"));
        }
        String decision = string(body, "decision", null);
        if (decision == null || decision.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "decision is required"));
        }
        int updated =
                caseService.decideCase(
                        id,
                        decision,
                        string(body, "reason", null),
                        string(body, "actor", null));
        if (updated == 0) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("updated", updated, "id", id));
    }

    @PostMapping(path = "/profiles")
    public ResponseEntity<?> upsertProfile(@RequestBody Map<String, Object> body) {
        long entityId;
        try {
            entityId = longValue(body, "entityId");
        } catch (IllegalArgumentException ex) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", ex.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
        int updated =
                caseService.upsertProfile(
                        string(body, "entityType", "MERCHANT"),
                        entityId,
                        string(body, "profileType", "KYC"),
                        string(body, "tier", "STANDARD"),
                        string(body, "status", "PENDING"),
                        string(body, "riskRating", "UNKNOWN"),
                        string(body, "requiredDocumentsJson", null),
                        string(body, "decisionReason", null),
                        string(body, "verifiedBy", null));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("updated", updated);
        return ResponseEntity.ok(response);
    }

    private String string(Map<String, Object> body, String key, String defaultValue) {
        if (body == null) {
            return defaultValue;
        }
        Object value = body.get(key);
        return value == null ? defaultValue : String.valueOf(value);
    }

    private long longValue(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            throw new IllegalArgumentException(key + " is required");
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(key + " must be numeric");
        }
    }
}
