package net.citotech.cito.gateway;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.citotech.cito.api.v2.dto.PaymentRequest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IntelligentPaymentRoutingService {
    private static final Set<String> OPERATIONS = Set.of("COLLECT", "PAYOUT");
    private static final Set<String> STRATEGIES =
            Set.of("BALANCED", "SUCCESS_RATE", "LATENCY", "COST", "PRIORITY");
    private static final Set<String> ENVIRONMENTS = Set.of("SANDBOX", "PRODUCTION");

    private final PaymentChannelRegistry registry;
    private final ChannelCircuitBreaker circuitBreaker;
    private final NamedParameterJdbcTemplate jdbcTemplate;

    public IntelligentPaymentRoutingService(
            PaymentChannelRegistry registry,
            ChannelCircuitBreaker circuitBreaker,
            NamedParameterJdbcTemplate jdbcTemplate) {
        this.registry = registry;
        this.circuitBreaker = circuitBreaker;
        this.jdbcTemplate = jdbcTemplate;
    }

    public RoutingPlan rank(
            PaymentRequest request,
            String merchantNumber,
            String operation,
            String accountIdentifier) {
        return rank(
                request, merchantNumber, operation, accountIdentifier, requestEnvironment(request));
    }

    public RoutingPlan rank(
            PaymentRequest request,
            String merchantNumber,
            String operation,
            String accountIdentifier,
            String environment) {
        if (request == null) {
            throw new PaymentGatewayException("Request body is required");
        }
        String op = normalizeOperation(operation);
        String env = normalizeEnvironment(environment);
        String country = normalizeOptional(request.getCountry());
        String currency = normalizeOptional(request.getCurrency());
        Policy policy = resolvePolicy(merchantNumber, op, env, country, currency);
        Map<String, Rule> rules = rules(policy.id());

        Collection<PaymentChannelAdapter> initial =
                country.isEmpty() || currency.isEmpty()
                        ? registry.getAdapters()
                        : registry.listByCountryAndCurrency(country, currency);
        List<RoutingCandidate> candidates = new ArrayList<>();
        for (PaymentChannelAdapter adapter : initial) {
            if (accountIdentifier != null
                    && !accountIdentifier.isBlank()
                    && !adapter.supportsAccount(accountIdentifier)) {
                continue;
            }
            if (circuitBreaker.stateOf(adapter.channelCode()) == ChannelCircuitBreaker.State.OPEN) {
                continue;
            }
            Rule rule = rules.get(adapter.channelCode().toUpperCase(Locale.ROOT));
            if (rule != null && !rule.active()) {
                continue;
            }
            Health health = health(adapter.channelCode(), country, currency);
            if (rule != null
                    && rule.minSuccessRate() != null
                    && health.successRate().compareTo(rule.minSuccessRate()) < 0) {
                continue;
            }
            if (rule != null
                    && rule.maxLatencyMs() != null
                    && health.averageLatencyMs() > rule.maxLatencyMs()) {
                continue;
            }
            int priority = rule == null ? 100 : rule.priorityRank();
            BigDecimal weight = rule == null ? BigDecimal.ONE : rule.weight();
            BigDecimal costScore = rule == null ? BigDecimal.ZERO : rule.costScore();
            double score =
                    score(
                            policy.strategy(),
                            priority,
                            weight.doubleValue(),
                            costScore.doubleValue(),
                            health.successRate().doubleValue(),
                            health.averageLatencyMs());
            candidates.add(
                    new RoutingCandidate(
                            adapter,
                            score,
                            priority,
                            health.successRate(),
                            health.averageLatencyMs(),
                            costScore));
        }
        candidates.sort(
                Comparator.comparingDouble(RoutingCandidate::score)
                        .reversed()
                        .thenComparing(candidate -> candidate.adapter().channelCode()));
        if (candidates.isEmpty()) {
            throw new PaymentGatewayException(
                    "No healthy eligible payment channel is available in " + env);
        }
        String explanation =
                "Strategy "
                        + policy.strategy()
                        + " in "
                        + env
                        + " ranked "
                        + candidates.size()
                        + " eligible channel(s); highest score selected first";
        return new RoutingPlan(policy, candidates, explanation);
    }

    @Transactional
    public String recordDecision(
            RoutingPlan plan,
            long merchantId,
            String merchantNumber,
            PaymentRequest request,
            String operation,
            String selectedChannel,
            String explanationSuffix) {
        return recordDecision(
                plan,
                merchantId,
                merchantNumber,
                request,
                operation,
                plan.policy().environment(),
                selectedChannel,
                explanationSuffix);
    }

    @Transactional
    public String recordDecision(
            RoutingPlan plan,
            long merchantId,
            String merchantNumber,
            PaymentRequest request,
            String operation,
            String environment,
            String selectedChannel,
            String explanationSuffix) {
        String reference =
                "ROUTE-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        String candidateJson =
                plan.candidates().stream()
                        .map(candidate -> "\"" + escape(candidate.adapter().channelCode()) + "\"")
                        .collect(Collectors.joining(",", "[", "]"));
        String explanation = plan.explanation();
        if (explanationSuffix != null && !explanationSuffix.isBlank()) {
            explanation += "; " + explanationSuffix.trim();
        }
        jdbcTemplate.update(
                "INSERT INTO payment_route_decisions "
                        + "(decision_reference, merchant_number, merchant_id, transaction_reference, operation, environment, country_code, currency_code, "
                        + "policy_id, selected_channel, candidate_channels_json, explanation) "
                        + "VALUES (:decision_reference, :merchant_number, :merchant_id, :transaction_reference, :operation, :environment, :country_code, "
                        + ":currency_code, :policy_id, :selected_channel, :candidate_channels_json, :explanation)",
                new MapSqlParameterSource()
                        .addValue("decision_reference", reference)
                        .addValue("merchant_number", merchantNumber)
                        .addValue("merchant_id", merchantId)
                        .addValue("transaction_reference", request.getReference())
                        .addValue("operation", normalizeOperation(operation))
                        .addValue("environment", normalizeEnvironment(environment))
                        .addValue("country_code", normalizeOptional(request.getCountry()))
                        .addValue("currency_code", normalizeOptional(request.getCurrency()))
                        .addValue("policy_id", plan.policy().id())
                        .addValue("selected_channel", selectedChannel)
                        .addValue("candidate_channels_json", candidateJson)
                        .addValue("explanation", explanation));
        return reference;
    }

    @Transactional
    public void recordOutcome(
            String decisionReference,
            String channelCode,
            String country,
            String currency,
            boolean success,
            long latencyMs) {
        if (decisionReference != null && !decisionReference.isBlank()) {
            jdbcTemplate.update(
                    "UPDATE payment_route_decisions SET outcome=:outcome, latency_ms=:latency_ms, "
                            + "completed_at=CURRENT_TIMESTAMP WHERE decision_reference=:decision_reference",
                    new MapSqlParameterSource()
                            .addValue("outcome", success ? "SUCCESS" : "FAILED")
                            .addValue("latency_ms", Math.max(0, latencyMs))
                            .addValue("decision_reference", decisionReference));
        }
        String cc = normalizeOptional(country);
        String cur = normalizeOptional(currency);
        MapSqlParameterSource p =
                new MapSqlParameterSource()
                        .addValue("channel_code", channelCode)
                        .addValue("country_code", cc)
                        .addValue("currency_code", cur)
                        .addValue("success_delta", success ? 1 : 0)
                        .addValue("failure_delta", success ? 0 : 1)
                        .addValue("latency_ms", Math.max(0, latencyMs))
                        .addValue("status", success ? "HEALTHY" : "DEGRADED");
        jdbcTemplate.update(
                "INSERT INTO provider_health_metrics "
                        + "(channel_code, country_code, currency_code, success_count, failure_count, average_latency_ms, status, last_success_at, last_failure_at) "
                        + "VALUES (:channel_code, :country_code, :currency_code, :success_delta, :failure_delta, :latency_ms, :status, "
                        + "CASE WHEN :success_delta=1 THEN CURRENT_TIMESTAMP ELSE NULL END, "
                        + "CASE WHEN :failure_delta=1 THEN CURRENT_TIMESTAMP ELSE NULL END) "
                        + "ON DUPLICATE KEY UPDATE "
                        + "average_latency_ms=CASE WHEN (success_count+failure_count)=0 THEN VALUES(average_latency_ms) "
                        + "ELSE ROUND(((average_latency_ms*(success_count+failure_count))+VALUES(average_latency_ms))/((success_count+failure_count)+1)) END, "
                        + "success_count=success_count+:success_delta, failure_count=failure_count+:failure_delta, status=:status, "
                        + "last_success_at=CASE WHEN :success_delta=1 THEN CURRENT_TIMESTAMP ELSE last_success_at END, "
                        + "last_failure_at=CASE WHEN :failure_delta=1 THEN CURRENT_TIMESTAMP ELSE last_failure_at END",
                p);
        if (success) {
            circuitBreaker.recordSuccess(channelCode);
        } else {
            circuitBreaker.recordFailure(channelCode);
        }
    }

    /** Called exactly once by the canonical terminal transaction, never for HTTP acceptance. */
    @Transactional
    public void recordMobileMoneyOutcome(
            long merchantId,
            String reference,
            String operation,
            String environment,
            String channel,
            String country,
            String currency,
            boolean success,
            long completionLatencyMs) {
        jdbcTemplate.update(
                "UPDATE payment_route_decisions SET outcome=:outcome, latency_ms=:latency, completed_at=CURRENT_TIMESTAMP "
                        + "WHERE merchant_id=:merchant AND transaction_reference=:reference AND operation=:operation "
                        + "AND environment=:environment AND selected_channel=:channel AND outcome IS NULL",
                new MapSqlParameterSource("merchant", merchantId)
                        .addValue("reference", reference)
                        .addValue("operation", operation)
                        .addValue("environment", environment)
                        .addValue("channel", channel)
                        .addValue("outcome", success ? "SUCCESS" : "FAILED")
                        .addValue("latency", Math.max(0, completionLatencyMs)));
        // Sandbox results must not alter production routing health or the circuit breaker.
        if ("PRODUCTION".equals(environment))
            recordOutcome(null, channel, country, currency, success, completionLatencyMs);
    }

    /** A replay can create a fresh routing decision, but must not count another payment outcome. */
    public void recordMobileMoneyReplay(String decisionReference, String status) {
        if (decisionReference == null || !("SUCCESSFUL".equals(status) || "FAILED".equals(status)))
            return;
        jdbcTemplate.update(
                "UPDATE payment_route_decisions SET outcome=:outcome, completed_at=CURRENT_TIMESTAMP "
                        + "WHERE decision_reference=:reference AND outcome IS NULL",
                new MapSqlParameterSource("reference", decisionReference)
                        .addValue("outcome", "SUCCESSFUL".equals(status) ? "SUCCESS" : "FAILED"));
    }

    public List<Map<String, Object>> decisions(String merchantNumber, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        return jdbcTemplate.queryForList(
                "SELECT decision_reference AS decisionReference, transaction_reference AS transactionReference, "
                        + "operation, environment, country_code AS countryCode, currency_code AS currencyCode, selected_channel AS selectedChannel, "
                        + "candidate_channels_json AS candidateChannels, explanation, outcome, latency_ms AS latencyMs, "
                        + "created_at AS createdAt, completed_at AS completedAt "
                        + "FROM payment_route_decisions WHERE merchant_number=:merchant_number "
                        + "ORDER BY id DESC LIMIT "
                        + safeLimit,
                new MapSqlParameterSource("merchant_number", merchantNumber));
    }

    @Transactional
    public Map<String, Object> savePolicy(
            String merchantNumber,
            String operation,
            String country,
            String currency,
            String strategy,
            boolean fallbackAllowed,
            String actor) {
        return savePolicy(
                merchantNumber,
                operation,
                country,
                currency,
                "SANDBOX",
                strategy,
                fallbackAllowed,
                actor);
    }

    @Transactional
    public Map<String, Object> savePolicy(
            String merchantNumber,
            String operation,
            String country,
            String currency,
            String environment,
            String strategy,
            boolean fallbackAllowed,
            String actor) {
        String op = normalizeOperation(operation);
        String env = normalizeEnvironment(environment);
        String normalizedStrategy = normalizeStrategy(strategy);
        String scope =
                (merchantNumber == null || merchantNumber.isBlank()
                                ? "GLOBAL"
                                : merchantNumber.trim())
                        + "-"
                        + op
                        + "-"
                        + env
                        + "-"
                        + normalizeOptional(country)
                        + "-"
                        + normalizeOptional(currency);
        String policyCode =
                "POL-"
                        + Integer.toUnsignedString(scope.hashCode(), 36).toUpperCase(Locale.ROOT)
                        + "-"
                        + op
                        + "-"
                        + env.substring(0, 3);
        MapSqlParameterSource p =
                new MapSqlParameterSource()
                        .addValue("policy_code", policyCode)
                        .addValue("merchant_number", blankToNull(merchantNumber))
                        .addValue("operation", op)
                        .addValue("environment", env)
                        .addValue("country_code", blankToNull(normalizeOptional(country)))
                        .addValue("currency_code", blankToNull(normalizeOptional(currency)))
                        .addValue("strategy", normalizedStrategy)
                        .addValue("fallback_allowed", fallbackAllowed ? "YES" : "NO")
                        .addValue("created_by", blankToNull(actor));
        jdbcTemplate.update(
                "INSERT INTO payment_routing_policies "
                        + "(policy_code, merchant_number, operation, environment, country_code, currency_code, strategy, fallback_allowed, status, created_by) "
                        + "VALUES (:policy_code, :merchant_number, :operation, :environment, :country_code, :currency_code, :strategy, :fallback_allowed, 'ACTIVE', :created_by) "
                        + "ON DUPLICATE KEY UPDATE environment=VALUES(environment), strategy=VALUES(strategy), "
                        + "fallback_allowed=VALUES(fallback_allowed), status='ACTIVE', updated_at=CURRENT_TIMESTAMP",
                p);
        return policyRow(policyCode);
    }

    @Transactional
    public Map<String, Object> saveRule(
            String policyCode,
            String channelCode,
            int priorityRank,
            BigDecimal weight,
            BigDecimal costScore,
            BigDecimal minSuccessRate,
            Long maxLatencyMs,
            boolean active) {
        Policy policy = policyByCode(policyCode);
        if (registry.findByChannelCode(channelCode).isEmpty()) {
            throw new PaymentGatewayException("Unknown channel: " + channelCode);
        }
        jdbcTemplate.update(
                "INSERT INTO payment_routing_rules "
                        + "(policy_id, channel_code, priority_rank, weight, cost_score, min_success_rate, max_latency_ms, status) "
                        + "VALUES (:policy_id, :channel_code, :priority_rank, :weight, :cost_score, :min_success_rate, :max_latency_ms, :status) "
                        + "ON DUPLICATE KEY UPDATE priority_rank=VALUES(priority_rank), weight=VALUES(weight), cost_score=VALUES(cost_score), "
                        + "min_success_rate=VALUES(min_success_rate), max_latency_ms=VALUES(max_latency_ms), status=VALUES(status), updated_at=CURRENT_TIMESTAMP",
                new MapSqlParameterSource()
                        .addValue("policy_id", policy.id())
                        .addValue("channel_code", channelCode.trim().toUpperCase(Locale.ROOT))
                        .addValue("priority_rank", Math.max(1, priorityRank))
                        .addValue("weight", weight == null ? BigDecimal.ONE : weight)
                        .addValue("cost_score", costScore == null ? BigDecimal.ZERO : costScore)
                        .addValue("min_success_rate", minSuccessRate)
                        .addValue("max_latency_ms", maxLatencyMs)
                        .addValue("status", active ? "ACTIVE" : "DISABLED"));
        return Map.of(
                "policyCode",
                policyCode,
                "channelCode",
                channelCode,
                "environment",
                policy.environment(),
                "status",
                active ? "ACTIVE" : "DISABLED");
    }

    public Map<String, Object> simulate(
            PaymentRequest request,
            String merchantNumber,
            String operation,
            String accountIdentifier) {
        return simulate(
                request, merchantNumber, operation, accountIdentifier, requestEnvironment(request));
    }

    public Map<String, Object> simulate(
            PaymentRequest request,
            String merchantNumber,
            String operation,
            String accountIdentifier,
            String environment) {
        RoutingPlan plan = rank(request, merchantNumber, operation, accountIdentifier, environment);
        List<Map<String, Object>> candidates =
                plan.candidates().stream()
                        .map(
                                candidate -> {
                                    Map<String, Object> row = new LinkedHashMap<>();
                                    row.put("channelCode", candidate.adapter().channelCode());
                                    row.put("score", candidate.score());
                                    row.put("priorityRank", candidate.priorityRank());
                                    row.put("successRate", candidate.successRate());
                                    row.put("averageLatencyMs", candidate.averageLatencyMs());
                                    row.put("costScore", candidate.costScore());
                                    return row;
                                })
                        .toList();
        return Map.of(
                "policyCode",
                plan.policy().policyCode(),
                "strategy",
                plan.policy().strategy(),
                "environment",
                plan.policy().environment(),
                "selectedChannel",
                plan.candidates().get(0).adapter().channelCode(),
                "fallbackAllowed",
                plan.policy().fallbackAllowed(),
                "explanation",
                plan.explanation(),
                "candidates",
                candidates);
    }

    private Policy resolvePolicy(
            String merchantNumber,
            String operation,
            String environment,
            String country,
            String currency) {
        MapSqlParameterSource p =
                new MapSqlParameterSource()
                        .addValue("merchant_number", merchantNumber)
                        .addValue("operation", operation)
                        .addValue("environment", environment)
                        .addValue("country_code", country)
                        .addValue("currency_code", currency);
        List<Map<String, Object>> rows =
                jdbcTemplate.queryForList(
                        "SELECT id, policy_code, strategy, fallback_allowed, environment FROM payment_routing_policies "
                                + "WHERE status='ACTIVE' AND operation=:operation AND environment=:environment "
                                + "AND (merchant_number=:merchant_number OR merchant_number IS NULL) "
                                + "AND (country_code=:country_code OR country_code IS NULL) "
                                + "AND (currency_code=:currency_code OR currency_code IS NULL) "
                                + "ORDER BY (merchant_number IS NOT NULL) DESC, (country_code IS NOT NULL) DESC, "
                                + "(currency_code IS NOT NULL) DESC, id DESC LIMIT 1",
                        p);
        if (!rows.isEmpty()) {
            return policy(rows.get(0));
        }
        String defaultCode =
                "SANDBOX".equals(environment)
                        ? "DEFAULT-" + operation
                        : "DEFAULT-" + operation + "-PRODUCTION";
        return policyByCode(defaultCode);
    }

    private Policy policyByCode(String policyCode) {
        List<Map<String, Object>> rows =
                jdbcTemplate.queryForList(
                        "SELECT id, policy_code, strategy, fallback_allowed, environment FROM payment_routing_policies "
                                + "WHERE policy_code=:policy_code AND status='ACTIVE'",
                        new MapSqlParameterSource("policy_code", policyCode));
        if (rows.isEmpty()) {
            throw new PaymentGatewayException("Routing policy was not found");
        }
        return policy(rows.get(0));
    }

    private Map<String, Object> policyRow(String policyCode) {
        return jdbcTemplate.queryForMap(
                "SELECT policy_code AS policyCode, merchant_number AS merchantNumber, operation, environment, "
                        + "country_code AS countryCode, currency_code AS currencyCode, strategy, "
                        + "fallback_allowed AS fallbackAllowed, status, updated_at AS updatedAt "
                        + "FROM payment_routing_policies WHERE policy_code=:policy_code",
                new MapSqlParameterSource("policy_code", policyCode));
    }

    private Policy policy(Map<String, Object> row) {
        return new Policy(
                ((Number) row.get("id")).longValue(),
                String.valueOf(row.get("policy_code")),
                String.valueOf(row.get("strategy")),
                "YES".equalsIgnoreCase(String.valueOf(row.get("fallback_allowed"))),
                normalizeEnvironment(String.valueOf(row.get("environment"))));
    }

    private Map<String, Rule> rules(long policyId) {
        Map<String, Rule> result = new LinkedHashMap<>();
        List<Map<String, Object>> rows =
                jdbcTemplate.queryForList(
                        "SELECT channel_code, priority_rank, weight, cost_score, min_success_rate, max_latency_ms, status "
                                + "FROM payment_routing_rules WHERE policy_id=:policy_id",
                        new MapSqlParameterSource("policy_id", policyId));
        for (Map<String, Object> row : rows) {
            String channel = String.valueOf(row.get("channel_code")).toUpperCase(Locale.ROOT);
            result.put(
                    channel,
                    new Rule(
                            ((Number) row.get("priority_rank")).intValue(),
                            decimal(row.get("weight"), BigDecimal.ONE),
                            decimal(row.get("cost_score"), BigDecimal.ZERO),
                            nullableDecimal(row.get("min_success_rate")),
                            row.get("max_latency_ms") == null
                                    ? null
                                    : ((Number) row.get("max_latency_ms")).longValue(),
                            "ACTIVE".equalsIgnoreCase(String.valueOf(row.get("status")))));
        }
        return result;
    }

    private Health health(String channelCode, String country, String currency) {
        List<Map<String, Object>> rows =
                jdbcTemplate.queryForList(
                        "SELECT success_count, failure_count, average_latency_ms FROM provider_health_metrics "
                                + "WHERE channel_code=:channel_code AND country_code=:country_code AND currency_code=:currency_code",
                        new MapSqlParameterSource()
                                .addValue("channel_code", channelCode)
                                .addValue("country_code", normalizeOptional(country))
                                .addValue("currency_code", normalizeOptional(currency)));
        if (rows.isEmpty()) {
            return new Health(BigDecimal.ONE, 0L);
        }
        Map<String, Object> row = rows.get(0);
        long successes = ((Number) row.get("success_count")).longValue();
        long failures = ((Number) row.get("failure_count")).longValue();
        long total = successes + failures;
        BigDecimal rate =
                total == 0
                        ? BigDecimal.ONE
                        : BigDecimal.valueOf(successes)
                                .divide(BigDecimal.valueOf(total), 5, RoundingMode.HALF_UP);
        return new Health(rate, ((Number) row.get("average_latency_ms")).longValue());
    }

    private double score(
            String strategy,
            int priority,
            double weight,
            double costScore,
            double successRate,
            long latencyMs) {
        double priorityScore = Math.max(0, 1000 - priority * 5.0);
        double successScore = successRate * 1000.0;
        double latencyScore = Math.max(0, 1000 - Math.min(latencyMs, 10000) / 10.0);
        double costComponent = Math.max(0, 1000 - costScore * 100.0);
        double base =
                switch (strategy) {
                    case "SUCCESS_RATE" ->
                            successScore * 0.75 + priorityScore * 0.15 + latencyScore * 0.10;
                    case "LATENCY" ->
                            latencyScore * 0.70 + successScore * 0.20 + priorityScore * 0.10;
                    case "COST" ->
                            costComponent * 0.60 + successScore * 0.25 + priorityScore * 0.15;
                    case "PRIORITY" ->
                            priorityScore * 0.80 + successScore * 0.15 + latencyScore * 0.05;
                    default ->
                            priorityScore * 0.30
                                    + successScore * 0.35
                                    + latencyScore * 0.25
                                    + costComponent * 0.10;
                };
        return base * Math.max(0.01, weight);
    }

    private String requestEnvironment(PaymentRequest request) {
        if (request == null || request.getMetadata() == null) {
            return "SANDBOX";
        }
        return normalizeEnvironment(request.getMetadata().getOrDefault("environment", "SANDBOX"));
    }

    private String normalizeOperation(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!OPERATIONS.contains(normalized)) {
            throw new PaymentGatewayException("operation must be COLLECT or PAYOUT");
        }
        return normalized;
    }

    private String normalizeStrategy(String value) {
        String normalized = value == null ? "BALANCED" : value.trim().toUpperCase(Locale.ROOT);
        if (!STRATEGIES.contains(normalized)) {
            throw new PaymentGatewayException("Unsupported routing strategy");
        }
        return normalized;
    }

    private String normalizeEnvironment(String value) {
        String normalized =
                value == null || value.isBlank()
                        ? "SANDBOX"
                        : value.trim().toUpperCase(Locale.ROOT);
        if (!ENVIRONMENTS.contains(normalized)) {
            throw new PaymentGatewayException("environment must be SANDBOX or PRODUCTION");
        }
        return normalized;
    }

    private String normalizeOptional(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String blankToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private BigDecimal decimal(Object value, BigDecimal fallback) {
        return value == null ? fallback : new BigDecimal(String.valueOf(value));
    }

    private BigDecimal nullableDecimal(Object value) {
        return value == null ? null : new BigDecimal(String.valueOf(value));
    }

    private String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public record Policy(
            long id,
            String policyCode,
            String strategy,
            boolean fallbackAllowed,
            String environment) {}

    public record RoutingCandidate(
            PaymentChannelAdapter adapter,
            double score,
            int priorityRank,
            BigDecimal successRate,
            long averageLatencyMs,
            BigDecimal costScore) {}

    public record RoutingPlan(
            Policy policy, List<RoutingCandidate> candidates, String explanation) {}

    private record Rule(
            int priorityRank,
            BigDecimal weight,
            BigDecimal costScore,
            BigDecimal minSuccessRate,
            Long maxLatencyMs,
            boolean active) {}

    private record Health(BigDecimal successRate, long averageLatencyMs) {}
}
