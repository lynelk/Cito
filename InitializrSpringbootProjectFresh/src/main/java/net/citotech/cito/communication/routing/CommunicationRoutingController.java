package net.citotech.cito.communication.routing;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import net.citotech.cito.communication.domain.CommunicationChannel;
import net.citotech.cito.communication.routing.CommunicationRoutingRepository.ProviderRow;
import net.citotech.cito.communication.routing.CommunicationRoutingRepository.RuleRow;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin admin surface for communication provider routing (ISO domain mapping: communication/routing,
 * track B1a). Backs the {@code ModuleCommunicationRouting} admin screen: browse the provider
 * catalog, list/upsert/delete routing rules, and preview which adapter a merchant+channel resolves
 * to today. Writes are immediate — the router reads these tables on every send, so a saved rule
 * takes effect on the next pending-send sweep with no restart.
 */
@RestController
@RequestMapping(path = "/api/v2/admin/communication/routing")
@PreAuthorize("hasRole('ADMIN')")
public class CommunicationRoutingController {

    private final CommunicationRoutingRepository repository;

    public CommunicationRoutingController(CommunicationRoutingRepository repository) {
        this.repository = repository;
    }

    @GetMapping(path = "/providers")
    public Map<String, Object> providers() {
        return Map.of("code", "000", "providers", repository.providers());
    }

    @GetMapping(path = "/rules")
    public Map<String, Object> rules() {
        return Map.of("code", "000", "rules", repository.rules());
    }

    /**
     * Preview which rule and provider a merchant+channel would use on the next send. merchantId is
     * optional; omitted resolves the platform default. Invalid channel/id inputs are rejected at
     * the API boundary, while missing rules/providers are represented as deterministic unresolved
     * states instead of bubbling into an HTTP 500.
     */
    @GetMapping(path = "/effective")
    public ResponseEntity<Map<String, Object>> effective(
            @RequestParam(value = "merchantId", required = false) Long merchantId,
            @RequestParam(value = "channel", defaultValue = "SMS") String channel) {
        CommunicationChannel parsedChannel = CommunicationChannel.fromString(channel);
        if (parsedChannel == null) {
            return ResponseEntity.badRequest()
                    .body(
                            Map.of(
                                    "code", "400",
                                    "resolved", false,
                                    "error", "UNSUPPORTED_CHANNEL"));
        }
        if (merchantId != null && merchantId <= 0) {
            return ResponseEntity.badRequest()
                    .body(
                            Map.of(
                                    "code", "400",
                                    "resolved", false,
                                    "error", "INVALID_MERCHANT_ID"));
        }

        String normalizedChannel = parsedChannel.name();
        Optional<RuleRow> rule = repository.effectiveRule(normalizedChannel, merchantId);
        if (rule.isEmpty()) {
            return ResponseEntity.ok(
                    Map.of(
                            "code", "000",
                            "resolved", false,
                            "reason", "ROUTE_NOT_CONFIGURED"));
        }

        RuleRow row = rule.get();
        Optional<ProviderRow> provider = repository.provider(row.providerCode(), normalizedChannel);
        if (provider.isEmpty()) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("code", "000");
            response.put("resolved", false);
            response.put("reason", "PROVIDER_NOT_CONFIGURED");
            response.put("rule", row);
            return ResponseEntity.ok(response);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("code", "000");
        response.put("resolved", true);
        response.put("rule", row);
        response.put("provider", provider.get());
        return ResponseEntity.ok(response);
    }

    @PostMapping(path = "/rules")
    public Map<String, Object> upsertRule(@RequestBody RuleUpsertRequest request) {
        RuleRow saved =
                repository.upsertRule(
                        request.id(),
                        request.channel() == null || request.channel().isBlank()
                                ? "SMS"
                                : request.channel(),
                        request.merchantId(),
                        request.priority() == null ? 100 : request.priority(),
                        request.providerCode(),
                        request.enabledFlag() == null || request.enabledFlag().isBlank()
                                ? "YES"
                                : request.enabledFlag().toUpperCase());
        return Map.of("code", "000", "rule", saved);
    }

    @DeleteMapping(path = "/rules/{ruleId}")
    public Map<String, Object> deleteRule(@PathVariable long ruleId) {
        repository.deleteRule(ruleId);
        return Map.of("code", "000", "deleted", ruleId);
    }

    /** Upsert payload; id null inserts (or reuses the row for the channel+merchant scope). */
    public record RuleUpsertRequest(
            Long id,
            String channel,
            Long merchantId,
            Integer priority,
            String providerCode,
            String enabledFlag) {}
}
