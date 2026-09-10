package net.citotech.cito.experience;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** One tenant-safe onboarding/readiness surface shared by merchant and administrator portals. */
@RestController
@RequestMapping(path = "/api/v2/merchants/{merchantId}/onboarding")
public class MerchantOnboardingController {
    private final ExperienceAccessContext accessContext;
    private final MerchantOnboardingReadinessService readinessService;

    public MerchantOnboardingController(
            ExperienceAccessContext accessContext,
            MerchantOnboardingReadinessService readinessService) {
        this.accessContext = accessContext;
        this.readinessService = readinessService;
    }

    @GetMapping
    public Map<String, Object> readiness(
            @PathVariable long merchantId,
            HttpServletRequest request,
            Authentication authentication) {
        ExperienceAccessContext.Access access = accessContext.require(request, authentication);
        accessContext.requireMerchantScope(access, merchantId);
        return readinessService.readiness(merchantId);
    }
}
