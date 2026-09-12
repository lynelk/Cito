package net.citotech.cito.experience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.citotech.cito.platform.kernel.PlatformTenantContext;
import net.citotech.cito.platform.kernel.PlatformTenantContextResolver;
import net.citotech.cito.platform.provider.PlatformProviderRegistry;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.TransactionTemplate;

/** Runs only against the explicitly enabled disposable, fully migrated CI MySQL database. */
class PlatformEvidenceMysqlTest {
    @Test
    void canonicalSchemaProducesScopedTimeBoundedEvidenceWithoutCombiningProviders() {
        Assumptions.assumeTrue("true".equals(System.getenv("CITO_EVIDENCE_MYSQL_TESTS")));
        String url = System.getenv("DB_URL");
        assertThat(System.getenv("CI")).isEqualTo("true");
        assertThat(url).startsWith("jdbc:mysql://127.0.0.1:3306/cpayadmin");
        DriverManagerDataSource source =
                new DriverManagerDataSource(
                        url, System.getenv("DB_USERNAME"), System.getenv("DB_PASSWORD"));
        JdbcTemplate jdbc = new JdbcTemplate(source);
        PlatformTenantContextResolver contexts = mock(PlatformTenantContextResolver.class);
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(contexts.require(request, null))
                .thenReturn(
                        new PlatformTenantContext(
                                null,
                                null,
                                "ci-reviewer",
                                "ADMIN",
                                Set.of("ADMIN"),
                                "PRODUCTION",
                                "CITO_PORTAL",
                                "ci-evidence"));
        PlatformEvidenceController controller =
                new PlatformEvidenceController(
                        contexts, new PlatformProviderRegistry(List.of()), jdbc);
        String prefix = "PR203-" + UUID.randomUUID().toString().substring(0, 12);
        TransactionTemplate transaction =
                new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.executeWithoutResult(
                status -> {
                    try {
                        Map<String, Object> before = controller.scorecard(request, null);
                        List<Long> merchants = new ArrayList<>();
                        for (int index = 0; index < 4; index++) {
                            String account = prefix + "-" + index;
                            jdbc.update(
                                    "INSERT INTO merchants(name,status,account_number,created_by,account_type,short_name,allowed_apis) VALUES(?,'ACTIVE',?,'ci-evidence','business','QA','')",
                                    account,
                                    account);
                            merchants.add(
                                    jdbc.queryForObject(
                                            "SELECT id FROM merchants WHERE account_number=?",
                                            Long.class,
                                            account));
                        }
                        jdbc.update(
                                "INSERT INTO cito_commercial_packages(package_code,package_name,service_codes_json,status) VALUES(?,?,'[]','ACTIVE')",
                                prefix,
                                prefix);
                        Long packageId =
                                jdbc.queryForObject(
                                        "SELECT id FROM cito_commercial_packages WHERE package_code=?",
                                        Long.class,
                                        prefix);
                        jdbc.update(
                                "INSERT INTO merchant_commercial_package_assignments(merchant_id,package_id,environment,status) VALUES(?,?,'PRODUCTION','ACTIVE')",
                                merchants.get(0),
                                packageId);
                        jdbc.update(
                                "INSERT INTO merchant_commercial_package_assignments(merchant_id,package_id,environment,status,effective_from) VALUES(?,?,'PRODUCTION','ACTIVE',TIMESTAMPADD(DAY,1,CURRENT_TIMESTAMP))",
                                merchants.get(1),
                                packageId);
                        jdbc.update(
                                "INSERT INTO merchant_commercial_package_assignments(merchant_id,package_id,environment,status,effective_to) VALUES(?,?,'PRODUCTION','ACTIVE',TIMESTAMPADD(DAY,-1,CURRENT_TIMESTAMP))",
                                merchants.get(2),
                                packageId);
                        jdbc.update(
                                "INSERT INTO merchant_commercial_package_assignments(merchant_id,package_id,environment,status) VALUES(?,?,'SANDBOX','ACTIVE')",
                                merchants.get(3),
                                packageId);
                        jdbc.update(
                                "INSERT INTO merchant_production_usage(merchant_id,operation,request_reference,created_at) VALUES(?,'COLLECTION',?,TIMESTAMPADD(HOUR,-1,CURRENT_TIMESTAMP))",
                                merchants.get(0),
                                prefix + "-recent");
                        jdbc.update(
                                "INSERT INTO merchant_production_usage(merchant_id,operation,request_reference,created_at) VALUES(?,'COLLECTION',?,TIMESTAMPADD(DAY,1,CURRENT_TIMESTAMP))",
                                merchants.get(1),
                                prefix + "-future");
                        jdbc.update(
                                "INSERT INTO merchant_production_usage(merchant_id,operation,request_reference,created_at) VALUES(?,'COLLECTION',?,TIMESTAMPADD(DAY,-40,CURRENT_TIMESTAMP))",
                                merchants.get(2),
                                prefix + "-old");
                        for (int response : new int[] {201, 302, 503}) {
                            jdbc.update(
                                    "INSERT INTO developer_api_request_log(merchant_id,request_id,http_method,route_template,environment,response_status,created_at) VALUES(?,?,'GET','/ci-evidence','SANDBOX',?,TIMESTAMPADD(HOUR,-1,CURRENT_TIMESTAMP))",
                                    merchants.get(0),
                                    prefix + response,
                                    response);
                        }
                        jdbc.update(
                                "INSERT INTO developer_api_request_log(merchant_id,request_id,http_method,route_template,environment,response_status,created_at) VALUES(?,?,'GET','/ci-evidence','PRODUCTION',200,TIMESTAMPADD(DAY,1,CURRENT_TIMESTAMP))",
                                merchants.get(1),
                                prefix + "future");

                        String first = prefix + "-A";
                        String second = prefix + "-B";
                        String channel = "TEST_CHANNEL";
                        Timestamp reviewedAt = Timestamp.from(Instant.now().minusSeconds(60));
                        evidence(
                                jdbc,
                                first,
                                channel,
                                "collect_success",
                                "APPROVED",
                                "ci-reviewer",
                                reviewedAt);
                        evidence(
                                jdbc,
                                first,
                                channel,
                                "collect_success",
                                "APPROVED",
                                "ci-reviewer",
                                reviewedAt);
                        evidence(
                                jdbc,
                                second,
                                channel,
                                "payout_success",
                                "APPROVED",
                                "ci-reviewer",
                                reviewedAt);
                        evidence(
                                jdbc,
                                first,
                                channel,
                                "status_check",
                                "PASSED",
                                "ci-reviewer",
                                reviewedAt);
                        evidence(
                                jdbc,
                                first,
                                channel,
                                "callback_success",
                                "APPROVED",
                                null,
                                reviewedAt);
                        evidence(
                                jdbc,
                                first,
                                channel,
                                "timeout",
                                "APPROVED",
                                "ci-reviewer",
                                Timestamp.from(Instant.now().plusSeconds(86400)));
                        jdbc.update(
                                "INSERT INTO provider_certification_requirements(provider_code,channel_code,scenario_name,required_flag) VALUES(?,?,'collect_success','YES')",
                                first,
                                channel);

                        Map<String, Object> after = controller.scorecard(request, null);
                        assertThat(
                                        metric(after, "commercial", "activePackageAssignments")
                                                - metric(
                                                        before,
                                                        "commercial",
                                                        "activePackageAssignments"))
                                .isEqualTo(1);
                        assertThat(
                                        metric(after, "adoption", "productionCommands30d")
                                                - metric(
                                                        before,
                                                        "adoption",
                                                        "productionCommands30d"))
                                .isEqualTo(1);
                        assertThat(
                                        metric(after, "adoption", "productionMerchants30d")
                                                - metric(
                                                        before,
                                                        "adoption",
                                                        "productionMerchants30d"))
                                .isEqualTo(1);
                        assertThat(
                                        metric(after, "developer", "apiRequests7d")
                                                - metric(before, "developer", "apiRequests7d"))
                                .isEqualTo(3);
                        assertThat(
                                        metric(after, "developer", "successfulApiRequests7d")
                                                - metric(
                                                        before,
                                                        "developer",
                                                        "successfulApiRequests7d"))
                                .isEqualTo(2);
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> groups =
                                (List<Map<String, Object>>) after.get("providerCertification");
                        Map<String, Object> a =
                                groups.stream()
                                        .filter(row -> first.equals(row.get("providerCode")))
                                        .findFirst()
                                        .orElseThrow();
                        Map<String, Object> b =
                                groups.stream()
                                        .filter(row -> second.equals(row.get("providerCode")))
                                        .findFirst()
                                        .orElseThrow();
                        assertThat(((Number) a.get("approvedScenarios")).longValue()).isEqualTo(1);
                        assertThat(((Number) b.get("approvedScenarios")).longValue()).isEqualTo(1);
                        assertThat(((Number) a.get("requiredScenarios")).longValue())
                                .isEqualTo(((Number) b.get("requiredScenarios")).longValue());
                        assertThat(groups)
                                .noneMatch(
                                        row ->
                                                "*".equals(row.get("providerCode"))
                                                        || "*".equals(row.get("channelCode")));
                        assertThat(after.get("targetsReportedAsActuals")).isEqualTo(false);
                    } finally {
                        status.setRollbackOnly();
                    }
                });
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM merchants WHERE account_number LIKE ?",
                                Long.class,
                                prefix + "%"))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM provider_certification_evidence WHERE provider_code LIKE ?",
                                Long.class,
                                prefix + "%"))
                .isZero();
    }

    private void evidence(
            JdbcTemplate jdbc,
            String provider,
            String channel,
            String scenario,
            String state,
            String reviewer,
            Timestamp reviewedAt) {
        jdbc.update(
                "INSERT INTO provider_certification_evidence(provider_code,channel_code,scenario_name,evidence_type,evidence_status,approved_by,approved_at) VALUES(?,?,?,'CI_FIXTURE',?,?,?)",
                provider,
                channel,
                scenario,
                state,
                reviewer,
                reviewedAt);
    }

    private long metric(Map<String, Object> scorecard, String group, String key) {
        return ((Number) ((Map<?, ?>) scorecard.get(group)).get(key)).longValue();
    }
}
