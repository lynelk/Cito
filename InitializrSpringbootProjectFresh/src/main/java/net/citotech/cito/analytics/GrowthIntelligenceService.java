package net.citotech.cito.analytics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Executive and merchant-level growth reporting built exclusively from existing durable Cito
 * operational sources. The service aggregates facts; it does not create alternative lifecycle,
 * transaction, entitlement, or billing state.
 */
@Service
public class GrowthIntelligenceService {
    private final NamedParameterJdbcTemplate jdbcTemplate;

    public GrowthIntelligenceService(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Map<String, Object> definitions() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("metrics", GrowthMetricCatalog.definitions());
        response.put("productFamilies", GrowthMetricCatalog.serviceFamilies());
        response.put(
                "retentionDefinition",
                "Retained at or beyond day N means a merchant activated at least N days ago has at least one durable production-usage record on or after activation + N days.");
        response.put(
                "activeMerchantDefinition",
                "An active merchant has at least one durable merchant_production_usage record inside the stated window.");
        return response;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> scorecard(int requestedWindowDays) {
        int windowDays = safeWindow(requestedWindowDays);
        Instant now = Instant.now();
        Timestamp from = Timestamp.from(now.minus(windowDays, ChronoUnit.DAYS));
        Timestamp wauFrom = Timestamp.from(now.minus(7, ChronoUnit.DAYS));
        Timestamp mauFrom = Timestamp.from(now.minus(30, ChronoUnit.DAYS));
        Timestamp dormantFrom = Timestamp.from(now.minus(90, ChronoUnit.DAYS));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("generatedAt", now.toString());
        response.put("windowDays", windowDays);
        response.put("definitionsVersion", "2026-09-10");
        response.put("activation", activation(from));
        response.put("retention", retention(now));
        response.put("usage", usage(from, wauFrom, mauFrom, dormantFrom));
        response.put("payments", paymentPerformance(from));
        response.put("serviceAttachment", serviceAttachment(mauFrom));
        response.put("economics", economics(from));
        response.put("providerPerformance", providerPerformance(from));
        response.put("lifecycleBlockers", lifecycleBlockers());
        response.put("funnel", funnel());
        response.put("funnelTrend", funnelTrend(from));
        return response;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> merchant(long merchantId, int requestedWindowDays) {
        requireMerchant(merchantId);
        int windowDays = safeWindow(requestedWindowDays);
        Timestamp from = Timestamp.from(Instant.now().minus(windowDays, ChronoUnit.DAYS));

        MapSqlParameterSource merchant = new MapSqlParameterSource("merchantId", merchantId);
        MapSqlParameterSource scoped =
                new MapSqlParameterSource("merchantId", merchantId).addValue("from", from);

        List<Map<String, Object>> lifecycle =
                jdbcTemplate.queryForList(
                        "SELECT status,current_step_code AS currentStepCode,next_action AS nextAction,"
                                + "blocked_reason AS blockedReason,created_at AS createdAt,activated_at AS activatedAt "
                                + "FROM merchant_activation_lifecycles WHERE merchant_id=:merchantId",
                        merchant);
        if (lifecycle.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Merchant activation lifecycle was not found");
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("merchantId", merchantId);
        response.put("windowDays", windowDays);
        response.put("lifecycle", lifecycle.get(0));
        response.put(
                "steps",
                jdbcTemplate.queryForList(
                        "SELECT s.step_code AS stepCode,s.step_name AS stepName,s.status,"
                                + "s.responsible_party AS responsibleParty,s.blocker,s.completed_at AS completedAt "
                                + "FROM merchant_activation_steps s JOIN merchant_activation_lifecycles l ON l.id=s.lifecycle_id "
                                + "WHERE l.merchant_id=:merchantId ORDER BY s.sort_order",
                        merchant));
        response.put(
                "productionUsage",
                jdbcTemplate.queryForList(
                        "SELECT operation,COUNT(*) eventCount,MIN(created_at) firstSeenAt,MAX(created_at) lastSeenAt "
                                + "FROM merchant_production_usage WHERE merchant_id=:merchantId AND created_at>=:from "
                                + "GROUP BY operation ORDER BY operation",
                        scoped));
        response.put("payments", paymentPerformance(scoped));
        response.put("serviceAttachment", serviceAttachmentForMerchant(merchantId));
        response.put("economics", economicsForMerchant(merchantId, from));
        response.put("providerPerformance", providerPerformanceForMerchant(merchantId, from));
        response.put(
                "milestones",
                jdbcTemplate.queryForList(
                        "SELECT event_name AS eventName,environment,occurred_at AS occurredAt "
                                + "FROM product_analytics_events WHERE merchant_id=:merchantId "
                                + "AND event_name IN ('MERCHANT_ACCOUNT_CREATED','MERCHANT_USER_ACTIVATED','SANDBOX_CREDENTIAL_READY',"
                                + "'FIRST_SANDBOX_SUCCESS','PROVIDER_CONFIGURED','GO_LIVE_APPROVED','PRODUCTION_ACTIVATED','FIRST_PRODUCTION_SUCCESS') "
                                + "ORDER BY occurred_at,event_name",
                        merchant));
        return response;
    }

    private Map<String, Object> activation(Timestamp from) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(
                "signedMerchants",
                count("SELECT COUNT(*) FROM merchants WHERE created_on>=:from", params("from", from)));
        result.put(
                "liveMerchants",
                count(
                        "SELECT COUNT(*) FROM merchant_activation_lifecycles WHERE status='LIVE'",
                        new MapSqlParameterSource()));
        result.put(
                "activatedInWindow",
                count(
                        "SELECT COUNT(*) FROM merchant_activation_lifecycles WHERE activated_at>=:from",
                        params("from", from)));

        List<Long> activationSeconds =
                jdbcTemplate.query(
                        "SELECT TIMESTAMPDIFF(SECOND,created_at,activated_at) seconds_to_activation "
                                + "FROM merchant_activation_lifecycles WHERE activated_at IS NOT NULL "
                                + "AND activated_at>=created_at",
                        new MapSqlParameterSource(),
                        (rs, rowNum) -> rs.getLong("seconds_to_activation"));
        result.put("medianActivationHours", medianHours(activationSeconds));
        return result;
    }

    private Map<String, Object> retention(Instant now) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("day7", retentionAtOrBeyond(now, 7));
        result.put("day30", retentionAtOrBeyond(now, 30));
        result.put("day90", retentionAtOrBeyond(now, 90));
        return result;
    }

    private Map<String, Object> retentionAtOrBeyond(Instant now, int days) {
        Timestamp eligibleBefore = Timestamp.from(now.minus(days, ChronoUnit.DAYS));
        MapSqlParameterSource p =
                new MapSqlParameterSource("eligibleBefore", eligibleBefore).addValue("days", days);
        Map<String, Object> row =
                one(
                        "SELECT COUNT(*) eligibleMerchants,"
                                + "SUM(CASE WHEN EXISTS (SELECT 1 FROM merchant_production_usage u "
                                + "WHERE u.merchant_id=l.merchant_id "
                                + "AND u.created_at>=DATE_ADD(l.activated_at, INTERVAL :days DAY)) THEN 1 ELSE 0 END) retainedMerchants "
                                + "FROM merchant_activation_lifecycles l WHERE l.activated_at IS NOT NULL "
                                + "AND l.activated_at<=:eligibleBefore",
                        p);
        long eligible = number(row, "eligibleMerchants");
        long retained = number(row, "retainedMerchants");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("eligibleMerchants", eligible);
        result.put("retainedMerchants", retained);
        result.put("ratePercent", percent(retained, eligible));
        return result;
    }

    private Map<String, Object> usage(
            Timestamp from, Timestamp wauFrom, Timestamp mauFrom, Timestamp dormantFrom) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("activeInWindow", activeMerchants(from));
        result.put("weeklyActiveMerchants", activeMerchants(wauFrom));
        result.put("monthlyActiveMerchants", activeMerchants(mauFrom));
        result.put(
                "dormantMerchants",
                count(
                        "SELECT COUNT(*) FROM (SELECT merchant_id,MAX(created_at) last_seen "
                                + "FROM merchant_production_usage GROUP BY merchant_id "
                                + "HAVING last_seen<:mauFrom AND last_seen>=:dormantFrom) dormant",
                        new MapSqlParameterSource("mauFrom", mauFrom)
                                .addValue("dormantFrom", dormantFrom)));
        result.put(
                "byOperation",
                jdbcTemplate.queryForList(
                        "SELECT operation,COUNT(*) eventCount,COUNT(DISTINCT merchant_id) merchantCount "
                                + "FROM merchant_production_usage WHERE created_at>=:from "
                                + "GROUP BY operation ORDER BY eventCount DESC",
                        params("from", from)));
        return result;
    }

    private List<Map<String, Object>> paymentPerformance(Timestamp from) {
        return paymentPerformance(params("from", from));
    }

    private List<Map<String, Object>> paymentPerformance(MapSqlParameterSource parameters) {
        String merchantClause =
                parameters.hasValue("merchantId") ? " AND merchant_id=:merchantId" : "";
        return jdbcTemplate.queryForList(
                "SELECT COALESCE(NULLIF(currency,''),'UNKNOWN') currency,COUNT(*) transactionCount,"
                        + "SUM(CASE WHEN status IN ('SUCCESS','SUCCESSFUL','COMPLETED') THEN 1 ELSE 0 END) successfulCount,"
                        + "SUM(CASE WHEN status IN ('FAILED','FAILURE','REJECTED','CANCELLED') THEN 1 ELSE 0 END) failedCount,"
                        + "COALESCE(SUM(CASE WHEN status IN ('SUCCESS','SUCCESSFUL','COMPLETED') "
                        + "THEN CAST(original_amount AS DECIMAL(20,4)) ELSE CAST(0 AS DECIMAL(20,4)) END),CAST(0 AS DECIMAL(20,4))) successfulVolume "
                        + "FROM merchant_transactions_log WHERE created_on>=:from"
                        + merchantClause
                        + " GROUP BY COALESCE(NULLIF(currency,''),'UNKNOWN') ORDER BY transactionCount DESC",
                parameters);
    }

    private Map<String, Object> serviceAttachment(Timestamp mauFrom) {
        List<Map<String, Object>> rows =
                jdbcTemplate.queryForList(
                        "SELECT o.merchant_id AS merchantId,e.service_code AS serviceCode "
                                + "FROM cito_organizations o JOIN cito_service_entitlements e ON e.organization_id=o.id "
                                + "WHERE o.merchant_id IS NOT NULL AND e.environment='PRODUCTION' AND e.status='ACTIVE' "
                                + "AND (e.starts_at IS NULL OR e.starts_at<=CURRENT_TIMESTAMP) "
                                + "AND (e.ends_at IS NULL OR e.ends_at>CURRENT_TIMESTAMP) "
                                + "AND o.merchant_id IN (SELECT DISTINCT merchant_id FROM merchant_production_usage WHERE created_at>=:mauFrom)",
                        params("mauFrom", mauFrom));
        long active = activeMerchants(mauFrom);
        return attachmentSummary(rows, active);
    }

    private Map<String, Object> serviceAttachmentForMerchant(long merchantId) {
        List<Map<String, Object>> rows =
                jdbcTemplate.queryForList(
                        "SELECT e.service_code AS serviceCode FROM cito_organizations o "
                                + "JOIN cito_service_entitlements e ON e.organization_id=o.id "
                                + "WHERE o.merchant_id=:merchantId AND e.environment='PRODUCTION' AND e.status='ACTIVE' "
                                + "AND (e.starts_at IS NULL OR e.starts_at<=CURRENT_TIMESTAMP) "
                                + "AND (e.ends_at IS NULL OR e.ends_at>CURRENT_TIMESTAMP) ORDER BY e.service_code",
                        params("merchantId", merchantId));
        Map<String, Object> result = new LinkedHashMap<>();
        Set<String> services = new LinkedHashSet<>();
        Set<String> families = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            String service = String.valueOf(row.get("serviceCode"));
            services.add(service);
            String family = GrowthMetricCatalog.familyForService(service);
            if (family != null) {
                families.add(family);
            }
        }
        result.put("activeProductionServices", services);
        result.put("activeProductFamilies", families);
        result.put("familyCount", families.size());
        return result;
    }

    private Map<String, Object> attachmentSummary(
            List<Map<String, Object>> rows, long activeMerchants) {
        Map<Long, Set<String>> merchantFamilies = new LinkedHashMap<>();
        Map<String, Set<Long>> familyMerchants = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            long merchantId = ((Number) row.get("merchantId")).longValue();
            String family =
                    GrowthMetricCatalog.familyForService(String.valueOf(row.get("serviceCode")));
            if (family == null) {
                continue;
            }
            merchantFamilies
                    .computeIfAbsent(merchantId, ignored -> new LinkedHashSet<>())
                    .add(family);
            familyMerchants
                    .computeIfAbsent(family, ignored -> new LinkedHashSet<>())
                    .add(merchantId);
        }
        long multiProduct =
                merchantFamilies.values().stream().filter(families -> families.size() >= 2).count();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("monthlyActiveMerchants", activeMerchants);
        result.put("multiProductMerchants", multiProduct);
        result.put("attachRatePercent", percent(multiProduct, activeMerchants));
        Map<String, Integer> familyCounts = new LinkedHashMap<>();
        familyMerchants.forEach(
                (family, merchants) -> familyCounts.put(family, merchants.size()));
        result.put("merchantCountByFamily", familyCounts);
        return result;
    }

    private List<Map<String, Object>> economics(Timestamp from) {
        return economicsQuery(
                "WHERE r.computed_at>=:from", new MapSqlParameterSource("from", from));
    }

    private List<Map<String, Object>> economicsForMerchant(long merchantId, Timestamp from) {
        return economicsQuery(
                "JOIN billing_tenants t ON t.id=r.billing_tenant_id "
                        + "WHERE t.merchant_id=:merchantId AND r.computed_at>=:from",
                new MapSqlParameterSource("merchantId", merchantId).addValue("from", from));
    }

    private List<Map<String, Object>> economicsQuery(
            String scopeSql, MapSqlParameterSource parameters) {
        List<Map<String, Object>> rows =
                jdbcTemplate.queryForList(
                        "SELECT r.currency,"
                                + "COALESCE(SUM(CASE WHEN r.charge_type='CUSTOMER_CHARGE' THEN r.rated_amount ELSE 0 END),0) billedRevenue,"
                                + "COALESCE(SUM(CASE WHEN r.charge_type='PROVIDER_COST' THEN r.rated_amount ELSE 0 END),0) providerCost "
                                + "FROM billing_rated_charges r "
                                + scopeSql
                                + " GROUP BY r.currency ORDER BY r.currency",
                        parameters);
        for (Map<String, Object> row : rows) {
            BigDecimal revenue = decimal(row, "billedRevenue");
            BigDecimal cost = decimal(row, "providerCost");
            row.put("grossMargin", revenue.subtract(cost));
            row.put(
                    "grossMarginPercent",
                    revenue.signum() == 0
                            ? null
                            : revenue.subtract(cost)
                                    .multiply(BigDecimal.valueOf(100))
                                    .divide(revenue, 2, RoundingMode.HALF_UP));
        }
        return rows;
    }

    private List<Map<String, Object>> providerPerformance(Timestamp from) {
        return jdbcTemplate.queryForList(
                "SELECT selected_channel AS channelCode,operation,COUNT(*) routedCount,"
                        + "SUM(CASE WHEN outcome='SUCCESS' THEN 1 ELSE 0 END) successfulCount,"
                        + "SUM(CASE WHEN outcome='FAILED' THEN 1 ELSE 0 END) failedCount,"
                        + "ROUND(COALESCE(SUM(CASE WHEN completed_at IS NOT NULL THEN COALESCE(latency_ms,0) ELSE 0 END) "
                        + "/ NULLIF(SUM(CASE WHEN completed_at IS NOT NULL THEN 1 ELSE 0 END),0),0)) averageLatencyMs "
                        + "FROM payment_route_decisions WHERE created_at>=:from AND environment='PRODUCTION' "
                        + "GROUP BY selected_channel,operation ORDER BY routedCount DESC",
                params("from", from));
    }

    private List<Map<String, Object>> providerPerformanceForMerchant(
            long merchantId, Timestamp from) {
        return jdbcTemplate.queryForList(
                "SELECT selected_channel AS channelCode,operation,COUNT(*) routedCount,"
                        + "SUM(CASE WHEN outcome='SUCCESS' THEN 1 ELSE 0 END) successfulCount,"
                        + "SUM(CASE WHEN outcome='FAILED' THEN 1 ELSE 0 END) failedCount,"
                        + "ROUND(COALESCE(SUM(CASE WHEN completed_at IS NOT NULL THEN COALESCE(latency_ms,0) ELSE 0 END) "
                        + "/ NULLIF(SUM(CASE WHEN completed_at IS NOT NULL THEN 1 ELSE 0 END),0),0)) averageLatencyMs "
                        + "FROM payment_route_decisions WHERE merchant_id=:merchantId AND created_at>=:from "
                        + "AND environment='PRODUCTION' GROUP BY selected_channel,operation ORDER BY routedCount DESC",
                new MapSqlParameterSource("merchantId", merchantId).addValue("from", from));
    }

    private List<Map<String, Object>> lifecycleBlockers() {
        return jdbcTemplate.queryForList(
                "SELECT current_step_code AS currentStepCode,status,COUNT(*) merchantCount "
                        + "FROM merchant_activation_lifecycles WHERE status<>'LIVE' "
                        + "GROUP BY current_step_code,status ORDER BY merchantCount DESC,current_step_code",
                new MapSqlParameterSource());
    }

    private List<Map<String, Object>> funnel() {
        return jdbcTemplate.queryForList(
                "SELECT event_name AS eventName,COUNT(DISTINCT merchant_id) merchantCount,"
                        + "MIN(occurred_at) firstObservedAt,MAX(occurred_at) lastObservedAt "
                        + "FROM product_analytics_events WHERE event_name IN "
                        + "('MERCHANT_ACCOUNT_CREATED','MERCHANT_USER_ACTIVATED','SANDBOX_CREDENTIAL_READY',"
                        + "'FIRST_SANDBOX_SUCCESS','PROVIDER_CONFIGURED','GO_LIVE_APPROVED','PRODUCTION_ACTIVATED','FIRST_PRODUCTION_SUCCESS') "
                        + "GROUP BY event_name ORDER BY MIN(occurred_at),event_name",
                new MapSqlParameterSource());
    }

    private List<Map<String, Object>> funnelTrend(Timestamp from) {
        return jdbcTemplate.queryForList(
                "SELECT DATE(occurred_at) eventDate,event_name AS eventName,COUNT(DISTINCT merchant_id) merchantCount "
                        + "FROM product_analytics_events WHERE occurred_at>=:from AND event_name IN "
                        + "('MERCHANT_ACCOUNT_CREATED','MERCHANT_USER_ACTIVATED','SANDBOX_CREDENTIAL_READY',"
                        + "'FIRST_SANDBOX_SUCCESS','PROVIDER_CONFIGURED','GO_LIVE_APPROVED','PRODUCTION_ACTIVATED','FIRST_PRODUCTION_SUCCESS') "
                        + "GROUP BY DATE(occurred_at),event_name ORDER BY eventDate,eventName",
                params("from", from));
    }

    private long activeMerchants(Timestamp from) {
        return count(
                "SELECT COUNT(DISTINCT merchant_id) FROM merchant_production_usage WHERE created_at>=:from",
                params("from", from));
    }

    private long count(String sql, MapSqlParameterSource parameters) {
        Long value = jdbcTemplate.queryForObject(sql, parameters, Long.class);
        return value == null ? 0L : value;
    }

    private Map<String, Object> one(String sql, MapSqlParameterSource parameters) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, parameters);
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    private int safeWindow(int requested) {
        if (requested <= 0) {
            return 30;
        }
        return Math.min(requested, 366);
    }

    private void requireMerchant(long merchantId) {
        if (merchantId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "merchantId must be positive");
        }
        if (count(
                        "SELECT COUNT(*) FROM merchants WHERE id=:merchantId",
                        params("merchantId", merchantId))
                == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Merchant was not found");
        }
    }

    private Object medianHours(List<Long> seconds) {
        if (seconds == null || seconds.isEmpty()) {
            return null;
        }
        List<Long> sorted = new ArrayList<>(seconds);
        sorted.sort(Comparator.naturalOrder());
        int middle = sorted.size() / 2;
        double medianSeconds =
                sorted.size() % 2 == 0
                        ? (sorted.get(middle - 1) + sorted.get(middle)) / 2.0
                        : sorted.get(middle);
        return BigDecimal.valueOf(medianSeconds / Duration.ofHours(1).getSeconds())
                .setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal percent(long numerator, long denominator) {
        if (denominator <= 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        return BigDecimal.valueOf(numerator)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP);
    }

    private long number(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private BigDecimal decimal(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(value));
    }

    private MapSqlParameterSource params(String name, Object value) {
        return new MapSqlParameterSource(name, value);
    }
}
