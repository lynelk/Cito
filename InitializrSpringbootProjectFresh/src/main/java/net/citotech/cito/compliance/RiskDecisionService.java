package net.citotech.cito.compliance;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import net.citotech.cito.Model.Merchant;
import net.citotech.cito.Model.Transaction;
import net.citotech.cito.api.v2.dto.PaymentRequest;
import net.citotech.cito.gateway.PaymentGatewayException;
import net.citotech.cito.money.MoneyAmount;
import net.citotech.cito.security.CanonicalRequestSigner;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class RiskDecisionService {
    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ComplianceCaseService complianceCaseService;
    private final SanctionsScreeningService sanctionsScreeningService;

    public RiskDecisionService(
            NamedParameterJdbcTemplate jdbcTemplate,
            ComplianceCaseService complianceCaseService,
            SanctionsScreeningService sanctionsScreeningService) {
        this.jdbcTemplate = jdbcTemplate;
        this.complianceCaseService = complianceCaseService;
        this.sanctionsScreeningService = sanctionsScreeningService;
    }

    public RiskDecision authorizePayment(
            Merchant merchant, PaymentRequest request, String direction) {
        if (merchant == null || request == null) {
            throw new PaymentGatewayException(
                    "Merchant and payment request are required for risk authorization");
        }
        BigDecimal amount = MoneyAmount.of(request.getAmount()).asBigDecimal();
        String currency = normalized(request.getCurrency(), "UGX");
        String account = accountValue(request, direction);

        RiskDecision decision = evaluate(merchant, request, direction, amount, currency, account);
        recordDecision(merchant, request.getReference(), direction, amount, currency, decision);
        complianceCaseService.captureRiskDecision(
                merchant.getId(), request.getReference(), direction, amount, currency, decision);
        if (decision.isBlocked()) {
            throw new PaymentGatewayException(
                    "Risk authorization blocked request: " + decision.getSummary());
        }
        return decision;
    }

    private RiskDecision evaluate(
            Merchant merchant,
            PaymentRequest request,
            String direction,
            BigDecimal amount,
            String currency,
            String account) {
        if (!blank(account) && isBlockedAccount(account)) {
            return RiskDecision.block("BLOCKLIST_MATCH", "Account is blocklisted");
        }
        RiskDecision screening =
                sanctionsScreeningService.screenPayment(merchant, request, direction);
        if (!"ALLOW".equals(screening.getDecision())) {
            return screening;
        }
        RiskDecision singleCap = evaluateSingleTransactionCap(merchant, amount, currency);
        if (!"ALLOW".equals(singleCap.getDecision())) {
            return singleCap;
        }
        RiskDecision velocity =
                evaluatePayerVelocity(merchant, request, direction, account, currency);
        if (!"ALLOW".equals(velocity.getDecision())) {
            return velocity;
        }
        RiskDecision dailyCap = evaluateDailyMerchantCap(merchant, amount, currency);
        if (!"ALLOW".equals(dailyCap.getDecision())) {
            return dailyCap;
        }
        return RiskDecision.allow(
                "Risk checks passed for " + request.getReference() + " (" + direction + ")");
    }

    private boolean isBlockedAccount(String account) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("hash", CanonicalRequestSigner.sha256Hex(account.trim()));
        Integer count =
                queryCount(
                        "SELECT COUNT(*) FROM compliance_blocklist "
                                + "WHERE active_flag='YES' AND value_type IN ('ACCOUNT','MSISDN','PAYER','PAYEE') "
                                + "AND value_hash=:hash",
                        p);
        return count != null && count > 0;
    }

    private RiskDecision evaluateSingleTransactionCap(
            Merchant merchant, BigDecimal amount, String currency) {
        Rule rule = findRule("SINGLE_TRANSACTION_CAP", merchant, currency);
        if (rule == null
                || rule.thresholdAmount == null
                || amount.compareTo(rule.thresholdAmount) <= 0) {
            return RiskDecision.allow("single transaction cap passed");
        }
        if ("BLOCK".equalsIgnoreCase(rule.decision)) {
            return RiskDecision.block(
                    "SINGLE_TRANSACTION_CAP", "Amount exceeds single transaction cap");
        }
        return RiskDecision.review("SINGLE_TRANSACTION_CAP", "Amount exceeds review threshold");
    }

    /**
     * Payer-velocity cap (compliance roadmap: per-payer velocity caps). Applies to COLLECT requests
     * only - for PAYOUT, payee velocity is governed by the payout-control limits layer. Counts this
     * exact payer's in-flight/processed payins to this merchant in the last hour and compares
     * against the rule's {@code threshold_count}; the rule's {@code decision} (REVIEW or BLOCK)
     * decides the outcome, same as the amount-based caps.
     */
    private RiskDecision evaluatePayerVelocity(
            Merchant merchant,
            PaymentRequest request,
            String direction,
            String payerAccount,
            String currency) {
        if (!"COLLECT".equalsIgnoreCase(direction) || blank(payerAccount)) {
            return RiskDecision.allow("payer velocity not applicable");
        }
        Rule rule = findRule("PAYER_VELOCITY", merchant, currency);
        if (rule == null || rule.thresholdCount == null || rule.thresholdCount <= 0) {
            return RiskDecision.allow("payer velocity not configured");
        }
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("merchant_id", merchant.getId());
        p.addValue("payer", payerAccount);
        p.addValue("tx_type", Transaction.TX_TYPE_PAYIN);
        p.addValue("hour_ago", Timestamp.valueOf(LocalDateTime.now().minusHours(1)));
        Integer count =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM merchant_production_transactions "
                                + "WHERE merchant_id=:merchant_id AND payer_number=:payer AND tx_type=:tx_type "
                                + "AND status IN ('PENDING','SUBMITTED','SUCCESSFUL') AND created_on >= :hour_ago",
                        p,
                        Integer.class);
        int velocity = count == null ? 0 : count;
        if (velocity < rule.thresholdCount) {
            return RiskDecision.allow(
                    "payer velocity passed (" + velocity + "/" + rule.thresholdCount + ")");
        }
        if ("BLOCK".equalsIgnoreCase(rule.decision)) {
            return RiskDecision.block(
                    "PAYER_VELOCITY",
                    "Payer velocity exceeded: " + velocity + " transactions in the last hour");
        }
        return RiskDecision.review(
                "PAYER_VELOCITY",
                "Payer velocity review: " + velocity + " transactions in the last hour");
    }

    private RiskDecision evaluateDailyMerchantCap(
            Merchant merchant, BigDecimal amount, String currency) {
        Rule rule = findRule("MERCHANT_DAILY_CAP", merchant, currency);
        if (rule == null || rule.thresholdAmount == null) {
            return RiskDecision.allow("daily cap not configured");
        }
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("merchant_id", merchant.getId());
        p.addValue("currency", currency);
        p.addValue("today", LocalDate.now().toString());
        BigDecimal todayTotal =
                queryAmount(
                        "SELECT COALESCE(SUM(amount), 0) FROM risk_decisions "
                                + "WHERE merchant_id=:merchant_id AND currency=:currency "
                                + "AND DATE(created_at)=:today AND decision IN ('ALLOW','REVIEW')",
                        p);
        if (todayTotal.add(amount).compareTo(rule.thresholdAmount) <= 0) {
            return RiskDecision.allow("daily cap passed");
        }
        if ("BLOCK".equalsIgnoreCase(rule.decision)) {
            return RiskDecision.block("MERCHANT_DAILY_CAP", "Daily merchant cap would be exceeded");
        }
        return RiskDecision.review(
                "MERCHANT_DAILY_CAP", "Daily merchant review threshold would be exceeded");
    }

    private Rule findRule(String ruleType, Merchant merchant, String currency) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("rule_type", ruleType);
        p.addValue("merchant_scope", "merchant:" + merchant.getId());
        p.addValue("tier_scope", "tier:" + kycTier(merchant));
        p.addValue("currency", currency);
        List<Rule> rules =
                jdbcTemplate.query(
                        "SELECT decision, threshold_amount, threshold_count FROM risk_rules "
                                + "WHERE enabled='YES' AND rule_type=:rule_type "
                                + "AND (currency IS NULL OR currency=:currency) "
                                + "AND ((scope_type='MERCHANT' AND scope_reference=:merchant_scope) "
                                + "OR (scope_type='TIER' AND scope_reference=:tier_scope) "
                                + "OR (scope_type='GLOBAL' AND scope_reference='*')) "
                                + "ORDER BY CASE scope_type WHEN 'MERCHANT' THEN 0 WHEN 'TIER' THEN 1 ELSE 2 END, id ASC LIMIT 1",
                        p,
                        (rs, rowNum) ->
                                new Rule(
                                        rs.getString("decision"),
                                        rs.getBigDecimal("threshold_amount"),
                                        (Integer) rs.getObject("threshold_count")));
        return rules.isEmpty() ? null : rules.get(0);
    }

    /**
     * Audit I3: resolves the merchant's KYC tier from {@code compliance_profiles} ({@code
     * profile_type='KYC'}), defaulting to {@code STANDARD} when no profile exists. Rules scoped
     * {@code TIER}/{@code tier:<TIER>} then bound that merchant's transaction and daily caps, with
     * precedence MERCHANT > TIER > GLOBAL so a merchant-specific override always beats its tier
     * default.
     */
    private String kycTier(Merchant merchant) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("entity_type", "MERCHANT");
        p.addValue("entity_id", merchant.getId());
        p.addValue("profile_type", "KYC");
        List<String> tiers =
                jdbcTemplate.query(
                        "SELECT COALESCE(tier, 'STANDARD') FROM compliance_profiles "
                                + "WHERE entity_type=:entity_type AND entity_id=:entity_id AND profile_type=:profile_type "
                                + "ORDER BY id DESC LIMIT 1",
                        p,
                        (rs, rowNum) -> rs.getString(1));
        return normalizeTier(tiers.isEmpty() ? "STANDARD" : tiers.get(0));
    }

    private String normalizeTier(String tier) {
        if (tier == null || tier.trim().isEmpty()) {
            return "STANDARD";
        }
        String normalized = tier.trim().toUpperCase(Locale.ROOT);
        if ("STARTER".equals(normalized)
                || "BUSINESS".equals(normalized)
                || "ENHANCED".equals(normalized)) {
            return normalized;
        }
        return "STANDARD";
    }

    private void recordDecision(
            Merchant merchant,
            String reference,
            String direction,
            BigDecimal amount,
            String currency,
            RiskDecision decision) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("merchant_id", merchant.getId());
        p.addValue("reference", reference);
        p.addValue("direction", direction);
        p.addValue("amount", amount);
        p.addValue("currency", currency);
        p.addValue("decision", decision.getDecision());
        p.addValue("reason_code", decision.getReasonCode());
        p.addValue("summary", decision.getSummary());
        jdbcTemplate.update(
                "INSERT INTO risk_decisions "
                        + "(merchant_id, request_reference, direction, amount, currency, decision, reason_code, decision_summary) "
                        + "VALUES (:merchant_id, :reference, :direction, :amount, :currency, :decision, :reason_code, :summary)",
                p);
    }

    private String accountValue(PaymentRequest request, String direction) {
        if ("PAYOUT".equalsIgnoreCase(direction) && request.getPayee() != null) {
            return request.getPayee().getValue();
        }
        if (request.getPayer() != null) {
            return request.getPayer().getValue();
        }
        return "";
    }

    private Integer queryCount(String sql, MapSqlParameterSource p) {
        Integer value = jdbcTemplate.queryForObject(sql, p, Integer.class);
        return value == null ? 0 : value;
    }

    private BigDecimal queryAmount(String sql, MapSqlParameterSource p) {
        BigDecimal value = jdbcTemplate.queryForObject(sql, p, BigDecimal.class);
        return value == null ? BigDecimal.ZERO : value;
    }

    private String normalized(String value, String defaultValue) {
        return blank(value) ? defaultValue : value.trim().toUpperCase();
    }

    private boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private record Rule(String decision, BigDecimal thresholdAmount, Integer thresholdCount) {}
}
