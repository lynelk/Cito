package net.citotech.cito.analytics;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotently projects durable activation milestones into the existing product analytics event
 * stream. The source operational tables remain authoritative; this projection exists only to make
 * historical funnel analysis consistent and queryable without accepting client-supplied milestone
 * claims.
 */
@Service
public class GrowthMilestoneProjectionService {
    private final NamedParameterJdbcTemplate jdbcTemplate;

    public GrowthMilestoneProjectionService(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public Map<String, Integer> reconcile() {
        Map<String, Integer> projected = new LinkedHashMap<>();
        projected.put(
                "MERCHANT_ACCOUNT_CREATED",
                project(
                        "MERCHANT_ACCOUNT_CREATED",
                        "PRODUCTION",
                        "GROWTH-ACCOUNT-",
                        "SELECT m.id merchant_id, m.created_on occurred_at FROM merchants m "
                                + "WHERE m.created_on IS NOT NULL"));
        projected.put(
                "MERCHANT_USER_ACTIVATED",
                projectLifecycleStep(
                        "MERCHANT_USER_ACTIVATED", "EMAIL_VERIFIED", "PRODUCTION", "GROWTH-USER-"));
        projected.put(
                "SANDBOX_CREDENTIAL_READY",
                projectLifecycleStep(
                        "SANDBOX_CREDENTIAL_READY",
                        "SANDBOX_CONFIGURED",
                        "SANDBOX",
                        "GROWTH-SANDBOX-"));
        projected.put(
                "FIRST_SANDBOX_SUCCESS",
                projectLifecycleStep(
                        "FIRST_SANDBOX_SUCCESS",
                        "INTEGRATION_TESTED",
                        "SANDBOX",
                        "GROWTH-SANDBOX-SUCCESS-"));
        projected.put(
                "PROVIDER_CONFIGURED",
                projectLifecycleStep(
                        "PROVIDER_CONFIGURED",
                        "PROVIDER_CERTIFIED",
                        "PRODUCTION",
                        "GROWTH-PROVIDER-"));
        projected.put(
                "GO_LIVE_APPROVED",
                projectLifecycleStep(
                        "GO_LIVE_APPROVED",
                        "GO_LIVE_APPROVED",
                        "PRODUCTION",
                        "GROWTH-GOLIVE-"));
        projected.put(
                "PRODUCTION_ACTIVATED",
                project(
                        "PRODUCTION_ACTIVATED",
                        "PRODUCTION",
                        "GROWTH-LIVE-",
                        "SELECT merchant_id, activated_at occurred_at "
                                + "FROM merchant_activation_lifecycles WHERE activated_at IS NOT NULL"));
        projected.put("FIRST_PRODUCTION_SUCCESS", projectFirstProductionSuccess());
        return projected;
    }

    private int projectLifecycleStep(
            String eventName, String stepCode, String environment, String referencePrefix) {
        MapSqlParameterSource params =
                new MapSqlParameterSource("stepCode", stepCode)
                        .addValue("eventName", eventName)
                        .addValue("environment", environment)
                        .addValue("referencePrefix", referencePrefix)
                        .addValue("source", "merchant_activation_steps");
        return jdbcTemplate.update(
                """
                INSERT IGNORE INTO product_analytics_events
                    (event_reference,event_name,audience,merchant_id,environment,properties_json,occurred_at)
                SELECT CONCAT(:referencePrefix,l.merchant_id), :eventName, 'ADMIN', l.merchant_id,
                       :environment, JSON_OBJECT('source',:source,'stepCode',s.step_code), s.completed_at
                FROM merchant_activation_steps s
                JOIN merchant_activation_lifecycles l ON l.id=s.lifecycle_id
                WHERE s.step_code=:stepCode
                  AND s.status IN ('COMPLETED','WAIVED','SKIPPED')
                  AND s.completed_at IS NOT NULL
                """,
                params);
    }

    private int project(
            String eventName, String environment, String referencePrefix, String sourceQuery) {
        MapSqlParameterSource params =
                new MapSqlParameterSource("eventName", eventName)
                        .addValue("environment", environment)
                        .addValue("referencePrefix", referencePrefix)
                        .addValue("source", eventName);
        return jdbcTemplate.update(
                "INSERT IGNORE INTO product_analytics_events "
                        + "(event_reference,event_name,audience,merchant_id,environment,properties_json,occurred_at) "
                        + "SELECT CONCAT(:referencePrefix,source.merchant_id), :eventName, 'ADMIN', source.merchant_id, "
                        + ":environment, JSON_OBJECT('source',:source), source.occurred_at FROM ("
                        + sourceQuery
                        + ") source",
                params);
    }

    private int projectFirstProductionSuccess() {
        return jdbcTemplate.update(
                """
                INSERT IGNORE INTO product_analytics_events
                    (event_reference,event_name,audience,merchant_id,environment,properties_json,occurred_at)
                SELECT CONCAT('GROWTH-FIRST-PROD-',merchant_id), 'FIRST_PRODUCTION_SUCCESS', 'ADMIN',
                       merchant_id, 'PRODUCTION', JSON_OBJECT('source','merchant_production_usage'),
                       MIN(created_at)
                FROM merchant_production_usage
                GROUP BY merchant_id
                """,
                new MapSqlParameterSource());
    }
}
