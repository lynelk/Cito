package net.citotech.cito.experience;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.citotech.cito.platform.CitoEntitlementService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds one coherent onboarding/readiness view from the existing activation lifecycle, sandbox,
 * entitlement and go-live stores. It is a read model only; the owning services remain the mutation
 * boundaries for lifecycle, entitlements and production activation.
 */
@Service
public class MerchantOnboardingReadinessService {
    private static final Set<String> DONE_STATUSES =
            Set.of("COMPLETED", "WAIVED", "WAIVED_LEGACY", "SKIPPED");
    private static final Set<String> BLOCKING_STATUSES =
            Set.of("BLOCKED", "FAILED", "NEEDS_RESUBMISSION");

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final MerchantActivationLifecycleService lifecycleService;
    private final CitoEntitlementService entitlementService;

    public MerchantOnboardingReadinessService(
            NamedParameterJdbcTemplate jdbcTemplate,
            MerchantActivationLifecycleService lifecycleService,
            CitoEntitlementService entitlementService) {
        this.jdbcTemplate = jdbcTemplate;
        this.lifecycleService = lifecycleService;
        this.entitlementService = entitlementService;
    }

    @Transactional
    public Map<String, Object> readiness(long merchantId) {
        lifecycleService.ensure(merchantId);
        entitlementService.ensureMerchantOrganization(merchantId);
        MapSqlParameterSource scope = new MapSqlParameterSource("merchantId", merchantId);

        Map<String, Object> lifecycle =
                first(
                        "SELECT id,lifecycle_reference AS lifecycleReference,status,"
                                + "current_step_code AS currentStepCode,next_action AS nextAction,"
                                + "blocked_reason AS blockedReason,created_at AS createdAt,"
                                + "updated_at AS updatedAt,activated_at AS activatedAt "
                                + "FROM merchant_activation_lifecycles WHERE merchant_id=:merchantId",
                        scope);
        List<Map<String, Object>> steps =
                jdbcTemplate.queryForList(
                        "SELECT s.step_code AS stepCode,s.step_name AS stepName,s.status,"
                                + "s.responsible_party AS responsibleParty,"
                                + "s.required_for_activation AS requiredForActivation,s.guidance,s.blocker,"
                                + "s.completed_by AS completedBy,s.completed_at AS completedAt,s.sort_order AS sortOrder "
                                + "FROM merchant_activation_steps s JOIN merchant_activation_lifecycles l ON l.id=s.lifecycle_id "
                                + "WHERE l.merchant_id=:merchantId ORDER BY s.sort_order",
                        scope);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("merchantId", merchantId);
        response.put("lifecycle", lifecycle);
        response.put("progress", progress(steps));
        response.put("steps", steps);
        response.put("blockers", blockers(steps));
        response.put("sandbox", sandbox(scope, steps));
        response.put("products", products(scope));
        response.put("goLive", goLive(scope));
        response.put("productionRollout", productionRollout(scope));
        response.put("nextAction", lifecycle.get("nextAction"));
        Map<String, Object> assessment = MerchantReadinessAssessment.assess(lifecycle, steps);
        response.put("readinessAssessment", assessment);
        response.put("readyForProduction", assessment.get("readyForProduction"));
        return response;
    }

    private Map<String, Object> progress(List<Map<String, Object>> steps) {
        long required =
                steps.stream().filter(step -> truthy(step.get("requiredForActivation"))).count();
        long completed =
                steps.stream()
                        .filter(step -> truthy(step.get("requiredForActivation")))
                        .filter(step -> DONE_STATUSES.contains(text(step.get("status"))))
                        .count();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requiredSteps", required);
        result.put("completedRequiredSteps", completed);
        result.put(
                "completionPercent",
                required == 0
                        ? BigDecimal.ZERO.setScale(2)
                        : BigDecimal.valueOf(completed)
                                .multiply(BigDecimal.valueOf(100))
                                .divide(BigDecimal.valueOf(required), 2, RoundingMode.HALF_UP));
        return result;
    }

    private List<Map<String, Object>> blockers(List<Map<String, Object>> steps) {
        return steps.stream()
                .filter(step -> BLOCKING_STATUSES.contains(text(step.get("status"))))
                .map(
                        step -> {
                            Map<String, Object> blocker = new LinkedHashMap<>();
                            blocker.put("stepCode", step.get("stepCode"));
                            blocker.put("stepName", step.get("stepName"));
                            blocker.put("status", step.get("status"));
                            blocker.put("responsibleParty", step.get("responsibleParty"));
                            blocker.put("blocker", step.get("blocker"));
                            blocker.put("guidance", step.get("guidance"));
                            return blocker;
                        })
                .toList();
    }

    private Map<String, Object> sandbox(
            MapSqlParameterSource scope, List<Map<String, Object>> steps) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("configured", stepDone(steps, "SANDBOX_CONFIGURED"));
        result.put("integrationTested", stepDone(steps, "INTEGRATION_TESTED"));
        Map<String, Object> certification =
                first(
                        "SELECT id,run_status AS runStatus,passed_checks AS passedChecks,"
                                + "total_checks AS totalChecks,started_at AS startedAt,completed_at AS completedAt "
                                + "FROM sandbox_certification_runs WHERE merchant_id=:merchantId "
                                + "ORDER BY id DESC LIMIT 1",
                        scope);
        result.put("latestCertification", certification.isEmpty() ? null : certification);
        return result;
    }

    private List<Map<String, Object>> products(MapSqlParameterSource scope) {
        List<Map<String, Object>> rows =
                jdbcTemplate.queryForList(
                        "SELECT s.service_code AS serviceCode,s.service_name AS serviceName,s.description,"
                                + "e.environment,"
                                + "CASE WHEN e.status='ACTIVE' "
                                + "AND (e.starts_at IS NULL OR e.starts_at<=CURRENT_TIMESTAMP) "
                                + "AND (e.ends_at IS NULL OR e.ends_at>CURRENT_TIMESTAMP) "
                                + "THEN 'ACTIVE' WHEN e.status='ACTIVE' THEN 'INACTIVE_WINDOW' ELSE e.status END AS entitlementStatus,"
                                + "e.plan_code AS planCode,e.starts_at AS startsAt,e.ends_at AS endsAt "
                                + "FROM cito_service_catalog s LEFT JOIN cito_organizations o ON o.merchant_id=:merchantId "
                                + "LEFT JOIN cito_service_entitlements e ON e.organization_id=o.id AND e.service_code=s.service_code "
                                + "WHERE s.status='ACTIVE' ORDER BY s.service_name,e.environment",
                        scope);
        Map<String, Map<String, Object>> products = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String serviceCode = text(row.get("serviceCode"));
            Map<String, Object> product =
                    products.computeIfAbsent(
                            serviceCode,
                            ignored -> {
                                Map<String, Object> created = new LinkedHashMap<>();
                                created.put("serviceCode", serviceCode);
                                created.put("serviceName", row.get("serviceName"));
                                created.put("description", row.get("description"));
                                created.put("sandboxStatus", "NOT_ENABLED");
                                created.put("productionStatus", "NOT_ENABLED");
                                return created;
                            });
            String environment = text(row.get("environment"));
            String status = text(row.get("entitlementStatus"));
            if ("SANDBOX".equals(environment) && !status.isBlank()) {
                product.put("sandboxStatus", status);
            }
            if ("PRODUCTION".equals(environment) && !status.isBlank()) {
                product.put("productionStatus", status);
                product.put("productionPlanCode", row.get("planCode"));
                product.put("productionStartsAt", row.get("startsAt"));
                product.put("productionEndsAt", row.get("endsAt"));
            }
        }
        return List.copyOf(products.values());
    }

    private Map<String, Object> goLive(MapSqlParameterSource scope) {
        Map<String, Object> request =
                first(
                        "SELECT id,request_status AS requestStatus,current_stage AS currentStage,"
                                + "requested_at AS requestedAt,updated_at AS updatedAt,approved_at AS approvedAt,"
                                + "activated_at AS activatedAt,decision_notes AS decisionNotes "
                                + "FROM merchant_go_live_requests WHERE merchant_id=:merchantId "
                                + "ORDER BY id DESC LIMIT 1",
                        scope);
        return request.isEmpty() ? Map.of("requestStatus", "NOT_REQUESTED") : request;
    }

    private Map<String, Object> productionRollout(MapSqlParameterSource scope) {
        Map<String, Object> rollout =
                first(
                        "SELECT stage_code AS stageCode,production_daily_limit AS productionDailyLimit,"
                                + "collections_enabled AS collectionsEnabled,refunds_enabled AS refundsEnabled,"
                                + "payouts_enabled AS payoutsEnabled,updated_at AS updatedAt "
                                + "FROM merchant_rollout_stages WHERE merchant_id=:merchantId",
                        scope);
        return rollout.isEmpty() ? Map.of("stageCode", "SANDBOX") : rollout;
    }

    private boolean stepDone(List<Map<String, Object>> steps, String stepCode) {
        return steps.stream()
                .anyMatch(
                        step ->
                                stepCode.equals(text(step.get("stepCode")))
                                        && DONE_STATUSES.contains(text(step.get("status"))));
    }

    private Map<String, Object> first(String sql, MapSqlParameterSource parameters) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, parameters);
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    private boolean truthy(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        return "TRUE".equalsIgnoreCase(text(value)) || "YES".equalsIgnoreCase(text(value));
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim().toUpperCase();
    }
}
