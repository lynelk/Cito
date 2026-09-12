package net.citotech.cito.experience;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administrator-only evidence scorecard. Every number is computed from durable
 * records already owned by Cito. Missing evidence stays missing; targets are
 * never converted into achieved metrics.
 */
@RestController
@RequestMapping("/api/v2/admin/platform-evidence")
public class PlatformEvidenceController {
    private final ExperienceAccessContext accessContext;
    private final JdbcTemplate jdbc;

    public PlatformEvidenceController(ExperienceAccessContext accessContext, JdbcTemplate jdbc) {
        this.accessContext = accessContext;
        this.jdbc = jdbc;
    }

    @GetMapping("/scorecard")
    public Map<String, Object> scorecard(HttpServletRequest request, Authentication authentication) {
        var access = accessContext.require(request, authentication);
        accessContext.requireAdmin(access);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("evidenceBasis", "DURABLE_RECORDS_ONLY");
        out.put("targetsReportedAsActuals", false);
        out.put("commercial", Map.of(
                "founding20Candidates", count("SELECT COUNT(*) FROM founding20_merchants WHERE programme_status='CANDIDATE'"),
                "founding20Active", count("SELECT COUNT(*) FROM founding20_merchants WHERE programme_status IN ('ACTIVE','ONBOARDING','LIVE')"),
                "founding20Live", count("SELECT COUNT(*) FROM founding20_merchants WHERE programme_status='LIVE'"),
                "activePackageAssignments", count("SELECT COUNT(*) FROM merchant_commercial_package_assignments WHERE status='ACTIVE' AND environment='PRODUCTION' AND (effective_to IS NULL OR effective_to>CURRENT_TIMESTAMP)"),
                "embeddedProgrammesLive", count("SELECT COUNT(*) FROM embedded_partner_programmes WHERE programme_status='LIVE'")));
        out.put("developer", Map.of(
                "activeProjects", count("SELECT COUNT(*) FROM developer_projects WHERE status='ACTIVE'"),
                "productionEligibleProjects", count("SELECT COUNT(*) FROM developer_project_environments WHERE environment='PRODUCTION' AND status='ACTIVE' AND production_eligible='YES'"),
                "apiRequests7d", count("SELECT COUNT(*) FROM developer_api_request_log WHERE created_at>=TIMESTAMPADD(DAY,-7,CURRENT_TIMESTAMP)"),
                "activeApiMerchants30d", count("SELECT COUNT(DISTINCT merchant_id) FROM developer_api_request_log WHERE created_at>=TIMESTAMPADD(DAY,-30,CURRENT_TIMESTAMP)"),
                "successfulApiRequests7d", count("SELECT COUNT(*) FROM developer_api_request_log WHERE created_at>=TIMESTAMPADD(DAY,-7,CURRENT_TIMESTAMP) AND response_status BETWEEN 200 AND 399")));
        out.put("adoption", Map.of(
                "productionMerchants30d", count("SELECT COUNT(DISTINCT merchant_id) FROM merchant_production_usage WHERE usage_date>=DATE_SUB(CURRENT_DATE,INTERVAL 30 DAY)"),
                "productionCommands30d", count("SELECT COUNT(*) FROM merchant_production_usage WHERE usage_date>=DATE_SUB(CURRENT_DATE,INTERVAL 30 DAY)"),
                "liveOnboardingWorkflows", count("SELECT COUNT(*) FROM merchant_onboarding_workflows WHERE status='LIVE'"),
                "approvedGoLiveChecklists", count("SELECT COUNT(*) FROM go_live_checklists WHERE status IN ('APPROVED','LIVE')")));
        out.put("providerCertification", providerCertification());
        return out;
    }

    private List<Map<String, Object>> providerCertification() {
        return jdbc.queryForList("""
                SELECT r.provider_code providerCode,r.channel_code channelCode,
                       COUNT(*) requiredScenarios,
                       SUM(CASE WHEN EXISTS (
                           SELECT 1 FROM provider_certification_evidence e
                            WHERE (e.provider_code=r.provider_code OR r.provider_code='*')
                              AND (e.channel_code=r.channel_code OR r.channel_code='*')
                              AND e.scenario_name=r.scenario_name
                              AND e.approved_at IS NOT NULL
                              AND e.evidence_status IN ('APPROVED','PASSED','VERIFIED')
                       ) THEN 1 ELSE 0 END) approvedScenarios
                FROM provider_certification_requirements r
                WHERE r.required_flag='YES'
                GROUP BY r.provider_code,r.channel_code
                ORDER BY r.provider_code,r.channel_code
                """);
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0L : value;
    }
}
