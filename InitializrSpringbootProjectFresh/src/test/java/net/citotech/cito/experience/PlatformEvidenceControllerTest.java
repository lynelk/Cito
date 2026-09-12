package net.citotech.cito.experience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.citotech.cito.platform.kernel.PlatformTenantContext;
import net.citotech.cito.platform.kernel.PlatformTenantContextResolver;
import net.citotech.cito.platform.provider.PlatformProviderDomain;
import net.citotech.cito.platform.provider.PlatformProviderRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

class PlatformEvidenceControllerTest {
    private final PlatformTenantContextResolver contexts =
            mock(PlatformTenantContextResolver.class);
    private final PlatformProviderRegistry providers = mock(PlatformProviderRegistry.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final Authentication authentication = mock(Authentication.class);
    private final PlatformEvidenceController controller =
            new PlatformEvidenceController(contexts, providers, jdbc);

    @Test
    void administratorGetsDurableEvidenceWithoutTargetInflation() {
        var admin =
                new PlatformTenantContext(
                        null,
                        null,
                        "admin@example.invalid",
                        "ADMIN",
                        Set.of("ADMIN"),
                        "PRODUCTION",
                        "CITO_PORTAL",
                        "req-1");
        when(contexts.require(request, authentication)).thenReturn(admin);
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(2L);
        when(providers.definitions())
                .thenReturn(
                        List.of(
                                new PlatformProviderRegistry.ProviderDefinition(
                                        "MTN_MOMO",
                                        PlatformProviderDomain.PAYMENT,
                                        Set.of("COLLECTION", "PAYOUT"),
                                        Set.of("SANDBOX", "PRODUCTION"))));

        Map<String, Object> providerEvidence = new LinkedHashMap<>();
        providerEvidence.put("providerCode", "*");
        providerEvidence.put("channelCode", "*");
        providerEvidence.put("requiredScenarios", 11L);
        providerEvidence.put("approvedScenarios", 0L);
        when(jdbc.queryForList(anyString())).thenReturn(List.of(providerEvidence));

        Map<String, Object> result = controller.scorecard(request, authentication);

        verify(contexts).requireAdmin(admin);
        assertThat(result.get("evidenceBasis")).isEqualTo("DURABLE_RECORDS_ONLY");
        assertThat(result.get("targetsReportedAsActuals")).isEqualTo(false);
        @SuppressWarnings("unchecked")
        Map<String, Long> developer = (Map<String, Long>) result.get("developer");
        assertThat(developer.get("apiRequests7d")).isEqualTo(2L);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> definitions =
                (List<Map<String, Object>>) result.get("providerDefinitions");
        assertThat(definitions).hasSize(1);
        assertThat(definitions.getFirst().get("domain")).isEqualTo("PAYMENT");
        assertThat(result.get("providerCertification")).isEqualTo(List.of(providerEvidence));
    }

    @Test
    void merchantCannotReadPlatformCommercialEvidence() {
        var merchant =
                new PlatformTenantContext(
                        9L,
                        42L,
                        "merchant@example.invalid",
                        "MERCHANT_USER",
                        Set.of("OWNER"),
                        "SANDBOX",
                        "CITO_PORTAL",
                        "req-2");
        when(contexts.require(request, authentication)).thenReturn(merchant);
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Administrator access required"))
                .when(contexts)
                .requireAdmin(merchant);

        assertThatThrownBy(() -> controller.scorecard(request, authentication))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(jdbc);
        verifyNoInteractions(providers);
    }
}
