package net.citotech.cito;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.citotech.cito.Model.*;
import net.citotech.cito.security.SignatureVerificationService;
import net.citotech.cito.service.RateLimiterService;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
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
 * <p>Behaviour: 1. Locates the original PAYIN by original_reference + merchant_id. 2. Verifies it
 * is SUCCESSFUL. 3. Creates a PAYOUT_REVERSAL transaction for the same amount/gateway, using the
 * original payer_number as the payee (refund recipient). 4. Dispatches the payout via the same
 * gateway.
 */
@RestController
@RequestMapping(path = "/api", produces = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
public class RefundController {
    @org.springframework.beans.factory.annotation.Autowired
    private net.citotech.cito.developer.reference.ApiAccessBillingService apiBilling;

    private static final Logger logger = Logger.getLogger(RefundController.class.getName());

    @Autowired NamedParameterJdbcTemplate jdbcTemplate;

    @Autowired PlatformTransactionManager transactionManager;

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
            apiBilling.admitted(merchant.getId(), null, "PRODUCTION");

            // Look up original transaction
            Transaction originalTx = getSuccessfulPayin(original_reference, merchant.getId());
            if (originalTx == null) {
                return GeneralException.getError(
                        "109",
                        String.format(
                                GeneralException.ERRORS_109,
                                "Successful PAYIN",
                                original_reference));
            }

            // Check stock/revenue/suspense accounts
            Setting stockAccountSetting = Common.getSettings("float_stock_account", jdbcTemplate);
            Setting revenueAccountSetting = Common.getSettings("revenue_account", jdbcTemplate);
            Setting suspenseAccountSetting = Common.getSettings("suspense_account", jdbcTemplate);
            if (stockAccountSetting == null || stockAccountSetting.getSetting_value().isEmpty())
                return GeneralException.getError("112", GeneralException.ERRORS_112);
            if (revenueAccountSetting == null || revenueAccountSetting.getSetting_value().isEmpty())
                return GeneralException.getError("117", GeneralException.ERRORS_117);
            if (suspenseAccountSetting == null
                    || suspenseAccountSetting.getSetting_value().isEmpty())
                return GeneralException.getError("127", GeneralException.ERRORS_127);

            // Ensure sufficient float balance for the refund
            ArrayList<Balance> balances =
                    Common.getMerchantBalances(merchant.getId() + "", jdbcTemplate);
            String gateway_id = originalTx.getGateway_id();
            Double refundAmount = originalTx.getOriginal_amount();
            for (Balance b : balances) {
                if (b.getGateway_id().equals(gateway_id)) {
                    if (refundAmount > b.getAmount()) {
                        return GeneralException.getError(
                                "111",
                                String.format(
                                        GeneralException.ERRORS_111, b.getAmount(), b.getCode()));
                    }
                }
            }

            // Build the refund (payout reversal) transaction
            GatewayChargeDetails gwChargeDetails =
                    DoPayGateway.getGatewayChargeDetailsById(
                            jdbcTemplate, gateway_id, merchant.getId());

            Transaction refundTx = new Transaction();
            refundTx.setGateway_id(gateway_id);
            refundTx.setOriginal_amount(refundAmount);
            refundTx.setPayer_number(
                    originalTx.getPayer_number()); // refund goes back to original payer
            refundTx.setStatus("PENDING");
            refundTx.setMerchant_id(merchant.getId() + "");
            refundTx.setTx_description(merchant.getShort_name());
            refundTx.setTx_merchant_description(description);
            refundTx.setTx_type(Transaction.TX_TYPE_PAYOUT_REVERSAL);
            refundTx.setTx_unique_id(Common.generateUuid());
            refundTx.setTx_merchant_ref(refund_reference);
            refundTx.setCallback_url(callback_url);
            refundTx.setOriginate_ip(originating_ip);
            Double charges = DoPayGateway.getCustomerOutboundCharges(refundAmount, gwChargeDetails);
            Double tx_cost = DoPayGateway.getCostOfOutboundCharges(refundAmount, gwChargeDetails);
            refundTx.setCharging_method(gwChargeDetails.getCustomerOutboundChargeMethod());
            refundTx.setCharges(charges);
            refundTx.setTx_cost(tx_cost);
            refundTx.setTx_request_trace("");
            refundTx.setTx_update_trace("");
            refundTx.setTx_gateway_ref("");

            String result = Common.doPayOut(refundTx, merchant, jdbcTemplate, transactionManager);
            return result;

        } catch (Exception ex) {
            logger.log(Level.SEVERE, "doMobileMoneyRefund error: " + ex.getMessage(), ex);
            return GeneralException.getError("102", GeneralException.ERRORS_102);
        }
    }

    private Transaction getSuccessfulPayin(String merchantRef, Long merchantId) {
        try {
            String sql =
                    "SELECT * FROM "
                            + Common.DB_TABLE_MERCHANT_TRANSACTION_LOG
                            + " WHERE tx_merchant_ref=:ref AND merchant_id=:mid"
                            + " AND status='SUCCESSFUL' AND tx_type=:type LIMIT 1";
            MapSqlParameterSource p = new MapSqlParameterSource();
            p.addValue("ref", merchantRef);
            p.addValue("mid", merchantId);
            p.addValue("type", Transaction.TX_TYPE_PAYIN);
            List<Transaction> txList = jdbcTemplate.query(sql, p, Common.getTransactionRowMapper());
            return txList.isEmpty() ? null : txList.get(0);
        } catch (Exception e) {
            logger.log(Level.WARNING, "getSuccessfulPayin error: " + e.getMessage(), e);
            return null;
        }
    }
}
