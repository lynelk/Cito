package net.citotech.cito;

import net.citotech.cito.Model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * @author josephtabajjwa
 */
public class DoPayGateway {
    private boolean requireRecordedAirtelScope;

    public DoPayGateway withRecordedAirtelScope() {
        this.requireRecordedAirtelScope = true;
        return this;
    }

    // Audit H1: converted from java.util.logging to SLF4J (money-path class).
    private static final Logger logger = LoggerFactory.getLogger(DoPayGateway.class);

    MTNMoMoPaymentGateway mtn_mmpgw;
    AirtelMoneyPaymentGateway airtelmm_mmpgw;
    AirtelMoneyOpenApiPaymentGateway airteloapimm_mmpgw;
    SafariComPaymentGateway safaricom_mmpgw;

    @Value("${custom.gatewaystate}")
    public static String gatewaystate;

    private static String settingValue(
            NamedParameterJdbcTemplate jdbcTemplate, String name, String fallback) {
        Setting setting = Common.getSettings(name, jdbcTemplate);
        if (setting == null
                || setting.getSetting_value() == null
                || setting.getSetting_value().trim().isEmpty()) {
            return fallback;
        }
        return setting.getSetting_value().trim();
    }

    private static boolean hasBlank(String... values) {
        for (String value : values) {
            if (value == null || value.trim().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static void logBalanceWarning(String message, Exception ex) {
        if (ex == null) {
            logger.warn(message);
        } else {
            logger.warn(message, ex);
        }
    }

    static String getGatewayIdByMsisdn(String msisdn) {

        if (MTNMoMoPaymentGateway.isValidMisdn(msisdn)) {
            return MTNMoMoPaymentGateway.gateway_id;
        }

        if (AirtelMoneyPaymentGateway.isValidMisdn(msisdn)) {
            return AirtelMoneyPaymentGateway.gateway_id;
        }

        if (AirtelMoneyOpenApiPaymentGateway.isValidMisdn(msisdn)) {
            return AirtelMoneyOpenApiPaymentGateway.gateway_id;
        }

        if (SafariComPaymentGateway.isValidMisdn(msisdn)) {
            return SafariComPaymentGateway.gateway_id;
        }

        // Check other supported gateways like Airtel
        return null;
    }

    public static String getGatewayIdByMsisdn(
            String msisdn, NamedParameterJdbcTemplate jdbcTemplate) {

        if (MTNMoMoPaymentGateway.isValidMisdn(msisdn)) {
            return MTNMoMoPaymentGateway.gateway_id;
        }

        if (SafariComPaymentGateway.isValidMisdn(msisdn)) {
            return SafariComPaymentGateway.gateway_id;
        }

        Setting airtelApiSetting = Common.getSettings("gw_airtelmoney_use_open_api", jdbcTemplate);
        String use_open_api =
                (airtelApiSetting != null) ? airtelApiSetting.getSetting_value().trim() : "";

        // Do another gateway.
        if (use_open_api.equals("yes")) {
            if (AirtelMoneyOpenApiPaymentGateway.isValidMisdn(msisdn)) {
                return AirtelMoneyOpenApiPaymentGateway.gateway_id;
            }
        } else {
            if (AirtelMoneyPaymentGateway.isValidMisdn(msisdn)) {
                return AirtelMoneyPaymentGateway.gateway_id;
            }
        }

        // Check other supported gateways like Airtel
        return null;
    }

    public static GatewayChargeDetails getGatewayChargeDetailsById(
            NamedParameterJdbcTemplate jdbcTemplate, String gateway_id) {
        if (MTNMoMoPaymentGateway.gateway_id.equals(gateway_id)) {
            GatewayChargeDetails gwChargeDetails = new GatewayChargeDetails();
            Setting customerChargeMethod =
                    Common.getSettings("gw_mtn_api_customer_charge_method", jdbcTemplate);

            Setting customerOfPayInMethod =
                    Common.getSettings("gw_mtn_api_customer_charge_inbound_method", jdbcTemplate);
            Setting customerPayOutMethod =
                    Common.getSettings("gw_mtn_api_customer_charge_outbound_method", jdbcTemplate);
            Setting customerInboundCharge =
                    Common.getSettings("gw_mtn_api_customer_charge_inbound", jdbcTemplate);
            Setting customerOutboundCharge =
                    Common.getSettings("gw_mtn_api_customer_charge_outbound", jdbcTemplate);

            gwChargeDetails.setCustomerInboundChargeMethod(
                    customerOfPayInMethod.getSetting_value());
            gwChargeDetails.setCustomerInboundCharge(
                    Double.parseDouble(customerInboundCharge.getSetting_value()));
            gwChargeDetails.setCustomerOutboundChargeMethod(
                    customerPayOutMethod.getSetting_value());
            gwChargeDetails.setCustomerOutboundCharge(
                    Double.parseDouble(customerOutboundCharge.getSetting_value()));

            Setting costOfPayInMethod =
                    Common.getSettings("gw_mtn_api_cost_of_inbound_payment_method", jdbcTemplate);
            Setting costOfPayOutMethod =
                    Common.getSettings("gw_mtn_api_cost_of_outbound_payment_method", jdbcTemplate);
            Setting costOfInboundPayment =
                    Common.getSettings("gw_mtn_api_cost_of_inbound_payment", jdbcTemplate);
            Setting costOfOutboundPayment =
                    Common.getSettings("gw_mtn_api_cost_of_outbound_payment", jdbcTemplate);

            gwChargeDetails.setCostOfPayInMethod(costOfPayInMethod.getSetting_value());
            gwChargeDetails.setCostOfPayOutMethod(costOfPayOutMethod.getSetting_value());
            gwChargeDetails.setCostOfInboundPayment(
                    Double.parseDouble(costOfInboundPayment.getSetting_value()));
            gwChargeDetails.setCostOfOutboundPayment(
                    Double.parseDouble(costOfOutboundPayment.getSetting_value()));
            gwChargeDetails.setCustomerChargeMethod(customerChargeMethod.getSetting_value());

            return gwChargeDetails;
        }

        // Do another gateway.

        if (AirtelMoneyPaymentGateway.gateway_id.equals(gateway_id)
                || AirtelMoneyOpenApiPaymentGateway.gateway_id.equals(gateway_id)) {
            GatewayChargeDetails gwChargeDetails = new GatewayChargeDetails();
            Setting customerChargeMethod =
                    Common.getSettings("gw_airtelmoney_api_customer_charge_method", jdbcTemplate);

            Setting customerOfPayInMethod =
                    Common.getSettings(
                            "gw_airtelmoney_api_customer_charge_inbound_method", jdbcTemplate);
            Setting customerPayOutMethod =
                    Common.getSettings(
                            "gw_airtelmoney_api_customer_charge_outbound_method", jdbcTemplate);
            Setting customerInboundCharge =
                    Common.getSettings("gw_airtelmoney_api_customer_charge_inbound", jdbcTemplate);
            Setting customerOutboundCharge =
                    Common.getSettings("gw_airtelmoney_api_customer_charge_outbound", jdbcTemplate);

            gwChargeDetails.setCustomerInboundChargeMethod(
                    customerOfPayInMethod.getSetting_value());
            gwChargeDetails.setCustomerInboundCharge(
                    Double.parseDouble(customerInboundCharge.getSetting_value()));
            gwChargeDetails.setCustomerOutboundChargeMethod(
                    customerPayOutMethod.getSetting_value());
            gwChargeDetails.setCustomerOutboundCharge(
                    Double.parseDouble(customerOutboundCharge.getSetting_value()));

            Setting costOfPayInMethod =
                    Common.getSettings(
                            "gw_airtelmoney_api_cost_of_inbound_payment_method", jdbcTemplate);
            Setting costOfPayOutMethod =
                    Common.getSettings(
                            "gw_airtelmoney_api_cost_of_outbound_payment_method", jdbcTemplate);
            Setting costOfInboundPayment =
                    Common.getSettings("gw_airtelmoney_api_cost_of_inbound_payment", jdbcTemplate);
            Setting costOfOutboundPayment =
                    Common.getSettings("gw_airtelmoney_api_cost_of_outbound_payment", jdbcTemplate);

            gwChargeDetails.setCostOfPayInMethod(costOfPayInMethod.getSetting_value());
            gwChargeDetails.setCostOfPayOutMethod(costOfPayOutMethod.getSetting_value());
            gwChargeDetails.setCostOfInboundPayment(
                    Double.parseDouble(costOfInboundPayment.getSetting_value()));
            gwChargeDetails.setCostOfOutboundPayment(
                    Double.parseDouble(costOfOutboundPayment.getSetting_value()));
            gwChargeDetails.setCustomerChargeMethod(customerChargeMethod.getSetting_value());

            return gwChargeDetails;
        }

        if (SafariComPaymentGateway.gateway_id.equals(gateway_id)) {
            GatewayChargeDetails gwChargeDetails = new GatewayChargeDetails();
            Setting customerChargeMethod =
                    Common.getSettings("gw_safaricom_api_customer_charge_method", jdbcTemplate);

            Setting customerOfPayInMethod =
                    Common.getSettings(
                            "gw_safaricom_api_customer_charge_inbound_method", jdbcTemplate);
            Setting customerPayOutMethod =
                    Common.getSettings(
                            "gw_safaricom_api_customer_charge_outbound_method", jdbcTemplate);
            Setting customerInboundCharge =
                    Common.getSettings("gw_safaricom_api_customer_charge_inbound", jdbcTemplate);
            Setting customerOutboundCharge =
                    Common.getSettings("gw_safaricom_api_customer_charge_outbound", jdbcTemplate);

            gwChargeDetails.setCustomerInboundChargeMethod(
                    customerOfPayInMethod.getSetting_value());
            gwChargeDetails.setCustomerInboundCharge(
                    Double.parseDouble(customerInboundCharge.getSetting_value()));
            gwChargeDetails.setCustomerOutboundChargeMethod(
                    customerPayOutMethod.getSetting_value());
            gwChargeDetails.setCustomerOutboundCharge(
                    Double.parseDouble(customerOutboundCharge.getSetting_value()));

            Setting costOfPayInMethod =
                    Common.getSettings(
                            "gw_safaricom_api_cost_of_inbound_payment_method", jdbcTemplate);
            Setting costOfPayOutMethod =
                    Common.getSettings(
                            "gw_safaricom_api_cost_of_outbound_payment_method", jdbcTemplate);
            Setting costOfInboundPayment =
                    Common.getSettings("gw_safaricom_api_cost_of_inbound_payment", jdbcTemplate);
            Setting costOfOutboundPayment =
                    Common.getSettings("gw_safaricom_api_cost_of_outbound_payment", jdbcTemplate);

            gwChargeDetails.setCostOfPayInMethod(costOfPayInMethod.getSetting_value());
            gwChargeDetails.setCostOfPayOutMethod(costOfPayOutMethod.getSetting_value());
            gwChargeDetails.setCostOfInboundPayment(
                    Double.parseDouble(costOfInboundPayment.getSetting_value()));
            gwChargeDetails.setCostOfOutboundPayment(
                    Double.parseDouble(costOfOutboundPayment.getSetting_value()));
            gwChargeDetails.setCustomerChargeMethod(customerChargeMethod.getSetting_value());

            return gwChargeDetails;
        }

        // Check other supported gateways like Airtel
        return null;
    }

    static Setting getMerchantSPecificSetting(
            String setting, long merchant_id, NamedParameterJdbcTemplate jdbcTemplate) {
        Setting s = Common.getMerchantSettings(setting, merchant_id, jdbcTemplate);
        if (s == null || s.getSetting_value().isEmpty()) {
            return null;
        } else {
            return s;
        }
    }

    /**
     * When useMerchantCreds is true, returns the merchant-specific setting only (no global
     * fallback). When useMerchantCreds is false, returns the global setting.
     */
    private static Setting resolveCredentialSetting(
            String name,
            Long merchantId,
            boolean useMerchantCreds,
            NamedParameterJdbcTemplate jdbcTemplate) {
        if (useMerchantCreds && merchantId != null) {
            return Common.getMerchantSettings(name, merchantId, jdbcTemplate);
        }
        return Common.getSettings(name, jdbcTemplate);
    }

    private static String resolveCredentialValue(
            String name,
            Long merchantId,
            boolean useMerchantCreds,
            NamedParameterJdbcTemplate jdbcTemplate) {
        Setting s = resolveCredentialSetting(name, merchantId, useMerchantCreds, jdbcTemplate);
        return (s == null || s.getSetting_value() == null) ? "" : s.getSetting_value().trim();
    }

    private static GateWayResponse merchantCredsMissingError(String gateway, String settingName) {
        GateWayResponse err = new GateWayResponse();
        err.setHttpStatus("0");
        err.setStatus("ERROR");
        err.setTransactionStatus("FAILED");
        err.setMessage(
                "Merchant provider credential '"
                        + settingName
                        + "' is not configured for "
                        + gateway
                        + ". Please configure merchant-specific gateway settings.");
        return err;
    }

    private static void configureAirtelOpenApiEndpoints(
            AirtelMoneyOpenApiPaymentGateway gateway,
            Long merchantId,
            boolean useMerchantCreds,
            NamedParameterJdbcTemplate jdbcTemplate) {
        gateway.setEndpointDetails(
                resolveCredentialValue(
                        "gw_airtelmoney_token_url", merchantId, useMerchantCreds, jdbcTemplate),
                resolveCredentialValue(
                        "gw_airtelmoney_collections_url",
                        merchantId,
                        useMerchantCreds,
                        jdbcTemplate),
                resolveCredentialValue(
                        "gw_airtelmoney_disbursements_url",
                        merchantId,
                        useMerchantCreds,
                        jdbcTemplate),
                resolveCredentialValue(
                        "gw_airtelmoney_balance_url", merchantId, useMerchantCreds, jdbcTemplate),
                resolveCredentialValue(
                        "gw_airtelmoney_collections_status_url",
                        merchantId,
                        useMerchantCreds,
                        jdbcTemplate),
                resolveCredentialValue(
                        "gw_airtelmoney_disbursements_status_url",
                        merchantId,
                        useMerchantCreds,
                        jdbcTemplate));
    }

    public static GatewayChargeDetails getGatewayChargeDetailsById(
            NamedParameterJdbcTemplate jdbcTemplate, String gateway_id, Long merchant_id) {
        if (MTNMoMoPaymentGateway.gateway_id.equals(gateway_id)) {
            GatewayChargeDetails gwChargeDetails = new GatewayChargeDetails();
            Setting customerChargeMethod =
                    getMerchantSPecificSetting(
                                            "gw_mtn_api_customer_charge_method",
                                            merchant_id,
                                            jdbcTemplate)
                                    == null
                            ? Common.getSettings("gw_mtn_api_customer_charge_method", jdbcTemplate)
                            : getMerchantSPecificSetting(
                                    "gw_mtn_api_customer_charge_method", merchant_id, jdbcTemplate);

            Setting customerOfPayInMethod =
                    getMerchantSPecificSetting(
                                            "gw_mtn_api_customer_charge_inbound_method",
                                            merchant_id,
                                            jdbcTemplate)
                                    == null
                            ? Common.getSettings(
                                    "gw_mtn_api_customer_charge_inbound_method", jdbcTemplate)
                            : getMerchantSPecificSetting(
                                    "gw_mtn_api_customer_charge_inbound_method",
                                    merchant_id,
                                    jdbcTemplate);

            Setting customerPayOutMethod =
                    getMerchantSPecificSetting(
                                            "gw_mtn_api_customer_charge_outbound_method",
                                            merchant_id,
                                            jdbcTemplate)
                                    == null
                            ? Common.getSettings(
                                    "gw_mtn_api_customer_charge_outbound_method", jdbcTemplate)
                            : getMerchantSPecificSetting(
                                    "gw_mtn_api_customer_charge_outbound_method",
                                    merchant_id,
                                    jdbcTemplate);

            Setting customerInboundCharge =
                    getMerchantSPecificSetting(
                                            "gw_mtn_api_customer_charge_inbound",
                                            merchant_id,
                                            jdbcTemplate)
                                    == null
                            ? Common.getSettings("gw_mtn_api_customer_charge_inbound", jdbcTemplate)
                            : getMerchantSPecificSetting(
                                    "gw_mtn_api_customer_charge_inbound",
                                    merchant_id,
                                    jdbcTemplate);

            Setting customerOutboundCharge =
                    getMerchantSPecificSetting(
                                            "gw_mtn_api_customer_charge_outbound",
                                            merchant_id,
                                            jdbcTemplate)
                                    == null
                            ? Common.getSettings(
                                    "gw_mtn_api_customer_charge_outbound", jdbcTemplate)
                            : getMerchantSPecificSetting(
                                    "gw_mtn_api_customer_charge_outbound",
                                    merchant_id,
                                    jdbcTemplate);

            gwChargeDetails.setCustomerInboundChargeMethod(
                    customerOfPayInMethod.getSetting_value());
            gwChargeDetails.setCustomerInboundCharge(
                    Double.parseDouble(customerInboundCharge.getSetting_value()));
            gwChargeDetails.setCustomerOutboundChargeMethod(
                    customerPayOutMethod.getSetting_value());
            gwChargeDetails.setCustomerOutboundCharge(
                    Double.parseDouble(customerOutboundCharge.getSetting_value()));

            Setting costOfPayInMethod =
                    Common.getSettings("gw_mtn_api_cost_of_inbound_payment_method", jdbcTemplate);
            Setting costOfPayOutMethod =
                    Common.getSettings("gw_mtn_api_cost_of_outbound_payment_method", jdbcTemplate);
            Setting costOfInboundPayment =
                    Common.getSettings("gw_mtn_api_cost_of_inbound_payment", jdbcTemplate);
            Setting costOfOutboundPayment =
                    Common.getSettings("gw_mtn_api_cost_of_outbound_payment", jdbcTemplate);

            gwChargeDetails.setCostOfPayInMethod(costOfPayInMethod.getSetting_value());
            gwChargeDetails.setCostOfPayOutMethod(costOfPayOutMethod.getSetting_value());
            gwChargeDetails.setCostOfInboundPayment(
                    Double.parseDouble(costOfInboundPayment.getSetting_value()));
            gwChargeDetails.setCostOfOutboundPayment(
                    Double.parseDouble(costOfOutboundPayment.getSetting_value()));
            gwChargeDetails.setCustomerChargeMethod(customerChargeMethod.getSetting_value());

            return gwChargeDetails;
        }

        if (SafariComPaymentGateway.gateway_id.equals(gateway_id)) {
            GatewayChargeDetails gwChargeDetails = new GatewayChargeDetails();
            Setting customerChargeMethod =
                    getMerchantSPecificSetting(
                                            "gw_safaricom_api_customer_charge_method",
                                            merchant_id,
                                            jdbcTemplate)
                                    == null
                            ? Common.getSettings(
                                    "gw_safaricom_api_customer_charge_method", jdbcTemplate)
                            : getMerchantSPecificSetting(
                                    "gw_safaricom_api_customer_charge_method",
                                    merchant_id,
                                    jdbcTemplate);

            Setting customerOfPayInMethod =
                    getMerchantSPecificSetting(
                                            "gw_safaricom_api_customer_charge_inbound_method",
                                            merchant_id,
                                            jdbcTemplate)
                                    == null
                            ? Common.getSettings(
                                    "gw_safaricom_api_customer_charge_inbound_method", jdbcTemplate)
                            : getMerchantSPecificSetting(
                                    "gw_safaricom_api_customer_charge_inbound_method",
                                    merchant_id,
                                    jdbcTemplate);

            Setting customerPayOutMethod =
                    getMerchantSPecificSetting(
                                            "gw_safaricom_api_customer_charge_outbound_method",
                                            merchant_id,
                                            jdbcTemplate)
                                    == null
                            ? Common.getSettings(
                                    "gw_safaricom_api_customer_charge_outbound_method",
                                    jdbcTemplate)
                            : getMerchantSPecificSetting(
                                    "gw_safaricom_api_customer_charge_outbound_method",
                                    merchant_id,
                                    jdbcTemplate);

            Setting customerInboundCharge =
                    getMerchantSPecificSetting(
                                            "gw_safaricom_api_customer_charge_inbound",
                                            merchant_id,
                                            jdbcTemplate)
                                    == null
                            ? Common.getSettings(
                                    "gw_safaricom_api_customer_charge_inbound", jdbcTemplate)
                            : getMerchantSPecificSetting(
                                    "gw_safaricom_api_customer_charge_inbound",
                                    merchant_id,
                                    jdbcTemplate);

            Setting customerOutboundCharge =
                    getMerchantSPecificSetting(
                                            "gw_safaricom_api_customer_charge_outbound",
                                            merchant_id,
                                            jdbcTemplate)
                                    == null
                            ? Common.getSettings(
                                    "gw_safaricom_api_customer_charge_outbound", jdbcTemplate)
                            : getMerchantSPecificSetting(
                                    "gw_safaricom_api_customer_charge_outbound",
                                    merchant_id,
                                    jdbcTemplate);

            gwChargeDetails.setCustomerInboundChargeMethod(
                    customerOfPayInMethod.getSetting_value());
            gwChargeDetails.setCustomerInboundCharge(
                    Double.parseDouble(customerInboundCharge.getSetting_value()));
            gwChargeDetails.setCustomerOutboundChargeMethod(
                    customerPayOutMethod.getSetting_value());
            gwChargeDetails.setCustomerOutboundCharge(
                    Double.parseDouble(customerOutboundCharge.getSetting_value()));

            Setting costOfPayInMethod =
                    Common.getSettings(
                            "gw_safaricom_api_cost_of_inbound_payment_method", jdbcTemplate);
            Setting costOfPayOutMethod =
                    Common.getSettings(
                            "gw_safaricom_api_cost_of_outbound_payment_method", jdbcTemplate);
            Setting costOfInboundPayment =
                    Common.getSettings("gw_safaricom_api_cost_of_inbound_payment", jdbcTemplate);
            Setting costOfOutboundPayment =
                    Common.getSettings("gw_safaricom_api_cost_of_outbound_payment", jdbcTemplate);

            gwChargeDetails.setCostOfPayInMethod(costOfPayInMethod.getSetting_value());
            gwChargeDetails.setCostOfPayOutMethod(costOfPayOutMethod.getSetting_value());
            gwChargeDetails.setCostOfInboundPayment(
                    Double.parseDouble(costOfInboundPayment.getSetting_value()));
            gwChargeDetails.setCostOfOutboundPayment(
                    Double.parseDouble(costOfOutboundPayment.getSetting_value()));
            gwChargeDetails.setCustomerChargeMethod(customerChargeMethod.getSetting_value());

            return gwChargeDetails;
        }

        String use_open_api =
                Common.getSettings("gw_airtelmoney_use_open_api", jdbcTemplate).getSetting_value();

        // Do another gateway.

        if (AirtelMoneyOpenApiPaymentGateway.gateway_id.equals(gateway_id)) {
            GatewayChargeDetails gwChargeDetails = new GatewayChargeDetails();
            Setting customerChargeMethod =
                    getMerchantSPecificSetting(
                                            "gw_airtelmoney_api_customer_charge_method",
                                            merchant_id,
                                            jdbcTemplate)
                                    == null
                            ? Common.getSettings(
                                    "gw_airtelmoney_api_customer_charge_method", jdbcTemplate)
                            : getMerchantSPecificSetting(
                                    "gw_airtelmoney_api_customer_charge_method",
                                    merchant_id,
                                    jdbcTemplate);

            Setting customerOfPayInMethod =
                    getMerchantSPecificSetting(
                                            "gw_airtelmoney_api_customer_charge_inbound_method",
                                            merchant_id,
                                            jdbcTemplate)
                                    == null
                            ? Common.getSettings(
                                    "gw_airtelmoney_api_customer_charge_inbound_method",
                                    jdbcTemplate)
                            : getMerchantSPecificSetting(
                                    "gw_airtelmoney_api_customer_charge_inbound_method",
                                    merchant_id,
                                    jdbcTemplate);

            Setting customerPayOutMethod =
                    getMerchantSPecificSetting(
                                            "gw_airtelmoney_api_customer_charge_outbound_method",
                                            merchant_id,
                                            jdbcTemplate)
                                    == null
                            ? Common.getSettings(
                                    "gw_airtelmoney_api_customer_charge_outbound_method",
                                    jdbcTemplate)
                            : getMerchantSPecificSetting(
                                    "gw_airtelmoney_api_customer_charge_outbound_method",
                                    merchant_id,
                                    jdbcTemplate);

            Setting customerInboundCharge =
                    getMerchantSPecificSetting(
                                            "gw_airtelmoney_api_customer_charge_inbound",
                                            merchant_id,
                                            jdbcTemplate)
                                    == null
                            ? Common.getSettings(
                                    "gw_airtelmoney_api_customer_charge_inbound", jdbcTemplate)
                            : getMerchantSPecificSetting(
                                    "gw_airtelmoney_api_customer_charge_inbound",
                                    merchant_id,
                                    jdbcTemplate);

            Setting customerOutboundCharge =
                    getMerchantSPecificSetting(
                                            "gw_airtelmoney_api_customer_charge_outbound",
                                            merchant_id,
                                            jdbcTemplate)
                                    == null
                            ? Common.getSettings(
                                    "gw_airtelmoney_api_customer_charge_outbound", jdbcTemplate)
                            : getMerchantSPecificSetting(
                                    "gw_airtelmoney_api_customer_charge_outbound",
                                    merchant_id,
                                    jdbcTemplate);

            gwChargeDetails.setCustomerInboundChargeMethod(
                    customerOfPayInMethod.getSetting_value());
            gwChargeDetails.setCustomerInboundCharge(
                    Double.parseDouble(customerInboundCharge.getSetting_value()));
            gwChargeDetails.setCustomerOutboundChargeMethod(
                    customerPayOutMethod.getSetting_value());
            gwChargeDetails.setCustomerOutboundCharge(
                    Double.parseDouble(customerOutboundCharge.getSetting_value()));

            Setting costOfPayInMethod =
                    Common.getSettings(
                            "gw_airtelmoney_api_cost_of_inbound_payment_method", jdbcTemplate);
            Setting costOfPayOutMethod =
                    Common.getSettings(
                            "gw_airtelmoney_api_cost_of_outbound_payment_method", jdbcTemplate);
            Setting costOfInboundPayment =
                    Common.getSettings("gw_airtelmoney_api_cost_of_inbound_payment", jdbcTemplate);
            Setting costOfOutboundPayment =
                    Common.getSettings("gw_airtelmoney_api_cost_of_outbound_payment", jdbcTemplate);

            gwChargeDetails.setCostOfPayInMethod(costOfPayInMethod.getSetting_value());
            gwChargeDetails.setCostOfPayOutMethod(costOfPayOutMethod.getSetting_value());
            gwChargeDetails.setCostOfInboundPayment(
                    Double.parseDouble(costOfInboundPayment.getSetting_value()));
            gwChargeDetails.setCostOfOutboundPayment(
                    Double.parseDouble(costOfOutboundPayment.getSetting_value()));
            gwChargeDetails.setCustomerChargeMethod(customerChargeMethod.getSetting_value());

            return gwChargeDetails;
        }

        if (AirtelMoneyPaymentGateway.gateway_id.equals(gateway_id)) {
            GatewayChargeDetails gwChargeDetails = new GatewayChargeDetails();
            Setting customerChargeMethod =
                    getMerchantSPecificSetting(
                                            "gw_airtelmoney_api_customer_charge_method",
                                            merchant_id,
                                            jdbcTemplate)
                                    == null
                            ? Common.getSettings(
                                    "gw_airtelmoney_api_customer_charge_method", jdbcTemplate)
                            : getMerchantSPecificSetting(
                                    "gw_airtelmoney_api_customer_charge_method",
                                    merchant_id,
                                    jdbcTemplate);

            Setting customerOfPayInMethod =
                    getMerchantSPecificSetting(
                                            "gw_airtelmoney_api_customer_charge_inbound_method",
                                            merchant_id,
                                            jdbcTemplate)
                                    == null
                            ? Common.getSettings(
                                    "gw_airtelmoney_api_customer_charge_inbound_method",
                                    jdbcTemplate)
                            : getMerchantSPecificSetting(
                                    "gw_airtelmoney_api_customer_charge_inbound_method",
                                    merchant_id,
                                    jdbcTemplate);

            Setting customerPayOutMethod =
                    getMerchantSPecificSetting(
                                            "gw_airtelmoney_api_customer_charge_outbound_method",
                                            merchant_id,
                                            jdbcTemplate)
                                    == null
                            ? Common.getSettings(
                                    "gw_airtelmoney_api_customer_charge_outbound_method",
                                    jdbcTemplate)
                            : getMerchantSPecificSetting(
                                    "gw_airtelmoney_api_customer_charge_outbound_method",
                                    merchant_id,
                                    jdbcTemplate);

            Setting customerInboundCharge =
                    getMerchantSPecificSetting(
                                            "gw_airtelmoney_api_customer_charge_inbound",
                                            merchant_id,
                                            jdbcTemplate)
                                    == null
                            ? Common.getSettings(
                                    "gw_airtelmoney_api_customer_charge_inbound", jdbcTemplate)
                            : getMerchantSPecificSetting(
                                    "gw_airtelmoney_api_customer_charge_inbound",
                                    merchant_id,
                                    jdbcTemplate);

            Setting customerOutboundCharge =
                    getMerchantSPecificSetting(
                                            "gw_airtelmoney_api_customer_charge_outbound",
                                            merchant_id,
                                            jdbcTemplate)
                                    == null
                            ? Common.getSettings(
                                    "gw_airtelmoney_api_customer_charge_outbound", jdbcTemplate)
                            : getMerchantSPecificSetting(
                                    "gw_airtelmoney_api_customer_charge_outbound",
                                    merchant_id,
                                    jdbcTemplate);

            gwChargeDetails.setCustomerInboundChargeMethod(
                    customerOfPayInMethod.getSetting_value());
            gwChargeDetails.setCustomerInboundCharge(
                    Double.parseDouble(customerInboundCharge.getSetting_value()));
            gwChargeDetails.setCustomerOutboundChargeMethod(
                    customerPayOutMethod.getSetting_value());
            gwChargeDetails.setCustomerOutboundCharge(
                    Double.parseDouble(customerOutboundCharge.getSetting_value()));

            Setting costOfPayInMethod =
                    Common.getSettings(
                            "gw_airtelmoney_api_cost_of_inbound_payment_method", jdbcTemplate);
            Setting costOfPayOutMethod =
                    Common.getSettings(
                            "gw_airtelmoney_api_cost_of_outbound_payment_method", jdbcTemplate);
            Setting costOfInboundPayment =
                    Common.getSettings("gw_airtelmoney_api_cost_of_inbound_payment", jdbcTemplate);
            Setting costOfOutboundPayment =
                    Common.getSettings("gw_airtelmoney_api_cost_of_outbound_payment", jdbcTemplate);

            gwChargeDetails.setCostOfPayInMethod(costOfPayInMethod.getSetting_value());
            gwChargeDetails.setCostOfPayOutMethod(costOfPayOutMethod.getSetting_value());
            gwChargeDetails.setCostOfInboundPayment(
                    Double.parseDouble(costOfInboundPayment.getSetting_value()));
            gwChargeDetails.setCostOfOutboundPayment(
                    Double.parseDouble(costOfOutboundPayment.getSetting_value()));
            gwChargeDetails.setCustomerChargeMethod(customerChargeMethod.getSetting_value());

            return gwChargeDetails;
        }

        // Check other supported gateways like Airtel
        return null;
    }

    public static Double getCustomerInboundCharges(
            Double amount, GatewayChargeDetails chargeDetails) {
        if (chargeDetails.getCustomerInboundChargeMethod().equals("percentage")) {
            Double r = ((chargeDetails.getCustomerInboundCharge() / 100) * amount);
            return r;
        } else if (chargeDetails.getCustomerInboundChargeMethod().equals("flat")) {
            return chargeDetails.getCustomerInboundCharge();
        } else {
            return 0.00;
        }
    }

    public static Double getCustomerOutboundCharges(
            Double amount, GatewayChargeDetails chargeDetails) {
        if (chargeDetails.getCustomerOutboundChargeMethod().equals("percentage")) {
            Double r = ((chargeDetails.getCustomerOutboundCharge() / 100) * amount);
            return r;
        } else if (chargeDetails.getCustomerOutboundChargeMethod().equals("flat")) {
            return chargeDetails.getCustomerOutboundCharge();
        } else {
            return 0.00;
        }
    }

    public static Double getCostOfInboundCharges(
            Double amount, GatewayChargeDetails chargeDetails) {
        if (chargeDetails.getCostOfPayInMethod().equals("percentage")) {
            Double r = ((chargeDetails.getCostOfInboundPayment() / 100) * amount);
            return r;
        } else if (chargeDetails.getCostOfPayInMethod().equals("flat")) {
            return chargeDetails.getCostOfInboundPayment();
        } else {
            return 0.00;
        }
    }

    public static Double getCostOfOutboundCharges(
            Double amount, GatewayChargeDetails chargeDetails) {
        if (chargeDetails.getCostOfPayOutMethod().equals("percentage")) {
            Double r = ((chargeDetails.getCostOfOutboundPayment() / 100) * amount);
            return r;
        } else if (chargeDetails.getCostOfPayOutMethod().equals("flat")) {
            return chargeDetails.getCostOfOutboundPayment();
        } else {
            return 0.00;
        }
    }

    public static Double getCustomertOfOutboundCharges(
            Double amount, GatewayChargeDetails chargeDetails) {
        if (chargeDetails.getCostOfPayOutMethod().equals("percentage")) {
            Double r = ((chargeDetails.getCostOfOutboundPayment() / 100) * amount);
            return r;
        } else if (chargeDetails.getCostOfPayOutMethod().equals("flat")) {
            return chargeDetails.getCostOfOutboundPayment();
        } else {
            return 0.00;
        }
    }

    public GateWayResponse runPayGatewayDoPayIn(
            NamedParameterJdbcTemplate jdbcTemplate,
            String msisdn,
            Double amount,
            String ref,
            String narrative,
            Long merchantId) {

        // First check if this is a test.
        String state =
                Common.getSettings("application_settings_state", jdbcTemplate) == null
                        ? "production"
                        : Common.getSettings("application_settings_state", jdbcTemplate)
                                .getSetting_value();

        String simulateTransactions =
                Common.getSettings("simulate_transactions", jdbcTemplate) == null
                        ? "yes"
                        : Common.getSettings("simulate_transactions", jdbcTemplate)
                                .getSetting_value();

        if (simulateTransactions.equalsIgnoreCase("yes")) {
            if (state.equalsIgnoreCase("sandbox")) {
                return sandboxRunPayGatewayDoPayIn(msisdn, amount, ref, narrative);
            }
        }

        boolean useMerchantCreds = Common.useMerchantProviderCredentials(jdbcTemplate);

        // Select the gateway
        if (MTNMoMoPaymentGateway.isValidMisdn(msisdn)) {
            Setting env =
                    resolveCredentialSetting(
                            "gw_mtn_api_env", merchantId, useMerchantCreds, jdbcTemplate);
            if (useMerchantCreds
                    && (env == null
                            || env.getSetting_value() == null
                            || env.getSetting_value().trim().isEmpty())) {
                return merchantCredsMissingError("MTN MoMo", "gw_mtn_api_env");
            }
            String global_url;
            String api_collections_user;
            String api_collections_key;
            String api_collections_subscription;
            String api_disbursements_user;
            String api_disbursements_key;
            String api_disbursements_subscription;
            String base_currency;

            if (env.getSetting_value().equals("mtnuganda")) {
                global_url =
                        resolveCredentialValue(
                                "gw_mtn_api_url", merchantId, useMerchantCreds, jdbcTemplate);
                api_collections_user =
                        resolveCredentialValue(
                                "gw_mtn_api_collections_user_id",
                                merchantId,
                                useMerchantCreds,
                                jdbcTemplate);
                api_collections_key =
                        resolveCredentialValue(
                                "gw_mtn_api_collections_user_key",
                                merchantId,
                                useMerchantCreds,
                                jdbcTemplate);
                api_collections_subscription =
                        resolveCredentialValue(
                                "gw_mtn_api_collections_subscription_key",
                                merchantId,
                                useMerchantCreds,
                                jdbcTemplate);
                api_disbursements_user =
                        resolveCredentialValue(
                                "gw_mtn_api_disbursements_user_id",
                                merchantId,
                                useMerchantCreds,
                                jdbcTemplate);
                api_disbursements_key =
                        resolveCredentialValue(
                                "gw_mtn_api_disbursements_user_key",
                                merchantId,
                                useMerchantCreds,
                                jdbcTemplate);
                api_disbursements_subscription =
                        resolveCredentialValue(
                                "gw_mtn_api_disbursements_subscription_key",
                                merchantId,
                                useMerchantCreds,
                                jdbcTemplate);
                base_currency =
                        resolveCredentialValue(
                                "gw_mtn_api_base_currency",
                                merchantId,
                                useMerchantCreds,
                                jdbcTemplate);
            } else {
                global_url =
                        resolveCredentialValue(
                                "gw_mtn_api_url_sandbox",
                                merchantId,
                                useMerchantCreds,
                                jdbcTemplate);
                api_collections_user =
                        resolveCredentialValue(
                                "gw_mtn_api_collections_user_id_sandbox",
                                merchantId,
                                useMerchantCreds,
                                jdbcTemplate);
                api_collections_key =
                        resolveCredentialValue(
                                "gw_mtn_api_collections_user_key_sandbox",
                                merchantId,
                                useMerchantCreds,
                                jdbcTemplate);
                api_collections_subscription =
                        resolveCredentialValue(
                                "gw_mtn_api_collections_subscription_key_sandbox",
                                merchantId,
                                useMerchantCreds,
                                jdbcTemplate);
                api_disbursements_user =
                        resolveCredentialValue(
                                "gw_mtn_api_disbursements_user_id_sandbox",
                                merchantId,
                                useMerchantCreds,
                                jdbcTemplate);
                api_disbursements_key =
                        resolveCredentialValue(
                                "gw_mtn_api_disbursements_user_key_sandbox",
                                merchantId,
                                useMerchantCreds,
                                jdbcTemplate);
                api_disbursements_subscription =
                        resolveCredentialValue(
                                "gw_mtn_api_disbursements_subscription_key_sandbox",
                                merchantId,
                                useMerchantCreds,
                                jdbcTemplate);
                base_currency =
                        resolveCredentialValue(
                                "gw_mtn_api_base_currency_sandbox",
                                merchantId,
                                useMerchantCreds,
                                jdbcTemplate);
            }
            mtn_mmpgw = new MTNMoMoPaymentGateway();
            mtn_mmpgw.setSegment("collection");
            mtn_mmpgw.setApiDetails(
                    global_url,
                    api_collections_user,
                    api_collections_key,
                    api_collections_subscription,
                    api_disbursements_user,
                    api_disbursements_key,
                    api_disbursements_subscription,
                    env.getSetting_value(),
                    base_currency);
            return mtn_mmpgw.doPayIn(amount, msisdn, ref, narrative);
        }

        if (SafariComPaymentGateway.isValidMisdn(msisdn)) {
            Setting env =
                    resolveCredentialSetting(
                            "gw_safaricom_api_env", merchantId, useMerchantCreds, jdbcTemplate);
            if (useMerchantCreds
                    && (env == null
                            || env.getSetting_value() == null
                            || env.getSetting_value().trim().isEmpty())) {
                return merchantCredsMissingError("Safaricom", "gw_safaricom_api_env");
            }
            String global_url =
                    resolveCredentialValue(
                            "gw_safaricom_api_url", merchantId, useMerchantCreds, jdbcTemplate);
            String gw_safaricom_api_shortcode =
                    resolveCredentialValue(
                            "gw_safaricom_api_shortcode",
                            merchantId,
                            useMerchantCreds,
                            jdbcTemplate);
            String gw_safaricom_api_password =
                    resolveCredentialValue(
                            "gw_safaricom_api_password",
                            merchantId,
                            useMerchantCreds,
                            jdbcTemplate);
            String gw_safaricom_api_consumer_key =
                    resolveCredentialValue(
                            "gw_safaricom_api_consumer_key",
                            merchantId,
                            useMerchantCreds,
                            jdbcTemplate);
            String gw_safaricom_api_consumer_secret =
                    resolveCredentialValue(
                            "gw_safaricom_api_consumer_secret",
                            merchantId,
                            useMerchantCreds,
                            jdbcTemplate);
            String app_setting_app_url =
                    Common.getSettings("app_setting_app_url", jdbcTemplate).getSetting_value();

            safaricom_mmpgw = new SafariComPaymentGateway();
            Setting safVer =
                    resolveCredentialSetting(
                            "gw_safaricom_api_version", merchantId, useMerchantCreds, jdbcTemplate);
            if (safVer != null) safaricom_mmpgw.setApiVersion(safVer.getSetting_value());
            safaricom_mmpgw.setSegment("collection");
            safaricom_mmpgw.setApiDetails(
                    global_url,
                    gw_safaricom_api_consumer_key,
                    gw_safaricom_api_consumer_secret,
                    gw_safaricom_api_shortcode,
                    gw_safaricom_api_password,
                    env.getSetting_value(),
                    app_setting_app_url);
            return safaricom_mmpgw.doPayIn(amount, msisdn, ref, narrative);
        }

        String use_open_api =
                resolveCredentialValue(
                        "gw_airtelmoney_use_open_api", merchantId, useMerchantCreds, jdbcTemplate);
        if (useMerchantCreds && use_open_api.isEmpty()) {
            return merchantCredsMissingError("Airtel Money", "gw_airtelmoney_use_open_api");
        }

        if (use_open_api.equals("yes")) {
            if (AirtelMoneyOpenApiPaymentGateway.isValidMisdn(msisdn)) {
                String global_url =
                        resolveCredentialValue(
                                "gw_airtelmoney_api_url",
                                merchantId,
                                useMerchantCreds,
                                jdbcTemplate);
                String api_username =
                        resolveCredentialValue(
                                "gw_airtelmoney_api_username",
                                merchantId,
                                useMerchantCreds,
                                jdbcTemplate);
                String api_password =
                        resolveCredentialValue(
                                "gw_airtelmoney_api_password",
                                merchantId,
                                useMerchantCreds,
                                jdbcTemplate);
                String api_pin =
                        resolveCredentialValue(
                                "gw_airtelmoney_api_pin",
                                merchantId,
                                useMerchantCreds,
                                jdbcTemplate);

                airteloapimm_mmpgw = new AirtelMoneyOpenApiPaymentGateway();
                airteloapimm_mmpgw.setApiDetails(global_url, api_username, api_password, api_pin);
                configureAirtelOpenApiEndpoints(
                        airteloapimm_mmpgw, merchantId, useMerchantCreds, jdbcTemplate);
                Setting airtelPublicKey =
                        resolveCredentialSetting(
                                "gw_airtelmoney_api_public_key",
                                merchantId,
                                useMerchantCreds,
                                jdbcTemplate);
                if (airtelPublicKey != null)
                    airteloapimm_mmpgw.setPublicKey(airtelPublicKey.getSetting_value());
                net.citotech.cito.gateway.AirtelRecoveryScopeStore.record(
                        jdbcTemplate,
                        ref,
                        merchantId,
                        "COLLECT",
                        useMerchantCreds ? "LEGACY_MERCHANT" : "LEGACY_PLATFORM",
                        global_url,
                        api_username,
                        "UG",
                        "UGX");
                return airteloapimm_mmpgw.doPayIn(amount, msisdn, ref, narrative);
            }
        } else {
            if (AirtelMoneyPaymentGateway.isValidMisdn(msisdn)) {
                String global_url =
                        resolveCredentialValue(
                                "gw_airtelmoney_api_url",
                                merchantId,
                                useMerchantCreds,
                                jdbcTemplate);
                String api_username =
                        resolveCredentialValue(
                                "gw_airtelmoney_api_username",
                                merchantId,
                                useMerchantCreds,
                                jdbcTemplate);
                String api_password =
                        resolveCredentialValue(
                                "gw_airtelmoney_api_password",
                                merchantId,
                                useMerchantCreds,
                                jdbcTemplate);

                airtelmm_mmpgw = new AirtelMoneyPaymentGateway();
                airtelmm_mmpgw.setApiDetails(global_url, api_username, api_password);
                return airtelmm_mmpgw.doPayIn(amount, msisdn, ref, narrative);
            }
        }
        return null;
    }

    public double[] runPayGatewayNetworkBalances(NamedParameterJdbcTemplate jdbcTemplate) {

        double[] rData = {0.0, 0.0, 0.0, 0.0};

        // First check if this is a test.
        String state = settingValue(jdbcTemplate, "application_settings_state", "production");

        if (state.toLowerCase().equals("sandbox")) {
            rData[0] = 200000.0;
            rData[1] = 3500000.0;
            rData[2] = 1000000.0;
            rData[3] = 4000000.0;

            return rData;
        }

        // Select the gateway
        // Get MTN balances
        try {
            String env = settingValue(jdbcTemplate, "gw_mtn_api_env", "");
            if (env.isEmpty()) {
                logBalanceWarning(
                        "Skipping MTN dashboard balances: gw_mtn_api_env is not configured.", null);
            } else {
                boolean production = env.equalsIgnoreCase("mtnuganda");
                String suffix = production ? "" : "_sandbox";
                String global_url = settingValue(jdbcTemplate, "gw_mtn_api_url" + suffix, "");
                String api_collections_user =
                        settingValue(jdbcTemplate, "gw_mtn_api_collections_user_id" + suffix, "");
                String api_collections_key =
                        settingValue(jdbcTemplate, "gw_mtn_api_collections_user_key" + suffix, "");
                String api_collections_subscription =
                        settingValue(
                                jdbcTemplate,
                                "gw_mtn_api_collections_subscription_key" + suffix,
                                "");

                String api_disbursements_user =
                        settingValue(jdbcTemplate, "gw_mtn_api_disbursements_user_id" + suffix, "");
                String api_disbursements_key =
                        settingValue(
                                jdbcTemplate, "gw_mtn_api_disbursements_user_key" + suffix, "");
                String api_disbursements_subscription =
                        settingValue(
                                jdbcTemplate,
                                "gw_mtn_api_disbursements_subscription_key" + suffix,
                                "");
                String base_currency =
                        settingValue(jdbcTemplate, "gw_mtn_api_base_currency" + suffix, "");

                if (hasBlank(
                        global_url,
                        api_collections_user,
                        api_collections_key,
                        api_collections_subscription,
                        api_disbursements_user,
                        api_disbursements_key,
                        api_disbursements_subscription,
                        base_currency)) {
                    logBalanceWarning(
                            "Skipping MTN dashboard balances: MTN gateway settings are incomplete.",
                            null);
                } else {
                    mtn_mmpgw = new MTNMoMoPaymentGateway();
                    mtn_mmpgw.setSegment("collection");
                    mtn_mmpgw.setApiDetails(
                            global_url,
                            api_collections_user,
                            api_collections_key,
                            api_collections_subscription,
                            api_disbursements_user,
                            api_disbursements_key,
                            api_disbursements_subscription,
                            env,
                            base_currency);
                    rData[0] = Common.round(mtn_mmpgw.getBalance("collection"), 2);
                    mtn_mmpgw.setSegment("disbursement");
                    rData[1] = Common.round(mtn_mmpgw.getBalance("disbursement"), 2);
                }
            }
        } catch (Exception ex) {
            logBalanceWarning("Skipping MTN dashboard balances after gateway error.", ex);
        }

        // Get details for Airtel Money.
        try {
            String use_open_api = settingValue(jdbcTemplate, "gw_airtelmoney_use_open_api", "no");
            String global_url = settingValue(jdbcTemplate, "gw_airtelmoney_api_url", "");
            String api_username = settingValue(jdbcTemplate, "gw_airtelmoney_api_username", "");
            String api_password = settingValue(jdbcTemplate, "gw_airtelmoney_api_password", "");
            String disbursement_acc =
                    settingValue(jdbcTemplate, "gw_airtelmoney_disbursement_account", "");
            String collections_acc =
                    settingValue(jdbcTemplate, "gw_airtelmoney_collections_account", "");

            if (use_open_api.equals("yes")) {
                String api_pin = settingValue(jdbcTemplate, "gw_airtelmoney_api_pin", "");
                if (hasBlank(
                        global_url,
                        api_username,
                        api_password,
                        api_pin,
                        disbursement_acc,
                        collections_acc)) {
                    logBalanceWarning(
                            "Skipping Airtel dashboard balances: Airtel Open API settings are incomplete.",
                            null);
                } else {
                    airteloapimm_mmpgw = new AirtelMoneyOpenApiPaymentGateway();
                    airteloapimm_mmpgw.setApiDetails(
                            global_url, api_username, api_password, api_pin);
                    configureAirtelOpenApiEndpoints(airteloapimm_mmpgw, null, false, jdbcTemplate);
                    Setting airtelPublicKey =
                            Common.getSettings("gw_airtelmoney_api_public_key", jdbcTemplate);
                    if (airtelPublicKey != null)
                        airteloapimm_mmpgw.setPublicKey(airtelPublicKey.getSetting_value());

                    rData[2] = Common.round(airteloapimm_mmpgw.getBalance(collections_acc), 2);
                    rData[3] = Common.round(airteloapimm_mmpgw.getBalance(disbursement_acc), 2);
                }
            } else if (hasBlank(
                    global_url, api_username, api_password, disbursement_acc, collections_acc)) {
                logBalanceWarning(
                        "Skipping Airtel dashboard balances: Airtel gateway settings are incomplete.",
                        null);
            } else {
                airtelmm_mmpgw = new AirtelMoneyPaymentGateway();
                airtelmm_mmpgw.setApiDetails(global_url, api_username, api_password);

                rData[2] = Common.round(airtelmm_mmpgw.getBalance(collections_acc), 2);
                rData[3] = Common.round(airtelmm_mmpgw.getBalance(disbursement_acc), 2);
            }
        } catch (Exception ex) {
            logBalanceWarning("Skipping Airtel dashboard balances after gateway error.", ex);
        }
        return rData;
    }

    private GateWayResponse sandboxRunPayGatewayDoPayIn(
            String msisdn, Double amount, String ref, String narrative) {
        String tx_status = "PENDING";
        if (amount.equals(2020) || amount.equals(2020.0) || amount.equals("2020.00")) {
            tx_status = "UNDETERMINED";
        } else if (amount.equals(2021) || amount.equals(2021.0) || amount.equals("2021.00")) {
            tx_status = "FAILED";
        }
        GateWayResponse gwResponse = new GateWayResponse();
        gwResponse.setHttpStatus("200");
        gwResponse.setMessage("");
        gwResponse.setStatus("OK");
        gwResponse.setTransactionStatus(tx_status);
        gwResponse.setRequestTrace("SANDBOX SIMULATION transaction to " + msisdn);
        return gwResponse;
    }

    private GateWayResponse sandboxRunPayGatewayDoPayOut(
            String msisdn, Double amount, String ref, String narrative) {

        String tx_status = "PENDING";
        if (amount.equals(2020) || amount.equals(2020.0) || amount.equals("2020.00")) {
            tx_status = "UNDETERMINED";
        } else if (amount.equals(2021) || amount.equals(2021.0) || amount.equals("2021.00")) {
            tx_status = "FAILED";
        }
        GateWayResponse gwResponse = new GateWayResponse();
        gwResponse.setHttpStatus("200");
        gwResponse.setMessage("");
        gwResponse.setStatus("OK");
        gwResponse.setTransactionStatus(tx_status);
        gwResponse.setRequestTrace("SANDBOX SIMULATION transaction to " + msisdn);
        return gwResponse;
    }

    private GateWayResponse sandboxrunPayGatewayDoCheckStatus(String ref) {

        String tx_status = "SUCCESSFUL";

        GateWayResponse gwResponse = new GateWayResponse();
        gwResponse.setHttpStatus("200");
        gwResponse.setMessage("");
        gwResponse.setStatus("OK");
        gwResponse.setTransactionStatus(tx_status);
        gwResponse.setNetworkId(Common.generateUuid());
        gwResponse.setRequestTrace("SANDBOX SIMULATION transaction to " + ref);
        return gwResponse;
    }

    public GateWayResponse runPayGatewayDoPayOut(
            NamedParameterJdbcTemplate jdbcTemplate,
            String msisdn,
            Double amount,
            String ref,
            String narrative,
            Long merchantId) {

        // First check if this is a test.
        String state =
                Common.getSettings("application_settings_state", jdbcTemplate) == null
                        ? "production"
                        : Common.getSettings("application_settings_state", jdbcTemplate)
                                .getSetting_value();
        String simulateTransactions =
                Common.getSettings("simulate_transactions", jdbcTemplate) == null
                        ? "yes"
                        : Common.getSettings("simulate_transactions", jdbcTemplate)
                                .getSetting_value();

        if (simulateTransactions.equalsIgnoreCase("yes")) {
            if (state.toLowerCase().equals("sandbox")) {
                return sandboxRunPayGatewayDoPayOut(msisdn, amount, ref, narrative);
            }
        }

        boolean useMerchantCreds = Common.useMerchantProviderCredentials(jdbcTemplate);

        // Select the gateway
        if (MTNMoMoPaymentGateway.isValidMisdn(msisdn)) {
            Setting env =
                    resolveCredentialSetting(
                            "gw_mtn_api_env", merchantId, useMerchantCreds, jdbcTemplate);
            if (useMerchantCreds
                    && (env == null
                            || env.getSetting_value() == null
                            || env.getSetting_value().trim().isEmpty())) {
                return merchantCredsMissingError("MTN MoMo", "gw_mtn_api_env");
            }
            String global_url = "";
            String api_collections_user = "";
            String api_collections_key = "";
            String api_collections_subscription = "";

            String api_disbursements_user = "";
            String api_disbursements_key = "";
            String api_disbursements_subscription = "";
            String base_currency = "";
            if (env.getSetting_value().equals("mtnuganda")) {
                global_url =
                        resolveCredentialValue(
                                "gw_mtn_api_url", merchantId, useMerchantCreds, jdbcTemplate);
                api_collections_user =
                        resolveCredentialValue(
                                "gw_mtn_api_collections_user_id",
                                merchantId,
                                useMerchantCreds,
                                jdbcTemplate);
                api_collections_key =
                        resolveCredentialValue(
                                "gw_mtn_api_collections_user_key",
                                merchantId,
                                useMerchantCreds,
                                jdbcTemplate);
                api_collections_subscription =
                        resolveCredentialValue(
                                "gw_mtn_api_collections_subscription_key",
                                merchantId,
                                useMerchantCreds,
                                jdbcTemplate);

                api_disbursements_user =
                        resolveCredentialValue(
                                "gw_mtn_api_disbursements_user_id",
                                merchantId,
                                useMerchantCreds,
                                jdbcTemplate);
                api_disbursements_key =
                        resolveCredentialValue(
                                "gw_mtn_api_disbursements_user_key",
                                merchantId,
                                useMerchantCreds,
                                jdbcTemplate);
                api_disbursements_subscription =
                        resolveCredentialValue(
                                "gw_mtn_api_disbursements_subscription_key",
                                merchantId,
                                useMerchantCreds,
                                jdbcTemplate);

                base_currency =
                        resolveCredentialValue(
                                "gw_mtn_api_base_currency",
                                merchantId,
                                useMerchantCreds,
                                jdbcTemplate);
            } else {
                global_url =
                        resolveCredentialValue(
                                "gw_mtn_api_url_sandbox",
                                merchantId,
                                useMerchantCreds,
                                jdbcTemplate);
                api_collections_user =
                        resolveCredentialValue(
                                "gw_mtn_api_collections_user_id_sandbox",
                                merchantId,
                                useMerchantCreds,
                                jdbcTemplate);
                api_collections_key =
                        resolveCredentialValue(
                                "gw_mtn_api_collections_user_key_sandbox",
                                merchantId,
                                useMerchantCreds,
                                jdbcTemplate);
                api_collections_subscription =
                        resolveCredentialValue(
                                "gw_mtn_api_collections_subscription_key_sandbox",
                                merchantId,
                                useMerchantCreds,
                                jdbcTemplate);

                api_disbursements_user =
                        resolveCredentialValue(
                                "gw_mtn_api_disbursements_user_id_sandbox",
                                merchantId,
                                useMerchantCreds,
                                jdbcTemplate);
                api_disbursements_key =
                        resolveCredentialValue(
                                "gw_mtn_api_disbursements_user_key_sandbox",
                                merchantId,
                                useMerchantCreds,
                                jdbcTemplate);
                api_disbursements_subscription =
                        resolveCredentialValue(
                                "gw_mtn_api_disbursements_subscription_key_sandbox",
                                merchantId,
                                useMerchantCreds,
                                jdbcTemplate);
                base_currency =
                        resolveCredentialValue(
                                "gw_mtn_api_base_currency_sandbox",
                                merchantId,
                                useMerchantCreds,
                                jdbcTemplate);
            }
            mtn_mmpgw = new MTNMoMoPaymentGateway();
            mtn_mmpgw.setApiDetails(
                    global_url,
                    api_collections_user,
                    api_collections_key,
                    api_collections_subscription,
                    api_disbursements_user,
                    api_disbursements_key,
                    api_disbursements_subscription,
                    env.getSetting_value(),
                    base_currency);
            mtn_mmpgw.setSegment("disbursement");
            GateWayResponse pResponse = mtn_mmpgw.doPayOut(amount, msisdn, ref, narrative);
            return pResponse;
        }

        // Select the gateway
        if (SafariComPaymentGateway.isValidMisdn(msisdn)) {
            String global_url =
                    resolveCredentialValue(
                            "gw_safaricom_api_url", merchantId, useMerchantCreds, jdbcTemplate);
            if (useMerchantCreds && global_url.isEmpty()) {
                return merchantCredsMissingError("Safaricom", "gw_safaricom_api_url");
            }
            String api_disbursements_user =
                    resolveCredentialValue(
                            "gw_safaricom_api_consumer_key_disbursement",
                            merchantId,
                            useMerchantCreds,
                            jdbcTemplate);
            String api_disbursements_key =
                    resolveCredentialValue(
                            "gw_safaricom_api_consumer_secret_disbursement",
                            merchantId,
                            useMerchantCreds,
                            jdbcTemplate);
            String shortcode =
                    resolveCredentialValue(
                            "gw_safaricom_api_shortcode_disbursement",
                            merchantId,
                            useMerchantCreds,
                            jdbcTemplate);
            String initiatorUsername =
                    resolveCredentialValue(
                            "gw_safaricom_api_initiator_username_disbursement",
                            merchantId,
                            useMerchantCreds,
                            jdbcTemplate);
            String initiatorPassword =
                    resolveCredentialValue(
                            "gw_safaricom_api_initiator_pw_disbursement",
                            merchantId,
                            useMerchantCreds,
                            jdbcTemplate);
            String app_setting_app_url =
                    Common.getSettings("app_setting_app_url", jdbcTemplate).getSetting_value();

            safaricom_mmpgw = new SafariComPaymentGateway();
            Setting safVer2 = Common.getSettings("gw_safaricom_api_version", jdbcTemplate);
            if (safVer2 != null) safaricom_mmpgw.setApiVersion(safVer2.getSetting_value());
            safaricom_mmpgw.setApiDetails(
                    global_url,
                    api_disbursements_user,
                    api_disbursements_key,
                    initiatorUsername,
                    initiatorPassword,
                    shortcode,
                    "disbursement",
                    app_setting_app_url);
            safaricom_mmpgw.setSegment("disbursement");
            GateWayResponse pResponse = safaricom_mmpgw.doPayOut(amount, msisdn, ref, narrative);
            return pResponse;
        }

        // Do another gateway.

        String use_open_api =
                resolveCredentialValue(
                        "gw_airtelmoney_use_open_api", merchantId, useMerchantCreds, jdbcTemplate);
        if (useMerchantCreds && use_open_api.isEmpty()) {
            return merchantCredsMissingError("Airtel Money", "gw_airtelmoney_use_open_api");
        }

        // Do another gateway.
        if (use_open_api.equals("yes")) {
            if (AirtelMoneyOpenApiPaymentGateway.isValidMisdn(msisdn)) {

                String global_url =
                        resolveCredentialValue(
                                "gw_airtelmoney_api_url",
                                merchantId,
                                useMerchantCreds,
                                jdbcTemplate);
                String api_username =
                        resolveCredentialValue(
                                "gw_airtelmoney_api_username",
                                merchantId,
                                useMerchantCreds,
                                jdbcTemplate);
                String api_password =
                        resolveCredentialValue(
                                "gw_airtelmoney_api_password",
                                merchantId,
                                useMerchantCreds,
                                jdbcTemplate);
                String api_pin =
                        resolveCredentialValue(
                                "gw_airtelmoney_api_pin",
                                merchantId,
                                useMerchantCreds,
                                jdbcTemplate);

                airteloapimm_mmpgw = new AirtelMoneyOpenApiPaymentGateway();
                airteloapimm_mmpgw.setApiDetails(global_url, api_username, api_password, api_pin);
                configureAirtelOpenApiEndpoints(
                        airteloapimm_mmpgw, merchantId, useMerchantCreds, jdbcTemplate);
                Setting airtelPublicKey =
                        resolveCredentialSetting(
                                "gw_airtelmoney_api_public_key",
                                merchantId,
                                useMerchantCreds,
                                jdbcTemplate);
                if (airtelPublicKey != null)
                    airteloapimm_mmpgw.setPublicKey(airtelPublicKey.getSetting_value());
                if (airteloapimm_mmpgw.getPublicKey().isEmpty()) {
                    GateWayResponse err = new GateWayResponse();
                    err.setHttpStatus("0");
                    err.setStatus("ERROR");
                    err.setTransactionStatus("FAILED");
                    err.setMessage(
                            "Airtel Open API public key not configured (gw_airtelmoney_api_public_key).");
                    return err;
                }

                net.citotech.cito.gateway.AirtelRecoveryScopeStore.record(
                        jdbcTemplate,
                        ref,
                        merchantId,
                        "PAYOUT",
                        useMerchantCreds ? "LEGACY_MERCHANT" : "LEGACY_PLATFORM",
                        global_url,
                        api_username,
                        "UG",
                        "UGX");
                GateWayResponse pResponse =
                        airteloapimm_mmpgw.doPayOut(amount, msisdn, ref, narrative);
                return pResponse;
            }
        } else {
            if (AirtelMoneyPaymentGateway.isValidMisdn(msisdn)) {

                String global_url =
                        resolveCredentialValue(
                                "gw_airtelmoney_api_url",
                                merchantId,
                                useMerchantCreds,
                                jdbcTemplate);
                String api_username =
                        resolveCredentialValue(
                                "gw_airtelmoney_api_username",
                                merchantId,
                                useMerchantCreds,
                                jdbcTemplate);
                String api_password =
                        resolveCredentialValue(
                                "gw_airtelmoney_api_password",
                                merchantId,
                                useMerchantCreds,
                                jdbcTemplate);

                airtelmm_mmpgw = new AirtelMoneyPaymentGateway();
                airtelmm_mmpgw.setApiDetails(global_url, api_username, api_password);

                GateWayResponse pResponse = airtelmm_mmpgw.doPayOut(amount, msisdn, ref, narrative);
                return pResponse;
            }
        }

        return null;
    }

    public GateWayResponse runPayGatewayDoCheckStatus(
            NamedParameterJdbcTemplate jdbcTemplate,
            String gateway_id,
            String ref,
            String tx_type,
            Long merchantId) {

        String app_setting_app_url =
                Common.getSettings("app_setting_app_url", jdbcTemplate) == null
                        ? ""
                        : Common.getSettings("app_setting_app_url", jdbcTemplate)
                                .getSetting_value();

        // If it's a sandbox, just simulate.
        String state =
                Common.getSettings("application_settings_state", jdbcTemplate) == null
                        ? "production"
                        : Common.getSettings("application_settings_state", jdbcTemplate)
                                .getSetting_value();
        String simulateTransactions =
                Common.getSettings("simulate_transactions", jdbcTemplate) == null
                        ? "yes"
                        : Common.getSettings("simulate_transactions", jdbcTemplate)
                                .getSetting_value();

        if (simulateTransactions.equalsIgnoreCase("yes")) {
            if (state.equalsIgnoreCase("sandbox")) {
                return sandboxrunPayGatewayDoCheckStatus(ref);
            }
        }

        boolean useMerchantCreds = Common.useMerchantProviderCredentials(jdbcTemplate);

        // Select the gateway
        if (gateway_id.equals("MTNMoMoPaymentGateway")) {
            Setting env =
                    resolveCredentialSetting(
                            "gw_mtn_api_env", merchantId, useMerchantCreds, jdbcTemplate);
            if (useMerchantCreds
                    && (env == null
                            || env.getSetting_value() == null
                            || env.getSetting_value().trim().isEmpty())) {
                return merchantCredsMissingError("MTN MoMo", "gw_mtn_api_env");
            }
            String global_url = "";
            String api_collections_user = "";
            String api_collections_key = "";
            String api_collections_subscription = "";

            String api_disbursements_user = "";
            String api_disbursements_key = "";
            String api_disbursements_subscription = "";
            String base_currency = "";

            if (env.getSetting_value().equals("mtnuganda")) {
                global_url =
                        resolveCredentialValue(
                                "gw_mtn_api_url", merchantId, useMerchantCreds, jdbcTemplate);
                api_collections_user =
                        resolveCredentialValue(
                                "gw_mtn_api_collections_user_id",
                                merchantId,
                                useMerchantCreds,
                                jdbcTemplate);
                api_collections_key =
                        resolveCredentialValue(
                                "gw_mtn_api_collections_user_key",
                                merchantId,
                                useMerchantCreds,
                                jdbcTemplate);
                api_collections_subscription =
                        resolveCredentialValue(
                                "gw_mtn_api_collections_subscription_key",
                                merchantId,
                                useMerchantCreds,
                                jdbcTemplate);

                api_disbursements_user =
                        resolveCredentialValue(
                                "gw_mtn_api_disbursements_user_id",
                                merchantId,
                                useMerchantCreds,
                                jdbcTemplate);
                api_disbursements_key =
                        resolveCredentialValue(
                                "gw_mtn_api_disbursements_user_key",
                                merchantId,
                                useMerchantCreds,
                                jdbcTemplate);
                api_disbursements_subscription =
                        resolveCredentialValue(
                                "gw_mtn_api_disbursements_subscription_key",
                                merchantId,
                                useMerchantCreds,
                                jdbcTemplate);
                base_currency =
                        resolveCredentialValue(
                                "gw_mtn_api_base_currency",
                                merchantId,
                                useMerchantCreds,
                                jdbcTemplate);
            } else {
                global_url =
                        resolveCredentialValue(
                                "gw_mtn_api_url_sandbox",
                                merchantId,
                                useMerchantCreds,
                                jdbcTemplate);
                api_collections_user =
                        resolveCredentialValue(
                                "gw_mtn_api_collections_user_id_sandbox",
                                merchantId,
                                useMerchantCreds,
                                jdbcTemplate);
                api_collections_key =
                        resolveCredentialValue(
                                "gw_mtn_api_collections_user_key_sandbox",
                                merchantId,
                                useMerchantCreds,
                                jdbcTemplate);
                api_collections_subscription =
                        resolveCredentialValue(
                                "gw_mtn_api_collections_subscription_key_sandbox",
                                merchantId,
                                useMerchantCreds,
                                jdbcTemplate);

                api_disbursements_user =
                        resolveCredentialValue(
                                "gw_mtn_api_disbursements_user_id_sandbox",
                                merchantId,
                                useMerchantCreds,
                                jdbcTemplate);
                api_disbursements_key =
                        resolveCredentialValue(
                                "gw_mtn_api_disbursements_user_key_sandbox",
                                merchantId,
                                useMerchantCreds,
                                jdbcTemplate);
                api_disbursements_subscription =
                        resolveCredentialValue(
                                "gw_mtn_api_disbursements_subscription_key_sandbox",
                                merchantId,
                                useMerchantCreds,
                                jdbcTemplate);
                base_currency =
                        resolveCredentialValue(
                                "gw_mtn_api_base_currency_sandbox",
                                merchantId,
                                useMerchantCreds,
                                jdbcTemplate);
            }

            mtn_mmpgw = new MTNMoMoPaymentGateway();
            mtn_mmpgw.setApiDetails(
                    global_url,
                    api_collections_user,
                    api_collections_key,
                    api_collections_subscription,
                    api_disbursements_user,
                    api_disbursements_key,
                    api_disbursements_subscription,
                    env.getSetting_value(),
                    base_currency);

            mtn_mmpgw.setSegment(tx_type);
            GateWayResponse pResponse = mtn_mmpgw.checkStatus(ref);
            return pResponse;
        }

        if (gateway_id.equals("SafariComPaymentGateway")) {
            Setting env =
                    resolveCredentialSetting(
                            "gw_safaricom_api_env", merchantId, useMerchantCreds, jdbcTemplate);
            if (useMerchantCreds
                    && (env == null
                            || env.getSetting_value() == null
                            || env.getSetting_value().trim().isEmpty())) {
                return merchantCredsMissingError("Safaricom", "gw_safaricom_api_env");
            }
            String global_url =
                    resolveCredentialValue(
                            "gw_safaricom_api_url", merchantId, useMerchantCreds, jdbcTemplate);
            String gw_safaricom_api_shortcode =
                    resolveCredentialValue(
                            "gw_safaricom_api_shortcode",
                            merchantId,
                            useMerchantCreds,
                            jdbcTemplate);
            String gw_safaricom_api_password =
                    resolveCredentialValue(
                            "gw_safaricom_api_password",
                            merchantId,
                            useMerchantCreds,
                            jdbcTemplate);
            String gw_safaricom_api_consumer_key =
                    resolveCredentialValue(
                            "gw_safaricom_api_consumer_key",
                            merchantId,
                            useMerchantCreds,
                            jdbcTemplate);
            String gw_safaricom_api_consumer_secret =
                    resolveCredentialValue(
                            "gw_safaricom_api_consumer_secret",
                            merchantId,
                            useMerchantCreds,
                            jdbcTemplate);

            safaricom_mmpgw = new SafariComPaymentGateway();
            Setting safVer3 = Common.getSettings("gw_safaricom_api_version", jdbcTemplate);
            if (safVer3 != null) safaricom_mmpgw.setApiVersion(safVer3.getSetting_value());
            if (tx_type.equals("collection")) {
                safaricom_mmpgw.setSegment("collection");
                safaricom_mmpgw.setApiDetails(
                        global_url,
                        gw_safaricom_api_consumer_key,
                        gw_safaricom_api_consumer_secret,
                        gw_safaricom_api_shortcode,
                        gw_safaricom_api_password,
                        env.getSetting_value(),
                        app_setting_app_url);
            } else {
                String api_disbursements_user =
                        resolveCredentialValue(
                                "gw_safaricom_api_consumer_key_disbursement",
                                merchantId,
                                useMerchantCreds,
                                jdbcTemplate);
                String api_disbursements_key =
                        resolveCredentialValue(
                                "gw_safaricom_api_consumer_secret_disbursement",
                                merchantId,
                                useMerchantCreds,
                                jdbcTemplate);
                String shortcode =
                        resolveCredentialValue(
                                "gw_safaricom_api_shortcode_disbursement",
                                merchantId,
                                useMerchantCreds,
                                jdbcTemplate);
                String initiatorUsername =
                        resolveCredentialValue(
                                "gw_safaricom_api_initiator_username_disbursement",
                                merchantId,
                                useMerchantCreds,
                                jdbcTemplate);
                String initiatorPassword =
                        resolveCredentialValue(
                                "gw_safaricom_api_initiator_pw_disbursement",
                                merchantId,
                                useMerchantCreds,
                                jdbcTemplate);
                safaricom_mmpgw.setApiDetails(
                        resolveCredentialValue(
                                "gw_safaricom_api_url", merchantId, useMerchantCreds, jdbcTemplate),
                        api_disbursements_user,
                        api_disbursements_key,
                        initiatorUsername,
                        initiatorPassword,
                        shortcode,
                        "disbursement",
                        app_setting_app_url);
                safaricom_mmpgw.setSegment("disbursement");
            }

            safaricom_mmpgw.setSegment(tx_type);
            GateWayResponse pResponse = safaricom_mmpgw.checkStatus(ref);
            return pResponse;
        }

        String use_open_api =
                resolveCredentialValue(
                        "gw_airtelmoney_use_open_api", merchantId, useMerchantCreds, jdbcTemplate);
        if (useMerchantCreds && use_open_api.isEmpty()) {
            return merchantCredsMissingError("Airtel Money", "gw_airtelmoney_use_open_api");
        }

        // Do another gateway.
        if (use_open_api.equals("yes")) {
            if (gateway_id.equals("AirtelMoneyOpenApiPaymentGateway")) {

                String global_url =
                        resolveCredentialValue(
                                "gw_airtelmoney_api_url",
                                merchantId,
                                useMerchantCreds,
                                jdbcTemplate);
                String api_username =
                        resolveCredentialValue(
                                "gw_airtelmoney_api_username",
                                merchantId,
                                useMerchantCreds,
                                jdbcTemplate);
                String api_password =
                        resolveCredentialValue(
                                "gw_airtelmoney_api_password",
                                merchantId,
                                useMerchantCreds,
                                jdbcTemplate);
                String api_pin =
                        resolveCredentialValue(
                                "gw_airtelmoney_api_pin",
                                merchantId,
                                useMerchantCreds,
                                jdbcTemplate);

                airteloapimm_mmpgw = new AirtelMoneyOpenApiPaymentGateway();
                airteloapimm_mmpgw.setApiDetails(global_url, api_username, api_password, api_pin);
                configureAirtelOpenApiEndpoints(
                        airteloapimm_mmpgw, merchantId, useMerchantCreds, jdbcTemplate);
                Setting airtelPublicKey =
                        resolveCredentialSetting(
                                "gw_airtelmoney_api_public_key",
                                merchantId,
                                useMerchantCreds,
                                jdbcTemplate);
                if (airtelPublicKey != null)
                    airteloapimm_mmpgw.setPublicKey(airtelPublicKey.getSetting_value());
                airteloapimm_mmpgw.setSegment(tx_type);

                if (requireRecordedAirtelScope) {
                    net.citotech.cito.gateway.AirtelRecoveryScopeStore.require(
                            jdbcTemplate,
                            ref,
                            merchantId,
                            "disbursement".equalsIgnoreCase(tx_type) ? "PAYOUT" : "COLLECT",
                            useMerchantCreds ? "LEGACY_MERCHANT" : "LEGACY_PLATFORM",
                            global_url,
                            api_username,
                            "UG",
                            "UGX");
                }
                GateWayResponse pResponse = airteloapimm_mmpgw.checkStatus(ref);
                return pResponse;
            }
        } else {
            if (gateway_id.equals("AirtelMoneyPaymentGateway")) {

                String global_url =
                        resolveCredentialValue(
                                "gw_airtelmoney_api_url",
                                merchantId,
                                useMerchantCreds,
                                jdbcTemplate);
                String api_username =
                        resolveCredentialValue(
                                "gw_airtelmoney_api_username",
                                merchantId,
                                useMerchantCreds,
                                jdbcTemplate);
                String api_password =
                        resolveCredentialValue(
                                "gw_airtelmoney_api_password",
                                merchantId,
                                useMerchantCreds,
                                jdbcTemplate);

                airtelmm_mmpgw = new AirtelMoneyPaymentGateway();
                airtelmm_mmpgw.setApiDetails(global_url, api_username, api_password);
                airtelmm_mmpgw.setSegment(tx_type);

                GateWayResponse pResponse = airtelmm_mmpgw.checkStatus(ref);
                return pResponse;
            }
        }
        // Another gateway
        return null;
    }

    public static net.citotech.cito.Model.AccountInfo getAccountInfo(
            String msisdn, NamedParameterJdbcTemplate jdbcTemplate) {
        String gateway_id = DoPayGateway.getGatewayIdByMsisdn(msisdn, jdbcTemplate);
        if (gateway_id == null) return null;

        Setting env = Common.getSettings("gw_mtn_api_env", jdbcTemplate);
        PaymentGateway.AccountInfo raw = null;

        if (MTNMoMoPaymentGateway.gateway_id.equals(gateway_id)) {
            String global_url;
            String api_collections_user, api_collections_key, api_collections_subscription;
            String api_disbursements_user,
                    api_disbursements_key,
                    api_disbursements_subscription,
                    base_currency;
            if (env != null && env.getSetting_value().equals("mtnuganda")) {
                global_url = Common.getSettings("gw_mtn_api_url", jdbcTemplate).getSetting_value();
                api_collections_user =
                        Common.getSettings("gw_mtn_api_collections_user_id", jdbcTemplate)
                                .getSetting_value();
                api_collections_key =
                        Common.getSettings("gw_mtn_api_collections_user_key", jdbcTemplate)
                                .getSetting_value();
                api_collections_subscription =
                        Common.getSettings("gw_mtn_api_collections_subscription_key", jdbcTemplate)
                                .getSetting_value();
                api_disbursements_user =
                        Common.getSettings("gw_mtn_api_disbursements_user_id", jdbcTemplate)
                                .getSetting_value();
                api_disbursements_key =
                        Common.getSettings("gw_mtn_api_disbursements_user_key", jdbcTemplate)
                                .getSetting_value();
                api_disbursements_subscription =
                        Common.getSettings(
                                        "gw_mtn_api_disbursements_subscription_key", jdbcTemplate)
                                .getSetting_value();
                base_currency =
                        Common.getSettings("gw_mtn_api_base_currency", jdbcTemplate)
                                .getSetting_value();
            } else {
                global_url =
                        Common.getSettings("gw_mtn_api_url_sandbox", jdbcTemplate)
                                .getSetting_value();
                api_collections_user =
                        Common.getSettings("gw_mtn_api_collections_user_id_sandbox", jdbcTemplate)
                                .getSetting_value();
                api_collections_key =
                        Common.getSettings("gw_mtn_api_collections_user_key_sandbox", jdbcTemplate)
                                .getSetting_value();
                api_collections_subscription =
                        Common.getSettings(
                                        "gw_mtn_api_collections_subscription_key_sandbox",
                                        jdbcTemplate)
                                .getSetting_value();
                api_disbursements_user =
                        Common.getSettings("gw_mtn_api_disbursements_user_id_sandbox", jdbcTemplate)
                                .getSetting_value();
                api_disbursements_key =
                        Common.getSettings(
                                        "gw_mtn_api_disbursements_user_key_sandbox", jdbcTemplate)
                                .getSetting_value();
                api_disbursements_subscription =
                        Common.getSettings(
                                        "gw_mtn_api_disbursements_subscription_key_sandbox",
                                        jdbcTemplate)
                                .getSetting_value();
                base_currency =
                        Common.getSettings("gw_mtn_api_base_currency_sandbox", jdbcTemplate)
                                .getSetting_value();
            }
            MTNMoMoPaymentGateway mtn = new MTNMoMoPaymentGateway();
            mtn.setSegment("collection");
            mtn.setApiDetails(
                    global_url,
                    api_collections_user,
                    api_collections_key,
                    api_collections_subscription,
                    api_disbursements_user,
                    api_disbursements_key,
                    api_disbursements_subscription,
                    env != null ? env.getSetting_value() : "sandbox",
                    base_currency);
            raw = mtn.getAccountInfo(msisdn);
        } else if (AirtelMoneyOpenApiPaymentGateway.gateway_id.equals(gateway_id)) {
            String global_url =
                    Common.getSettings("gw_airtelmoney_api_url", jdbcTemplate).getSetting_value();
            String api_username =
                    Common.getSettings("gw_airtelmoney_api_username", jdbcTemplate)
                            .getSetting_value();
            String api_password =
                    Common.getSettings("gw_airtelmoney_api_password", jdbcTemplate)
                            .getSetting_value();
            String api_pin =
                    Common.getSettings("gw_airtelmoney_api_pin", jdbcTemplate).getSetting_value();
            AirtelMoneyOpenApiPaymentGateway airtel = new AirtelMoneyOpenApiPaymentGateway();
            airtel.setApiDetails(global_url, api_username, api_password, api_pin);
            configureAirtelOpenApiEndpoints(airtel, null, false, jdbcTemplate);
            Setting airtelPublicKey =
                    Common.getSettings("gw_airtelmoney_api_public_key", jdbcTemplate);
            if (airtelPublicKey != null) airtel.setPublicKey(airtelPublicKey.getSetting_value());
            raw = airtel.getAccountInfo(msisdn);
        }

        if (raw == null) return null;
        net.citotech.cito.Model.AccountInfo result = new net.citotech.cito.Model.AccountInfo();
        result.setMsisdn(raw.getMsisdn());
        result.setFirstName(raw.getFirstName());
        result.setLastName(raw.getLastName());
        result.setStatus(raw.getStatus());
        result.setProvided_name(raw.getProvided_name());
        return result;
    }
}
