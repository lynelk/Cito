package net.citotech.cito.refund;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.citotech.cito.Common;
import net.citotech.cito.DoPayGateway;
import net.citotech.cito.Model.GatewayChargeDetails;
import net.citotech.cito.Model.Merchant;
import net.citotech.cito.Model.Transaction;
import net.citotech.cito.SendMail;
import net.citotech.cito.async.ManagedAsyncTasks;
import net.citotech.cito.gateway.PaymentGatewayException;
import net.citotech.cito.ledger.DoubleEntryLedgerService;
import net.citotech.cito.merchant.MerchantNotificationPreferenceService;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Governed refund lifecycle. Partial refunds, cumulative refund protection, optional maker-checker
 * approval, provider-attempt evidence, ledger reservation/capture, notifications and a financial
 * timeline are handled as one lifecycle rather than separate operational side effects.
 */
@Service
public class RefundService {
    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final PlatformTransactionManager transactionManager;
    private final DoubleEntryLedgerService ledgerService;
    private final MerchantNotificationPreferenceService notificationPreferenceService;
    private final BigDecimal approvalThreshold;

    public RefundService(
            NamedParameterJdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            DoubleEntryLedgerService ledgerService,
            MerchantNotificationPreferenceService notificationPreferenceService,
            @Value("${cpay.refund.approval-threshold:500000}") BigDecimal approvalThreshold) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionManager = transactionManager;
        this.ledgerService = ledgerService;
        this.notificationPreferenceService = notificationPreferenceService;
        this.approvalThreshold = approvalThreshold == null ? BigDecimal.ZERO : approvalThreshold;
    }

    /** amount == null means a full refund of whatever remains unrefunded on the original payin. */
    @Transactional
    public RefundRecord requestRefund(
            Merchant merchant,
            String originalMerchantRef,
            String refundReference,
            BigDecimal amount,
            String reason) {
        String requestedBy =
                merchant == null || merchant.getAccount_number() == null
                        ? "API"
                        : "API:" + merchant.getAccount_number();
        return requestRefund(
                merchant, originalMerchantRef, refundReference, amount, reason, requestedBy);
    }

    @Transactional
    public RefundRecord requestRefund(
            Merchant merchant,
            String originalMerchantRef,
            String refundReference,
            BigDecimal amount,
            String reason,
            String requestedBy) {
        return requestRefund(
                merchant,
                originalMerchantRef,
                refundReference,
                amount,
                reason,
                requestedBy,
                null,
                null);
    }

    /** Legacy callback context is persisted so deferred approval keeps the same destination. */
    @Transactional
    public RefundRecord requestRefund(
            Merchant merchant,
            String originalMerchantRef,
            String refundReference,
            BigDecimal amount,
            String reason,
            String requestedBy,
            String callbackUrl,
            String originatingIp) {
        if (merchant == null || merchant.getId() == null) {
            throw new PaymentGatewayException("Merchant is required");
        }
        if (refundReference == null || refundReference.isBlank()) {
            throw new PaymentGatewayException("Refund reference is required");
        }
        Optional<RefundRecord> existing = findByReference(merchant.getId(), refundReference);
        if (existing.isPresent()) {
            if (!java.util.Objects.equals(existing.get().originalMerchantRef(), originalMerchantRef)
                    || (amount != null
                            && existing.get().requestedAmount().compareTo(amount) != 0)) {
                throw new PaymentGatewayException(
                        "Refund reference is already bound to another request");
            }
            return existing.get();
        }

        Transaction originalTx = getSuccessfulPayin(originalMerchantRef, merchant.getId());
        if (originalTx == null) {
            throw new PaymentGatewayException(
                    "No successful payin found for reference " + originalMerchantRef);
        }
        BigDecimal originalAmount = originalTx.getOriginalAmountDecimal();
        BigDecimal alreadyRefunded = refundedSoFar(originalTx.getId());
        BigDecimal remaining = originalAmount.subtract(alreadyRefunded);
        BigDecimal refundAmount = amount == null ? remaining : amount;
        if (refundAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new PaymentGatewayException("Refund amount must be greater than zero");
        }
        if (refundAmount.compareTo(remaining) > 0) {
            throw new PaymentGatewayException(
                    "Refund amount "
                            + refundAmount
                            + " exceeds the unrefunded balance "
                            + remaining
                            + " on transaction "
                            + originalMerchantRef);
        }

        long refundId =
                insertRefund(
                        merchant.getId(),
                        refundReference,
                        originalTx.getId(),
                        originalMerchantRef,
                        refundAmount,
                        reason,
                        requestedBy,
                        callbackUrl,
                        originatingIp);
        recordTimeline(
                merchant.getId(),
                originalMerchantRef,
                refundReference,
                "REFUND_REQUESTED",
                "REQUESTED",
                refundAmount,
                currency(originalTx),
                null);

        if (requiresApproval(refundAmount)) {
            markPendingApproval(refundId);
            recordTimeline(
                    merchant.getId(),
                    originalMerchantRef,
                    refundReference,
                    "REFUND_APPROVAL_REQUIRED",
                    "PENDING_APPROVAL",
                    refundAmount,
                    currency(originalTx),
                    null);
            return findById(refundId)
                    .orElseThrow(
                            () ->
                                    new PaymentGatewayException(
                                            "Failed to read back the refund just created"));
        }
        return processRefund(refundId, merchant, originalTx, refundReference, refundAmount, reason);
    }

    @Transactional
    public RefundRecord approveRefund(long merchantId, String refundReference, String approver) {
        RefundRecord refund =
                findByReference(merchantId, refundReference)
                        .orElseThrow(() -> new PaymentGatewayException("Refund was not found"));
        if (refund.status() != RefundStatus.PENDING_APPROVAL) {
            throw new PaymentGatewayException("Refund is not awaiting approval");
        }
        String actor = required(approver, "approver");
        Map<String, Object> approval = approvalMetadata(refund.id());
        String requestedBy = String.valueOf(approval.getOrDefault("requested_by", ""));
        if (!requestedBy.isBlank() && requestedBy.equalsIgnoreCase(actor)) {
            throw new PaymentGatewayException(
                    "Maker-checker approval requires a different approver");
        }
        jdbcTemplate.update(
                "UPDATE refunds SET approval_status='APPROVED', approved_by=:approved_by, "
                        + "approved_at=CURRENT_TIMESTAMP WHERE id=:id AND refund_status='PENDING_APPROVAL'",
                new MapSqlParameterSource()
                        .addValue("id", refund.id())
                        .addValue("approved_by", actor));
        Merchant merchant = Common.getMerchantById(String.valueOf(merchantId), jdbcTemplate);
        if (merchant == null) {
            throw new PaymentGatewayException("Merchant was not found");
        }
        Transaction originalTx = getSuccessfulPayin(refund.originalMerchantRef(), merchantId);
        if (originalTx == null) {
            throw new PaymentGatewayException("Original transaction was not found");
        }
        recordTimeline(
                merchantId,
                refund.originalMerchantRef(),
                refundReference,
                "REFUND_APPROVED",
                "APPROVED",
                refund.requestedAmount(),
                currency(originalTx),
                "{\"approvedBy\":\"" + escape(actor) + "\"}");
        return processRefund(
                refund.id(),
                merchant,
                originalTx,
                refundReference,
                refund.requestedAmount(),
                refund.reason());
    }

    @Transactional
    public RefundRecord rejectRefund(
            long merchantId, String refundReference, String reviewer, String reason) {
        RefundRecord refund =
                findByReference(merchantId, refundReference)
                        .orElseThrow(() -> new PaymentGatewayException("Refund was not found"));
        if (refund.status() != RefundStatus.PENDING_APPROVAL
                && refund.status() != RefundStatus.REQUESTED) {
            throw new PaymentGatewayException("Refund cannot be rejected in its current state");
        }
        String actor = required(reviewer, "reviewer");
        jdbcTemplate.update(
                "UPDATE refunds SET refund_status='REJECTED', approval_status='REJECTED', "
                        + "approved_by=:reviewer, approved_at=CURRENT_TIMESTAMP, "
                        + "failure_message=:reason WHERE id=:id",
                new MapSqlParameterSource()
                        .addValue("id", refund.id())
                        .addValue("reviewer", actor)
                        .addValue("reason", blankToNull(reason)));
        recordTimeline(
                merchantId,
                refund.originalMerchantRef(),
                refundReference,
                "REFUND_REJECTED",
                "REJECTED",
                refund.requestedAmount(),
                null,
                "{\"reviewer\":\"" + escape(actor) + "\"}");
        return findById(refund.id())
                .orElseThrow(() -> new PaymentGatewayException("Unable to read refund"));
    }

    public Optional<RefundRecord> findByReference(long merchantId, String refundReference) {
        List<RefundRecord> rows =
                jdbcTemplate.query(
                        "SELECT * FROM refunds WHERE merchant_id=:merchant_id AND refund_reference=:refund_reference",
                        new MapSqlParameterSource()
                                .addValue("merchant_id", merchantId)
                                .addValue("refund_reference", refundReference),
                        this::mapRow);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<Map<String, Object>> attempts(long merchantId, String refundReference) {
        RefundRecord refund =
                findByReference(merchantId, refundReference)
                        .orElseThrow(() -> new PaymentGatewayException("Refund was not found"));
        return jdbcTemplate.queryForList(
                "SELECT attempt_number AS attemptNumber, provider_channel AS providerChannel, "
                        + "provider_reference AS providerReference, outcome, failure_code AS failureCode, "
                        + "failure_message AS failureMessage, started_at AS startedAt, completed_at AS completedAt "
                        + "FROM refund_attempts WHERE refund_id=:refund_id ORDER BY attempt_number",
                new MapSqlParameterSource("refund_id", refund.id()));
    }

    public List<Map<String, Object>> financialTimeline(
            long merchantId, String transactionReference, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        return jdbcTemplate.queryForList(
                "SELECT event_reference AS eventReference, event_type AS eventType, event_status AS eventStatus, "
                        + "amount, currency_code AS currencyCode, detail_json AS detailJson, occurred_at AS occurredAt "
                        + "FROM payment_financial_timeline WHERE merchant_id=:merchant_id AND transaction_reference=:transaction_reference "
                        + "ORDER BY id DESC LIMIT "
                        + safeLimit,
                new MapSqlParameterSource()
                        .addValue("merchant_id", merchantId)
                        .addValue("transaction_reference", transactionReference));
    }

    private RefundRecord processRefund(
            long refundId,
            Merchant merchant,
            Transaction originalTx,
            String refundReference,
            BigDecimal refundAmount,
            String reason) {
        transition(refundId, RefundStatus.PROCESSING, null);
        int attemptNumber = beginAttempt(refundId, originalTx.getGateway_id());
        recordTimeline(
                merchant.getId(),
                originalTx.getTx_merchant_ref(),
                refundReference,
                "REFUND_PROCESSING",
                "PROCESSING",
                refundAmount,
                currency(originalTx),
                null);
        try {
            RefundOutcome outcome =
                    executeRefundPayout(
                            merchant, originalTx, refundReference, refundAmount, reason);
            if (outcome.pending()) {
                if (outcome.payoutTransactionId() != null)
                    linkPayoutTransaction(refundId, outcome.payoutTransactionId());
                return findById(refundId)
                        .orElseThrow(
                                () -> new PaymentGatewayException("Unable to read pending refund"));
            }
            if (outcome.succeeded()) {
                transition(refundId, RefundStatus.COMPLETED, null);
                linkPayoutTransaction(refundId, outcome.payoutTransactionId());
                completeAttempt(
                        refundId,
                        attemptNumber,
                        "COMPLETED",
                        outcome.payoutTransactionId() == null
                                ? null
                                : String.valueOf(outcome.payoutTransactionId()),
                        null);
                recordTimeline(
                        merchant.getId(),
                        originalTx.getTx_merchant_ref(),
                        refundReference,
                        "REFUND_COMPLETED",
                        "COMPLETED",
                        refundAmount,
                        currency(originalTx),
                        null);
                notifyRefundOutcome(merchant, refundReference, refundAmount, true, null);
            } else {
                transition(refundId, RefundStatus.FAILED, outcome.message());
                completeAttempt(
                        refundId,
                        attemptNumber,
                        "FAILED",
                        outcome.payoutTransactionId() == null
                                ? null
                                : String.valueOf(outcome.payoutTransactionId()),
                        outcome.message());
                recordTimeline(
                        merchant.getId(),
                        originalTx.getTx_merchant_ref(),
                        refundReference,
                        "REFUND_FAILED",
                        "FAILED",
                        refundAmount,
                        currency(originalTx),
                        null);
                notifyRefundOutcome(
                        merchant, refundReference, refundAmount, false, outcome.message());
            }
        } catch (RuntimeException ex) {
            transition(refundId, RefundStatus.FAILED, ex.getMessage());
            completeAttempt(refundId, attemptNumber, "FAILED", null, ex.getMessage());
            recordTimeline(
                    merchant.getId(),
                    originalTx.getTx_merchant_ref(),
                    refundReference,
                    "REFUND_FAILED",
                    "FAILED",
                    refundAmount,
                    currency(originalTx),
                    null);
            notifyRefundOutcome(merchant, refundReference, refundAmount, false, ex.getMessage());
            throw ex;
        }
        return findById(refundId)
                .orElseThrow(
                        () -> new PaymentGatewayException("Failed to read back processed refund"));
    }

    @org.springframework.beans.factory.annotation.Autowired
    private net.citotech.cito.webhook.MerchantWebhookService paymentWebhooks;

    @org.springframework.scheduling.annotation.Scheduled(
            fixedDelayString = "${cpay.refunds.status-poll.delay-ms:30000}")
    @net.javacrumbs.shedlock.spring.annotation.SchedulerLock(
            name = "mobileMoneyRefundRecovery",
            lockAtMostFor = "PT2M")
    @Transactional
    public void synchronizeMobileMoneyRefunds() {
        List<Map<String, Object>> rows =
                jdbcTemplate.queryForList(
                        "SELECT r.id,r.merchant_id,r.refund_reference,r.original_merchant_ref,r.requested_amount,t.id AS payout_id,t.status,t.currency FROM refunds r JOIN mobile_money_executions e ON e.merchant_id=r.merchant_id AND e.merchant_reference=r.refund_reference JOIN merchant_production_transactions t ON t.tx_unique_id=e.transaction_id WHERE r.refund_status='PROCESSING' AND t.status IN ('SUCCESSFUL','FAILED') ORDER BY r.id LIMIT 100 FOR UPDATE",
                        new MapSqlParameterSource());
        rows.addAll(
                jdbcTemplate.queryForList(
                        "SELECT r.id,r.merchant_id,r.refund_reference,r.original_merchant_ref,r.requested_amount,NULL AS payout_id,'FAILED' AS status,q.currency,'Payout approval rejected or cancelled' AS failure_message FROM refunds r JOIN payout_approval_queue q ON q.merchant_id=r.merchant_id AND q.payout_reference=r.refund_reference WHERE r.refund_status='PROCESSING' AND q.queue_status IN ('REJECTED','CANCELLED') AND NOT EXISTS (SELECT 1 FROM mobile_money_executions e WHERE e.merchant_id=r.merchant_id AND e.merchant_reference=r.refund_reference) ORDER BY r.id LIMIT 100 FOR UPDATE",
                        new MapSqlParameterSource()));
        for (Map<String, Object> row : rows) {
            long id = ((Number) row.get("id")).longValue(),
                    merchantId = ((Number) row.get("merchant_id")).longValue();
            boolean success = "SUCCESSFUL".equals(row.get("status"));
            String status = success ? "COMPLETED" : "FAILED",
                    reference = String.valueOf(row.get("refund_reference"));
            transition(
                    id,
                    success ? RefundStatus.COMPLETED : RefundStatus.FAILED,
                    success
                            ? null
                            : java.util.Objects.toString(
                                    row.get("failure_message"),
                                    "Provider confirmed refund failure"));
            if (row.get("payout_id") instanceof Number payoutId)
                linkPayoutTransaction(id, payoutId.longValue());
            jdbcTemplate.update(
                    "UPDATE refund_attempts SET outcome=:outcome,provider_reference=:provider,completed_at=CURRENT_TIMESTAMP WHERE refund_id=:id AND outcome='PROCESSING'",
                    new MapSqlParameterSource("id", id)
                            .addValue("outcome", status)
                            .addValue(
                                    "provider",
                                    java.util.Objects.toString(row.get("payout_id"), null)));
            recordTimeline(
                    merchantId,
                    String.valueOf(row.get("original_merchant_ref")),
                    reference,
                    "REFUND_" + status,
                    status,
                    (BigDecimal) row.get("requested_amount"),
                    String.valueOf(row.get("currency")),
                    null);
            paymentWebhooks.enqueue(
                    merchantId,
                    success ? "refund.completed" : "refund.failed",
                    reference,
                    new JSONObject()
                            .put("reference", reference)
                            .put("status", status)
                            .put("environment", "PRODUCTION")
                            .toString());
        }
    }

    private void notifyRefundOutcome(
            Merchant merchant,
            String refundReference,
            BigDecimal amount,
            boolean succeeded,
            String failureMessage) {
        try {
            String eventType = succeeded ? "refund.completed" : "refund.failed";
            MerchantNotificationPreferenceService.ResolvedNotification notification =
                    notificationPreferenceService.resolveChannel(merchant.getId(), eventType);
            if (!notification.shouldSend()
                    || notification.channel()
                            != MerchantNotificationPreferenceService.Channel.EMAIL) {
                return;
            }
            String to = notification.address();
            String subject =
                    succeeded
                            ? "Refund completed: " + refundReference
                            : "Refund failed: " + refundReference;
            String body =
                    succeeded
                            ? "Your refund "
                                    + refundReference
                                    + " for "
                                    + amount
                                    + " has completed successfully."
                            : "Your refund "
                                    + refundReference
                                    + " for "
                                    + amount
                                    + " failed."
                                    + (failureMessage == null || failureMessage.isEmpty()
                                            ? ""
                                            : " Reason: " + failureMessage);
            ManagedAsyncTasks.run(
                    "refund-notification-" + refundReference,
                    () -> new SendMail().sendSimpleMessage(to, subject, body, jdbcTemplate));
        } catch (Exception ignored) {
            // Notification delivery cannot change the recorded financial result.
        }
    }

    private Optional<RefundRecord> findById(long id) {
        List<RefundRecord> rows =
                jdbcTemplate.query(
                        "SELECT * FROM refunds WHERE id=:id",
                        new MapSqlParameterSource("id", id),
                        this::mapRow);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private RefundRecord mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new RefundRecord(
                rs.getLong("id"),
                rs.getString("refund_reference"),
                rs.getLong("merchant_id"),
                rs.getLong("original_transaction_id"),
                rs.getString("original_merchant_ref"),
                rs.getObject("payout_transaction_id") == null
                        ? null
                        : rs.getLong("payout_transaction_id"),
                rs.getBigDecimal("requested_amount"),
                RefundStatus.valueOf(rs.getString("refund_status")),
                rs.getString("reason"),
                rs.getString("failure_message"));
    }

    private BigDecimal refundedSoFar(long originalTransactionId) {
        // A locking read sees claims committed while this request waited for the payin
        // lock, even when an earlier idempotency lookup opened a repeatable-read snapshot.
        List<BigDecimal> claims =
                jdbcTemplate.queryForList(
                        "SELECT requested_amount FROM refunds "
                                + "WHERE original_transaction_id=:original_transaction_id "
                                + "AND refund_status IN ('REQUESTED','PENDING_APPROVAL','PROCESSING','COMPLETED') FOR UPDATE",
                        new MapSqlParameterSource("original_transaction_id", originalTransactionId),
                        BigDecimal.class);
        return claims.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private long insertRefund(
            long merchantId,
            String refundReference,
            long originalTransactionId,
            String originalMerchantRef,
            BigDecimal amount,
            String reason,
            String requestedBy,
            String callbackUrl,
            String originatingIp) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("refund_reference", refundReference);
        p.addValue("merchant_id", merchantId);
        p.addValue("original_transaction_id", originalTransactionId);
        p.addValue("original_merchant_ref", originalMerchantRef);
        p.addValue("requested_amount", amount);
        p.addValue("reason", reason);
        p.addValue("requested_by", blankToNull(requestedBy));
        p.addValue("callback_url", blankToNull(callbackUrl));
        p.addValue("originating_ip", blankToNull(originatingIp));
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(
                "INSERT INTO refunds (refund_reference, merchant_id, original_transaction_id, original_merchant_ref, "
                        + "requested_amount, reason, requested_by, callback_url, originating_ip) "
                        + "VALUES (:refund_reference, :merchant_id, :original_transaction_id, :original_merchant_ref, "
                        + ":requested_amount, :reason, :requested_by, :callback_url, :originating_ip)",
                p,
                keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new PaymentGatewayException("Unable to create refund record");
        }
        return key.longValue();
    }

    private void markPendingApproval(long refundId) {
        jdbcTemplate.update(
                "UPDATE refunds SET refund_status='PENDING_APPROVAL', approval_required='YES', approval_status='PENDING' WHERE id=:id",
                new MapSqlParameterSource("id", refundId));
    }

    private void transition(long refundId, RefundStatus next, String failureMessage) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("id", refundId);
        p.addValue("status", next.name());
        p.addValue("failure_message", failureMessage);
        jdbcTemplate.update(
                "UPDATE refunds SET refund_status=:status, failure_message=COALESCE(:failure_message, failure_message) WHERE id=:id",
                p);
    }

    private int beginAttempt(long refundId, String providerChannel) {
        Integer attempt =
                jdbcTemplate.queryForObject(
                        "SELECT COALESCE(MAX(attempt_number),0)+1 FROM refund_attempts WHERE refund_id=:refund_id",
                        new MapSqlParameterSource("refund_id", refundId),
                        Integer.class);
        int attemptNumber = attempt == null ? 1 : attempt;
        jdbcTemplate.update(
                "INSERT INTO refund_attempts (refund_id, attempt_number, provider_channel, outcome) "
                        + "VALUES (:refund_id, :attempt_number, :provider_channel, 'PROCESSING')",
                new MapSqlParameterSource()
                        .addValue("refund_id", refundId)
                        .addValue("attempt_number", attemptNumber)
                        .addValue("provider_channel", blankToNull(providerChannel)));
        return attemptNumber;
    }

    private void completeAttempt(
            long refundId,
            int attemptNumber,
            String outcome,
            String providerReference,
            String failureMessage) {
        jdbcTemplate.update(
                "UPDATE refund_attempts SET outcome=:outcome, provider_reference=:provider_reference, "
                        + "failure_message=:failure_message, completed_at=CURRENT_TIMESTAMP "
                        + "WHERE refund_id=:refund_id AND attempt_number=:attempt_number",
                new MapSqlParameterSource()
                        .addValue("refund_id", refundId)
                        .addValue("attempt_number", attemptNumber)
                        .addValue("outcome", outcome)
                        .addValue("provider_reference", blankToNull(providerReference))
                        .addValue("failure_message", blankToNull(failureMessage)));
    }

    private void linkPayoutTransaction(long refundId, Long payoutTransactionId) {
        if (payoutTransactionId == null) {
            return;
        }
        jdbcTemplate.update(
                "UPDATE refunds SET payout_transaction_id=:payout_transaction_id WHERE id=:id",
                new MapSqlParameterSource()
                        .addValue("id", refundId)
                        .addValue("payout_transaction_id", payoutTransactionId));
    }

    private RefundOutcome executeRefundPayout(
            Merchant merchant,
            Transaction originalTx,
            String refundReference,
            BigDecimal refundAmount,
            String reason) {
        String gatewayId = originalTx.getGateway_id();
        GatewayChargeDetails chargeDetails =
                DoPayGateway.getGatewayChargeDetailsById(jdbcTemplate, gatewayId, merchant.getId());
        Double amountDouble = refundAmount.doubleValue();
        Double charges =
                chargeDetails == null
                        ? 0.0
                        : DoPayGateway.getCustomerOutboundCharges(amountDouble, chargeDetails);
        Double txCost =
                chargeDetails == null
                        ? 0.0
                        : DoPayGateway.getCostOfOutboundCharges(amountDouble, chargeDetails);

        Transaction refundTx = new Transaction();
        refundTx.setGateway_id(gatewayId);
        refundTx.setOriginal_amount(amountDouble);
        refundTx.setPayer_number(originalTx.getPayer_number());
        refundTx.setStatus("PENDING");
        refundTx.setMerchant_id(merchant.getId() + "");
        refundTx.setTx_description(merchant.getShort_name());
        refundTx.setTx_merchant_description(reason == null ? "Refund" : reason);
        refundTx.setTx_type(Transaction.TX_TYPE_PAYOUT_REVERSAL);
        refundTx.setTx_unique_id(Common.generateUuid());
        refundTx.setTx_merchant_ref(refundReference);
        Map<String, Object> context =
                jdbcTemplate.queryForMap(
                        "SELECT callback_url, originating_ip FROM refunds WHERE merchant_id=:merchant AND refund_reference=:reference",
                        new MapSqlParameterSource("merchant", merchant.getId())
                                .addValue("reference", refundReference));
        refundTx.setCallback_url(java.util.Objects.toString(context.get("callback_url"), ""));
        refundTx.setOriginate_ip(java.util.Objects.toString(context.get("originating_ip"), ""));
        refundTx.setCharging_method(
                chargeDetails == null ? "" : chargeDetails.getCustomerOutboundChargeMethod());
        refundTx.setCharges(charges == null ? 0.0 : charges);
        refundTx.setTx_cost(txCost == null ? 0.0 : txCost);
        refundTx.setTx_request_trace("");
        refundTx.setTx_update_trace("");
        refundTx.setTx_gateway_ref("");

        if (net.citotech.cito.gateway.MobileMoneyCompatibilityBridge.manages(refundTx)) {
            refundTx.setCurrency(currency(originalTx));
            try {
                net.citotech.cito.gateway.MobileMoneyCompatibilityBridge.submitRefund(
                        refundTx, merchant);
            } catch (RuntimeException failure) {
                TransactionTemplate current = new TransactionTemplate(transactionManager);
                current.setPropagationBehavior(
                        org.springframework.transaction.TransactionDefinition
                                .PROPAGATION_REQUIRES_NEW);
                Transaction durable =
                        current.execute(
                                ignored ->
                                        Common.getMerchantTxByTheirRef(
                                                refundReference,
                                                merchant.getId().toString(),
                                                jdbcTemplate));
                if (durable == null) throw failure;
                refundTx =
                        durable; // A persisted submission must be recovered, never retried as new
                // money.
            }
            if ("SUCCESSFUL".equals(refundTx.getStatus()))
                return RefundOutcome.succeeded(refundTx.getId());
            if ("FAILED".equals(refundTx.getStatus()))
                return RefundOutcome.failed(
                        "Provider confirmed refund payout failure", refundTx.getId());
            return RefundOutcome.pending(refundTx.getId() > 0 ? refundTx.getId() : null);
        }

        String reservationReference =
                "refund-reserve:" + merchant.getAccount_number() + ":" + refundReference;
        BigDecimal reservedAmount =
                refundAmount.add(charges == null ? BigDecimal.ZERO : BigDecimal.valueOf(charges));
        ledgerService.reserve(
                reservationReference,
                merchant.getId(),
                refundReference,
                reservedAmount,
                currency(originalTx));

        String resultJson;
        try {
            resultJson = Common.doPayOut(refundTx, merchant, jdbcTemplate, transactionManager);
        } catch (RuntimeException ex) {
            ledgerService.releaseReservation(reservationReference);
            return RefundOutcome.failed(ex.getMessage(), null);
        }

        JSONObject result = new JSONObject(resultJson);
        boolean succeeded =
                "OK".equals(result.optString("state")) && "000".equals(result.optString("code"));
        if (succeeded) {
            ledgerService.captureReservation(reservationReference);
            return RefundOutcome.succeeded(refundTx.getId());
        }
        ledgerService.releaseReservation(reservationReference);
        return RefundOutcome.failed(result.optString("message", resultJson), refundTx.getId());
    }

    private Transaction getSuccessfulPayin(String merchantRef, long merchantId) {
        String sql =
                "SELECT * FROM "
                        + "merchant_production_transactions"
                        + " WHERE tx_merchant_ref=:ref AND merchant_id=:mid AND status='SUCCESSFUL' AND tx_type=:type LIMIT 1 FOR UPDATE";
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("ref", merchantRef);
        p.addValue("mid", merchantId);
        p.addValue("type", Transaction.TX_TYPE_PAYIN);
        List<Transaction> rows = jdbcTemplate.query(sql, p, Common.getTransactionRowMapper());
        return rows.isEmpty() ? null : rows.get(0);
    }

    private boolean requiresApproval(BigDecimal amount) {
        return approvalThreshold.compareTo(BigDecimal.ZERO) > 0
                && amount.compareTo(approvalThreshold) >= 0;
    }

    private Map<String, Object> approvalMetadata(long refundId) {
        return jdbcTemplate.queryForMap(
                "SELECT requested_by, approval_status, approved_by FROM refunds WHERE id=:id",
                new MapSqlParameterSource("id", refundId));
    }

    private void recordTimeline(
            long merchantId,
            String transactionReference,
            String eventReference,
            String eventType,
            String eventStatus,
            BigDecimal amount,
            String currency,
            String detailJson) {
        jdbcTemplate.update(
                "INSERT INTO payment_financial_timeline "
                        + "(merchant_id, transaction_reference, event_reference, event_type, event_status, amount, currency_code, detail_json) "
                        + "VALUES (:merchant_id, :transaction_reference, :event_reference, :event_type, :event_status, :amount, :currency_code, :detail_json)",
                new MapSqlParameterSource()
                        .addValue("merchant_id", merchantId)
                        .addValue("transaction_reference", transactionReference)
                        .addValue("event_reference", eventReference)
                        .addValue("event_type", eventType)
                        .addValue("event_status", eventStatus)
                        .addValue("amount", amount)
                        .addValue("currency_code", blankToNull(currency))
                        .addValue("detail_json", detailJson));
    }

    private String currency(Transaction transaction) {
        return transaction.getCurrency() == null || transaction.getCurrency().isBlank()
                ? "UGX"
                : transaction.getCurrency();
    }

    private String required(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new PaymentGatewayException(field + " is required");
        }
        return value.trim();
    }

    private String blankToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private record RefundOutcome(
            boolean succeeded, boolean pending, String message, Long payoutTransactionId) {
        static RefundOutcome pending(Long payoutTransactionId) {
            return new RefundOutcome(false, true, null, payoutTransactionId);
        }

        static RefundOutcome succeeded(long payoutTransactionId) {
            return new RefundOutcome(true, false, null, payoutTransactionId);
        }

        static RefundOutcome failed(String message, Long payoutTransactionId) {
            return new RefundOutcome(false, false, message, payoutTransactionId);
        }
    }
}
