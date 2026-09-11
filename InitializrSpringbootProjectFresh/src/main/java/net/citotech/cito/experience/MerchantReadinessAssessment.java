package net.citotech.cito.experience;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** One evidence-derived onboarding assessment; it does not grant access or activate a provider. */
public final class MerchantReadinessAssessment {
    private static final Set<String> DONE =
            Set.of("COMPLETED", "WAIVED", "WAIVED_LEGACY", "SKIPPED");
    private static final Set<String> BLOCKED = Set.of("BLOCKED", "FAILED", "NEEDS_RESUBMISSION");
    private static final Set<String> CORE =
            Set.of(
                    "SANDBOX_CONFIGURED",
                    "INTEGRATION_TESTED",
                    "PROVIDER_CERTIFIED",
                    "GO_LIVE_APPROVED");

    private MerchantReadinessAssessment() {}

    public static Map<String, Object> assess(
            Map<String, Object> lifecycle, List<Map<String, Object>> steps) {
        boolean blocked =
                steps.stream().anyMatch(step -> BLOCKED.contains(text(step.get("status"))))
                        || Set.of("SUSPENDED", "REJECTED", "BLOCKED")
                                .contains(text(lifecycle.get("status")));
        List<Map<String, Object>> required =
                steps.stream()
                        .filter(step -> required(step.get("requiredForActivation")))
                        .filter(step -> !"PRODUCTION_ACTIVATED".equals(text(step.get("stepCode"))))
                        .toList();
        Set<String> codes =
                required.stream()
                        .map(step -> text(step.get("stepCode")))
                        .collect(java.util.stream.Collectors.toSet());
        boolean allRequired =
                !required.isEmpty()
                        && codes.containsAll(CORE)
                        && required.stream()
                                .allMatch(step -> DONE.contains(text(step.get("status"))));
        boolean configured = completed(steps, "SANDBOX_CONFIGURED");
        boolean sandboxVerified = completed(steps, "INTEGRATION_TESTED");
        boolean certified = completed(steps, "PROVIDER_CERTIFIED");
        boolean approved = completed(steps, "GO_LIVE_APPROVED");
        boolean ready =
                !blocked && allRequired && configured && sandboxVerified && certified && approved;
        boolean enabled =
                ready
                        && "LIVE".equals(text(lifecycle.get("status")))
                        && lifecycle.get("activatedAt") != null
                        && completed(steps, "PRODUCTION_ACTIVATED");
        String state;
        String action;
        if (blocked || ("LIVE".equals(text(lifecycle.get("status"))) && !enabled)) {
            state = "DEGRADED";
            action = "Review the recorded blockers or missing activation evidence with Operations.";
        } else if (enabled) {
            state = "PRODUCTION_ENABLED";
            action =
                    "Use only the services, providers, environments and limits separately approved for this account.";
        } else if (!configured) {
            state = "NOT_CONFIGURED";
            action =
                    "Complete the sandbox configuration and its evidence in the activation journey.";
        } else if (!sandboxVerified) {
            state = "CONFIGURED";
            action = "Complete approved sandbox tests; configuration alone is not verification.";
        } else if (!certified || !approved) {
            state = "CERTIFICATION_PENDING";
            action = "Complete provider certification and independent go-live approval.";
        } else {
            state = "SANDBOX_VERIFIED";
            action =
                    ready
                            ? "Request controlled production activation from Operations."
                            : "Complete the remaining required activation steps.";
        }
        return Map.of(
                "scope",
                "MERCHANT_ONBOARDING",
                "state",
                state,
                "configured",
                configured,
                "sandboxVerified",
                sandboxVerified,
                "providerCertificationRecorded",
                certified,
                "readyForProduction",
                ready,
                "productionEnabled",
                enabled,
                "nextAction",
                action,
                "providerActivationImplied",
                false);
    }

    private static boolean completed(List<Map<String, Object>> steps, String code) {
        // A waived or skipped step is not proof that a provider/test actually passed.
        return steps.stream()
                .anyMatch(
                        step ->
                                code.equals(text(step.get("stepCode")))
                                        && "COMPLETED".equals(text(step.get("status")))
                                        && step.get("completedAt") != null);
    }

    private static boolean required(Object value) {
        return Boolean.TRUE.equals(value)
                || value instanceof Number number && number.intValue() != 0
                || "YES".equals(text(value))
                || "TRUE".equals(text(value));
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString().trim().toUpperCase(Locale.ROOT);
    }
}
