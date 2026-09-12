package net.citotech.cito.platform.kernel;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import net.citotech.cito.config.RequestCorrelationFilter;
import net.citotech.cito.experience.ExperienceAccessContext;
import net.citotech.cito.gateway.PaymentGatewayException;
import net.citotech.cito.platform.CitoEntitlementService;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/** Resolves one server-side tenant/actor/environment/correlation context for portal operations. */
@Component
public class PlatformTenantContextResolver {
    private final ExperienceAccessContext accessContext;
    private final CitoEntitlementService entitlements;

    public PlatformTenantContextResolver(
            ExperienceAccessContext accessContext, CitoEntitlementService entitlements) {
        this.accessContext = accessContext;
        this.entitlements = entitlements;
    }

    public PlatformTenantContext require(
            HttpServletRequest request, Authentication authentication) {
        ExperienceAccessContext.Access access = accessContext.require(request, authentication);
        Long merchantId = access.merchantId();
        Long organizationId =
                merchantId == null ? null : entitlements.ensureMerchantOrganization(merchantId);
        return new PlatformTenantContext(
                organizationId,
                merchantId,
                access.actor(),
                access.admin() ? "ADMIN" : "MERCHANT_USER",
                Set.of(access.role()),
                environment(request),
                application(request),
                correlationId(request));
    }

    private String environment(HttpServletRequest request) {
        String value = request.getHeader("X-CPay-Environment");
        if (value == null || value.isBlank()) {
            return "SANDBOX";
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("SANDBOX", "PRODUCTION").contains(normalized)) {
            throw new PaymentGatewayException("Environment must be SANDBOX or PRODUCTION");
        }
        return normalized;
    }

    private String application(HttpServletRequest request) {
        String value = request.getHeader("X-Cito-Application-ID");
        return value == null || value.isBlank() ? "CITO_PORTAL" : value.trim();
    }

    private String correlationId(HttpServletRequest request) {
        String value = MDC.get("request_id");
        if (value == null || value.isBlank()) {
            value = request.getHeader(RequestCorrelationFilter.REQUEST_ID_HEADER);
        }
        return value == null || value.isBlank() ? UUID.randomUUID().toString() : value.trim();
    }
}
