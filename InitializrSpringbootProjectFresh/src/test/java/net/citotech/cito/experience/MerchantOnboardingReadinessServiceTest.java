package net.citotech.cito.experience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.citotech.cito.platform.CitoEntitlementService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class MerchantOnboardingReadinessServiceTest {

    @Test
    void derivesProgressAndBlockersFromTheCanonicalLifecycle() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        MerchantActivationLifecycleService lifecycleService =
                mock(MerchantActivationLifecycleService.class);
        CitoEntitlementService entitlementService = mock(CitoEntitlementService.class);

        Map<String, Object> lifecycle = new LinkedHashMap<>();
        lifecycle.put("status", "RISK_REVIEW");
        lifecycle.put("nextAction", "Resolve the risk review blocker.");

        Map<String, Object> completed = step("KYB_REVIEW", "COMPLETED", null);
        Map<String, Object> blocked = step("RISK_REVIEW", "BLOCKED", "Missing review evidence");

        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class)))
                .thenAnswer(
                        invocation -> {
                            String sql = invocation.getArgument(0);
                            if (sql.contains("FROM merchant_activation_lifecycles WHERE")) {
                                return List.of(lifecycle);
                            }
                            if (sql.contains("FROM merchant_activation_steps s")) {
                                return List.of(completed, blocked);
                            }
                            if (sql.contains("FROM cito_service_catalog")) {
                                return List.of();
                            }
                            return List.of();
                        });

        Map<String, Object> result =
                new MerchantOnboardingReadinessService(jdbc, lifecycleService, entitlementService)
                        .readiness(42L);

        verify(lifecycleService).ensure(42L);
        verify(entitlementService).ensureMerchantOrganization(42L);
        Map<?, ?> progress = (Map<?, ?>) result.get("progress");
        assertThat(progress.get("requiredSteps")).isEqualTo(2L);
        assertThat(progress.get("completedRequiredSteps")).isEqualTo(1L);
        assertThat((List<?>) result.get("blockers")).hasSize(1);
        assertThat(result.get("readyForProduction")).isEqualTo(false);
        assertThat(result.get("nextAction")).isEqualTo("Resolve the risk review blocker.");
        Map<?, ?> goLive = (Map<?, ?>) result.get("goLive");
        assertThat(goLive.get("requestStatus")).isEqualTo("NOT_REQUESTED");
    }

    @Test
    void requiresEveryCanonicalRequiredStepExceptFinalActivation() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        MerchantActivationLifecycleService lifecycleService =
                mock(MerchantActivationLifecycleService.class);
        CitoEntitlementService entitlementService = mock(CitoEntitlementService.class);

        Map<String, Object> lifecycle = new LinkedHashMap<>();
        lifecycle.put("status", "GO_LIVE_APPROVED");
        lifecycle.put("nextAction", "Complete the business profile.");

        Map<String, Object> completed = step("GO_LIVE_APPROVED", "COMPLETED", null);
        Map<String, Object> missing = step("BUSINESS_PROFILE", "NOT_STARTED", null);
        Map<String, Object> finalActivation = step("PRODUCTION_ACTIVATED", "NOT_STARTED", null);

        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class)))
                .thenAnswer(
                        invocation -> {
                            String sql = invocation.getArgument(0);
                            if (sql.contains("FROM merchant_activation_lifecycles WHERE")) {
                                return List.of(lifecycle);
                            }
                            if (sql.contains("FROM merchant_activation_steps s")) {
                                return List.of(completed, missing, finalActivation);
                            }
                            return List.of();
                        });

        Map<String, Object> result =
                new MerchantOnboardingReadinessService(jdbc, lifecycleService, entitlementService)
                        .readiness(77L);

        assertThat(result.get("readyForProduction")).isEqualTo(false);
    }

    private Map<String, Object> step(String code, String status, String blocker) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("stepCode", code);
        step.put("stepName", code);
        step.put("status", status);
        step.put("responsibleParty", "COMPLIANCE");
        step.put("requiredForActivation", true);
        step.put("guidance", "Complete the required review.");
        step.put("blocker", blocker);
        return step;
    }
}
