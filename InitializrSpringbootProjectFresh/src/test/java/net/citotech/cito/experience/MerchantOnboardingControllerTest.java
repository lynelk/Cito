package net.citotech.cito.experience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

class MerchantOnboardingControllerTest {

    @Test
    void preservesAuthenticatedMerchantScopeBeforeReadingReadiness() {
        ExperienceAccessContext accessContext = mock(ExperienceAccessContext.class);
        MerchantOnboardingReadinessService readinessService =
                mock(MerchantOnboardingReadinessService.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        Authentication authentication = mock(Authentication.class);
        ExperienceAccessContext.Access access =
                new ExperienceAccessContext.Access("merchant@example.test", 42L, false, "OWNER");
        when(accessContext.require(request, authentication)).thenReturn(access);
        when(readinessService.readiness(42L)).thenReturn(Map.of("merchantId", 42L));

        Map<String, Object> response =
                new MerchantOnboardingController(accessContext, readinessService)
                        .readiness(42L, request, authentication);

        assertThat(response).containsEntry("merchantId", 42L);
        verify(accessContext).requireMerchantScope(access, 42L);
        verify(readinessService).readiness(42L);
    }
}
