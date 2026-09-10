package net.citotech.cito.commercial;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/v2/admin/commercial")
@PreAuthorize("hasRole('ADMIN')")
public class CitoCommercialProgrammeController {
    private final CitoCommercialProgrammeService service;

    public CitoCommercialProgrammeController(CitoCommercialProgrammeService service) {
        this.service = service;
    }

    @GetMapping(path = "/packages")
    public List<Map<String, Object>> packages() {
        return service.packages();
    }

    @PutMapping(path = "/packages/{packageCode}")
    public Map<String, Object> savePackage(
            @PathVariable String packageCode, @RequestBody Map<String, Object> body) {
        return service.savePackage(
                packageCode,
                text(body, "packageName"),
                text(body, "targetSegment"),
                text(body, "description"),
                json(body.get("serviceCodes")),
                text(body, "onboardingSupportLevel"),
                json(body.get("commercialTerms")),
                text(body, "actor"));
    }

    @PostMapping(path = "/packages/{packageCode}/submit")
    public Map<String, Object> submitPackage(
            @PathVariable String packageCode, @RequestBody Map<String, Object> body) {
        return service.submitPackage(packageCode, text(body, "actor"));
    }

    @PostMapping(path = "/packages/{packageCode}/approve")
    public Map<String, Object> approvePackage(
            @PathVariable String packageCode, @RequestBody Map<String, Object> body) {
        return service.approvePackage(packageCode, text(body, "actor"));
    }

    @GetMapping(path = "/merchants/{merchantId}")
    public Map<String, Object> merchant(@PathVariable long merchantId) {
        return service.merchantCommercialSummary(merchantId);
    }

    @PostMapping(path = "/merchants/{merchantId}/packages/{packageCode}")
    public Map<String, Object> assignPackage(
            @PathVariable long merchantId,
            @PathVariable String packageCode,
            @RequestBody Map<String, Object> body) {
        return service.assignPackage(
                merchantId,
                packageCode,
                text(body, "environment"),
                instant(body, "effectiveFrom"),
                instant(body, "effectiveTo"),
                text(body, "notes"),
                text(body, "actor"));
    }

    @PostMapping(path = "/merchants/{merchantId}/packages/{packageCode}/activate")
    public Map<String, Object> activatePackage(
            @PathVariable long merchantId,
            @PathVariable String packageCode,
            @RequestBody Map<String, Object> body) {
        return service.activateAssignment(
                merchantId, packageCode, text(body, "environment"), text(body, "actor"));
    }

    @GetMapping(path = "/founding20")
    public List<Map<String, Object>> founding20() {
        return service.founding20();
    }

    @PostMapping(path = "/founding20/{merchantId}")
    public Map<String, Object> enrollFounding20(
            @PathVariable long merchantId, @RequestBody Map<String, Object> body) {
        return service.enrollFounding20(
                merchantId,
                intValue(body, "cohortSlot"),
                text(body, "commercialOwner"),
                text(body, "customerSuccessOwner"),
                instant(body, "targetGoLiveAt"),
                text(body, "notes"),
                text(body, "actor"));
    }

    @PostMapping(path = "/founding20/{merchantId}/status")
    public Map<String, Object> updateFounding20Status(
            @PathVariable long merchantId, @RequestBody Map<String, Object> body) {
        return service.updateFounding20Status(
                merchantId, text(body, "programmeStatus"), text(body, "exitReason"));
    }

    @GetMapping(path = "/embedded-partners")
    public List<Map<String, Object>> embeddedPartners() {
        return service.embeddedPartners();
    }

    @PostMapping(path = "/embedded-partners/{merchantId}")
    public Map<String, Object> enrollEmbeddedPartner(
            @PathVariable long merchantId, @RequestBody Map<String, Object> body) {
        return service.enrollEmbeddedPartner(
                merchantId,
                text(body, "programmeTier"),
                text(body, "commercialOwner"),
                nullableInt(body, "targetDownstreamMerchants"),
                instant(body, "targetGoLiveAt"),
                text(body, "notes"),
                text(body, "actor"));
    }

    @PostMapping(path = "/embedded-partners/{merchantId}/status")
    public Map<String, Object> updateEmbeddedPartnerStatus(
            @PathVariable long merchantId, @RequestBody Map<String, Object> body) {
        return service.updateEmbeddedPartnerStatus(merchantId, text(body, "programmeStatus"));
    }

    private String text(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private String json(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String string) {
            return string;
        }
        if (value instanceof List<?> list) {
            return "[" + list.stream().map(this::quoted).reduce((a, b) -> a + "," + b).orElse("") + "]";
        }
        if (value instanceof Map<?, ?> map) {
            return "{" + map.entrySet().stream()
                    .map(entry -> quoted(entry.getKey()) + ":" + quoted(entry.getValue()))
                    .reduce((a, b) -> a + "," + b)
                    .orElse("") + "}";
        }
        return String.valueOf(value);
    }

    private String quoted(Object value) {
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        return "\"" + String.valueOf(value).replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private int intValue(Map<String, Object> body, String key) {
        Integer value = nullableInt(body, key);
        if (value == null) {
            throw new IllegalArgumentException(key + " is required");
        }
        return value;
    }

    private Integer nullableInt(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.valueOf(String.valueOf(value));
    }

    private Instant instant(Map<String, Object> body, String key) {
        String value = text(body, key);
        return value == null || value.isBlank() ? null : Instant.parse(value.trim());
    }
}
