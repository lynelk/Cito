package net.citotech.cito.platform.kernel;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import net.citotech.cito.config.RequestCorrelationFilter;
import net.citotech.cito.experience.ExperienceAccessContext;
import net.citotech.cito.gateway.PaymentGatewayException;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Resolves portal identity from the existing authenticated access boundary and organization store.
 * Reading a context never provisions an organization or grants an entitlement. Provisioning remains
 * owned by CitoEntitlementService. A caller-supplied application header is not an authenticated app.
 */
@Component
public class PlatformTenantContextResolver {
    private final ExperienceAccessContext accessContext;
    private final NamedParameterJdbcTemplate jdbc;

    public PlatformTenantContextResolver(
            ExperienceAccessContext accessContext, NamedParameterJdbcTemplate jdbc) {
        this.accessContext = accessContext;
        this.jdbc = jdbc;
    }

    public PlatformTenantContext require(
            HttpServletRequest request, Authentication authentication) {
        ExperienceAccessContext.Access access = accessContext.require(request, authentication);
        String environment = environment(request);
        Long merchantId = access.merchantId();
        return new PlatformTenantContext(
                organizationId(merchantId),
                merchantId,
                access.actor(),
                access.admin() ? "ADMIN" : "MERCHANT_USER",
                Set.of(access.role()),
                environment,
                "ADMIN_API".equals(access.role()) ? "CITO_ADMIN_API" : "CITO_PORTAL",
                correlationId(request));
    }

    public void requireAdmin(PlatformTenantContext context) {
        if (!isAdmin(context)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Administrator access required");
        }
    }

    public void requireMerchantScope(PlatformTenantContext context, long merchantId) {
        if (context == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Portal login is required");
        }
        if (merchantId <= 0
                || (!isAdmin(context) && !Long.valueOf(merchantId).equals(context.merchantId()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Merchant access denied");
        }
    }

    private boolean isAdmin(PlatformTenantContext context) {
        return context != null
                && context.platformScoped()
                && "ADMIN".equals(context.actorType())
                && (context.roles().contains("ADMIN") || context.roles().contains("ADMIN_API"));
    }

    private Long organizationId(Long merchantId) {
        if (merchantId == null) return null;
        List<Long> rows = jdbc.queryForList(
                "SELECT id FROM cito_organizations WHERE merchant_id=:merchant_id",
                new MapSqlParameterSource("merchant_id", merchantId), Long.class);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private String environment(HttpServletRequest request) {
        String value = request.getHeader("X-CPay-Environment");
        if (value == null || value.isBlank()) return "SANDBOX";
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("SANDBOX", "PRODUCTION").contains(normalized)) {
            throw new PaymentGatewayException("Environment must be SANDBOX or PRODUCTION");
        }
        return normalized;
    }

    private String correlationId(HttpServletRequest request) {
        String value = MDC.get("request_id");
        if (value == null || value.isBlank()) {
            value = request.getHeader(RequestCorrelationFilter.REQUEST_ID_HEADER);
        }
        return value != null && value.trim().matches("[A-Za-z0-9._:-]{1,128}")
                ? value.trim() : UUID.randomUUID().toString();
    }
}
