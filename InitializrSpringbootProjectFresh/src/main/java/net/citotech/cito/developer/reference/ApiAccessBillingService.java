package net.citotech.cito.developer.reference;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.citotech.cito.admin.AdminAuditService;
import net.citotech.cito.billing.pricing.PriceBookAuthoringService;
import net.citotech.cito.billing.pricing.PriceBookAuthoringService.ComponentDraft;
import net.citotech.cito.billing.pricing.RatedCharge;
import net.citotech.cito.billing.pricing.RatedChargeRepository;
import net.citotech.cito.billing.tenancy.BillingTenantResolver;
import net.citotech.cito.billing.usage.UsageEvent;
import net.citotech.cito.billing.usage.UsageEventRepository;
import net.citotech.cito.money.MoneyAmount;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

/** Durable admission fees. Invoked only after an existing authentication boundary succeeds. */
@Service
public class ApiAccessBillingService {
    private static final String RECORDED = ApiAccessBillingService.class.getName();
    private final NamedParameterJdbcTemplate jdbc;
    private final BillingTenantResolver tenants;
    private final UsageEventRepository usage;
    private final RatedChargeRepository charges;
    private final PriceBookAuthoringService prices;
    private final AdminAuditService audit;
    private final ObjectMapper mapper;

    public ApiAccessBillingService(
            NamedParameterJdbcTemplate jdbc,
            BillingTenantResolver tenants,
            UsageEventRepository usage,
            RatedChargeRepository charges,
            PriceBookAuthoringService prices,
            AdminAuditService audit,
            ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.tenants = tenants;
        this.usage = usage;
        this.charges = charges;
        this.prices = prices;
        this.audit = audit;
        this.mapper = mapper;
    }

    public static String key(String method, String route) {
        return UUID.nameUUIDFromBytes(
                        (method + " " + route).getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toString();
    }

    /** Caller must supply the environment actually used by the operation, never a UI preference. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void admitted(long merchantId, Long billingTenantId, String environment) {
        if (!(RequestContextHolder.getRequestAttributes()
                instanceof ServletRequestAttributes attrs)) {
            throw new IllegalStateException("API admission requires an HTTP request");
        }
        HttpServletRequest request = attrs.getRequest();
        if (request.getAttribute(RECORDED) != null) return;
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern == null) throw new IllegalStateException("API route template is unavailable");
        String method = request.getMethod();
        String route = pattern.toString();
        if (!List.of("SANDBOX", "PRODUCTION").contains(environment)) {
            throw new IllegalArgumentException("Invalid API billing environment");
        }
        if (billingTenantId == null) {
            // Provision only from an existing, already-authenticated merchant; never from
            // client-supplied tenant IDs.
            jdbc.update(
                    "INSERT INTO billing_tenants (merchant_id,tenant_type,tenant_status) "
                            + "SELECT id,'CPAY_MERCHANT','ACTIVE' FROM merchants WHERE id=:merchant "
                            + "ON DUPLICATE KEY UPDATE merchant_id=merchant_id",
                    Map.of("merchant", merchantId));
        }
        long tenant =
                billingTenantId == null ? tenants.resolveTenantId(merchantId) : billingTenantId;
        Map<String, Object> rate = ensureRate(method, route);
        Instant now = Instant.now();
        String reference = "API-" + UUID.randomUUID();
        String meter = "api_" + key(method, route);
        String currency = rate.get("currency").toString();
        BigDecimal amount =
                "SANDBOX".equals(environment)
                        ? MoneyAmount.normalize(BigDecimal.ZERO)
                        : MoneyAmount.normalize((BigDecimal) rate.get("amount"));
        usage.insertIfAbsent(
                new UsageEvent(
                        0L,
                        tenant,
                        "API_ACCESS",
                        meter,
                        now,
                        BigDecimal.ONE,
                        currency,
                        Map.of(
                                "method",
                                method,
                                "route",
                                route,
                                "environment",
                                environment,
                                "unit",
                                "AUTHENTICATED_REQUEST",
                                "rateVersion",
                                rate.get("version_id").toString(),
                                "amount",
                                amount.toPlainString()),
                        reference,
                        reference,
                        null));
        // Sandbox evidence must never enter the production invoice staging query.
        if ("PRODUCTION".equals(environment)) {
            RatedCharge charge =
                    new RatedCharge(
                            ((Number) rate.get("version_id")).longValue(),
                            amount,
                            currency,
                            "HALF_UP_SCALE_4",
                            "[]",
                            "{\"quantity\":1,\"unit\":\"AUTHENTICATED_REQUEST\"}");
            charges.insertIfAbsent(
                    tenant,
                    "API_ACCESS",
                    meter,
                    "CUSTOMER_CHARGE",
                    reference,
                    BigDecimal.ONE,
                    charge,
                    reference);
        }
        request.setAttribute(RECORDED, reference);
    }

    public String signedEnvironment(HttpServletRequest request, String body) {
        String route =
                String.valueOf(
                        request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE));
        if (route.startsWith("/api/v2/refunds") || route.startsWith("/api/v2/batch-payouts/")) {
            String header = request.getHeader("X-CPay-Environment");
            return header == null
                            || header.isBlank()
                            || "PRODUCTION".equalsIgnoreCase(header.trim())
                    ? "PRODUCTION"
                    : "SANDBOX";
        }
        boolean nativePayment = route.startsWith("/api/v2/native/payments/");
        boolean compatibilityPayment =
                route.equals("/api/v2/payments/collect") || route.equals("/api/v2/payments/payout");
        // Reads and APIs without an explicit environment execution contract remain production.
        if (!nativePayment && !compatibilityPayment) return "PRODUCTION";
        String environment = request.getHeader("X-CPay-Environment");
        if (environment == null || environment.isBlank()) {
            try {
                environment = mapper.readTree(body).path("metadata").path("environment").asText("");
            } catch (Exception ignored) {
                environment = "";
            }
        }
        if (environment.isBlank()) return nativePayment ? "SANDBOX" : "PRODUCTION";
        // Mirrors MerchantEnvironmentService; this is billing classification, never activation.
        return "PRODUCTION".equalsIgnoreCase(environment.trim()) ? "PRODUCTION" : "SANDBOX";
    }

    @Transactional
    public Map<String, Object> ensureRate(String method, String route) {
        String id = key(method, route);
        jdbc.update(
                "INSERT INTO api_endpoint_rates (endpoint_key,http_method,route_template) "
                        + "VALUES (:id,:method,:route) ON DUPLICATE KEY UPDATE endpoint_key=endpoint_key",
                Map.of("id", id, "method", method, "route", route));
        Map<String, Object> row =
                jdbc.queryForMap(
                        "SELECT * FROM api_endpoint_rates WHERE endpoint_key=:id FOR UPDATE",
                        Map.of("id", id));
        if (row.get("version_id") == null) {
            var version =
                    prices.publish(
                            null,
                            "API_ACCESS",
                            "api_" + id,
                            "CUSTOMER_CHARGE",
                            "UGX",
                            List.of(new ComponentDraft("FLAT", BigDecimal.ZERO, null, null)),
                            Instant.now(),
                            "api-catalog");
            jdbc.update(
                    "UPDATE api_endpoint_rates SET version_id=:version WHERE endpoint_key=:id",
                    Map.of("version", version.id(), "id", id));
            row.put("version_id", version.id());
        }
        return row;
    }

    @Transactional
    public Map<String, Object> publish(
            String method, String route, String amountText, String currency, long expectedVersion) {
        if (amountText == null || amountText.isBlank())
            throw new IllegalArgumentException("Rate amount is required");
        BigDecimal amount = new BigDecimal(amountText);
        if (amount.signum() < 0 || amount.scale() > 4 || amount.precision() - amount.scale() > 15)
            throw new IllegalArgumentException(
                    "Rate must be non-negative with at most four decimal places");
        if (currency == null || !currency.matches("[A-Z]{3}"))
            throw new IllegalArgumentException("Currency must be a three-letter uppercase code");
        java.util.Currency.getInstance(currency);
        Map<String, Object> current = ensureRate(method, route);
        if (((Number) current.get("version_id")).longValue() != expectedVersion)
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT,
                    "Rate changed; reload before publishing");
        String id = key(method, route);
        var authentication =
                org.springframework.security.core.context.SecurityContextHolder.getContext()
                        .getAuthentication();
        String actor = authentication.getName();
        var version =
                prices.publish(
                        null,
                        "API_ACCESS",
                        "api_" + id,
                        "CUSTOMER_CHARGE",
                        currency,
                        List.of(
                                new ComponentDraft(
                                        "FLAT", MoneyAmount.normalize(amount), null, null)),
                        Instant.now(),
                        actor);
        jdbc.update(
                "UPDATE api_endpoint_rates SET version_id=:version,amount=:amount,currency=:currency "
                        + "WHERE endpoint_key=:id",
                Map.of(
                        "version",
                        version.id(),
                        "amount",
                        MoneyAmount.normalize(amount),
                        "currency",
                        currency,
                        "id",
                        id));
        audit.record(
                "API_PRICING",
                "API_RATE_PUBLISH",
                id,
                method + " " + route + " " + currency + " " + amount.toPlainString());
        return ensureRate(method, route);
    }

    public List<Map<String, Object>> rates() {
        return jdbc.queryForList(
                "SELECT * FROM api_endpoint_rates ORDER BY route_template,http_method", Map.of());
    }
}
