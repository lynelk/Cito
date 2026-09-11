package net.citotech.cito.experience;

import static org.assertj.core.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MerchantReadinessAssessmentTest {
    private Map<String, Object> step(String code, String status) {
        return Map.of("stepCode", code, "status", status, "requiredForActivation", true,
                "completedAt", "2026-09-11T00:00:00Z");
    }

    private List<Map<String, Object>> complete() {
        return new ArrayList<>(List.of(step("SANDBOX_CONFIGURED", "COMPLETED"),
                step("INTEGRATION_TESTED", "COMPLETED"), step("PROVIDER_CERTIFIED", "COMPLETED"),
                step("GO_LIVE_APPROVED", "COMPLETED")));
    }

    @Test void emptyStepsNeverBecomeReadyThroughVacuousTruth() {
        var result = MerchantReadinessAssessment.assess(Map.of(), List.of());
        assertThat(result.get("readyForProduction")).isEqualTo(false);
        assertThat(result.get("state")).isEqualTo("NOT_CONFIGURED");
    }

    @Test void liveLabelAloneCannotInventActivationEvidence() {
        var result = MerchantReadinessAssessment.assess(Map.of("status", "LIVE"), List.of());
        assertThat(result.get("productionEnabled")).isEqualTo(false);
        assertThat(result.get("state")).isEqualTo("DEGRADED");
    }

    @Test void configuredAndTestedAreNotCertifiedOrEnabled() {
        var steps = List.of(step("SANDBOX_CONFIGURED", "COMPLETED"), step("INTEGRATION_TESTED", "COMPLETED"));
        var result = MerchantReadinessAssessment.assess(Map.of(), steps);
        assertThat(result.get("sandboxVerified")).isEqualTo(true);
        assertThat(result.get("state")).isEqualTo("CERTIFICATION_PENDING");
        assertThat(result.get("productionEnabled")).isEqualTo(false);
    }

    @Test void waivedOrUntimestampedTestsDoNotClaimVerification() {
        for (String status : List.of("WAIVED", "WAIVED_LEGACY", "SKIPPED")) {
            var result = MerchantReadinessAssessment.assess(Map.of(), List.of(step("SANDBOX_CONFIGURED", status)));
            assertThat(result.get("configured")).isEqualTo(false);
        }
        var result = MerchantReadinessAssessment.assess(Map.of(), List.of(Map.of(
                "stepCode", "SANDBOX_CONFIGURED", "status", "COMPLETED", "requiredForActivation", true)));
        assertThat(result.get("configured")).isEqualTo(false);
    }

    @Test void completedEvidenceAllowsReadinessButNotAutomaticActivation() {
        var result = MerchantReadinessAssessment.assess(Map.of("status", "GO_LIVE_APPROVED"), complete());
        assertThat(result.get("readyForProduction")).isEqualTo(true);
        assertThat(result.get("productionEnabled")).isEqualTo(false);
        assertThat(result.get("providerActivationImplied")).isEqualTo(false);
    }

    @Test void completeActivationIsStillDistinctFromIndividualProviderEnablement() {
        var steps = complete();
        steps.add(step("PRODUCTION_ACTIVATED", "COMPLETED"));
        var result = MerchantReadinessAssessment.assess(Map.of("status", "LIVE", "activatedAt", "2026-09-11T00:00:00Z"), steps);
        assertThat(result.get("state")).isEqualTo("PRODUCTION_ENABLED");
        assertThat(result.get("providerActivationImplied")).isEqualTo(false);
        steps.add(step("RISK_REVIEW", "BLOCKED"));
        result = MerchantReadinessAssessment.assess(Map.of("status", "LIVE", "activatedAt", "2026-09-11T00:00:00Z"), steps);
        assertThat(result.get("state")).isEqualTo("DEGRADED");
        assertThat(result.get("readyForProduction")).isEqualTo(false);
    }
}
