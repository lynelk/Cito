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
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

class PlatformEvidenceControllerTest {
    private final ExperienceAccessContext access = mock(ExperienceAccessContext.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final Authentication authentication = mock(Authentication.class);
    private final PlatformEvidenceController controller =
            new PlatformEvidenceController(access, jdbc);

    @Test
    void administratorGetsDurableEvidenceWithoutTargetInflation() {
        var admin =
                new ExperienceAccessContext.Access(
                        "admin@example.invalid", null, true, "ADMIN");
        when(access.require(request, authentication)).thenReturn(admin);
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(2L);

        Map<String, Object> providerEvidence = new LinkedHashMap<>();
        providerEvidence.put("providerCode", "*");
        providerEvidence.put("channelCode", "*");
        providerEvidence.put("requiredScenarios", 11L);
        providerEvidence.put("approvedScenarios", 0L);
        when(jdbc.queryForList(anyString())).thenReturn(List.of(providerEvidence));

        Map<String, Object> result = controller.scorecard(request, authentication);

        verify(access).requireAdmin(admin);
        assertThat(result.get("evidenceBasis")).isEqualTo("DURABLE_RECORDS_ONLY");
        assertThat(result.get("targetsReportedAsActuals")).isEqualTo(false);
        @SuppressWarnings("unchecked")
        Map<String, Long> developer = (Map<String, Long>) result.get("developer");
        assertThat(developer.get("apiRequests7d")).isEqualTo(2L);
        assertThat(result.get("providerCertification")).isEqualTo(List.of(providerEvidence));
    }

    @Test
    void merchantCannotReadPlatformCommercialEvidence() {
        var merchant =
                new ExperienceAccessContext.Access(
                        "merchant@example.invalid", 42L, false, "OWNER");
        when(access.require(request, authentication)).thenReturn(merchant);
        doThrow(
                        new ResponseStatusException(
                                HttpStatus.FORBIDDEN, "Administrator access required"))
                .when(access)
                .requireAdmin(merchant);

        assertThatThrownBy(() -> controller.scorecard(request, authentication))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(jdbc);
    }
}
