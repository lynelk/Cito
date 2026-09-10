package net.citotech.cito.payout;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import net.citotech.cito.Common;
import net.citotech.cito.Model.Merchant;
import net.citotech.cito.PaymentOrchestrationService;
import net.citotech.cito.admin.AdminAuditService;
import net.citotech.cito.api.v2.dto.PaymentRequest;
import net.citotech.cito.api.v2.dto.PaymentResult;
import net.citotech.cito.gateway.PaymentGatewayException;
import net.citotech.cito.payout.PayoutControlService.QueuedPayout;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Maker-checker approval surface for queued payouts (audit item: payouts had no configurable limits
 * and no approval workflow). A payout that breached a configured limit or triggered a review is
 * queued by {@link PayoutControlService}; a checker (different actor from the requester) approves
 * or rejects it here. Approval re-executes the stored payout request through {@link
 * PaymentOrchestrationService} - the same tested executor the normal v2 payout endpoint uses
 * (reservation, risk, provider call, ledger, webhook) - so money only ever moves through one path.
 *
 * <p>Risk re-checks still run on approval: {@code RiskDecisionService} throws on BLOCK decisions
 * (sanctions/blocklist), so an approval can never bypass a hard risk block; it only overrides the
 * limit/velocity gate that parked the payout.
 */
@RestController
@RequestMapping(path = "/api/v2/admin/payout-approvals")
@PreAuthorize("hasRole('ADMIN')")
public class PayoutApprovalController {
    private String authenticatedActor() {
        var auth =
                org.springframework.security.core.context.SecurityContextHolder.getContext()
                        .getAuthentication();
        if (auth == null || !auth.isAuthenticated())
            throw new PaymentGatewayException("Authenticated checker is required");
        return auth.getName();
    }

    private final PayoutControlService payoutControlService;
    private final PaymentOrchestrationService paymentOrchestrationService;
    private final AdminAuditService auditService;
    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public PayoutApprovalController(
            PayoutControlService payoutControlService,
            PaymentOrchestrationService paymentOrchestrationService,
            AdminAuditService auditService,
            NamedParameterJdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper) {
        this.payoutControlService = payoutControlService;
        this.paymentOrchestrationService = paymentOrchestrationService;
        this.auditService = auditService;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public List<Map<String, Object>> pending(
            @RequestParam(value = "limit", defaultValue = "100") int limit) {
        return payoutControlService.listPending(limit);
    }

    /**
     * Checker approval: flips the queue row to APPROVED (maker-checker enforced), then re-executes
     * the stored payout request through the orchestrator. Returns the orchestrator's payment
     * result.
     */
    @PostMapping(path = "/{queueId}/approve")
    public ResponseEntity<?> approve(
            @PathVariable long queueId,
            @RequestParam(value = "approvedBy", required = false) String approvedBy) {
        approvedBy = authenticatedActor();
        QueuedPayout queued = payoutControlService.approve(queueId, approvedBy);
        auditService.record(
                "PAYOUT_APPROVAL",
                "PAYOUT_APPROVE",
                queued.payoutReference() + ":" + queueId,
                approvedBy);
        PaymentRequest request = deserialize(queued.payloadJson());
        Merchant merchant =
                Common.getMerchantByAccountNumber(queued.merchantNumber(), jdbcTemplate);
        if (merchant == null) {
            throw new PaymentGatewayException(
                    "Merchant for queued payout not found: " + queued.merchantNumber());
        }
        PaymentResult result =
                paymentOrchestrationService.payout(request, merchant, "admin-approval");
        return ResponseEntity.accepted().body(result);
    }

    @PostMapping(path = "/{queueId}/reject")
    public ResponseEntity<?> reject(
            @PathVariable long queueId,
            @RequestParam(value = "rejectedBy", required = false) String rejectedBy,
            @RequestParam(value = "reason", required = false) String reason) {
        rejectedBy = authenticatedActor();
        int updated = payoutControlService.reject(queueId, rejectedBy, reason);
        if (updated == 0) {
            throw new PaymentGatewayException(
                    "Payout rejection failed: a different actor than the requester must reject, and the payout must be awaiting approval");
        }
        auditService.record("PAYOUT_APPROVAL", "PAYOUT_REJECT", "queueId:" + queueId, rejectedBy);
        return ResponseEntity.ok(Map.of("code", "000", "queueId", queueId, "status", "REJECTED"));
    }

    @PostMapping(path = "/{queueId}/cancel")
    public ResponseEntity<?> cancel(
            @PathVariable long queueId,
            @RequestParam(value = "cancelledBy", required = false) String cancelledBy) {
        cancelledBy = authenticatedActor();
        int updated = payoutControlService.cancel(queueId, cancelledBy);
        if (updated == 0) {
            throw new PaymentGatewayException(
                    "Payout cancellation failed: the payout must be awaiting approval");
        }
        auditService.record("PAYOUT_APPROVAL", "PAYOUT_CANCEL", "queueId:" + queueId, cancelledBy);
        return ResponseEntity.ok(Map.of("code", "000", "queueId", queueId, "status", "CANCELLED"));
    }

    private PaymentRequest deserialize(String payloadJson) {
        try {
            return objectMapper.readValue(payloadJson, PaymentRequest.class);
        } catch (Exception e) {
            throw new PaymentGatewayException("Stored payout request could not be deserialized");
        }
    }
}
