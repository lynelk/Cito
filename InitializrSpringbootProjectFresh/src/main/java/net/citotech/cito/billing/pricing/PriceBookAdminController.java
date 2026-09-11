package net.citotech.cito.billing.pricing;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.citotech.cito.admin.AdminAuditService;
import net.citotech.cito.billing.pricing.PriceBookAuthoringService.ComponentDraft;
import net.citotech.cito.gateway.PaymentGatewayException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin authoring surface for {@code billing_price_book_versions}/{@code billing_price_components}
 * (Slice 20) - the first way to configure billing pricing without hand-writing SQL, mirroring
 * {@code PayoutConfigController}'s shape for this codebase's admin write surfaces. Every publish is
 * audited via {@link AdminAuditService}.
 */
@RestController
@RequestMapping(path = "/api/v2/admin/billing/price-books")
@PreAuthorize("hasRole('ADMIN')")
public class PriceBookAdminController {
    private final PriceBookAuthoringService authoringService;
    private final PriceResolver priceResolver;
    private final PriceBookRepository priceBookRepository;
    private final AdminAuditService auditService;

    public PriceBookAdminController(
            PriceBookAuthoringService authoringService,
            PriceResolver priceResolver,
            PriceBookRepository priceBookRepository,
            AdminAuditService auditService) {
        this.authoringService = authoringService;
        this.priceResolver = priceResolver;
        this.priceBookRepository = priceBookRepository;
        this.auditService = auditService;
    }

    /** The active version + components for a key, or 404-shaped body if none is configured yet. */
    @GetMapping
    public ResponseEntity<?> active(
            @RequestParam(value = "billingTenantId", required = false) Long billingTenantId,
            @RequestParam("serviceCode") String serviceCode,
            @RequestParam("meterCode") String meterCode,
            @RequestParam("chargeType") String chargeType) {
        Optional<PriceBookVersion> version =
                priceResolver.resolve(billingTenantId, serviceCode, meterCode, chargeType);
        if (version.isEmpty()) {
            return ResponseEntity.ok(
                    Map.of(
                            "code",
                            "PRICE_BOOK_NOT_CONFIGURED",
                            "message",
                            "No active price book for this key"));
        }
        List<PriceComponent> components = priceBookRepository.findComponents(version.get().id());
        return ResponseEntity.ok(toView(version.get(), components));
    }

    /** Publishes a new price-book version, closing the previously active one for the same key. */
    @PostMapping
    public ResponseEntity<?> publish(@RequestBody Map<String, Object> body) {
        try {
            Long billingTenantId = longValue(body.get("billingTenantId"));
            String serviceCode = stringValue(body.get("serviceCode"));
            if ("API_ACCESS".equals(serviceCode))
                throw new PaymentGatewayException(
                        "Use the API workbench to publish endpoint access rates");
            String meterCode = stringValue(body.get("meterCode"));
            String chargeType = stringValue(body.get("chargeType"));
            String currency = stringValue(body.get("currency"));
            Instant effectiveFrom = instantValue(body.get("effectiveFrom"));
            String createdBy = stringValue(body.get("createdBy"));
            List<ComponentDraft> components = toComponentDrafts(body.get("components"));

            PriceBookVersion published =
                    authoringService.publish(
                            billingTenantId,
                            serviceCode,
                            meterCode,
                            chargeType,
                            currency,
                            components,
                            effectiveFrom,
                            createdBy == null ? "system" : createdBy);

            auditService.record(
                    "BILLING_PRICE_BOOK",
                    "PRICE_BOOK_PUBLISH",
                    String.valueOf(published.id()),
                    createdBy == null ? "system" : createdBy);
            return ResponseEntity.ok(
                    toView(published, priceBookRepository.findComponents(published.id())));
        } catch (PaymentGatewayException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("code", "PRICE_BOOK_BAD_REQUEST", "message", e.getMessage()));
        }
    }

    @SuppressWarnings("unchecked")
    private List<ComponentDraft> toComponentDrafts(Object raw) {
        if (!(raw instanceof List<?> list)) {
            throw new PaymentGatewayException("components must be a non-empty array");
        }
        List<ComponentDraft> drafts = new ArrayList<>();
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> map)) {
                throw new PaymentGatewayException("Each component must be an object");
            }
            Map<String, Object> component = (Map<String, Object>) map;
            drafts.add(
                    new ComponentDraft(
                            stringValue(component.get("componentType")),
                            decimalValue(component.get("flatAmount")),
                            decimalValue(component.get("percentageRate")),
                            componentJsonValue(component.get("tierDefinition"))));
        }
        return drafts;
    }

    private String componentJsonValue(Object value) {
        if (value == null) {
            return null;
        }
        // Accept either a pre-serialized JSON string or the structured array Spring already
        // parsed the request body's tierDefinition into (a List of band objects) - RatingEngine
        // only needs it back out as a JSON string for storage.
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof List<?> list) {
            return new org.json.JSONArray(list).toString();
        }
        throw new PaymentGatewayException("tierDefinition must be a JSON array or string");
    }

    private Map<String, Object> toView(PriceBookVersion version, List<PriceComponent> components) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", version.id());
        view.put("billingTenantId", version.billingTenantId());
        view.put("serviceCode", version.serviceCode());
        view.put("meterCode", version.meterCode());
        view.put("chargeType", version.chargeType());
        view.put("currency", version.currency());
        view.put("versionNo", version.versionNo());
        view.put("effectiveFrom", version.effectiveFrom());
        view.put("effectiveTo", version.effectiveTo());
        List<Map<String, Object>> componentViews = new ArrayList<>();
        for (PriceComponent component : components) {
            Map<String, Object> componentView = new LinkedHashMap<>();
            componentView.put("componentType", component.componentType());
            componentView.put("sequenceNo", component.sequenceNo());
            componentView.put("flatAmount", component.flatAmount());
            componentView.put("percentageRate", component.percentageRate());
            componentView.put("tierDefinition", component.tierDefinitionJson());
            componentViews.add(componentView);
        }
        view.put("components", componentViews);
        return view;
    }

    private Long longValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : Long.valueOf(text);
    }

    private BigDecimal decimalValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : new BigDecimal(text);
    }

    private Instant instantValue(Object value) {
        if (value == null) {
            return null;
        }
        return Instant.parse(String.valueOf(value));
    }

    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }
}
