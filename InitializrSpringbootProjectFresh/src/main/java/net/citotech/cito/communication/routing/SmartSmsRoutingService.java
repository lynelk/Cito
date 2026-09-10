package net.citotech.cito.communication.routing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.citotech.cito.Common;
import net.citotech.cito.communication.domain.CommunicationChannel;
import net.citotech.cito.communication.provider.CommunicationProviderAdapter;
import net.citotech.cito.communication.provider.CommunicationProviderHealthService;
import net.citotech.cito.communication.provider.ProviderCapabilities;
import net.citotech.cito.communication.provider.ProviderRegistry;
import net.citotech.cito.communication.sms.SmsEncodingService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Explainable SMS least-cost router. Eligibility is resolved before scoring: a provider must be
 * enabled, registered, able to send, within circuit/health limits and satisfy requested DLR/inbound
 * capabilities. Eligible providers are then ranked by cost, observed reliability and configured
 * routing priority. Unknown prices are never treated as free.
 */
@Service
public class SmartSmsRoutingService {

    private static final String CHANNEL = "SMS";
    private static final BigDecimal DEFAULT_UNKNOWN_RELIABILITY = new BigDecimal("0.75");

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ProviderRegistry providerRegistry;
    private final CommunicationProviderHealthService healthService;
    private final SmsEncodingService encodingService;
    private final ObjectMapper objectMapper;

    public SmartSmsRoutingService(
            NamedParameterJdbcTemplate jdbcTemplate,
            ProviderRegistry providerRegistry,
            CommunicationProviderHealthService healthService,
            SmsEncodingService encodingService,
            ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.providerRegistry = providerRegistry;
        this.healthService = healthService;
        this.encodingService = encodingService;
        this.objectMapper = objectMapper;
    }

    public RouteDecision selectForMessage(long communicationId, long merchantId, String content) {
        MessageRoutingContext context = messageContext(communicationId, merchantId, content);
        return select(context);
    }

    /** Used by the API/UI before enqueue so a merchant can see the likely route and cost. */
    public RouteDecision preview(
            long merchantId,
            String content,
            String countryCode,
            String currencyCode,
            String strategy,
            boolean requireDeliveryReceipts,
            boolean requireInbound) {
        return select(
                new MessageRoutingContext(
                        null,
                        merchantId,
                        content,
                        normalizeCountry(countryCode),
                        normalizeCurrency(currencyCode),
                        normalizeStrategy(strategy),
                        requireDeliveryReceipts,
                        requireInbound,
                        null));
    }

    private RouteDecision select(MessageRoutingContext context) {
        RoutingPolicy policy = resolvePolicy(context);
        String strategy = context.strategy() == null ? policy.strategy() : context.strategy();
        boolean requireDlr = context.requireDeliveryReceipts() || policy.requireDeliveryReceipts();
        boolean requireInbound = context.requireInbound() || policy.requireInbound();
        var analysis = encodingService.analyze(context.content());
        int segments = Math.max(1, analysis.segments());

        String manualProvider = manualPreferredProvider(context.merchantId());
        List<Candidate> candidates = new ArrayList<>();
        for (ProviderCatalogRow provider : enabledProviders()) {
            Candidate candidate =
                    evaluate(
                            provider,
                            context,
                            policy,
                            segments,
                            requireDlr,
                            requireInbound,
                            manualProvider);
            candidates.add(candidate);
        }

        List<Candidate> eligible = candidates.stream().filter(Candidate::eligible).toList();
        if (eligible.isEmpty()) {
            RouteDecision decision =
                    new RouteDecision(
                            "CRD-" + Common.randomUrlSafeToken(16),
                            context.communicationId(),
                            context.merchantId(),
                            strategy,
                            null,
                            context.countryCode(),
                            context.currencyCode(),
                            segments,
                            null,
                            List.copyOf(candidates),
                            "No enabled SMS provider satisfied availability and capability requirements.");
            persistDecision(decision);
            return decision;
        }

        BigDecimal minKnownCost =
                eligible.stream()
                        .map(Candidate::providerCostPerSegment)
                        .filter(java.util.Objects::nonNull)
                        .min(BigDecimal::compareTo)
                        .orElse(null);

        List<Candidate> scored =
                eligible.stream()
                        .map(candidate -> score(candidate, policy, strategy, minKnownCost))
                        .sorted(
                                Comparator.comparing(Candidate::score)
                                        .reversed()
                                        .thenComparing(
                                                candidate ->
                                                        candidate.providerCostPerSegment() == null
                                                                ? new BigDecimal("999999999")
                                                                : candidate
                                                                        .providerCostPerSegment())
                                        .thenComparingInt(Candidate::priority)
                                        .thenComparing(Candidate::providerCode))
                        .toList();

        Candidate winner = scored.get(0);
        BigDecimal expectedCost =
                winner.providerCostPerSegment() == null
                        ? null
                        : winner.providerCostPerSegment().multiply(BigDecimal.valueOf(segments));
        String explanation = explanation(winner, strategy, requireDlr, requireInbound, segments);
        List<Candidate> auditCandidates = new ArrayList<>(candidates);
        for (Candidate candidate : scored) {
            for (int i = 0; i < auditCandidates.size(); i++) {
                if (auditCandidates.get(i).providerCode().equals(candidate.providerCode())) {
                    auditCandidates.set(i, candidate);
                    break;
                }
            }
        }

        RouteDecision decision =
                new RouteDecision(
                        "CRD-" + Common.randomUrlSafeToken(16),
                        context.communicationId(),
                        context.merchantId(),
                        strategy,
                        winner.providerCode(),
                        context.countryCode(),
                        context.currencyCode(),
                        segments,
                        expectedCost,
                        List.copyOf(auditCandidates),
                        explanation);
        persistDecision(decision);
        if (context.communicationId() != null) {
            jdbcTemplate.update(
                    "UPDATE communication_messages SET selected_channel='SMS',"
                            + " selected_provider_code=:provider WHERE id=:id AND merchant_id=:merchant",
                    new MapSqlParameterSource()
                            .addValue("provider", winner.providerCode())
                            .addValue("id", context.communicationId())
                            .addValue("merchant", context.merchantId()));
        }
        return decision;
    }

    private Candidate evaluate(
            ProviderCatalogRow provider,
            MessageRoutingContext context,
            RoutingPolicy policy,
            int segments,
            boolean requireDlr,
            boolean requireInbound,
            String manualProvider) {
        String code = provider.providerCode();
        Optional<CommunicationProviderAdapter> adapterOptional =
                providerRegistry.find(code, CommunicationChannel.SMS);
        if (adapterOptional.isEmpty()) {
            return Candidate.ineligible(code, provider.providerName(), "Adapter is not registered");
        }
        if (manualProvider != null && !manualProvider.equalsIgnoreCase(code)) {
            return Candidate.ineligible(
                    code,
                    provider.providerName(),
                    "Merchant manual routing selects another provider");
        }
        if (healthService.isOpen(code, CHANNEL)) {
            return Candidate.ineligible(code, provider.providerName(), "Provider circuit is open");
        }

        CommunicationProviderAdapter adapter = adapterOptional.get();
        ProviderCapabilities runtime = adapter.capabilities();
        CapabilityOverride override = capabilityOverride(code, context.countryCode());
        boolean canSend = runtime.send();
        boolean supportsDlr =
                override == null ? runtime.deliveryReceipts() : override.deliveryReceipts();
        boolean supportsInbound = override == null ? runtime.inbound() : override.inbound();
        if (!canSend)
            return Candidate.ineligible(code, provider.providerName(), "Provider cannot send SMS");
        if (requireDlr && !supportsDlr) {
            return Candidate.ineligible(
                    code,
                    provider.providerName(),
                    "Delivery receipts are required but unsupported");
        }
        if (requireInbound && !supportsInbound) {
            return Candidate.ineligible(
                    code,
                    provider.providerName(),
                    "Two-way inbound SMS is required but unsupported");
        }

        var health = healthService.find(code, CHANNEL).orElse(null);
        if (health != null && "UNAVAILABLE".equalsIgnoreCase(health.state())) {
            return Candidate.ineligible(
                    code, provider.providerName(), "Provider health is unavailable");
        }

        BigDecimal providerCost =
                effectiveProviderCost(code, context.countryCode(), context.currencyCode());
        if (policy.maxProviderCostPerUnit() != null
                && providerCost != null
                && providerCost.compareTo(policy.maxProviderCostPerUnit()) > 0) {
            return Candidate.ineligible(
                    code, provider.providerName(), "Provider cost exceeds policy ceiling");
        }
        int priority = routingPriority(context.merchantId(), code);
        BigDecimal reliability = observedReliability(code, health == null ? null : health.state());
        BigDecimal totalCost =
                providerCost == null ? null : providerCost.multiply(BigDecimal.valueOf(segments));
        return new Candidate(
                code,
                provider.providerName(),
                true,
                null,
                priority,
                health == null ? "UNKNOWN" : health.state(),
                reliability,
                providerCost,
                totalCost,
                supportsDlr,
                supportsInbound,
                BigDecimal.ZERO);
    }

    private Candidate score(
            Candidate candidate, RoutingPolicy policy, String strategy, BigDecimal minKnownCost) {
        BigDecimal costScore;
        if (candidate.providerCostPerSegment() == null) {
            costScore = minKnownCost == null ? new BigDecimal("0.70") : new BigDecimal("0.25");
        } else if (minKnownCost == null || candidate.providerCostPerSegment().signum() == 0) {
            costScore = BigDecimal.ONE;
        } else {
            costScore =
                    minKnownCost
                            .divide(candidate.providerCostPerSegment(), 6, RoundingMode.HALF_UP)
                            .min(BigDecimal.ONE);
        }
        BigDecimal priorityScore =
                BigDecimal.ONE.divide(
                        BigDecimal.ONE.add(
                                BigDecimal.valueOf(Math.max(0, candidate.priority()) / 100.0)),
                        6,
                        RoundingMode.HALF_UP);

        BigDecimal score;
        switch (strategy) {
            case "LOWEST_COST" ->
                    score =
                            costScore
                                    .multiply(new BigDecimal("0.80"))
                                    .add(candidate.reliability().multiply(new BigDecimal("0.15")))
                                    .add(priorityScore.multiply(new BigDecimal("0.05")));
            case "RELIABILITY_FIRST" ->
                    score =
                            candidate
                                    .reliability()
                                    .multiply(new BigDecimal("0.70"))
                                    .add(costScore.multiply(new BigDecimal("0.20")))
                                    .add(priorityScore.multiply(new BigDecimal("0.10")));
            case "PRIORITY" ->
                    score =
                            priorityScore
                                    .multiply(new BigDecimal("0.80"))
                                    .add(candidate.reliability().multiply(new BigDecimal("0.15")))
                                    .add(costScore.multiply(new BigDecimal("0.05")));
            default ->
                    score =
                            costScore
                                    .multiply(policy.costWeight())
                                    .add(
                                            candidate
                                                    .reliability()
                                                    .multiply(policy.reliabilityWeight()))
                                    .add(priorityScore.multiply(policy.priorityWeight()));
        }
        return candidate.withScore(score.setScale(6, RoundingMode.HALF_UP));
    }

    private MessageRoutingContext messageContext(
            long communicationId, long merchantId, String content) {
        List<Map<String, Object>> rows =
                jdbcTemplate.queryForList(
                        "SELECT metadata_json FROM communication_messages WHERE id=:id AND merchant_id=:merchant LIMIT 1",
                        new MapSqlParameterSource()
                                .addValue("id", communicationId)
                                .addValue("merchant", merchantId));
        JsonNode metadata = null;
        if (!rows.isEmpty() && rows.get(0).get("metadata_json") != null) {
            try {
                metadata = objectMapper.readTree(String.valueOf(rows.get(0).get("metadata_json")));
            } catch (Exception ignored) {
                // Malformed optional metadata must not make a durable message undispatchable.
            }
        }
        return new MessageRoutingContext(
                communicationId,
                merchantId,
                content,
                text(metadata, "countryCode"),
                normalizeCurrency(text(metadata, "currencyCode")),
                normalizeStrategy(text(metadata, "routingStrategy")),
                bool(metadata, "requireDeliveryReceipts"),
                bool(metadata, "requireInbound"),
                text(metadata, "senderId"));
    }

    private RoutingPolicy resolvePolicy(MessageRoutingContext context) {
        List<RoutingPolicy> rows =
                jdbcTemplate.query(
                        "SELECT strategy, cost_weight, reliability_weight, priority_weight, fallback_enabled,"
                                + " require_delivery_receipts, require_inbound, max_provider_cost_per_unit, currency_code"
                                + " FROM communication_smart_routing_policies WHERE channel='SMS' AND enabled_flag='Y'"
                                + " AND (merchant_id=:merchant OR merchant_id IS NULL)"
                                + " ORDER BY (merchant_id=:merchant) DESC, id DESC LIMIT 1",
                        new MapSqlParameterSource("merchant", context.merchantId()),
                        (rs, rowNum) ->
                                new RoutingPolicy(
                                        normalizeStrategy(rs.getString("strategy")),
                                        rs.getBigDecimal("cost_weight"),
                                        rs.getBigDecimal("reliability_weight"),
                                        rs.getBigDecimal("priority_weight"),
                                        "Y".equals(rs.getString("fallback_enabled")),
                                        "Y".equals(rs.getString("require_delivery_receipts")),
                                        "Y".equals(rs.getString("require_inbound")),
                                        rs.getBigDecimal("max_provider_cost_per_unit"),
                                        normalizeCurrency(rs.getString("currency_code"))));
        if (!rows.isEmpty()) return rows.get(0);
        return new RoutingPolicy(
                "BALANCED",
                new BigDecimal("0.55"),
                new BigDecimal("0.35"),
                new BigDecimal("0.10"),
                true,
                false,
                false,
                null,
                "UGX");
    }

    private List<ProviderCatalogRow> enabledProviders() {
        return jdbcTemplate.query(
                "SELECT provider_code, provider_name FROM communication_providers"
                        + " WHERE channel='SMS' AND enabled_flag='YES' ORDER BY provider_code",
                new MapSqlParameterSource(),
                (rs, rowNum) ->
                        new ProviderCatalogRow(
                                rs.getString("provider_code"), rs.getString("provider_name")));
    }

    private CapabilityOverride capabilityOverride(String providerCode, String countryCode) {
        List<CapabilityOverride> rows =
                jdbcTemplate.query(
                        "SELECT supports_delivery_receipts, supports_inbound FROM communication_provider_capabilities"
                                + " WHERE provider_code=:provider AND channel='SMS' AND enabled_flag='Y'"
                                + " AND (country_code=:country OR country_code IS NULL)"
                                + " ORDER BY (country_code=:country) DESC, id DESC LIMIT 1",
                        new MapSqlParameterSource()
                                .addValue("provider", providerCode)
                                .addValue("country", countryCode),
                        (rs, rowNum) ->
                                new CapabilityOverride(
                                        "Y".equals(rs.getString("supports_delivery_receipts")),
                                        "Y".equals(rs.getString("supports_inbound"))));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private BigDecimal effectiveProviderCost(
            String providerCode, String countryCode, String currencyCode) {
        List<BigDecimal> rows =
                jdbcTemplate.query(
                        "SELECT provider_cost_per_unit FROM communication_provider_rates"
                                + " WHERE provider_code=:provider AND channel='SMS' AND enabled_flag='Y'"
                                + " AND currency_code=:currency AND (country_code=:country OR country_code IS NULL)"
                                + " AND valid_from<=NOW() AND (valid_to IS NULL OR valid_to>NOW())"
                                + " ORDER BY (country_code=:country) DESC, valid_from DESC, id DESC LIMIT 1",
                        new MapSqlParameterSource()
                                .addValue("provider", providerCode)
                                .addValue("country", countryCode)
                                .addValue("currency", currencyCode),
                        (rs, rowNum) -> rs.getBigDecimal("provider_cost_per_unit"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private int routingPriority(long merchantId, String providerCode) {
        List<Integer> rows =
                jdbcTemplate.query(
                        "SELECT priority FROM communication_routing_rules WHERE channel='SMS'"
                                + " AND enabled_flag='YES' AND provider_code=:provider"
                                + " AND (merchant_id=:merchant OR merchant_id IS NULL)"
                                + " ORDER BY (merchant_id=:merchant) DESC, priority ASC, id ASC LIMIT 1",
                        new MapSqlParameterSource()
                                .addValue("provider", providerCode)
                                .addValue("merchant", merchantId),
                        (rs, rowNum) -> rs.getInt("priority"));
        return rows.isEmpty() ? 1000 : rows.get(0);
    }

    private String manualPreferredProvider(long merchantId) {
        List<Map<String, Object>> rows =
                jdbcTemplate.queryForList(
                        "SELECT routing_mode, preferred_provider_code FROM communication_merchant_capabilities"
                                + " WHERE merchant_id=:merchant AND channel='SMS' AND status='ACTIVE'"
                                + " ORDER BY id DESC LIMIT 1",
                        new MapSqlParameterSource("merchant", merchantId));
        if (rows.isEmpty()) return null;
        String mode = String.valueOf(rows.get(0).get("routing_mode"));
        Object preferred = rows.get(0).get("preferred_provider_code");
        return "MANUAL".equalsIgnoreCase(mode) && preferred != null
                ? String.valueOf(preferred)
                : null;
    }

    private BigDecimal observedReliability(String providerCode, String healthState) {
        List<Map<String, Object>> rows =
                jdbcTemplate.queryForList(
                        "SELECT COUNT(*) total, SUM(CASE WHEN status='SENT' THEN 1 ELSE 0 END) successes"
                                + " FROM communication_message_deliveries WHERE channel='SMS'"
                                + " AND provider_code=:provider AND created_at>=DATE_SUB(NOW(), INTERVAL 24 HOUR)",
                        new MapSqlParameterSource("provider", providerCode));
        if (!rows.isEmpty()) {
            long total = ((Number) rows.get(0).get("total")).longValue();
            Number successesValue = (Number) rows.get(0).get("successes");
            long successes = successesValue == null ? 0 : successesValue.longValue();
            if (total >= 5) {
                return BigDecimal.valueOf(successes)
                        .divide(BigDecimal.valueOf(total), 6, RoundingMode.HALF_UP);
            }
        }
        if (healthState == null) return DEFAULT_UNKNOWN_RELIABILITY;
        return switch (healthState.toUpperCase()) {
            case "HEALTHY" -> BigDecimal.ONE;
            case "DEGRADED" -> new BigDecimal("0.45");
            case "UNAVAILABLE" -> BigDecimal.ZERO;
            default -> DEFAULT_UNKNOWN_RELIABILITY;
        };
    }

    private void persistDecision(RouteDecision decision) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO communication_routing_decisions"
                            + " (decision_reference, communication_id, merchant_id, channel, strategy,"
                            + " selected_provider_code, country_code, currency_code, sms_segments,"
                            + " expected_provider_cost, candidate_providers_json, explanation) VALUES"
                            + " (:reference,:communication,:merchant,'SMS',:strategy,:provider,:country,:currency,"
                            + " :segments,:cost,:candidates,:explanation)",
                    new MapSqlParameterSource()
                            .addValue("reference", decision.decisionReference())
                            .addValue("communication", decision.communicationId())
                            .addValue("merchant", decision.merchantId())
                            .addValue("strategy", decision.strategy())
                            .addValue("provider", decision.selectedProviderCode())
                            .addValue("country", decision.countryCode())
                            .addValue("currency", decision.currencyCode())
                            .addValue("segments", decision.smsSegments())
                            .addValue("cost", decision.expectedProviderCost())
                            .addValue(
                                    "candidates",
                                    objectMapper.writeValueAsString(decision.candidates()))
                            .addValue("explanation", decision.explanation()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Routing decision could not be audited", e);
        }
    }

    private String explanation(
            Candidate winner,
            String strategy,
            boolean requireDlr,
            boolean requireInbound,
            int segments) {
        String cost =
                winner.providerCostPerSegment() == null
                        ? "cost not configured"
                        : winner.providerCostPerSegment().toPlainString() + " per segment";
        return "Selected "
                + winner.providerCode()
                + " using "
                + strategy
                + "; "
                + cost
                + "; health="
                + winner.healthState()
                + "; reliability="
                + winner.reliability().setScale(3, RoundingMode.HALF_UP)
                + "; priority="
                + winner.priority()
                + "; segments="
                + segments
                + (requireDlr ? "; delivery-receipts required" : "")
                + (requireInbound ? "; two-way inbound required" : "")
                + ".";
    }

    private String text(JsonNode node, String field) {
        if (node == null || node.get(field) == null || node.get(field).isNull()) return null;
        String value = node.get(field).asText();
        return value == null || value.isBlank() ? null : value.trim();
    }

    private boolean bool(JsonNode node, String field) {
        return node != null && node.get(field) != null && node.get(field).asBoolean(false);
    }

    private String normalizeCountry(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase();
    }

    private String normalizeCurrency(String value) {
        return value == null || value.isBlank() ? "UGX" : value.trim().toUpperCase();
    }

    private String normalizeStrategy(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toUpperCase();
        return List.of("BALANCED", "LOWEST_COST", "RELIABILITY_FIRST", "PRIORITY")
                        .contains(normalized)
                ? normalized
                : "BALANCED";
    }

    private record MessageRoutingContext(
            Long communicationId,
            long merchantId,
            String content,
            String countryCode,
            String currencyCode,
            String strategy,
            boolean requireDeliveryReceipts,
            boolean requireInbound,
            String senderId) {}

    private record RoutingPolicy(
            String strategy,
            BigDecimal costWeight,
            BigDecimal reliabilityWeight,
            BigDecimal priorityWeight,
            boolean fallbackEnabled,
            boolean requireDeliveryReceipts,
            boolean requireInbound,
            BigDecimal maxProviderCostPerUnit,
            String currencyCode) {}

    private record ProviderCatalogRow(String providerCode, String providerName) {}

    private record CapabilityOverride(boolean deliveryReceipts, boolean inbound) {}

    public record Candidate(
            String providerCode,
            String providerName,
            boolean eligible,
            String exclusionReason,
            int priority,
            String healthState,
            BigDecimal reliability,
            BigDecimal providerCostPerSegment,
            BigDecimal expectedProviderCost,
            boolean deliveryReceipts,
            boolean inbound,
            BigDecimal score) {
        static Candidate ineligible(String code, String name, String reason) {
            return new Candidate(
                    code,
                    name,
                    false,
                    reason,
                    1000,
                    "UNKNOWN",
                    BigDecimal.ZERO,
                    null,
                    null,
                    false,
                    false,
                    BigDecimal.ZERO);
        }

        Candidate withScore(BigDecimal value) {
            return new Candidate(
                    providerCode,
                    providerName,
                    eligible,
                    exclusionReason,
                    priority,
                    healthState,
                    reliability,
                    providerCostPerSegment,
                    expectedProviderCost,
                    deliveryReceipts,
                    inbound,
                    value);
        }
    }

    public record RouteDecision(
            String decisionReference,
            Long communicationId,
            long merchantId,
            String strategy,
            String selectedProviderCode,
            String countryCode,
            String currencyCode,
            int smsSegments,
            BigDecimal expectedProviderCost,
            List<Candidate> candidates,
            String explanation) {
        public boolean routable() {
            return selectedProviderCode != null;
        }
    }
}
