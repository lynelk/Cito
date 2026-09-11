package net.citotech.cito;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.math.BigInteger;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.citotech.cito.Model.*;
import net.citotech.cito.gateway.ProviderConversationReferenceStoreRegistry;
import net.citotech.cito.security.CallbackUrlValidator;
import net.citotech.cito.security.PiiMasking;
import net.citotech.cito.security.SignatureVerificationService;
import net.citotech.cito.service.RateLimiterService;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author josephtabajjwa
 */
@RestController
@RequestMapping(path = "/api", produces = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
public class Api {
    @org.springframework.beans.factory.annotation.Autowired
    private net.citotech.cito.developer.reference.ApiAccessBillingService apiBilling;

    private static final DateTimeFormatter SMS_SEND_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired NamedParameterJdbcTemplate jdbcTemplate;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired RateLimiterService rateLimiterService;
    @Autowired net.citotech.cito.ledger.LegacyLedgerPostingService legacyLedgerPostingService;
    @Autowired private net.citotech.cito.ledger.DoubleEntryLedgerService ledgerService;
    @Autowired net.citotech.cito.api.v2.IdempotencyService idempotencyService;
    @Autowired private net.citotech.cito.payout.PayoutControlService payoutControlService;

    @Value("${custom.gatewaystate}")
    private String gatewaystate;

    /*
     * API to add a new admin to the database
     */
    @PostMapping(path = "/doMobileMoneyPayIn")
    public String doMobileMoneyPayIn(
            @RequestBody String requestBody,
            HttpServletRequest request,
            HttpServletResponse response) {
        // Set the response header

        try {
            // Ensure that we have valid JSON data.
            JSONObject sObject;
            try {
                sObject = new JSONObject(requestBody);
            } catch (JSONException e) {
                return GeneralException.getError(
                        "124", String.format(GeneralException.ERRORS_124, ""));
            }

            List<String> fields = new ArrayList<>();
            fields.add("amount");
            fields.add("description");
            fields.add("reference");
            fields.add("merchant_number");
            fields.add("payer_number");
            fields.add("callback_url");
            fields.add("signature");

            List<String> missingFields = missingJsonFields(fields, sObject);
            if (missingFields.size() > 0) {
                String missing_f = "";
                for (String s : missingFields) {
                    missing_f += s + ", ";
                }
                missing_f = missing_f.substring(0, (missing_f.length() - 2));
                return GeneralException.getError(
                        "114", String.format(GeneralException.ERRORS_114, missing_f));
            }
            String amount_string = sObject.getString("amount");
            Double amount;
            try {
                amount = Double.parseDouble(amount_string);
            } catch (NumberFormatException e) {
                return GeneralException.getError(
                        "123", String.format(GeneralException.ERRORS_123, amount_string));
            }

            if (amount <= 0) {
                return GeneralException.getError(
                        "123", String.format(GeneralException.ERRORS_123, amount_string));
            }

            String description = sObject.getString("description");
            String reference = sObject.getString("reference");
            String merchant_number = sObject.getString("merchant_number");
            String payer_number = sObject.getString("payer_number");
            String signatureBase64 = sObject.getString("signature");
            // String signature = Common.base64Decode(signatureBase64);
            String callback_url = sObject.getString("callback_url");
            String origanting_ip = Common.getIpAddress(request);

            // Validate callback_url to prevent SSRF
            String callbackUrlError = CallbackUrlValidator.validate(callback_url);
            if (callbackUrlError != null) {
                return GeneralException.getError("124", callbackUrlError);
            }

            // Rate limiting per merchant_number
            if (!rateLimiterService.tryConsume(merchant_number)) {
                response.setStatus(429);
                return GeneralException.getError("145", GeneralException.ERRORS_145);
            }

            // Get this merchant
            Merchant merchant =
                    Common.getMerchantByAccountNumber(merchant_number + "", jdbcTemplate);
            if (merchant == null) {
                return GeneralException.getError(
                        "109",
                        String.format(GeneralException.ERRORS_109, "Merchant", merchant_number));
            }

            // Check if the user's account is suspended
            if (merchant.getStatus().equals("SUSPENDED")) {
                return GeneralException.getError("137", GeneralException.ERRORS_137);
            }

            // Check IP whitelist per merchant
            Setting ipWhitelistSetting =
                    Common.getMerchantSettings("api_allowed_ips", merchant.getId(), jdbcTemplate);
            if (ipWhitelistSetting != null && !ipWhitelistSetting.getSetting_value().isEmpty()) {
                String allowedIps = ipWhitelistSetting.getSetting_value();
                boolean ipAllowed = false;
                for (String ip : allowedIps.split(",")) {
                    if (ip.trim().equals(origanting_ip)) {
                        ipAllowed = true;
                        break;
                    }
                }
                if (!ipAllowed) {
                    return GeneralException.getError(
                            "139", String.format(GeneralException.ERRORS_139, origanting_ip));
                }
            }

            // Verify signature
            String signedData =
                    merchant_number + payer_number + amount_string + reference + description;
            String sigError =
                    SignatureVerificationService.verify(merchant, signedData, signatureBase64);
            if (sigError != null) {
                return sigError;
            }
            apiBilling.admitted(merchant.getId(), null, "PRODUCTION");

            // Now check if the merchant is not suspended
            if (!merchant.getStatus().equals("ACTIVE")) {
                return GeneralException.getError("119", GeneralException.ERRORS_119);
            }

            // Check if this API is allowed.
            String[] allowed_apis = merchant.getAllowed_apis();
            Boolean isAllowedToAccessApi = false;
            for (String api : allowed_apis) {
                if (api.equals(Common.API_MOBILE_MONEY_PAYIN)) {
                    isAllowedToAccessApi = true;
                }
            }

            if (!isAllowedToAccessApi) {
                return GeneralException.getError(
                        "120",
                        String.format(GeneralException.ERRORS_120, Common.API_MOBILE_MONEY_PAYIN));
            }

            // First check if stock account was configured transaction
            Setting getStockAccount = Common.getSettings("float_stock_account", jdbcTemplate);
            Setting getRevenueAccount = Common.getSettings("revenue_account", jdbcTemplate);
            if (getStockAccount == null || getStockAccount.getSetting_value().isEmpty()) {
                return GeneralException.getError("112", GeneralException.ERRORS_112);
            }

            if (getRevenueAccount == null || getRevenueAccount.getSetting_value().isEmpty()) {
                return GeneralException.getError("117", GeneralException.ERRORS_117);
            }

            // If it's stock account, this operation is not permitted
            String stock_account_number = getStockAccount.getSetting_value().trim();
            if (merchant.getAccount_number().equals(stock_account_number)) {
                return GeneralException.getError("113", GeneralException.ERRORS_113);
            }

            // First determine the gateway by msisdn
            String gateway_id = DoPayGateway.getGatewayIdByMsisdn(payer_number, jdbcTemplate);
            if (gateway_id == null) {
                return GeneralException.getError(
                        "118", String.format(GeneralException.ERRORS_118, payer_number));
            }

            // Enforce gateway-level min/max amount limits
            String amountLimitError = checkGatewayAmountLimits(gateway_id, amount);
            if (amountLimitError != null) return amountLimitError;

            // Enforce per-merchant daily/monthly volume limits
            String volumeLimitError = checkMerchantVolumeLimit(merchant, amount);
            if (volumeLimitError != null) return volumeLimitError;

            // Get this merchant by id.
            Transaction newTx = new Transaction();
            newTx.setGateway_id(gateway_id);
            newTx.setOriginal_amount(amount);
            newTx.setPayer_number(payer_number);
            newTx.setStatus("PENDING");
            newTx.setMerchant_id(merchant.getId() + "");
            newTx.setTx_description(merchant.getShort_name());
            newTx.setTx_merchant_description(description);
            newTx.setTx_type(Transaction.TX_TYPE_PAYIN);
            String tx_id = Common.generateUuid();
            if (gateway_id.equals(AirtelMoneyOpenApiPaymentGateway.gateway_id)) {
                tx_id = getAirtelOpenApiId(merchant_number);
            }
            newTx.setTx_unique_id(tx_id);
            newTx.setTx_merchant_ref(reference);
            newTx.setCallback_url(callback_url);
            newTx.setOriginate_ip(origanting_ip);
            // First get the charging method
            GatewayChargeDetails gwChargingDetails =
                    DoPayGateway.getGatewayChargeDetailsById(
                            jdbcTemplate, gateway_id, merchant.getId());
            newTx.setCharging_method(gwChargingDetails.getCustomerInboundChargeMethod());
            Double charges = DoPayGateway.getCustomerInboundCharges(amount, gwChargingDetails);
            Double tx_cost = DoPayGateway.getCostOfInboundCharges(amount, gwChargingDetails);
            newTx.setCharges(charges);
            newTx.setTx_cost(tx_cost);
            newTx.setTx_request_trace("");
            newTx.setTx_update_trace("");
            newTx.setTx_gateway_ref("");

            // Audit D1: optional idempotency-key support on the legacy v1 money endpoints. When
            // the merchant supplies an Idempotency-Key/X-Idempotency-Key header, a replayed
            // request with the identical body returns the previously recorded response instead of
            // re-submitting to the provider. Without the header the legacy behavior is unchanged
            // (fully backward compatible). The find/record wraps the whole validated submission
            // so validation-error responses on the first attempt are simply re-derived, never a
            // cached partial result, and a replay never re-runs Common.doPayIn.
            String idempotencyKey = headerValue(request, "Idempotency-Key", "X-Idempotency-Key");
            if (!isBlank(idempotencyKey)) {
                Optional<String> replayed =
                        idempotencyService.findExistingBody(
                                merchant_number, idempotencyKey, requestBody);
                if (replayed.isPresent()) {
                    return replayed.get();
                }
            }

            String result = Common.doPayIn(newTx, merchant, jdbcTemplate, transactionManager);

            // Managed collections settle through their verified canonical finalizer. Preserve
            // the existing ledger seam only for other compatibility providers.
            if (!net.citotech.cito.gateway.MobileMoneyCompatibilityBridge.manages(newTx)) {
                legacyLedgerPostingService.postPaymentEntries(
                        Transaction.TX_TYPE_PAYIN, gateway_id, merchant, newTx, amount, charges);
            }

            if (!isBlank(idempotencyKey)) {
                idempotencyService.recordBody(merchant_number, idempotencyKey, requestBody, result);
            }

            return result;
        } catch (Exception ex) {

            Logger.getLogger(AuthenticationController.class.getName())
                    .log(Level.SEVERE, "INTERNAL ERROR: " + ex.getMessage(), ex);
            return GeneralException.getError("102", GeneralException.ERRORS_102);
        }
    }

    List<String> missingJsonFields(List<String> fields, JSONObject jObject) {
        List<String> missing = new ArrayList<String>();
        for (String field : fields) {
            if (jObject.isNull(field)) {
                missing.add(field);
            }
        }
        return missing;
    }

    private String headerValue(HttpServletRequest request, String... names) {
        if (request == null) {
            return "";
        }
        for (String name : names) {
            String value = request.getHeader(name);
            if (!isBlank(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /*
     * Initiate a Mobile Money payout request
     */
    @PostMapping(path = "/doMobileMoneyPayOut")
    public String doMobileMoneyPayOut(
            @RequestBody String requestBody,
            HttpServletRequest request,
            HttpServletResponse response) {
        // Set the response header

        try {
            // Ensure that we have valid JSON data.
            JSONObject sObject;
            try {
                sObject = new JSONObject(requestBody);
            } catch (JSONException e) {
                return GeneralException.getError(
                        "124", String.format(GeneralException.ERRORS_124, ""));
            }

            List<String> fields = new ArrayList<>();
            fields.add("amount");
            fields.add("description");
            fields.add("reference");
            fields.add("merchant_number");
            fields.add("payee_number");
            fields.add("callback_url");
            fields.add("signature");

            List<String> missingFields = missingJsonFields(fields, sObject);
            if (missingFields.size() > 0) {
                String missing_f = "";
                for (String s : missingFields) {
                    missing_f += s + ", ";
                }
                missing_f = missing_f.substring(0, (missing_f.length() - 2));
                return GeneralException.getError(
                        "114", String.format(GeneralException.ERRORS_114, missing_f));
            }
            String amount_string = sObject.getString("amount");
            Double amount;
            try {
                amount = Double.parseDouble(amount_string);
            } catch (NumberFormatException e) {
                return GeneralException.getError(
                        "123", String.format(GeneralException.ERRORS_123, amount_string));
            }

            if (amount <= 0) {
                return GeneralException.getError(
                        "123", String.format(GeneralException.ERRORS_123, amount_string));
            }

            String description = sObject.getString("description");
            String reference = sObject.getString("reference");
            String merchant_number = sObject.getString("merchant_number");
            String payee_number = sObject.getString("payee_number");
            String signatureBase64 = sObject.getString("signature");
            String callback_url = sObject.getString("callback_url");
            String origanting_ip = Common.getIpAddress(request);

            // Validate callback_url to prevent SSRF
            String callbackUrlError = CallbackUrlValidator.validate(callback_url);
            if (callbackUrlError != null) {
                return GeneralException.getError("124", callbackUrlError);
            }

            // Rate limiting per merchant_number
            if (!rateLimiterService.tryConsume(merchant_number)) {
                response.setStatus(429);
                return GeneralException.getError("145", GeneralException.ERRORS_145);
            }

            // Get this merchant
            Merchant merchant =
                    Common.getMerchantByAccountNumber(merchant_number + "", jdbcTemplate);
            if (merchant == null) {
                return GeneralException.getError(
                        "109",
                        String.format(GeneralException.ERRORS_109, "Merchant", merchant_number));
            }

            // Check if the user's account is suspended
            if (merchant.getStatus().equals("SUSPENDED")) {
                return GeneralException.getError("137", GeneralException.ERRORS_137);
            }

            // Check IP whitelist per merchant
            Setting ipWhitelistSetting =
                    Common.getMerchantSettings("api_allowed_ips", merchant.getId(), jdbcTemplate);
            if (ipWhitelistSetting != null && !ipWhitelistSetting.getSetting_value().isEmpty()) {
                String allowedIps = ipWhitelistSetting.getSetting_value();
                boolean ipAllowed = false;
                for (String ip : allowedIps.split(",")) {
                    if (ip.trim().equals(origanting_ip)) {
                        ipAllowed = true;
                        break;
                    }
                }
                if (!ipAllowed) {
                    return GeneralException.getError(
                            "139", String.format(GeneralException.ERRORS_139, origanting_ip));
                }
            }

            // Verify signature
            String signedData =
                    merchant_number + payee_number + amount_string + reference + description;
            String sigError =
                    SignatureVerificationService.verify(merchant, signedData, signatureBase64);
            if (sigError != null) {
                return sigError;
            }
            apiBilling.admitted(merchant.getId(), null, "PRODUCTION");

            // Now check if the merchant is not suspended
            if (!merchant.getStatus().equals("ACTIVE")) {
                return GeneralException.getError("119", GeneralException.ERRORS_119);
            }

            // Check if this API is allowed.
            String[] allowed_apis = merchant.getAllowed_apis();
            Boolean isAllowedToAccessApi = false;
            for (String api : allowed_apis) {
                if (api.equals(Common.API_MOBILE_MONEY_PAYOUT)) {
                    isAllowedToAccessApi = true;
                }
            }

            if (!isAllowedToAccessApi) {
                return GeneralException.getError(
                        "120",
                        String.format(GeneralException.ERRORS_120, Common.API_MOBILE_MONEY_PAYOUT));
            }

            // First check if stock account was configured transaction
            Setting getStockAccount = Common.getSettings("float_stock_account", jdbcTemplate);
            Setting getRevenueAccount = Common.getSettings("revenue_account", jdbcTemplate);
            if (getStockAccount == null || getStockAccount.getSetting_value().isEmpty()) {
                return GeneralException.getError("112", GeneralException.ERRORS_112);
            }

            if (getRevenueAccount == null || getRevenueAccount.getSetting_value().isEmpty()) {
                return GeneralException.getError("117", GeneralException.ERRORS_117);
            }

            Setting getSuspenseAccount = Common.getSettings("suspense_account", jdbcTemplate);
            if (getSuspenseAccount == null || getSuspenseAccount.getSetting_value().isEmpty()) {
                return GeneralException.getError("127", GeneralException.ERRORS_127);
            }

            // If it's stock account, this operation is not permitted
            String stock_account_number = getStockAccount.getSetting_value().trim();
            if (merchant.getAccount_number().equals(stock_account_number)) {
                return GeneralException.getError("113", GeneralException.ERRORS_113);
            }

            // First determine the gateway by msisdn
            String gateway_id = DoPayGateway.getGatewayIdByMsisdn(payee_number, jdbcTemplate);
            if (gateway_id == null) {
                return GeneralException.getError(
                        "118", String.format(GeneralException.ERRORS_118, payee_number));
            }

            // Get this merchant by id.
            Transaction newTx = new Transaction();
            newTx.setGateway_id(gateway_id);
            newTx.setOriginate_ip(origanting_ip);
            newTx.setOriginal_amount(amount);
            newTx.setPayer_number(payee_number);
            newTx.setStatus("PENDING");
            newTx.setMerchant_id(merchant.getId() + "");
            newTx.setTx_description(merchant.getShort_name());
            newTx.setTx_merchant_description(description);
            newTx.setTx_type(Transaction.TX_TYPE_PAYOUT);
            String tx_id = Common.generateUuid();
            if (gateway_id.equals(AirtelMoneyOpenApiPaymentGateway.gateway_id)) {
                tx_id = getAirtelOpenApiId(merchant_number);
            }
            newTx.setTx_unique_id(tx_id);
            newTx.setTx_merchant_ref(reference);
            newTx.setCallback_url(callback_url);
            // First get the charging method
            GatewayChargeDetails gwChargingDetails =
                    DoPayGateway.getGatewayChargeDetailsById(
                            jdbcTemplate, gateway_id, merchant.getId());
            newTx.setCharging_method(gwChargingDetails.getCustomerOutboundChargeMethod());
            Double charges = DoPayGateway.getCustomerOutboundCharges(amount, gwChargingDetails);
            Double tx_cost = DoPayGateway.getCostOfOutboundCharges(amount, gwChargingDetails);

            // First check if their is enough balance.
            ArrayList<Balance> balances =
                    Common.getMerchantBalances(merchant.getId() + "", jdbcTemplate);

            for (Balance b : balances) {
                if (b.getGateway_id().equals(gateway_id)) {
                    if ((charges + amount) > b.getAmount()) {
                        // Insufficient funds.
                        return GeneralException.getError(
                                "111",
                                String.format(
                                        GeneralException.ERRORS_111,
                                        b.getAmount(),
                                        b.getCode() + " Balances: " + balances.size()));
                    }
                }
            }

            newTx.setCharges(charges);
            newTx.setTx_cost(tx_cost);
            newTx.setTx_request_trace("");
            newTx.setTx_update_trace("");
            newTx.setTx_gateway_ref("");

            // Audit D1: optional idempotency-key support on the legacy v1 money endpoints - see
            // the matching comment in doMobileMoneyPayIn above. A replay returns the previously
            // recorded response instead of re-running money movement (and never double-reserves).
            String idempotencyKey = headerValue(request, "Idempotency-Key", "X-Idempotency-Key");
            if (!isBlank(idempotencyKey)) {
                Optional<String> replayed =
                        idempotencyService.findExistingBody(
                                merchant_number, idempotencyKey, requestBody);
                if (replayed.isPresent()) {
                    return replayed.get();
                }
            }

            // Managed payments own approval, reservation and verified settlement. The v1
            // wrapper must not add a second hold or post/capture on HTTP acceptance.
            if (net.citotech.cito.gateway.MobileMoneyCompatibilityBridge.manages(newTx)) {
                String result = Common.doPayOut(newTx, merchant, jdbcTemplate, transactionManager);
                if (!isBlank(idempotencyKey)) {
                    idempotencyService.recordBody(
                            merchant_number, idempotencyKey, requestBody, result);
                }
                return result;
            }

            // Payout risk-control parity (V34): the raw v1 payout path previously bypassed
            // PayoutControlService entirely, so a configured daily/monthly/per-transaction or
            // beneficiary-velocity limit (or a first-beneficiary review trigger) could be evaded
            // by calling /api/v1 instead of /api/v2. Evaluate the same controls here, BEFORE
            // reserve/execute; when approval is required the payout is parked in
            // payout_approval_queue and a 000 envelope is returned so v1 clients treat it as
            // accepted, mirroring v2's APPROVAL_PENDING. With no control row (or a disabled one)
            // this returns EXECUTE and the pre-existing behavior is unchanged.
            net.citotech.cito.payout.PayoutControlService.PayoutEvaluation payoutControl =
                    evaluateV1PayoutControl(
                            merchant,
                            merchant_number,
                            amount_string,
                            payee_number,
                            gateway_id,
                            reference,
                            description,
                            callback_url);
            if (payoutControl != null && payoutControl.isApprovalRequired()) {
                String approvalMessage =
                        "Payout requires maker-checker approval: " + payoutControl.reasonCode();
                String approvalResponse = GeneralSuccessResponse.getMessage("000", approvalMessage);
                if (!isBlank(idempotencyKey)) {
                    idempotencyService.recordBody(
                            merchant_number, idempotencyKey, requestBody, approvalResponse);
                }
                return approvalResponse;
            }

            // Audit A8: reserve-then-capture on the v1 payout path, mirroring
            // PaymentOrchestrationService.payout and the batch-payout cron. Hold the payout
            // amount (+ charges) in the ledger before the provider call so concurrent payouts
            // cannot overspend the same float; capture once the call resolves, release if it
            // throws. Idempotent per attempt: the reservation reference includes the unique
            // transaction id, so a retried merchant reference gets its own hold and the failed
            // attempt's release never leaks onto a newer attempt.
            // BigDecimal throughout (money-path hardening): the previous expression performed
            // `amount + charges` in double precision before converting to MoneyAmount, which is a
            // rounding hazard for money that then feeds ledgerService.reserve. Both operands are
            // now converted via MoneyAmount before addition.
            // Charges can legally be zero (flat-fee gateways, promotions), but MoneyAmount.of
            // rejects non-positive values; convert-only when present and positive.
            java.math.BigDecimal reservedAmount =
                    net.citotech.cito.money.MoneyAmount.of(amount_string)
                            .asBigDecimal()
                            .add(
                                    (charges == null || charges <= 0)
                                            ? net.citotech.cito.money.MoneyAmount.zero()
                                                    .asBigDecimal()
                                            : net.citotech.cito.money.MoneyAmount.of(
                                                            String.valueOf(charges))
                                                    .asBigDecimal());
            String reservationCurrency =
                    newTx.getCurrency() == null || newTx.getCurrency().isEmpty()
                            ? "UGX"
                            : newTx.getCurrency().trim().toUpperCase();
            String reservationReference =
                    "v1-payout-reserve:"
                            + merchant_number
                            + ":"
                            + reference
                            + ":"
                            + newTx.getTx_unique_id();
            ledgerService.reserve(
                    reservationReference,
                    merchant.getId(),
                    reference,
                    reservedAmount,
                    reservationCurrency);

            String result;
            try {
                result = Common.doPayOut(newTx, merchant, jdbcTemplate, transactionManager);

                // Audit A1/B1: see the matching comment in doMobileMoneyPayIn above.
                legacyLedgerPostingService.postPaymentEntries(
                        Transaction.TX_TYPE_PAYOUT, gateway_id, merchant, newTx, amount, charges);

                ledgerService.captureReservation(reservationReference);
            } catch (RuntimeException payoutEx) {
                ledgerService.releaseReservation(reservationReference);
                throw payoutEx;
            }

            if (!isBlank(idempotencyKey)) {
                idempotencyService.recordBody(merchant_number, idempotencyKey, requestBody, result);
            }

            return result;

        } catch (Exception ex) {

            Logger.getLogger(AuthenticationController.class.getName())
                    .log(Level.SEVERE, "GENERAL INTERNAL ERROR: " + ex.getMessage(), ex);
            return GeneralException.getError("102", GeneralException.ERRORS_102);
        }
    }

    String getAirtelOpenApiId(String merchant_number) {
        String tx_id = Common.generateUuid();
        if (tx_id.length() >= 20) {
            tx_id = merchant_number + "-" + tx_id.substring(0, 10);
        }
        return tx_id;
    }

    /*
     * API to check the status of an earlier submitted transaction
     */
    @PostMapping(path = "/doTransactionCheckStatus")
    public String doTransactionCheckStatus(
            @RequestBody String requestBody,
            HttpServletRequest request,
            HttpServletResponse response) {
        // Set the response header

        try {
            // Ensure that we have valid JSON data.
            JSONObject sObject;
            try {
                sObject = new JSONObject(requestBody);
            } catch (JSONException e) {
                return GeneralException.getError(
                        "124", String.format(GeneralException.ERRORS_124, ""));
            }

            List<String> fields = new ArrayList<>();
            fields.add("merchant_number");
            fields.add("reference");
            fields.add("signature");

            List<String> missingFields = missingJsonFields(fields, sObject);
            if (missingFields.size() > 0) {
                String missing_f = "";
                for (String s : missingFields) {
                    missing_f += s + ", ";
                }
                missing_f = missing_f.substring(0, (missing_f.length() - 2));
                return GeneralException.getError(
                        "114", String.format(GeneralException.ERRORS_114, missing_f));
            }

            String signatureBase64 = sObject.getString("signature");
            String reference = sObject.getString("reference");
            String merchant_number = sObject.getString("merchant_number");

            // Get this merchant
            Merchant merchant =
                    Common.getMerchantByAccountNumber(merchant_number + "", jdbcTemplate);
            if (merchant == null) {
                return GeneralException.getError(
                        "109",
                        String.format(GeneralException.ERRORS_109, "Merchant", merchant_number));
            }

            // Check if the user's account is suspended
            if (merchant.getStatus().equals("SUSPENDED")) {
                return GeneralException.getError("137", GeneralException.ERRORS_137);
            }

            // Verify signature
            String signedData = merchant_number + reference;
            String sigError =
                    SignatureVerificationService.verify(merchant, signedData, signatureBase64);
            if (sigError != null) {
                return sigError;
            }
            apiBilling.admitted(merchant.getId(), null, "PRODUCTION");

            // Now check if the merchant is not suspended
            if (!merchant.getStatus().equals("ACTIVE")) {
                return GeneralException.getError("119", GeneralException.ERRORS_119);
            }

            // Check if this API is allowed.
            String[] allowed_apis = merchant.getAllowed_apis();
            Boolean isAllowedToAccessApi = false;
            for (String api : allowed_apis) {
                if (api.equals(Common.API_TRANSACTION_CHECKSTATUS)) {
                    isAllowedToAccessApi = true;
                }
            }

            if (!isAllowedToAccessApi) {
                return GeneralException.getError(
                        "120",
                        String.format(
                                GeneralException.ERRORS_120, Common.API_TRANSACTION_CHECKSTATUS));
            }

            String sql =
                    "SELECT * FROM `"
                            + Common.DB_TABLE_MERCHANT_TRANSACTION_LOG
                            + "`"
                            + " WHERE tx_merchant_ref=:tx_merchant_ref AND merchant_id=:merchant_id";
            MapSqlParameterSource parameters = new MapSqlParameterSource();
            parameters.addValue("tx_merchant_ref", reference);
            parameters.addValue("merchant_id", merchant.getId());

            RowMapper<Transaction> rm = Common.getTransactionRowMapper();

            List<Transaction> listTransactions = jdbcTemplate.query(sql, parameters, rm);
            if (listTransactions.size() > 0) {
                Transaction t = listTransactions.get(0);
                GateWayResponse pResponse_ = new GateWayResponse();
                pResponse_.setHttpStatus("200");
                pResponse_.setStatus("OK");
                pResponse_.setRequestTrace("");
                pResponse_.setTransactionStatus(t.getStatus());
                pResponse_.setNetworkId(t.getTx_gateway_ref());
                pResponse_.setMessage("");
                pResponse_.setOurUniqueTxId(t.getTx_unique_id());
                return GeneralSuccessResponse.getApiTxMessage(
                        "000", GeneralSuccessResponse.SUCCESS_000, pResponse_);
            } else {
                return GeneralException.getError(
                        "109",
                        String.format(GeneralException.ERRORS_109, "Transaction", reference));
            }

        } catch (Exception ex) {

            Logger.getLogger(AuthenticationController.class.getName())
                    .log(Level.SEVERE, "GENERAL INTERNAL ERROR: " + ex.getMessage(), ex);
            return GeneralException.getError("102", GeneralException.ERRORS_102);
        }
    }

    /*
     * API to check the status of an earlier submitted transaction
     */
    @PostMapping(path = "/doSafaricomPayCallback")
    public String doSafaricomPayCallback(
            @RequestBody String requestBody,
            HttpServletRequest request,
            HttpServletResponse response) {
        // Set the response header
        // PII masking (compliance I4): provider callback payloads can echo payer/payee MSISDNs;
        // only the masked form reaches the logs. The raw body is still used for parsing/updates.
        String maskedBody = maskMsisdnsInPayload(requestBody);
        Logger.getLogger(AuthenticationController.class.getName())
                .log(Level.INFO, "SAFARICOM API CALLBACK: " + maskedBody, maskedBody);
        try {
            // Ensure that we have valid JSON data.
            JSONObject sObject;
            try {
                sObject = new JSONObject(requestBody);
            } catch (JSONException e) {
                return GeneralException.getError(
                        "124", String.format(GeneralException.ERRORS_124, ""));
            }

            JSONObject body = sObject.getJSONObject("Body");
            if (body.isNull("stkCallback")) {
                return GeneralException.getError(
                        "114", String.format(GeneralException.ERRORS_114, "stkCallback"));
            }
            JSONObject stkCallback = body.getJSONObject("stkCallback");
            String transactionRef = "";
            int ResultCode = 0;
            Double amount = 0.0;
            String transactionCompletionDate = "";
            String CheckoutRequestID = "";
            String networkRef_ = "";

            if (stkCallback.isNull("CheckoutRequestID")) {
                return GeneralException.getError(
                        "114", String.format(GeneralException.ERRORS_114, "MerchantRequestID"));
            }
            if (stkCallback.isNull("MerchantRequestID")) {
                return GeneralException.getError(
                        "114", String.format(GeneralException.ERRORS_114, "MerchantRequestID"));
            }
            if (stkCallback.isNull("ResultCode")) {
                return GeneralException.getError(
                        "114", String.format(GeneralException.ERRORS_114, "ResultCode"));
            }
            if (stkCallback.isNull("CallbackMetadata")) {
                return GeneralException.getError(
                        "114", String.format(GeneralException.ERRORS_114, "CallbackMetadata"));
            }
            CheckoutRequestID = stkCallback.getString("CheckoutRequestID");
            final String networkRef = CheckoutRequestID;

            transactionRef = stkCallback.getString("MerchantRequestID");
            ResultCode = stkCallback.getInt("ResultCode");
            final int resultCodeFinal = ResultCode;

            if (resultCodeFinal != 0) {
                TransactionTemplate failTemplate = new TransactionTemplate(transactionManager);
                String failResult =
                        failTemplate.execute(
                                new TransactionCallback<String>() {
                                    @Override
                                    public String doInTransaction(TransactionStatus status) {
                                        try {
                                            Transaction tx =
                                                    Common.getTxByNetworkRef(
                                                            networkRef, jdbcTemplate);
                                            if (tx == null) {
                                                Logger.getLogger(
                                                                AuthenticationController.class
                                                                        .getName())
                                                        .log(
                                                                Level.INFO,
                                                                "SAFARICOM API CALLBACK FAILED - Transaction "
                                                                        + networkRef
                                                                        + " not found: "
                                                                        + maskedBody,
                                                                maskedBody);
                                                return GeneralException.getError(
                                                        "109",
                                                        String.format(
                                                                GeneralException.ERRORS_109,
                                                                "Transaction",
                                                                networkRef));
                                            }
                                            tx.setStatus("FAILED");
                                            tx.setTx_update_trace(requestBody);
                                            tx.setResolved_by("SYSTEM");
                                            Logger.getLogger(
                                                            AuthenticationController.class
                                                                    .getName())
                                                    .log(
                                                            Level.INFO,
                                                            "SAFARICOM API CALLBACK - ResultCode="
                                                                    + resultCodeFinal
                                                                    + ", marking FAILED: "
                                                                    + maskedBody,
                                                            maskedBody);
                                            String results =
                                                    Common.updateTx(
                                                            tx, jdbcTemplate, transactionManager);
                                            if (results.equals("success")) {
                                                return GeneralSuccessResponse.getMessage(
                                                        "000", "Request processed successfully");
                                            } else {
                                                return GeneralException.getError(
                                                        "109", GeneralException.ERRORS_142);
                                            }
                                        } catch (Exception e) {
                                            status.setRollbackOnly();
                                            Logger.getLogger(
                                                            AuthenticationController.class
                                                                    .getName())
                                                    .log(
                                                            Level.SEVERE,
                                                            "INTERNAL ERROR: " + e.getMessage(),
                                                            "");
                                            return GeneralException.getError(
                                                    "102", GeneralException.ERRORS_102);
                                        }
                                    }
                                });
                return failResult;
            }

            JSONObject CallbackMetadata = stkCallback.getJSONObject("CallbackMetadata");
            JSONArray Item = CallbackMetadata.getJSONArray("Item");
            for (int i = 0; i < Item.length(); i++) {
                JSONObject iTem = Item.getJSONObject(i);
                if (!iTem.isNull("Name") && iTem.getString("Name").equals("Amount")) {
                    amount = iTem.getDouble("Value");
                }
                if (!iTem.isNull("Name") && iTem.getString("Name").equals("MpesaReceiptNumber")) {
                    networkRef_ = iTem.getString("Value");
                }
                if (!iTem.isNull("Name") && iTem.getString("Name").equals("TransactionDate")) {
                    transactionCompletionDate = iTem.getString("Value");
                }
            }

            TransactionTemplate template = new TransactionTemplate(transactionManager);
            String result =
                    template.execute(
                            new TransactionCallback<String>() {
                                @Override
                                public String doInTransaction(TransactionStatus status) {
                                    try {

                                        Transaction tx =
                                                Common.getTxByNetworkRef(networkRef, jdbcTemplate);
                                        if (tx == null) {
                                            Logger.getLogger(
                                                            AuthenticationController.class
                                                                    .getName())
                                                    .log(
                                                            Level.INFO,
                                                            "SAFARICOM API CALLBACK - Transaction "
                                                                    + networkRef
                                                                    + " Doesnt exists: "
                                                                    + maskedBody,
                                                            maskedBody);
                                            return GeneralException.getError(
                                                    "109",
                                                    String.format(
                                                            GeneralException.ERRORS_109,
                                                            "Transaction",
                                                            networkRef));
                                        }
                                        tx.setStatus("SUCCESSFUL");
                                        tx.setTx_update_trace(requestBody);
                                        tx.setResolved_by("SYSTEM");
                                        Logger.getLogger(AuthenticationController.class.getName())
                                                .log(
                                                        Level.INFO,
                                                        "SAFARICOM API CALLBACK - Transaction "
                                                                + networkRef
                                                                + " exists: "
                                                                + maskedBody,
                                                        maskedBody);

                                        // tx.setTx_gateway_ref(networkRef);
                                        String results =
                                                Common.updateTx(
                                                        tx, jdbcTemplate, transactionManager);

                                        if (results.equals("success")) {
                                            Logger.getLogger(
                                                            AuthenticationController.class
                                                                    .getName())
                                                    .log(
                                                            Level.INFO,
                                                            "SAFARICOM API CALLBACK - Transaction UPDATED: ",
                                                            requestBody);
                                            return GeneralSuccessResponse.getMessage(
                                                    "000", "Request processed successfully");
                                        } else {
                                            Logger.getLogger(
                                                            AuthenticationController.class
                                                                    .getName())
                                                    .log(
                                                            Level.INFO,
                                                            "SAFARICOM API CALLBACK - Transaction UPDATE FAILED: ",
                                                            requestBody);
                                            return GeneralException.getError(
                                                    "109", GeneralException.ERRORS_142);
                                        }

                                    } catch (Exception e) {
                                        // transactionManager.rollback(status);
                                        status.setRollbackOnly();
                                        Logger.getLogger(AuthenticationController.class.getName())
                                                .log(
                                                        Level.SEVERE,
                                                        "INTERNAL ERROR: " + e.getMessage(),
                                                        "");
                                        return GeneralException.getError(
                                                "102", GeneralException.ERRORS_102);
                                    }
                                }
                            });

            // Transaction tx = Common.getTxByNetworkRef(CheckoutRequestID, jdbcTemplate);
            return result;

        } catch (Exception ex) {

            Logger.getLogger(AuthenticationController.class.getName())
                    .log(Level.SEVERE, "GENERAL INTERNAL ERROR: " + ex.getMessage(), ex);
            return GeneralException.getError("102", GeneralException.ERRORS_102);
        }
    }

    /*
     * API to check the status of an earlier submitted transaction
     */
    @PostMapping(path = "/doSafaricomPayInCallbackResults")
    public String doSafaricomPayInCallbackResults(
            @RequestBody String requestBody,
            HttpServletRequest request,
            HttpServletResponse response) {
        // Set the response header
        // PII masking (compliance I4): only the masked form of the provider callback reaches logs.
        String maskedBody = maskMsisdnsInPayload(requestBody);
        Logger.getLogger(AuthenticationController.class.getName())
                .log(
                        Level.INFO,
                        "SAFARICOM PAYIN API CALLBACK - PAYOUT: " + maskedBody,
                        maskedBody);

        try {
            // Ensure that we have valid JSON data.
            JSONObject sObject;
            try {
                sObject = new JSONObject(requestBody);
            } catch (JSONException e) {
                return GeneralException.getError(
                        "124", String.format(GeneralException.ERRORS_124, ""));
            }

            if (sObject.isNull("Body")) {
                return GeneralException.getError(
                        "114", String.format(GeneralException.ERRORS_114, "Body"));
            }

            JSONObject stkCallback;
            if (sObject.getJSONObject("Body").isNull("stkCallback")) {
                return GeneralException.getError(
                        "114", String.format(GeneralException.ERRORS_114, "stkCallback"));
            }
            stkCallback = sObject.getJSONObject("Body").getJSONObject("stkCallback");
            String transactionRef = "";
            int ResultCode = 0;
            String ResultDesc = "";
            String CheckoutRequestID = "";
            String networkRef = "";
            String msisdn = "";

            if (stkCallback.isNull("CheckoutRequestID")) {
                return GeneralException.getError(
                        "114", String.format(GeneralException.ERRORS_114, "CheckoutRequestID"));
            }

            if (stkCallback.isNull("ResultCode")) {
                return GeneralException.getError(
                        "114", String.format(GeneralException.ERRORS_114, "ResultCode"));
            }

            if (stkCallback.isNull("ResultDesc")) {
                return GeneralException.getError(
                        "114", String.format(GeneralException.ERRORS_114, "ResultDesc"));
            }

            ResultCode = stkCallback.getInt("ResultCode");
            ResultDesc = stkCallback.getString("ResultDesc");
            CheckoutRequestID = stkCallback.getString("CheckoutRequestID");
            if (!stkCallback.isNull("CallbackMetadata")) {
                if (!stkCallback.isNull("Item")) {
                    JSONArray items = stkCallback.getJSONArray("Item");
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject iTemObject = items.getJSONObject(i);
                        if (!iTemObject.isNull("Name") && !iTemObject.isNull("Value")) {
                            String itemName = iTemObject.getString("Name");
                            String itemValue = iTemObject.getString("Value");

                            if (itemName.equals("MpesaReceiptNumber")) {
                                networkRef = itemValue;
                            }
                        }
                    }
                }
            }

            final String networkRefFinal = networkRef;
            final String CheckoutRequestIDFinal = CheckoutRequestID;

            final int ResultCodeFinal = ResultCode;
            final String ResultDescFinal = ResultDesc;
            /*
            if (ResultCode != 0) {
                return GeneralException
                        .getError("143",
                                GeneralException.ERRORS_143+" ");
            } */
            // Continue to process the transaction

            // Get the reference from the stored tmp file

            /*
            String reference = getPayoutConversationIdToken( ConversationID );
            if (reference.isEmpty()) {
                Logger.getLogger(AuthenticationController.class.getName())
                        .log(Level.SEVERE, "GENERAL INTERNAL ERROR: ConversationID "+ConversationID+" not found", "");
                return GeneralException
                        .getError("102", GeneralException.ERRORS_102);
            }
            */
            TransactionTemplate template = new TransactionTemplate(transactionManager);
            String result =
                    template.execute(
                            new TransactionCallback<String>() {
                                @Override
                                public String doInTransaction(TransactionStatus status) {
                                    try {
                                        Transaction tx =
                                                Common.getTxBySafaricomRef(
                                                        CheckoutRequestIDFinal, jdbcTemplate);
                                        if (tx == null) {
                                            Logger.getLogger(
                                                            AuthenticationController.class
                                                                    .getName())
                                                    .log(
                                                            Level.INFO,
                                                            "SAFARICOM API CALLBACK COLLECTIONS- Transaction "
                                                                    + CheckoutRequestIDFinal
                                                                    + " Doesnt exists: "
                                                                    + maskedBody,
                                                            maskedBody);
                                            return GeneralException.getError(
                                                    "109",
                                                    String.format(
                                                            GeneralException.ERRORS_109,
                                                            "Transaction",
                                                            networkRefFinal));
                                        }
                                        if (tx.getStatus().equals("SUCCESSFUL")
                                                || tx.getStatus().equals("FAILED")) {
                                            Logger.getLogger(
                                                            AuthenticationController.class
                                                                    .getName())
                                                    .log(
                                                            Level.INFO,
                                                            "SAFARICOM API CALLBACK COLLECTIONS- Transaction "
                                                                    + CheckoutRequestIDFinal
                                                                    + " already in terminal state, ignoring duplicate callback: "
                                                                    + maskedBody,
                                                            maskedBody);
                                            return GeneralSuccessResponse.getMessage(
                                                    "000", "Request already processed");
                                        }
                                        if (ResultCodeFinal == 0) {
                                            tx.setStatus("SUCCESSFUL");
                                        } else {
                                            tx.setStatus("FAILED");
                                        }
                                        tx.setFinalStatusSet(true);
                                        tx.setTx_update_trace(requestBody);
                                        tx.setResolved_by("SYSTEM");
                                        Logger.getLogger(AuthenticationController.class.getName())
                                                .log(
                                                        Level.INFO,
                                                        "SAFARICOM API CALLBACK COLLECTIONS - Transaction "
                                                                + CheckoutRequestIDFinal
                                                                + " exists: "
                                                                + maskedBody,
                                                        maskedBody);

                                        tx.setTx_gateway_ref(networkRefFinal);
                                        String results =
                                                Common.updateTx(
                                                        tx, jdbcTemplate, transactionManager);

                                        if (tx.getStatus().equals("SUCCESSFUL")
                                                || tx.getStatus().equals("FAILED")) {
                                            Merchant merchant =
                                                    Common.getMerchantById(
                                                            tx.getMerchant_id(), jdbcTemplate);
                                            TxCallback txCallback = new TxCallback(tx, merchant);
                                            txCallback.start(jdbcTemplate, transactionManager);
                                        }

                                        if (results.equals("success")) {
                                            Logger.getLogger(
                                                            AuthenticationController.class
                                                                    .getName())
                                                    .log(
                                                            Level.INFO,
                                                            "SAFARICOM API CALLBACK COLLECTIONS - Transaction UPDATED: ",
                                                            requestBody);
                                            return GeneralSuccessResponse.getMessage(
                                                    "000", "Request processed successfully");
                                        } else {
                                            Logger.getLogger(
                                                            AuthenticationController.class
                                                                    .getName())
                                                    .log(
                                                            Level.INFO,
                                                            "SAFARICOM API CALLBACK COLLECTIONS - Transaction UPDATE FAILED: ",
                                                            requestBody);
                                            return GeneralException.getError(
                                                    "109", GeneralException.ERRORS_142);
                                        }

                                    } catch (Exception e) {
                                        Logger.getLogger(Api.class.getName())
                                                .log(Level.SEVERE, e.getMessage(), e);
                                        // transactionManager.rollback(status);
                                        status.setRollbackOnly();
                                        Logger.getLogger(AuthenticationController.class.getName())
                                                .log(
                                                        Level.SEVERE,
                                                        "INTERNAL ERROR: " + e.getMessage(),
                                                        "");
                                        return GeneralException.getError(
                                                "102", GeneralException.ERRORS_102);
                                    }
                                }
                            });
            // transactionManager.commit();
            // Transaction tx = Common.getTxByNetworkRef(CheckoutRequestID, jdbcTemplate);
            return result;

        } catch (Exception ex) {

            Logger.getLogger(AuthenticationController.class.getName())
                    .log(Level.SEVERE, "GENERAL INTERNAL ERROR: " + ex.getMessage(), ex);
            return GeneralException.getError("102", GeneralException.ERRORS_102);
        }
    }

    /*
     * API to check the status of an earlier submitted transaction
     */
    @PostMapping(path = "/doSafaricomPayOutCallbackResults")
    public String doSafaricomPayOutCallbackResults(
            @RequestBody String requestBody,
            HttpServletRequest request,
            HttpServletResponse response) {
        // Set the response header
        // PII masking (compliance I4): only the masked form of the provider callback reaches logs.
        String maskedBody = maskMsisdnsInPayload(requestBody);
        Logger.getLogger(AuthenticationController.class.getName())
                .log(Level.INFO, "SAFARICOM API CALLBACK - PAYOUT: " + maskedBody, maskedBody);

        try {
            // Ensure that we have valid JSON data.
            JSONObject sObject;
            try {
                sObject = new JSONObject(requestBody);
            } catch (JSONException e) {
                return GeneralException.getError(
                        "124", String.format(GeneralException.ERRORS_124, ""));
            }

            if (sObject.isNull("Result")) {
                return GeneralException.getError(
                        "114", String.format(GeneralException.ERRORS_114, "Result"));
            }

            JSONObject stkCallback = sObject.getJSONObject("Result");
            String transactionRef = "";
            int ResultCode = 0;
            int ResultType = 0;
            String ConversationID = "";
            String OriginatorConversationID = "";

            if (stkCallback.isNull("ConversationID")) {
                return GeneralException.getError(
                        "114", String.format(GeneralException.ERRORS_114, "ConversationID"));
            }
            if (stkCallback.isNull("OriginatorConversationID")) {
                return GeneralException.getError(
                        "114",
                        String.format(GeneralException.ERRORS_114, "OriginatorConversationID"));
            }
            if (stkCallback.isNull("ResultCode")) {
                return GeneralException.getError(
                        "114", String.format(GeneralException.ERRORS_114, "ResultCode"));
            }
            if (stkCallback.isNull("ResultType")) {
                return GeneralException.getError(
                        "114", String.format(GeneralException.ERRORS_114, "ResultType"));
            }

            ResultCode = stkCallback.getInt("ResultCode");
            ResultType = stkCallback.getInt("ResultType");
            ConversationID = stkCallback.getString("ConversationID");
            final String networkRef = ConversationID;
            final String Conversation_ID = ConversationID;

            final int ResultCodeFinal = ResultCode;
            final int ResultTypeFinal = ResultType;
            /*
            if (ResultCode != 0) {
                return GeneralException
                        .getError("143",
                                GeneralException.ERRORS_143+" ");
            } */
            // Continue to process the transaction

            // Get the reference from the stored tmp file

            /*
            String reference = getPayoutConversationIdToken( ConversationID );
            if (reference.isEmpty()) {
                Logger.getLogger(AuthenticationController.class.getName())
                        .log(Level.SEVERE, "GENERAL INTERNAL ERROR: ConversationID "+ConversationID+" not found", "");
                return GeneralException
                        .getError("102", GeneralException.ERRORS_102);
            }
            */
            TransactionTemplate template = new TransactionTemplate(transactionManager);
            String result =
                    template.execute(
                            new TransactionCallback<String>() {
                                @Override
                                public String doInTransaction(TransactionStatus status) {
                                    try {
                                        Transaction tx =
                                                Common.getTxBySafaricomRef(
                                                        Conversation_ID, jdbcTemplate);
                                        if (tx == null) {
                                            Logger.getLogger(
                                                            AuthenticationController.class
                                                                    .getName())
                                                    .log(
                                                            Level.INFO,
                                                            "SAFARICOM API CALLBACK DISBURSEMENT- Transaction "
                                                                    + Conversation_ID
                                                                    + " Doesnt exists: "
                                                                    + maskedBody,
                                                            maskedBody);
                                            return GeneralException.getError(
                                                    "109",
                                                    String.format(
                                                            GeneralException.ERRORS_109,
                                                            "Transaction",
                                                            networkRef));
                                        }
                                        if (ResultCodeFinal == 0 && ResultTypeFinal == 0) {
                                            tx.setStatus("SUCCESSFUL");
                                        } else {
                                            tx.setStatus("FAILED");
                                        }
                                        tx.setFinalStatusSet(true);
                                        tx.setTx_update_trace(requestBody);
                                        tx.setResolved_by("SYSTEM");
                                        Logger.getLogger(AuthenticationController.class.getName())
                                                .log(
                                                        Level.INFO,
                                                        "SAFARICOM API CALLBACK DISBURSEMENT - Transaction "
                                                                + Conversation_ID
                                                                + " exists: "
                                                                + maskedBody,
                                                        maskedBody);

                                        // tx.setTx_gateway_ref(networkRef);
                                        String results =
                                                Common.updateTx(
                                                        tx, jdbcTemplate, transactionManager);

                                        if (tx.getStatus().equals("SUCCESSFUL")
                                                || tx.getStatus().equals("FAILED")) {
                                            Merchant merchant =
                                                    Common.getMerchantById(
                                                            tx.getMerchant_id(), jdbcTemplate);
                                            TxCallback txCallback = new TxCallback(tx, merchant);
                                            txCallback.start(jdbcTemplate, transactionManager);
                                        }

                                        if (results.equals("success")) {
                                            getPayoutConversationIdDeleteFile(Conversation_ID);
                                            Logger.getLogger(
                                                            AuthenticationController.class
                                                                    .getName())
                                                    .log(
                                                            Level.INFO,
                                                            "SAFARICOM API CALLBACK DISBURSEMENT - Transaction UPDATED: ",
                                                            requestBody);
                                            return GeneralSuccessResponse.getMessage(
                                                    "000", "Request processed successfully");
                                        } else {
                                            Logger.getLogger(
                                                            AuthenticationController.class
                                                                    .getName())
                                                    .log(
                                                            Level.INFO,
                                                            "SAFARICOM API CALLBACK DISBURSEMENT - Transaction UPDATE FAILED: ",
                                                            requestBody);
                                            return GeneralException.getError(
                                                    "109", GeneralException.ERRORS_142);
                                        }

                                    } catch (Exception e) {
                                        Logger.getLogger(Api.class.getName())
                                                .log(Level.SEVERE, e.getMessage(), e);
                                        // transactionManager.rollback(status);
                                        status.setRollbackOnly();
                                        Logger.getLogger(AuthenticationController.class.getName())
                                                .log(
                                                        Level.SEVERE,
                                                        "INTERNAL ERROR: " + e.getMessage(),
                                                        "");
                                        return GeneralException.getError(
                                                "102", GeneralException.ERRORS_102);
                                    }
                                }
                            });
            // transactionManager.commit();
            // Transaction tx = Common.getTxByNetworkRef(CheckoutRequestID, jdbcTemplate);
            return result;

        } catch (Exception ex) {

            Logger.getLogger(AuthenticationController.class.getName())
                    .log(Level.SEVERE, "GENERAL INTERNAL ERROR: " + ex.getMessage(), ex);
            return GeneralException.getError("102", GeneralException.ERRORS_102);
        }
    }

    /*
     * API to check the status of an earlier submitted transaction
     */
    @PostMapping(path = "/doSafaricomPayOutCallback")
    public String doSafaricomPayOutCallback(
            @RequestBody String requestBody,
            HttpServletRequest request,
            HttpServletResponse response) {
        // Set the response header
        // PII masking (compliance I4): only the masked form of the provider callback reaches logs.
        String maskedBody = maskMsisdnsInPayload(requestBody);
        Logger.getLogger(AuthenticationController.class.getName())
                .log(Level.INFO, "SAFARICOM API CALLBACK - PAYOUT: " + maskedBody, maskedBody);
        try {
            // Ensure that we have valid JSON data.
            JSONObject sObject;
            try {
                sObject = new JSONObject(requestBody);
            } catch (JSONException e) {
                return GeneralException.getError(
                        "124", String.format(GeneralException.ERRORS_124, ""));
            }

            if (sObject.isNull("Result")) {
                return GeneralException.getError(
                        "114", String.format(GeneralException.ERRORS_114, "Result"));
            }

            JSONObject stkCallback = sObject.getJSONObject("Result");
            String transactionRef = "";
            int ResultCode = 0;
            int ResultType = 0;
            String ConversationID = "";
            String OriginatorConversationID = "";

            if (stkCallback.isNull("ConversationID")) {
                return GeneralException.getError(
                        "114", String.format(GeneralException.ERRORS_114, "ConversationID"));
            }
            if (stkCallback.isNull("OriginatorConversationID")) {
                return GeneralException.getError(
                        "114",
                        String.format(GeneralException.ERRORS_114, "OriginatorConversationID"));
            }
            if (stkCallback.isNull("ResultCode")) {
                return GeneralException.getError(
                        "114", String.format(GeneralException.ERRORS_114, "ResultCode"));
            }
            if (stkCallback.isNull("ResultType")) {
                return GeneralException.getError(
                        "114", String.format(GeneralException.ERRORS_114, "ResultType"));
            }

            ResultCode = stkCallback.getInt("ResultCode");
            ResultType = stkCallback.getInt("ResultType");
            ConversationID = stkCallback.getString("ConversationID");
            final String networkRef = ConversationID;
            final String Conversation_ID = ConversationID;

            final int ResultCodeFinal = ResultCode;
            /*
            if (ResultCode != 0) {
                return GeneralException
                        .getError("143",
                                GeneralException.ERRORS_143+" ");
            } */
            // Continue to process the transaction

            // Get the reference from the stored tmp file

            String reference = getPayoutConversationIdToken(ConversationID);
            if (reference.isEmpty()) {
                Logger.getLogger(AuthenticationController.class.getName())
                        .log(
                                Level.SEVERE,
                                "GENERAL INTERNAL ERROR: ConversationID "
                                        + ConversationID
                                        + " not found",
                                "");
                return GeneralException.getError("102", GeneralException.ERRORS_102);
            }

            TransactionTemplate template = new TransactionTemplate(transactionManager);
            String result =
                    template.execute(
                            new TransactionCallback<String>() {
                                @Override
                                public String doInTransaction(TransactionStatus status) {
                                    try {

                                        Transaction tx =
                                                Common.getTxBySafaricomRef(reference, jdbcTemplate);
                                        if (tx == null) {
                                            Logger.getLogger(
                                                            AuthenticationController.class
                                                                    .getName())
                                                    .log(
                                                            Level.INFO,
                                                            "SAFARICOM API CALLBACK DISBURSEMENT- Transaction "
                                                                    + reference
                                                                    + " Doesnt exists: "
                                                                    + maskedBody,
                                                            maskedBody);
                                            return GeneralException.getError(
                                                    "109",
                                                    String.format(
                                                            GeneralException.ERRORS_109,
                                                            "Transaction",
                                                            networkRef));
                                        }
                                        if (ResultCodeFinal != 0) {
                                            tx.setStatus("FAILED");
                                        } else {
                                            tx.setStatus("SUCCESSFUL");
                                        }
                                        tx.setFinalStatusSet(true);
                                        tx.setTx_update_trace(requestBody);
                                        tx.setResolved_by("SYSTEM");
                                        Logger.getLogger(AuthenticationController.class.getName())
                                                .log(
                                                        Level.INFO,
                                                        "SAFARICOM API CALLBACK DISBURSEMENT - Transaction "
                                                                + reference
                                                                + " exists: "
                                                                + maskedBody,
                                                        maskedBody);

                                        // tx.setTx_gateway_ref(networkRef);
                                        String results =
                                                Common.updateTx(
                                                        tx, jdbcTemplate, transactionManager);

                                        if (results.equals("success")) {
                                            getPayoutConversationIdDeleteFile(Conversation_ID);
                                            Logger.getLogger(
                                                            AuthenticationController.class
                                                                    .getName())
                                                    .log(
                                                            Level.INFO,
                                                            "SAFARICOM API CALLBACK DISBURSEMENT - Transaction UPDATED: ",
                                                            requestBody);
                                            return GeneralSuccessResponse.getMessage(
                                                    "000", "Request processed successfully");
                                        } else {
                                            Logger.getLogger(
                                                            AuthenticationController.class
                                                                    .getName())
                                                    .log(
                                                            Level.INFO,
                                                            "SAFARICOM API CALLBACK DISBURSEMENT - Transaction UPDATE FAILED: ",
                                                            requestBody);
                                            return GeneralException.getError(
                                                    "109", GeneralException.ERRORS_142);
                                        }

                                    } catch (Exception e) {
                                        Logger.getLogger(Api.class.getName())
                                                .log(Level.SEVERE, e.getMessage(), e);
                                        // transactionManager.rollback(status);
                                        status.setRollbackOnly();
                                        Logger.getLogger(AuthenticationController.class.getName())
                                                .log(
                                                        Level.SEVERE,
                                                        "INTERNAL ERROR: " + e.getMessage(),
                                                        "");
                                        return GeneralException.getError(
                                                "102", GeneralException.ERRORS_102);
                                    }
                                }
                            });
            // transactionManager.commit();
            // Transaction tx = Common.getTxByNetworkRef(CheckoutRequestID, jdbcTemplate);
            return result;

        } catch (Exception ex) {

            Logger.getLogger(AuthenticationController.class.getName())
                    .log(Level.SEVERE, "GENERAL INTERNAL ERROR: " + ex.getMessage(), ex);
            return GeneralException.getError("102", GeneralException.ERRORS_102);
        }
    }

    public String getPayoutConversationIdToken(String ConversationID) {
        // C1: was read back from a plaintext <ConversationID>.json file under
        // custom.lockfiledirectory (written by SafariComPaymentGateway.checkStatusResponseStorage).
        // That file is genuinely read here - this resolves a Safaricom TransactionStatusQuery
        // callback's ConversationID back to our own transaction reference - so it wasn't dead
        // code to delete; it's now backed by the provider_conversation_references DB table
        // instead of a per-instance local file. See ProviderConversationReferenceStoreService.
        Optional<String> reference =
                ProviderConversationReferenceStoreRegistry.find(
                        SafariComPaymentGateway.gateway_id, ConversationID);
        if (reference.isEmpty()) {
            Logger.getLogger(SettingsController.class.getName())
                    .log(Level.SEVERE, "ConversationID: " + ConversationID + " DOES NOT EXIST", "");
            return "";
        }
        Logger.getLogger(SettingsController.class.getName())
                .log(Level.INFO, "ConversationID Data Stored " + reference.get(), " ");
        return reference.get();
    }

    private void getPayoutConversationIdDeleteFile(String ConversationID) {
        ProviderConversationReferenceStoreRegistry.delete(
                SafariComPaymentGateway.gateway_id, ConversationID);
    }

    @org.springframework.beans.factory.annotation.Autowired
    private net.citotech.cito.gateway.MobileMoneyRecoveryService mobileMoneyRecovery;

    @PostMapping(path = "/doAirtelMoneyPayInCallback")
    public String doAirtelMoneyPayInCallback(
            @RequestBody String requestBody,
            HttpServletRequest request,
            HttpServletResponse response) {
        try {
            String id = new JSONObject(requestBody).getJSONObject("transaction").getString("id");
            if (!mobileMoneyRecovery.signal("airtel_open_api", id, id)) {
                // Historical requests used our transaction UUID as Airtel transaction.id.
                Transaction tx = Common.getTxByRef(id, jdbcTemplate);
                if (tx == null
                        || !net.citotech.cito.gateway.LegacyGatewayIds.AIRTEL_OPEN_API.equals(
                                tx.getGateway_id())) {
                    response.setStatus(404);
                    return GeneralException.getError("109", "Airtel transaction was not found");
                }
                tx.setFinalStatusSet(
                        false); // Never apply the unauthenticated callback's claimed status.
                Common.updateTx(tx, jdbcTemplate, transactionManager);
            }
            response.setStatus(202);
            return "{\"code\":\"000\",\"message\":\"Status verification scheduled\"}";
        } catch (Exception e) {
            response.setStatus(400);
            return GeneralException.getError("124", "Invalid Airtel callback");
        }
    }

    @PostMapping(path = "/doSafaricomAccountBalanceCallback")
    public String doSafaricomAccountBalanceCallback(
            @RequestBody String requestBody,
            HttpServletRequest request,
            HttpServletResponse response) {
        // PII masking (compliance I4): only the masked form of the provider callback reaches logs.
        String maskedBody = maskMsisdnsInPayload(requestBody);
        Logger.getLogger(AuthenticationController.class.getName())
                .log(Level.INFO, "SAFARICOM BALANCE CALLBACK: " + maskedBody, maskedBody);
        try {
            JSONObject sObject;
            try {
                sObject = new JSONObject(requestBody);
            } catch (JSONException e) {
                return GeneralException.getError(
                        "124", String.format(GeneralException.ERRORS_124, ""));
            }

            if (sObject.isNull("Result")) {
                return GeneralException.getError(
                        "114", String.format(GeneralException.ERRORS_114, "Result"));
            }
            JSONObject result = sObject.getJSONObject("Result");
            int resultCode = result.isNull("ResultCode") ? -1 : result.getInt("ResultCode");
            if (resultCode != 0) {
                Logger.getLogger(AuthenticationController.class.getName())
                        .log(
                                Level.WARNING,
                                "SAFARICOM BALANCE CALLBACK - non-zero ResultCode: " + resultCode,
                                "");
                return GeneralSuccessResponse.getMessage(
                        "000", "Balance result received with non-zero code");
            }

            double balance = 0.0;
            if (!result.isNull("ResultParameters")) {
                JSONObject resultParameters = result.getJSONObject("ResultParameters");
                if (!resultParameters.isNull("ResultParameter")) {
                    JSONArray params = resultParameters.getJSONArray("ResultParameter");
                    for (int i = 0; i < params.length(); i++) {
                        JSONObject param = params.getJSONObject(i);
                        if (!param.isNull("Key")
                                && param.getString("Key").equals("AccountBalance")) {
                            String rawBalance =
                                    param.isNull("Value") ? "" : param.getString("Value");
                            // Format: "Working Account|KES|Available Balance|<amount>|..."
                            String[] parts = rawBalance.split("\\|");
                            if (parts.length >= 4) {
                                try {
                                    balance = Double.parseDouble(parts[3].trim());
                                } catch (NumberFormatException ignored) {
                                }
                            }
                        }
                    }
                }
            }

            final double finalBalance = balance;
            Setting getStockAccount = Common.getSettings("float_stock_account", jdbcTemplate);
            if (getStockAccount == null || getStockAccount.getSetting_value().isEmpty()) {
                return GeneralException.getError("112", GeneralException.ERRORS_112);
            }
            Merchant stockMerchant =
                    Common.getMerchantByAccountNumber(
                            getStockAccount.getSetting_value().trim(), jdbcTemplate);
            if (stockMerchant == null) {
                return GeneralException.getError(
                        "109",
                        String.format(
                                GeneralException.ERRORS_109,
                                "Stock account merchant",
                                getStockAccount.getSetting_value()));
            }

            String sql =
                    "UPDATE "
                            + Common.DB_TABLE_MERCHANT_STATEMENT
                            + " SET safaricom_balance=:balance WHERE merchant_id=:merchant_id";
            MapSqlParameterSource params = new MapSqlParameterSource();
            params.addValue("balance", finalBalance);
            params.addValue("merchant_id", stockMerchant.getId());
            jdbcTemplate.update(sql, params);

            Logger.getLogger(AuthenticationController.class.getName())
                    .log(
                            Level.INFO,
                            "SAFARICOM BALANCE CALLBACK - balance updated: " + finalBalance,
                            "");
            return GeneralSuccessResponse.getMessage("000", "Balance updated successfully");

        } catch (Exception ex) {
            Logger.getLogger(AuthenticationController.class.getName())
                    .log(Level.SEVERE, "GENERAL INTERNAL ERROR: " + ex.getMessage(), ex);
            return GeneralException.getError("102", GeneralException.ERRORS_102);
        }
    }

    @PostMapping(path = "/doSafaricomReversalCallback")
    public String doSafaricomReversalCallback(
            @RequestBody String requestBody,
            HttpServletRequest request,
            HttpServletResponse response) {
        // PII masking (compliance I4): only the masked form of the provider callback reaches logs.
        String maskedBody = maskMsisdnsInPayload(requestBody);
        Logger.getLogger(AuthenticationController.class.getName())
                .log(Level.INFO, "SAFARICOM REVERSAL CALLBACK: " + maskedBody, maskedBody);
        try {
            JSONObject sObject;
            try {
                sObject = new JSONObject(requestBody);
            } catch (JSONException e) {
                return GeneralException.getError(
                        "124", String.format(GeneralException.ERRORS_124, ""));
            }
            if (sObject.isNull("Result")) {
                return GeneralException.getError(
                        "114", String.format(GeneralException.ERRORS_114, "Result"));
            }
            JSONObject result = sObject.getJSONObject("Result");
            int resultCode = result.isNull("ResultCode") ? -1 : result.getInt("ResultCode");
            String transactionId =
                    result.isNull("TransactionID") ? "" : result.getString("TransactionID");
            String conversationId =
                    result.isNull("ConversationID") ? "" : result.getString("ConversationID");
            String resultDesc = result.isNull("ResultDesc") ? "" : result.getString("ResultDesc");

            if (resultCode == 0) {
                Logger.getLogger(AuthenticationController.class.getName())
                        .log(
                                Level.INFO,
                                "SAFARICOM REVERSAL SUCCESS - TransactionID: "
                                        + transactionId
                                        + " ConversationID: "
                                        + conversationId,
                                "");
            } else {
                Logger.getLogger(AuthenticationController.class.getName())
                        .log(
                                Level.WARNING,
                                "SAFARICOM REVERSAL FAILED - ResultCode: "
                                        + resultCode
                                        + " Desc: "
                                        + resultDesc,
                                "");
            }
            return GeneralSuccessResponse.getMessage("000", "Reversal callback received");
        } catch (Exception ex) {
            Logger.getLogger(AuthenticationController.class.getName())
                    .log(Level.SEVERE, "GENERAL INTERNAL ERROR: " + ex.getMessage(), ex);
            return GeneralException.getError("102", GeneralException.ERRORS_102);
        }
    }

    /*
     * API to retrieve account KYC info (name lookup) for a given MSISDN
     */
    @PostMapping(path = "/doGetAccountInfo")
    public String doGetAccountInfo(
            @RequestBody String requestBody,
            HttpServletRequest request,
            HttpServletResponse response) {
        try {
            JSONObject sObject;
            try {
                sObject = new JSONObject(requestBody);
            } catch (JSONException e) {
                return GeneralException.getError(
                        "124", String.format(GeneralException.ERRORS_124, ""));
            }

            List<String> fields = new ArrayList<>();
            fields.add("merchant_number");
            fields.add("signature");
            fields.add("msisdn");
            List<String> missingFields = missingJsonFields(fields, sObject);
            if (missingFields.size() > 0) {
                String missing_f = "";
                for (String s : missingFields) missing_f += s + ", ";
                missing_f = missing_f.substring(0, missing_f.length() - 2);
                return GeneralException.getError(
                        "114", String.format(GeneralException.ERRORS_114, missing_f));
            }

            String merchant_number = sObject.getString("merchant_number");
            String signatureBase64 = sObject.getString("signature");
            String msisdn = sObject.getString("msisdn");

            Merchant merchant = Common.getMerchantByAccountNumber(merchant_number, jdbcTemplate);
            if (merchant == null) {
                return GeneralException.getError(
                        "109",
                        String.format(GeneralException.ERRORS_109, "Merchant", merchant_number));
            }
            if (merchant.getStatus().equals("SUSPENDED")) {
                return GeneralException.getError("137", GeneralException.ERRORS_137);
            }
            String sigError =
                    SignatureVerificationService.verify(merchant, merchant_number, signatureBase64);
            if (sigError != null) return sigError;
            apiBilling.admitted(merchant.getId(), null, "PRODUCTION");
            if (!merchant.getStatus().equals("ACTIVE")) {
                return GeneralException.getError("119", GeneralException.ERRORS_119);
            }

            boolean allowed = false;
            for (String api : merchant.getAllowed_apis()) {
                if (api.equals(Common.API_ACCOUNT_VALIDATION)) {
                    allowed = true;
                    break;
                }
            }
            if (!allowed) {
                return GeneralException.getError(
                        "120",
                        String.format(GeneralException.ERRORS_120, Common.API_ACCOUNT_VALIDATION));
            }

            net.citotech.cito.Model.AccountInfo info =
                    DoPayGateway.getAccountInfo(msisdn, jdbcTemplate);
            if (info == null) {
                return GeneralException.getError("102", GeneralException.ERRORS_102);
            }

            JSONObject result = new JSONObject();
            result.put("msisdn", msisdn);
            result.put("first_name", info.getFirstName() != null ? info.getFirstName() : "");
            result.put("last_name", info.getLastName() != null ? info.getLastName() : "");
            result.put("name", info.getProvided_name() != null ? info.getProvided_name() : "");
            result.put("status", info.getStatus() != null ? info.getStatus() : "");
            return GeneralSuccessResponse.getMessage("000", result.toString());

        } catch (Exception ex) {
            Logger.getLogger(AuthenticationController.class.getName())
                    .log(Level.SEVERE, "GENERAL INTERNAL ERROR: " + ex.getMessage(), ex);
            return GeneralException.getError("102", GeneralException.ERRORS_102);
        }
    }

    /*
     * API to retrieve merchant balances
     */
    @PostMapping(path = "/doGetBalances")
    public String doGetBalances(
            @RequestBody String requestBody,
            HttpServletRequest request,
            HttpServletResponse response) {
        // Set the response header

        try {
            // Ensure that we have valid JSON data.
            JSONObject sObject;
            try {
                sObject = new JSONObject(requestBody);
            } catch (JSONException e) {
                return GeneralException.getError(
                        "124", String.format(GeneralException.ERRORS_124, ""));
            }

            List<String> fields = new ArrayList<>();
            fields.add("merchant_number");
            fields.add("signature");

            List<String> missingFields = missingJsonFields(fields, sObject);
            if (missingFields.size() > 0) {
                String missing_f = "";
                for (String s : missingFields) {
                    missing_f += s + ", ";
                }
                missing_f = missing_f.substring(0, (missing_f.length() - 2));
                return GeneralException.getError(
                        "114", String.format(GeneralException.ERRORS_114, missing_f));
            }

            String signatureBase64 = sObject.getString("signature");
            String merchant_number = sObject.getString("merchant_number");

            // Get this merchant
            Merchant merchant =
                    Common.getMerchantByAccountNumber(merchant_number + "", jdbcTemplate);
            if (merchant == null) {
                return GeneralException.getError(
                        "109",
                        String.format(GeneralException.ERRORS_109, "Merchant", merchant_number));
            }

            // Check if the user's account is suspended
            if (merchant.getStatus().equals("SUSPENDED")) {
                return GeneralException.getError("137", GeneralException.ERRORS_137);
            }

            // Verify signature
            String signedData = merchant_number;
            String sigError =
                    SignatureVerificationService.verify(merchant, signedData, signatureBase64);
            if (sigError != null) {
                return sigError;
            }
            apiBilling.admitted(merchant.getId(), null, "PRODUCTION");

            // Now check if the merchant is not suspended
            if (!merchant.getStatus().equals("ACTIVE")) {
                return GeneralException.getError("119", GeneralException.ERRORS_119);
            }

            // Check if this API is allowed.
            String[] allowed_apis = merchant.getAllowed_apis();
            Boolean isAllowedToAccessApi = false;
            for (String api : allowed_apis) {
                if (api.equals(Common.API_BALANCE_CHECK)) {
                    isAllowedToAccessApi = true;
                }
            }

            if (!isAllowedToAccessApi) {
                return GeneralException.getError(
                        "120",
                        String.format(GeneralException.ERRORS_120, Common.API_BALANCE_CHECK));
            }

            List<Balance> balances =
                    Common.getMerchantBalances(merchant.getId() + "", jdbcTemplate);

            JSONArray jArray = new JSONArray();
            for (Balance b : balances) {
                JSONObject bObject = new JSONObject();
                bObject.put("name", b.getCode());
                bObject.put("amount", b.getAmount());
                // bObject.put("type", b.getBalance_type()[0]);
                bObject.put("base_currency", b.getBalance_type()[1]);
                jArray.put(bObject);
            }

            return GeneralSuccessResponse.getApiTxBalances(
                    "000", GeneralSuccessResponse.SUCCESS_000, jArray);

        } catch (Exception ex) {

            Logger.getLogger(AuthenticationController.class.getName())
                    .log(Level.SEVERE, "GENERAL INTERNAL ERROR: " + ex.getMessage(), ex);
            return GeneralException.getError("102", GeneralException.ERRORS_102);
        }
    }

    /*
     * API to send SMS
     */
    @PostMapping(path = "/doSendSms")
    public String doSendSms(
            @RequestBody String requestBody,
            HttpServletRequest request,
            HttpServletResponse response) {
        // Set the response header

        try {
            // Ensure that we have valid JSON data.
            JSONObject sObject;
            try {
                sObject = new JSONObject(requestBody);
            } catch (JSONException e) {
                return GeneralException.getError(
                        "124", String.format(GeneralException.ERRORS_124, ""));
            }

            List<String> fields = new ArrayList<>();
            fields.add("merchant_number");
            fields.add("recipients");
            fields.add("content");
            fields.add("signature");

            List<String> missingFields = missingJsonFields(fields, sObject);
            if (missingFields.size() > 0) {
                String missing_f = "";
                for (String s : missingFields) {
                    missing_f += s + ", ";
                }
                missing_f = missing_f.substring(0, (missing_f.length() - 2));
                return GeneralException.getError(
                        "114", String.format(GeneralException.ERRORS_114, missing_f));
            }

            String content = sObject.getString("content");
            String recipients = sObject.getString("recipients");
            String merchant_number = sObject.getString("merchant_number");
            String signatureBase64 = sObject.getString("signature");
            String send_time = "";

            if (!sObject.isNull("send_time")) {
                send_time = sObject.getString("send_time");

                // Check send_time is passed
                LocalDateTime scheduledSendTime =
                        LocalDateTime.parse(send_time, SMS_SEND_TIME_FORMAT);
                if (LocalDateTime.now().isAfter(scheduledSendTime)) {
                    return GeneralException.getError("135", GeneralException.ERRORS_135);
                }
            } else {
                send_time = LocalDateTime.now().format(SMS_SEND_TIME_FORMAT);
            }

            // Get this merchant
            Merchant merchant =
                    Common.getMerchantByAccountNumber(merchant_number + "", jdbcTemplate);
            if (merchant == null) {
                return GeneralException.getError(
                        "109",
                        String.format(GeneralException.ERRORS_109, "Merchant", merchant_number));
            }

            // Check if the user's account is suspended
            if (merchant.getStatus().equals("SUSPENDED")) {
                return GeneralException.getError("137", GeneralException.ERRORS_137);
            }

            // Verify signature
            String signedData = merchant_number + content + recipients;
            String sigError =
                    SignatureVerificationService.verify(merchant, signedData, signatureBase64);
            if (sigError != null) {
                return sigError;
            }
            apiBilling.admitted(merchant.getId(), null, "PRODUCTION");

            // Now check if the merchant is not suspended
            if (!merchant.getStatus().equals("ACTIVE")) {
                return GeneralException.getError("119", GeneralException.ERRORS_119);
            }

            // Check if this API is allowed.
            String[] allowed_apis = merchant.getAllowed_apis();
            Boolean isAllowedToAccessApi = false;
            for (String api : allowed_apis) {
                if (api.equals(Common.API_SEND_SMS)) {
                    isAllowedToAccessApi = true;
                }
            }

            if (!isAllowedToAccessApi) {
                return GeneralException.getError(
                        "120", String.format(GeneralException.ERRORS_120, Common.API_SEND_SMS));
            }

            MerchantSms newSms = new MerchantSms();
            newSms.setCreated_by("SYSTEM API");
            newSms.setContent(content);
            newSms.setMerchant_id(BigInteger.valueOf(merchant.getId()));
            newSms.setSend_time(send_time);
            newSms.setGw_response("");
            newSms.setStatus("PENDING");
            newSms.setTrace("");

            // Now get recipients
            int total_recipients = 0;
            String[] recipientsArray = recipients.split(",");

            total_recipients = recipientsArray.length;
            SmsGateway smsgw = new SmsGateway(jdbcTemplate);
            newSms.setSmsgw(smsgw.getGatewayName());

            double charge = smsgw.getCharge(merchant.getId());
            newSms.setCharge(charge);
            newSms.setCost(smsgw.getCost());
            final double total_amount = (charge * total_recipients);

            ArrayList<Balance> balances =
                    Common.getMerchantBalances(merchant.getId() + "", jdbcTemplate);
            // Check whether the user has enough funds
            for (Balance b : balances) {
                if (b.getGateway_id().equals(SmsGateway.getGatewayId())) {
                    if (b.getAmount() < total_amount) {
                        return GeneralException.getError(
                                "111",
                                String.format(
                                        GeneralException.ERRORS_111, b.getAmount(), "SMS Account"));
                    }
                }
            }

            TransactionTemplate template = new TransactionTemplate(transactionManager);
            String result =
                    template.execute(
                            new TransactionCallback<String>() {
                                @Override
                                public String doInTransaction(TransactionStatus status) {
                                    try {
                                        // Now add the user to database
                                        String sql =
                                                "INSERT INTO "
                                                        + Common.DB_TABLE_MERCHANT_SMS
                                                        + " "
                                                        + " SET `merchant_id`=:merchant_id,"
                                                        + " `cost`=:cost, "
                                                        + " `charge`=:charge, "
                                                        + " `created_by`=:created_by,"
                                                        + " `status`=:status,"
                                                        + " `total_recipients`=:total_recipients,"
                                                        + " `content`=:content,"
                                                        + " `gw_response`=:gw_response,"
                                                        + " `smsgw`=:smsgw,"
                                                        + " `trace`=:trace,"
                                                        + " `send_time`=:send_time,"
                                                        + " `total_amount`=:total_amount,"
                                                        + " `recipients`=:recipients";
                                        MapSqlParameterSource parameters =
                                                new MapSqlParameterSource();
                                        newSms.setRecipients(recipients);
                                        newSms.setTotal_recipients(recipientsArray.length);
                                        newSms.setTotal_amount(total_amount);

                                        MerchantSms newSms_ = newSms;
                                        parameters.addValue(
                                                "merchant_id", newSms_.getMerchant_id());
                                        parameters.addValue("created_by", "SMS API");
                                        parameters.addValue("status", newSms_.getStatus());
                                        parameters.addValue(
                                                "total_amount", newSms_.getTotal_amount());
                                        parameters.addValue("charge", newSms_.getCharge());
                                        parameters.addValue("cost", newSms_.getCost());
                                        parameters.addValue(
                                                "total_recipients", newSms_.getTotal_recipients());
                                        parameters.addValue("trace", newSms_.getTrace());
                                        parameters.addValue("content", newSms_.getContent());
                                        parameters.addValue(
                                                "gw_response", newSms_.getGw_response());
                                        parameters.addValue("smsgw", newSms_.getSmsgw());
                                        parameters.addValue("send_time", newSms_.getSend_time());
                                        parameters.addValue("recipients", newSms_.getRecipients());
                                        // Now save the SMS
                                        KeyHolder keyHolder = new GeneratedKeyHolder();
                                        // long userId;
                                        jdbcTemplate.update(sql, parameters, keyHolder);
                                        // Now insert privileges
                                        BigInteger smsId = (BigInteger) keyHolder.getKey();

                                        // TransactionManager.commit(status);
                                        return "success";
                                    } catch (Exception e) {
                                        // transactionManager.rollback(status);
                                        status.setRollbackOnly();
                                        return GeneralException.getError(
                                                "102", GeneralException.ERRORS_102);
                                    }
                                }
                            });

            if (result.equals("success")) {
                return GeneralSuccessResponse.getMessage("000", GeneralSuccessResponse.SUCCESS_000);
            } else {
                return result;
            }

        } catch (Exception ex) {

            Logger.getLogger(AuthenticationController.class.getName())
                    .log(Level.SEVERE, "GENERAL INTERNAL ERROR: " + ex.getMessage(), ex);
            return GeneralException.getError("102", GeneralException.ERRORS_102);
        }
    }

    /*
     * API to add a new admin to the database
     */
    @PostMapping(path = "/testCallbackReception")
    public String testCallbackReception(
            @RequestBody String requestBody,
            HttpServletRequest request,
            HttpServletResponse response) {
        // Set the response header

        try {
            JSONObject sObject = new JSONObject(requestBody);
            Double amount = sObject.getDouble("amount");
            String description = sObject.getString("description");
            String reference = sObject.getString("reference");
            String payee_number = sObject.getString("payer_number");
            String signatureBase64 = sObject.getString("signature");
            String status = sObject.getString("status");
            String completed_on = sObject.getString("completed_on");
            String created_on = sObject.getString("created_on");
            String network_ref = sObject.getString("network_ref");

            return GeneralSuccessResponse.getMessage(
                    "000", GeneralSuccessResponse.SUCCESS_000 + ". Ref: " + reference);

        } catch (JSONException e) {
            return GeneralException.getError(
                    "124", String.format(GeneralException.ERRORS_124, requestBody));
        }
    }

    /**
     * Checks whether {@code amount} falls within the gateway's configured min/max transaction
     * limits. Returns null if OK, or an error JSON string. Settings keys: gw_<name>_api_min_amount
     * / gw_<name>_api_max_amount.
     */
    private String checkGatewayAmountLimits(String gateway_id, Double amount) {
        String prefix = gatewaySettingPrefix(gateway_id);
        if (prefix == null) return null;

        Setting minSetting = Common.getSettings(prefix + "_min_amount", jdbcTemplate);
        Setting maxSetting = Common.getSettings(prefix + "_max_amount", jdbcTemplate);

        double min = 0;
        double max = Double.MAX_VALUE;
        try {
            if (minSetting != null && !minSetting.getSetting_value().isEmpty())
                min = Double.parseDouble(minSetting.getSetting_value().trim());
        } catch (NumberFormatException ignored) {
        }
        try {
            if (maxSetting != null && !maxSetting.getSetting_value().isEmpty())
                max = Double.parseDouble(maxSetting.getSetting_value().trim());
        } catch (NumberFormatException ignored) {
        }

        if (amount < min || amount > max) {
            return GeneralException.getError(
                    "146", String.format(GeneralException.ERRORS_146, amount, min, max));
        }
        return null;
    }

    private String gatewaySettingPrefix(String gateway_id) {
        if (gateway_id == null) return null;
        switch (gateway_id) {
            case "MTNMoMoPaymentGateway":
                return "gw_mtn_api";
            case "AirtelMoneyPaymentGateway":
                return "gw_airtelmoney_api";
            case "AirtelMoneyOpenApiPaymentGateway":
                return "gw_airtelmoney_api";
            case "SafariComPaymentGateway":
                return "gw_safaricom_api";
            default:
                return null;
        }
    }

    /**
     * Builds a v2 {@link net.citotech.cito.api.v2.dto.PaymentRequest} from the raw v1 payout
     * payload and runs it through {@link net.citotech.cito.payout.PayoutControlService#evaluate}.
     * Returns {@code null} when no control row exists, controls are disabled, or the control layer
     * errors (fail-open preserves the historical v1 behavior - v1 never had this gate, so an
     * evaluation failure must not start blocking money movement). Returns an {@code
     * APPROVAL_REQUIRED} evaluation when a limit/velocity/first-beneficiary trigger parked the
     * payout so the caller can return without executing.
     */
    private net.citotech.cito.payout.PayoutControlService.PayoutEvaluation evaluateV1PayoutControl(
            Merchant merchant,
            String merchantNumber,
            String amount,
            String payeeNumber,
            String gatewayId,
            String reference,
            String description,
            String callbackUrl) {
        try {
            if (merchant == null || payoutControlService == null) {
                return null;
            }
            net.citotech.cito.api.v2.dto.PaymentRequest request =
                    new net.citotech.cito.api.v2.dto.PaymentRequest();
            request.setMerchantNumber(merchantNumber);
            request.setAmount(amount);
            request.setCurrency("UGX");
            request.setChannel(gatewayId);
            request.setReference(reference);
            request.setDescription(description);
            request.setCallbackUrl(callbackUrl);
            net.citotech.cito.api.v2.dto.PaymentPartyRequest payee =
                    new net.citotech.cito.api.v2.dto.PaymentPartyRequest();
            payee.setType("MSISDN");
            payee.setValue(payeeNumber);
            request.setPayee(payee);
            return payoutControlService.evaluate(request, merchant, "v1-api:" + merchantNumber);
        } catch (Exception ex) {
            Logger.getLogger(Api.class.getName())
                    .log(
                            Level.WARNING,
                            "V1 payout control evaluation failed: " + ex.getMessage(),
                            ex);
            return null;
        }
    }

    /**
     * Masks mobile-money numbers in a raw provider callback payload before it reaches the logs
     * (compliance I4). Keeps the structural debugging context while hiding payer/payee MSISDNs.
     * Idempotent: an already-masked value contains no matching digit run.
     */
    private static String maskMsisdnsInPayload(String payload) {
        if (payload == null || payload.isEmpty()) {
            return payload;
        }
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("\\b(?:\\+?256|254|255|250)\\d{9}\\b")
                        .matcher(payload);
        StringBuffer masked = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(masked, PiiMasking.maskMsdn(matcher.group()));
        }
        matcher.appendTail(masked);
        return masked.toString();
    }

    /**
     * Checks whether the merchant has exceeded their daily or monthly transaction volume limit.
     * Returns null if OK, or an error JSON string. Merchant settings keys: daily_volume_limit,
     * monthly_volume_limit.
     */
    private String checkMerchantVolumeLimit(Merchant merchant, Double amount) {
        try {
            Setting dailyLimitSetting =
                    Common.getMerchantSettings(
                            "daily_volume_limit", merchant.getId(), jdbcTemplate);
            Setting monthlyLimitSetting =
                    Common.getMerchantSettings(
                            "monthly_volume_limit", merchant.getId(), jdbcTemplate);

            if (dailyLimitSetting != null && !dailyLimitSetting.getSetting_value().isEmpty()) {
                double dailyLimit = Double.parseDouble(dailyLimitSetting.getSetting_value().trim());
                String sql =
                        "SELECT COALESCE(SUM(original_amount),0) FROM "
                                + Common.DB_TABLE_MERCHANT_TRANSACTION_LOG
                                + " WHERE merchant_id=:mid AND tx_type=:type"
                                + " AND status IN ('SUCCESSFUL','PENDING')"
                                + " AND DATE(created_on)=CURDATE()";
                MapSqlParameterSource p = new MapSqlParameterSource();
                p.addValue("mid", merchant.getId());
                p.addValue("type", Transaction.TX_TYPE_PAYIN);
                Double dailyTotal = jdbcTemplate.queryForObject(sql, p, Double.class);
                if (dailyTotal == null) dailyTotal = 0.0;
                if ((dailyTotal + amount) > dailyLimit) {
                    return GeneralException.getError(
                            "146",
                            String.format(
                                    GeneralException.ERRORS_146,
                                    amount,
                                    0,
                                    dailyLimit - dailyTotal));
                }
            }

            if (monthlyLimitSetting != null && !monthlyLimitSetting.getSetting_value().isEmpty()) {
                double monthlyLimit =
                        Double.parseDouble(monthlyLimitSetting.getSetting_value().trim());
                String sql =
                        "SELECT COALESCE(SUM(original_amount),0) FROM "
                                + Common.DB_TABLE_MERCHANT_TRANSACTION_LOG
                                + " WHERE merchant_id=:mid AND tx_type=:type"
                                + " AND status IN ('SUCCESSFUL','PENDING')"
                                + " AND YEAR(created_on)=YEAR(NOW()) AND MONTH(created_on)=MONTH(NOW())";
                MapSqlParameterSource p = new MapSqlParameterSource();
                p.addValue("mid", merchant.getId());
                p.addValue("type", Transaction.TX_TYPE_PAYIN);
                Double monthlyTotal = jdbcTemplate.queryForObject(sql, p, Double.class);
                if (monthlyTotal == null) monthlyTotal = 0.0;
                if ((monthlyTotal + amount) > monthlyLimit) {
                    return GeneralException.getError(
                            "146",
                            String.format(
                                    GeneralException.ERRORS_146,
                                    amount,
                                    0,
                                    monthlyLimit - monthlyTotal));
                }
            }
        } catch (Exception e) {
            Logger.getLogger(Api.class.getName())
                    .log(Level.WARNING, "Volume limit check failed: " + e.getMessage(), e);
        }
        return null;
    }
}
