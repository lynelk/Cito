package net.citotech.cito.payout;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.citotech.cito.Model.Merchant;
import net.citotech.cito.Model.Transaction;
import net.citotech.cito.api.v2.dto.PaymentRequest;
import net.citotech.cito.gateway.PaymentGatewayException;
import net.citotech.cito.money.MoneyAmount;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Payout risk controls (audit item: payouts had risk authorization, step-up MFA, and a
 * failure-compensation saga, but no configurable limits and no maker-checker approval queue).
 *
 * <p>{@link #evaluate} is invoked by the v2 payout controller BEFORE execution. When a control row
 * exists and is enabled, a breach of the per-transaction, daily, monthly, or beneficiary velocity
 * limit - or a first payout to a beneficiary on a merchant/channel opted into approval - returns
 * {@code APPROVAL_REQUIRED} and writes a {@code payout_approval_queue} row carrying the original
 * request payload. An admin (checker) then approves; the approval controller re-invokes the normal
 * {@code PaymentOrchestrationService.payout} path with the stored request, so money only ever moves
 * through the existing, tested executor (reservation + risk + provider call).
 *
 * <p>No control row (or disabled control) preserves historical behavior: the payout executes
 * immediately. Risk BLOCK decisions continue to be enforced by {@code RiskDecisionService} inside
 * the orchestrator; this gate adds the limits layer in front, not a second risk evaluation.
 */
@Service
public class PayoutControlService {
    private static final int MONEY_SCALE = 4;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public PayoutControlService(
            NamedParameterJdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public record PayoutEvaluation(
            String decision, String reasonCode, String summary, Long queueId) {
        public static PayoutEvaluation execute() {
            return new PayoutEvaluation(
                    "EXECUTE", "ALLOW", "Payout within configured limits", null);
        }

        public static PayoutEvaluation approvalRequired(
                String reasonCode, String summary, long queueId) {
            return new PayoutEvaluation("APPROVAL_REQUIRED", reasonCode, summary, queueId);
        }

        public static PayoutEvaluation blocked(String reasonCode, String summary) {
            return new PayoutEvaluation("BLOCK", reasonCode, summary, null);
        }

        public boolean isApprovalRequired() {
            return "APPROVAL_REQUIRED".equals(decision);
        }
    }

    /**
     * Evaluates the payout against configured controls and, when approval is required, persists the
     * queue row with the original request payload so a checker's approve can re-execute it.
     */
    @Transactional
    public PayoutEvaluation evaluate(
            PaymentRequest request, Merchant merchant, String requestedBy) {
        if (request == null || merchant == null) {
            throw new PaymentGatewayException(
                    "Payment request and merchant are required for payout control evaluation");
        }
        List<Map<String, Object>> previous =
                jdbcTemplate.queryForList(
                        "SELECT * FROM payout_approval_queue WHERE merchant_id=:merchant AND payout_reference=:reference FOR UPDATE",
                        new MapSqlParameterSource("merchant", merchant.getId())
                                .addValue("reference", request.getReference()));
        if (!previous.isEmpty()) {
            Map<String, Object> queued = previous.get(0);
            try {
                PaymentRequest original =
                        objectMapper.readValue(
                                String.valueOf(queued.get("payload_json")), PaymentRequest.class);
                String environment =
                        request.getMetadata().getOrDefault("environment", "PRODUCTION");
                String originalEnvironment =
                        original.getMetadata().getOrDefault("environment", "PRODUCTION");
                if (!net.citotech.cito.gateway.MobileMoneyExecutionService.fingerprint(
                                request,
                                "PAYOUT",
                                environment,
                                String.valueOf(request.getChannel()))
                        .equals(
                                net.citotech.cito.gateway.MobileMoneyExecutionService.fingerprint(
                                        original,
                                        "PAYOUT",
                                        originalEnvironment,
                                        String.valueOf(original.getChannel()))))
                    throw new PaymentGatewayException(
                            "Payout approval reference conflicts with its approved request");
                if ("APPROVED".equals(queued.get("queue_status")))
                    return PayoutEvaluation.execute();
                if ("PENDING_APPROVAL".equals(queued.get("queue_status")))
                    return PayoutEvaluation.approvalRequired(
                            String.valueOf(queued.get("trigger_reason")),
                            "Payout awaits independent approval",
                            ((Number) queued.get("id")).longValue());
                return PayoutEvaluation.blocked(
                        "APPROVAL_CLOSED", "Payout approval was rejected or cancelled");
            } catch (PaymentGatewayException e) {
                throw e;
            } catch (Exception e) {
                throw new PaymentGatewayException("Payout approval evidence is unavailable");
            }
        }
        String channelCode = normalized(request.getChannel(), "UNKNOWN");
        String currency = normalized(request.getCurrency(), "UGX");
        String country = normalized(request.getCountry(), "UG");
        BigDecimal amount = MoneyAmount.of(request.getAmount()).asBigDecimal();
        String beneficiary = request.getPayee() == null ? null : request.getPayee().getValue();
        boolean firstBeneficiary =
                !blank(beneficiary) && beneficiaryPayoutCount(merchant.getId(), beneficiary) == 0;

        Control control = findControl(merchant.getId(), channelCode, currency, country);
        if (control == null || !"YES".equalsIgnoreCase(control.enabledFlag)) {
            return PayoutEvaluation.execute();
        }

        if (control.perTransactionLimit != null
                && amount.compareTo(control.perTransactionLimit) > 0) {
            return queueApproval(
                    request,
                    merchant,
                    channelCode,
                    currency,
                    country,
                    beneficiary,
                    amount,
                    "PER_TRANSACTION_LIMIT",
                    requestedBy);
        }
        LocalDateTime dayStart = LocalDate.now().atStartOfDay();
        BigDecimal dailyTotal = payoutTotal(merchant.getId(), currency, dayStart, null);
        if (control.dailyAmountLimit != null
                && dailyTotal.add(amount).compareTo(control.dailyAmountLimit) > 0) {
            return queueApproval(
                    request,
                    merchant,
                    channelCode,
                    currency,
                    country,
                    beneficiary,
                    amount,
                    "DAILY_LIMIT",
                    requestedBy);
        }
        LocalDateTime monthStart = LocalDate.now().withDayOfMonth(1).atStartOfDay();
        BigDecimal monthlyTotal = payoutTotal(merchant.getId(), currency, monthStart, null);
        if (control.monthlyAmountLimit != null
                && monthlyTotal.add(amount).compareTo(control.monthlyAmountLimit) > 0) {
            return queueApproval(
                    request,
                    merchant,
                    channelCode,
                    currency,
                    country,
                    beneficiary,
                    amount,
                    "MONTHLY_LIMIT",
                    requestedBy);
        }
        if (control.beneficiaryVelocityLimit != null
                && control.beneficiaryVelocityLimit > 0
                && !blank(beneficiary)
                && beneficiaryPayoutCount(merchant.getId(), beneficiary)
                        >= control.beneficiaryVelocityLimit) {
            return queueApproval(
                    request,
                    merchant,
                    channelCode,
                    currency,
                    country,
                    beneficiary,
                    amount,
                    "BENEFICIARY_VELOCITY_LIMIT",
                    requestedBy);
        }
        if ("YES".equalsIgnoreCase(control.approvalRequiredFlag)
                && !blank(beneficiary)
                && firstBeneficiary) {
            return queueApproval(
                    request,
                    merchant,
                    channelCode,
                    currency,
                    country,
                    beneficiary,
                    amount,
                    "FIRST_BENEFICIARY",
                    requestedBy);
        }
        return PayoutEvaluation.execute();
    }

    private PayoutEvaluation queueApproval(
            PaymentRequest request,
            Merchant merchant,
            String channelCode,
            String currency,
            String country,
            String beneficiary,
            BigDecimal amount,
            String triggerReason,
            String requestedBy) {
        long queueId =
                insertQueue(
                        request,
                        merchant,
                        channelCode,
                        currency,
                        country,
                        beneficiary,
                        amount,
                        triggerReason,
                        requestedBy);
        return PayoutEvaluation.approvalRequired(
                triggerReason, "Payout requires maker-checker approval", queueId);
    }

    private long insertQueue(
            PaymentRequest request,
            Merchant merchant,
            String channelCode,
            String currency,
            String country,
            String beneficiary,
            BigDecimal amount,
            String triggerReason,
            String requestedBy) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(request);
        } catch (Exception e) {
            throw new PaymentGatewayException(
                    "Unable to serialize payout request for approval queue");
        }
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("reference", request.getReference());
        p.addValue("merchant_id", merchant.getId());
        p.addValue("merchant_number", merchant.getAccount_number());
        p.addValue("payload_json", payload);
        p.addValue("amount", amount.setScale(MONEY_SCALE, java.math.RoundingMode.HALF_UP));
        p.addValue("currency", currency);
        p.addValue("channel_code", channelCode);
        p.addValue("country", country);
        p.addValue("beneficiary_reference", beneficiary);
        p.addValue("trigger_reason", triggerReason);
        p.addValue("requested_by", blank(requestedBy) ? "system" : requestedBy.trim());
        jdbcTemplate.update(
                "INSERT INTO payout_approval_queue "
                        + "(payout_reference, merchant_id, merchant_number, payload_json, amount, currency, channel_code, country, beneficiary_reference, trigger_reason, queue_status, requested_by) "
                        + "VALUES (:reference, :merchant_id, :merchant_number, :payload_json, :amount, :currency, :channel_code, :country, :beneficiary_reference, :trigger_reason, 'PENDING_APPROVAL', :requested_by) "
                        + "ON DUPLICATE KEY UPDATE queue_status=CASE WHEN queue_status='APPROVED' THEN queue_status ELSE 'PENDING_APPROVAL' END",
                p);
        Long id =
                jdbcTemplate.queryForObject(
                        "SELECT id FROM payout_approval_queue WHERE merchant_id=:merchant_id AND payout_reference=:reference",
                        new MapSqlParameterSource("reference", request.getReference())
                                .addValue("merchant_id", merchant.getId()),
                        Long.class);
        return id == null ? 0L : id;
    }

    public List<Map<String, Object>> listPending(int limit) {
        return jdbcTemplate.queryForList(
                "SELECT id, payout_reference, merchant_id, merchant_number, amount, currency, channel_code, country, "
                        + "beneficiary_reference, trigger_reason, queue_status, requested_by, requested_at "
                        + "FROM payout_approval_queue WHERE queue_status='PENDING_APPROVAL' "
                        + "ORDER BY requested_at ASC LIMIT :limit",
                new MapSqlParameterSource("limit", Math.max(1, Math.min(limit, 200))));
    }

    public List<Map<String, Object>> listRejectedReasons() {
        return jdbcTemplate.queryForList(
                "SELECT id, payout_reference, trigger_reason, rejection_reason, approved_by, approved_at "
                        + "FROM payout_approval_queue WHERE queue_status IN ('REJECTED','CANCELLED') ORDER BY requested_at DESC LIMIT 200",
                new MapSqlParameterSource());
    }

    /** Queued payout row fetched by id, null when missing. */
    public QueuedPayout findById(long queueId) {
        List<QueuedPayout> rows =
                jdbcTemplate.query(
                        "SELECT id, payout_reference, merchant_id, merchant_number, payload_json, amount, currency, queue_status, "
                                + "trigger_reason, requested_by, approved_by "
                                + "FROM payout_approval_queue WHERE id=:id",
                        new MapSqlParameterSource("id", queueId),
                        (rs, rowNum) ->
                                new QueuedPayout(
                                        rs.getLong("id"),
                                        rs.getString("payout_reference"),
                                        rs.getLong("merchant_id"),
                                        rs.getString("merchant_number"),
                                        rs.getString("payload_json"),
                                        rs.getBigDecimal("amount"),
                                        rs.getString("currency"),
                                        rs.getString("queue_status"),
                                        rs.getString("trigger_reason"),
                                        rs.getString("requested_by"),
                                        rs.getString("approved_by")));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * Checker approval: flips a PENDING_APPROVAL row to APPROVED only when the approver is not the
     * requester. Returns the queue row so the controller can re-execute the stored payout request.
     */
    @Transactional
    public QueuedPayout approve(long queueId, String approvedBy) {
        QueuedPayout queued = findById(queueId);
        if (queued == null) {
            throw new PaymentGatewayException("Payout approval record not found: " + queueId);
        }
        if (!"PENDING_APPROVAL".equals(queued.queueStatus())) {
            throw new PaymentGatewayException(
                    "Payout is not awaiting approval (status=" + queued.queueStatus() + ")");
        }
        if (queued.requestedBy() != null && queued.requestedBy().equals(approvedBy)) {
            throw new PaymentGatewayException(
                    "Payout approval requires a different actor than the requester");
        }
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("id", queueId);
        p.addValue("approved_by", approvedBy);
        int updated =
                jdbcTemplate.update(
                        "UPDATE payout_approval_queue SET queue_status='APPROVED', approved_by=:approved_by, approved_at=CURRENT_TIMESTAMP "
                                + "WHERE id=:id AND queue_status='PENDING_APPROVAL'",
                        p);
        if (updated == 0) {
            throw new PaymentGatewayException("Payout approval could not be applied");
        }
        return findById(queueId);
    }

    @Transactional
    public int reject(long queueId, String rejectedBy, String reason) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("id", queueId);
        p.addValue("rejected_by", rejectedBy);
        p.addValue("reason", blank(reason) ? "Rejected by checker" : reason.trim());
        int updated =
                jdbcTemplate.update(
                        "UPDATE payout_approval_queue SET queue_status='REJECTED', approved_by=:rejected_by, approved_at=CURRENT_TIMESTAMP, rejection_reason=:reason "
                                + "WHERE id=:id AND queue_status='PENDING_APPROVAL' AND requested_by IS NOT NULL AND requested_by<>:rejected_by",
                        p);
        if (updated > 0) releaseUnsubmittedBatch(queueId);
        return updated;
    }

    @Transactional
    public int cancel(long queueId, String cancelledBy) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("id", queueId);
        p.addValue("cancelled_by", cancelledBy);
        int updated =
                jdbcTemplate.update(
                        "UPDATE payout_approval_queue SET queue_status='CANCELLED', approved_by=:cancelled_by, approved_at=CURRENT_TIMESTAMP "
                                + "WHERE id=:id AND queue_status='PENDING_APPROVAL'",
                        p);
        if (updated > 0) releaseUnsubmittedBatch(queueId);
        return updated;
    }

    private void releaseUnsubmittedBatch(long queueId) {
        List<Map<String, Object>> rows =
                jdbcTemplate.queryForList(
                        "SELECT merchant_id,payout_reference FROM payout_approval_queue WHERE id=:id",
                        new MapSqlParameterSource("id", queueId));
        if (rows.isEmpty()) return;
        Map<String, Object> row = rows.get(0);
        String reference = String.valueOf(row.get("payout_reference"));
        if (!reference.matches("batch-payout:[0-9]+:[0-9]+")) return;
        String[] parts = reference.split(":");
        MapSqlParameterSource p =
                new MapSqlParameterSource("merchant", row.get("merchant_id"))
                        .addValue("reference", reference)
                        .addValue("batch", Long.parseLong(parts[1]))
                        .addValue("beneficiary", Long.parseLong(parts[2]));
        Integer submitted =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM mobile_money_executions WHERE merchant_id=:merchant AND merchant_reference=:reference",
                        p,
                        Integer.class);
        if (submitted == null || submitted > 0) return;
        jdbcTemplate.update(
                "UPDATE ledger_reservations SET reservation_status='RELEASED' WHERE merchant_id=:merchant AND source_reference=:reference AND reservation_status='RESERVED'",
                p);
        jdbcTemplate.update(
                "UPDATE beneficiaries SET status='FAILED',reason='Payout approval rejected or cancelled' WHERE id=:beneficiary AND batch_id=:batch AND EXISTS (SELECT 1 FROM merchant_batch_transactions_log b WHERE b.id=:batch AND b.merchant_id=:merchant)",
                p);
    }

    private Control findControl(
            long merchantId, String channelCode, String currency, String country) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("merchant_id", merchantId);
        p.addValue("channel_code", channelCode);
        p.addValue("currency", currency);
        p.addValue("country", country);
        List<Control> controls =
                jdbcTemplate.query(
                        "SELECT daily_amount_limit, monthly_amount_limit, per_transaction_limit, beneficiary_velocity_limit, "
                                + "approval_required_flag, enabled_flag "
                                + "FROM payout_controls WHERE merchant_id=:merchant_id AND channel_code=:channel_code "
                                + "AND currency=:currency AND country=:country LIMIT 1",
                        p,
                        (rs, rowNum) ->
                                new Control(
                                        rs.getBigDecimal("daily_amount_limit"),
                                        rs.getBigDecimal("monthly_amount_limit"),
                                        rs.getBigDecimal("per_transaction_limit"),
                                        (Integer) rs.getObject("beneficiary_velocity_limit"),
                                        rs.getString("approval_required_flag"),
                                        rs.getString("enabled_flag")));
        return controls.isEmpty() ? null : controls.get(0);
    }

    /**
     * Sums the merchant's outbound payouts in the window across all gateways. The v2 compat path
     * can resolve the legacy gateway id via MSISDN routing, so the stored {@code gateway_id} is not
     * a reliable channel-code match - counting every payout in the window is the conservative
     * (fail-safe) reading for a limit control.
     */
    private BigDecimal payoutTotal(
            long merchantId, String currency, LocalDateTime from, LocalDateTime to) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("merchant_id", merchantId);
        p.addValue("currency", currency);
        p.addValue("from", from);
        p.addValue("to", to);
        p.addValue("tx_type", Transaction.TX_TYPE_PAYOUT);
        BigDecimal value =
                jdbcTemplate.queryForObject(
                        "SELECT COALESCE(SUM(original_amount), 0) FROM merchant_production_transactions "
                                + "WHERE merchant_id=:merchant_id AND currency=:currency "
                                + "AND tx_type=:tx_type AND status IN ('PENDING','SUBMITTED','SUCCESSFUL') "
                                + "AND created_on >= :from AND (:to IS NULL OR created_on < :to)",
                        p,
                        BigDecimal.class);
        return value == null ? BigDecimal.ZERO : value;
    }

    /**
     * Counts prior payouts to this beneficiary. The legacy money path stores the payout payee in
     * {@code payer_number} (the v2 orchestrator calls {@code tx.setPayer_number(payee)}), so this
     * must filter on that column - filtering on {@code payee_number} would silently bypass the
     * velocity and first-beneficiary controls.
     */
    private int beneficiaryPayoutCount(long merchantId, String beneficiary) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("merchant_id", merchantId);
        p.addValue("beneficiary", beneficiary);
        p.addValue("tx_type", Transaction.TX_TYPE_PAYOUT);
        Integer count =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM merchant_production_transactions "
                                + "WHERE merchant_id=:merchant_id AND tx_type=:tx_type "
                                + "AND payer_number=:beneficiary AND status IN ('PENDING','SUBMITTED','SUCCESSFUL')",
                        p,
                        Integer.class);
        return count == null ? 0 : count;
    }

    private String normalized(String value, String defaultValue) {
        return blank(value) ? defaultValue : value.trim().toUpperCase(Locale.ROOT);
    }

    private boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    public record QueuedPayout(
            long id,
            String payoutReference,
            long merchantId,
            String merchantNumber,
            String payloadJson,
            BigDecimal amount,
            String currency,
            String queueStatus,
            String triggerReason,
            String requestedBy,
            String approvedBy) {}

    private record Control(
            BigDecimal dailyAmountLimit,
            BigDecimal monthlyAmountLimit,
            BigDecimal perTransactionLimit,
            Integer beneficiaryVelocityLimit,
            String approvalRequiredFlag,
            String enabledFlag) {}
}
