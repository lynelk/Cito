package net.citotech.cito.experience;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.citotech.cito.platform.kernel.PlatformTenantContext;
import net.citotech.cito.platform.kernel.PlatformTenantContextResolver;
import net.citotech.cito.platform.provider.PlatformProviderRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only operational evidence, not targets, settlement claims or live provider certification.
 */
@RestController
@RequestMapping("/api/v2/admin/platform-evidence")
public class PlatformEvidenceController {
    private final PlatformTenantContextResolver contexts;
    private final PlatformProviderRegistry providers;
    private final JdbcTemplate jdbc;

    public PlatformEvidenceController(
            PlatformTenantContextResolver contexts,
            PlatformProviderRegistry providers,
            JdbcTemplate jdbc) {
        this.contexts = contexts;
        this.providers = providers;
        this.jdbc = jdbc;
    }

    @GetMapping("/scorecard")
    @Transactional(readOnly = true)
    public Map<String, Object> scorecard(
            HttpServletRequest request, Authentication authentication) {
        PlatformTenantContext context = contexts.require(request, authentication);
        contexts.requireAdmin(context);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("evidenceBasis", "DURABLE_RECORDS_ONLY");
        out.put("targetsReportedAsActuals", false);
        out.put("commercial", commercialEvidence());
        out.put("developer", developerEvidence());
        out.put("adoption", adoptionEvidence());
        out.put("providerDefinitions", providerDefinitions());
        out.put("providerCertification", providerCertification());
        return out;
    }

    private Map<String, Long> commercialEvidence() {
        Map<String, Long> values = new LinkedHashMap<>();
        values.put(
                "founding20Candidates",
                count(
                        "SELECT COUNT(*) FROM founding20_merchants WHERE programme_status='CANDIDATE'"));
        values.put(
                "founding20Active",
                count(
                        "SELECT COUNT(*) FROM founding20_merchants WHERE programme_status IN ('ACTIVE','ONBOARDING','LIVE')"));
        values.put(
                "founding20Live",
                count("SELECT COUNT(*) FROM founding20_merchants WHERE programme_status='LIVE'"));
        values.put(
                "activePackageAssignments",
                count(
                        "SELECT COUNT(*) FROM merchant_commercial_package_assignments",
                        "WHERE status='ACTIVE' AND environment='PRODUCTION'",
                        "AND (effective_from IS NULL OR effective_from<=CURRENT_TIMESTAMP)",
                        "AND (effective_to IS NULL OR effective_to>CURRENT_TIMESTAMP)"));
        values.put(
                "embeddedProgrammesLive",
                count(
                        "SELECT COUNT(*) FROM embedded_partner_programmes WHERE programme_status='LIVE'"));
        return values;
    }

    private Map<String, Long> developerEvidence() {
        Map<String, Long> values = new LinkedHashMap<>();
        values.put(
                "activeProjects",
                count("SELECT COUNT(*) FROM developer_projects WHERE status='ACTIVE'"));
        values.put(
                "productionEligibleProjects",
                count(
                        "SELECT COUNT(*) FROM developer_project_environments",
                        "WHERE environment='PRODUCTION' AND status='ACTIVE' AND production_eligible='YES'"));
        values.put(
                "apiRequests7d",
                count(
                        "SELECT COUNT(*) FROM developer_api_request_log",
                        "WHERE created_at>=TIMESTAMPADD(DAY,-7,CURRENT_TIMESTAMP) AND created_at<=CURRENT_TIMESTAMP"));
        values.put(
                "activeApiMerchants30d",
                count(
                        "SELECT COUNT(DISTINCT merchant_id) FROM developer_api_request_log",
                        "WHERE created_at>=TIMESTAMPADD(DAY,-30,CURRENT_TIMESTAMP) AND created_at<=CURRENT_TIMESTAMP"));
        values.put(
                "successfulApiRequests7d",
                count(
                        "SELECT COUNT(*) FROM developer_api_request_log",
                        "WHERE created_at>=TIMESTAMPADD(DAY,-7,CURRENT_TIMESTAMP) AND created_at<=CURRENT_TIMESTAMP",
                        "AND response_status BETWEEN 200 AND 399"));
        return values;
    }

    private Map<String, Long> adoptionEvidence() {
        Map<String, Long> values = new LinkedHashMap<>();
        values.put(
                "productionMerchants30d",
                count(
                        "SELECT COUNT(DISTINCT merchant_id) FROM merchant_production_usage",
                        "WHERE created_at>=TIMESTAMPADD(DAY,-30,CURRENT_TIMESTAMP) AND created_at<=CURRENT_TIMESTAMP"));
        values.put(
                "productionCommands30d",
                count(
                        "SELECT COUNT(*) FROM merchant_production_usage",
                        "WHERE created_at>=TIMESTAMPADD(DAY,-30,CURRENT_TIMESTAMP) AND created_at<=CURRENT_TIMESTAMP"));
        values.put(
                "liveOnboardingWorkflows",
                count("SELECT COUNT(*) FROM merchant_onboarding_workflows WHERE status='LIVE'"));
        values.put(
                "approvedGoLiveChecklists",
                count(
                        "SELECT COUNT(*) FROM go_live_checklists WHERE status IN ('APPROVED','LIVE')"));
        return values;
    }

    private List<Map<String, Object>> providerDefinitions() {
        return providers.definitions().stream()
                .map(
                        definition ->
                                Map.<String, Object>of(
                                        "providerCode",
                                        definition.providerCode(),
                                        "domain",
                                        definition.domain().name(),
                                        "capabilities",
                                        definition.capabilities(),
                                        "environments",
                                        definition.environments()))
                .toList();
    }

    private List<Map<String, Object>> providerCertification() {
        // Expand wildcard requirements separately for each concrete provider/channel. Evidence for
        // different providers must never combine into apparent coverage for a synthetic '*'
        // provider.
        // These historical reviewed records do not establish current credential/environment
        // readiness.
        return jdbc.queryForList(
                """
                WITH provider_pairs AS (
                    SELECT DISTINCT provider_code, channel_code
                    FROM provider_certification_evidence
                    WHERE provider_code<>'*' AND channel_code<>'*'
                    UNION
                    SELECT provider_code, channel_code
                    FROM provider_certification_requirements
                    WHERE required_flag='YES' AND provider_code<>'*' AND channel_code<>'*'
                ), required_scenarios AS (
                    SELECT DISTINCT p.provider_code, p.channel_code, r.scenario_name
                    FROM provider_pairs p
                    JOIN provider_certification_requirements r
                      ON (r.provider_code='*' OR r.provider_code=p.provider_code)
                     AND (r.channel_code='*' OR r.channel_code=p.channel_code)
                    WHERE r.required_flag='YES'
                )
                SELECT r.provider_code providerCode, r.channel_code channelCode,
                       COUNT(*) requiredScenarios,
                       SUM(CASE WHEN EXISTS (
                           SELECT 1 FROM provider_certification_evidence e
                           WHERE e.provider_code=r.provider_code AND e.channel_code=r.channel_code
                             AND e.scenario_name=r.scenario_name AND e.evidence_status='APPROVED'
                             AND e.approved_at IS NOT NULL AND e.approved_at<=CURRENT_TIMESTAMP
                             AND e.approved_by IS NOT NULL AND TRIM(e.approved_by)<>''
                       ) THEN 1 ELSE 0 END) approvedScenarios
                FROM required_scenarios r
                GROUP BY r.provider_code, r.channel_code
                ORDER BY r.provider_code, r.channel_code
                """);
    }

    private long count(String... sqlParts) {
        Long value = jdbc.queryForObject(String.join(" ", sqlParts), Long.class);
        if (value == null || value < 0)
            throw new IllegalStateException("Durable evidence count is unavailable");
        return value;
    }
}
