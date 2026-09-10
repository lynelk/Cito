package net.citotech.cito;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.citotech.cito.Model.*;
import net.citotech.cito.gateway.PaymentGatewayException;
import net.citotech.cito.refund.RefundRecord;
import net.citotech.cito.refund.RefundService;
import net.citotech.cito.security.SignatureVerificationService;
import net.citotech.cito.service.RateLimiterService;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.*;

/**
 * Refund/reversal API.
 *
 * <p>POST /api/doMobileMoneyRefund
 *
 * <p>Request body (JSON): merchant_number – merchant account number original_reference – the
 * merchant's original reference for the PAYIN to refund reference – a new unique reference for this
 * refund description – reason for refund callback_url – webhook URL for the refund outcome
 * signature – RSA-SHA256 or HMAC-SHA256 over (merchant_number + original_reference + reference +
 * description)
 *
 * <p>Uses the same governed lifecycle as the v2 and merchant refund APIs: locks the successful
 * production payin, claims its remaining unrefunded balance, applies approval controls and tracks
 * the compensating payout until provider-confirmed settlement.
 */
@RestController
@RequestMapping(path = "/api", produces = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
public class RefundController {

    private static final Logger logger = Logger.getLogger(RefundController.class.getName());

    @Autowired NamedParameterJdbcTemplate jdbcTemplate;

    @Autowired RefundService refundService;

    @Autowired RateLimiterService rateLimiterService;

    @PostMapping(path = "/doMobileMoneyRefund")
    public String doMobileMoneyRefund(
            @RequestBody String requestBody,
            HttpServletRequest request,
            HttpServletResponse response) {
        try {
            JSONObject sObject;
            try {
                sObject = new JSONObject(requestBody);
            } catch (JSONException e) {
                return GeneralException.getError("124", GeneralException.ERRORS_124);
            }

            // Required fields
            List<String> fields = new ArrayList<>();
            fields.add("merchant_number");
            fields.add("original_reference");
            fields.add("reference");
            fields.add("description");
            fields.add("callback_url");
            fields.add("signature");

            List<String> missing = new ArrayList<>();
            for (String f : fields) if (sObject.isNull(f)) missing.add(f);
            if (!missing.isEmpty()) {
                return GeneralException.getError(
                        "114",
                        String.format(GeneralException.ERRORS_114, String.join(", ", missing)));
            }

            String merchant_number = sObject.getString("merchant_number");
            String original_reference = sObject.getString("original_reference");
            String refund_reference = sObject.getString("reference");
            String description = sObject.getString("description");
            String callback_url = sObject.getString("callback_url");
            String signatureBase64 = sObject.getString("signature");
            String originating_ip = Common.getIpAddress(request);

            // Rate limiting
            if (!rateLimiterService.tryConsume(merchant_number)) {
                response.setStatus(429);
                return GeneralException.getError("145", GeneralException.ERRORS_145);
            }

            // Resolve merchant
            Merchant merchant = Common.getMerchantByAccountNumber(merchant_number, jdbcTemplate);
            if (merchant == null) {
                return GeneralException.getError(
                        "109",
                        String.format(GeneralException.ERRORS_109, "Merchant", merchant_number));
            }
            if (!"ACTIVE".equals(merchant.getStatus())) {
                return GeneralException.getError("119", GeneralException.ERRORS_119);
            }

            // Verify signature
            String signedData =
                    merchant_number + original_reference + refund_reference + description;
            String sigError =
                    SignatureVerificationService.verify(merchant, signedData, signatureBase64);
            if (sigError != null) return sigError;

            RefundRecord refund =
                    refundService.requestRefund(
                            merchant,
                            original_reference,
                            refund_reference,
                            null,
                            description,
                            "API:" + merchant_number,
                            callback_url,
                            originating_ip);
            Transaction payout =
                    Common.getMerchantTxByTheirRef(
                            refund_reference, merchant.getId().toString(), jdbcTemplate);
            String status =
                    switch (refund.status()) {
                        case COMPLETED -> "SUCCESSFUL";
                        case FAILED, REJECTED -> "FAILED";
                        case PENDING_APPROVAL -> "PENDING_APPROVAL";
                        default -> "PENDING";
                    };
            String message = "Refund " + refund.status().name().toLowerCase(java.util.Locale.ROOT);
            GateWayResponse outcome = new GateWayResponse();
            outcome.setStatus("FAILED".equals(status) ? "ERROR" : "OK");
            outcome.setTransactionStatus(status);
            outcome.setOurUniqueTxId(payout == null ? null : payout.getTx_unique_id());
            outcome.setMessage(message);
            JSONObject result =
                    new JSONObject(
                            "FAILED".equals(status)
                                    ? GeneralException.getApiTxMessage("143", message, outcome)
                                    : GeneralSuccessResponse.getApiTxMessage(
                                            "000", message, outcome));
            result.put("refundReference", refund.refundReference());
            result.put("refundStatus", refund.status().name());
            return result.toString();

        } catch (PaymentGatewayException | IllegalArgumentException ex) {
            return GeneralException.getError(
                    "142",
                    "Refund could not be accepted. Check the original payment, remaining refundable amount, reference and approval requirements.");
        } catch (Exception ex) {
            logger.log(Level.SEVERE, "doMobileMoneyRefund error: " + ex.getMessage(), ex);
            return GeneralException.getError("102", GeneralException.ERRORS_102);
        }
    }
}
