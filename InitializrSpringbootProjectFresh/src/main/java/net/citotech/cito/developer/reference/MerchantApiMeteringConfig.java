package net.citotech.cito.developer.reference;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.citotech.cito.Model.MerchantUser;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Portal authentication remains enforced by the existing session/feature filters and controllers.
 */
@Configuration
public class MerchantApiMeteringConfig implements WebMvcConfigurer {
    private final ApiAccessBillingService billing;
    private final ApiReferenceService reference;

    public MerchantApiMeteringConfig(
            ApiAccessBillingService billing, ApiReferenceService reference) {
        this.billing = billing;
        this.reference = reference;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(
                        new HandlerInterceptor() {
                            @Override
                            public boolean preHandle(
                                    HttpServletRequest request,
                                    HttpServletResponse response,
                                    Object handler) {
                                var session = request.getSession(false);
                                Object route =
                                        request.getAttribute(
                                                HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
                                if (route == null
                                        || session == null
                                        || !(session.getAttribute("merchantUser")
                                                instanceof MerchantUser user)) return true;
                                String path = route.toString();
                                // Only session APIs. Signed/API-key routes are charged at their own
                                // verified boundary.
                                if (!path.startsWith("/api/v2/portal/api-reference")
                                        && reference.isWorkspace(request.getMethod(), path)) {
                                    billing.admitted(user.getMerchant_id(), null, "PRODUCTION");
                                }
                                return true;
                            }
                        })
                .addPathPatterns("/api/v2/**");
    }
}
