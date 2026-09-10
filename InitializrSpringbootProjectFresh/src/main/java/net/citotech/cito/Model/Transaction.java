package net.citotech.cito.Model;

import java.math.BigDecimal;
import java.util.ArrayList;
import net.citotech.cito.money.MoneyAmount;

public class Transaction {
    public static String TX_TYPE_FLOAT_STOCK_CREDIT = "FLOAT STOCK CREDIT";
    public static String TX_TYPE_FLOAT_STOCK_CREDIT_REVERSAL = "FLOAT STOCK CREDIT REVERSAL";
    public static String TX_TYPE_FLOAT_STOCK_DEBIT = "FLOAT STOCK DEBIT";
    public static String TX_TYPE_FLOAT_STOCK_DEBIT_RESERVAL = "FLOAT STOCK DEBIT REVERSAL";
    public static String TX_TYPE_PAYIN_REVENUE = "PAYIN REVENUE";
    public static String TX_TYPE_PAYOUT_REVENUE = "PAYOUT REVENUE";
    public static String TX_TYPE_PAYIN_CHARGE = "PAYIN CHARGE";
    public static String TX_TYPE_PAYOUT_CHARGE = "PAYOUT CHARGE";
    public static String TX_TYPE_PAYIN = "PAYIN";
    public static String TX_TYPE_PAYOUT = "PAYOUT";
    public static String TX_TYPE_PAYOUT_REVERSAL = "PAYOUT REVERSAL";
    public static String TX_TYPE_PAYOUT_CHARGE_REVERSAL = "PAYOUT CHARGE REVERSAL";
    public static String TX_TYPE_FLOAT_CREDIT = "FLOAT CREDIT";
    public static String TX_TYPE_FLOAT_DEDBIT = "FLOAT DEBIT";
    public static String TX_TYPE_SMS_PURCHASE = "SMS PURCHASE";
    public static String TX_TYPE_SMS_CUSTOMER_CHARGE = "SMS CHARGE";
    public static String TX_TYPE_SMS_CUSTOMER_CHARGE_REVERSAL = "SMS CHARGE REVERSAL";
    public static String TX_TYPE_PAYOUT_CHARGE_SETTLEMENT = "PAY OUT CHARGE SETTLEMENT";
    public static String TX_TYPE_PAYOUT_SETTLEMENT = "PAY OUT SETTLEMENT";
    public static String BALANCE_TYPE_MTNMM_BALANCE = "mtnmm_balance";
    public static String BALANCE_TYPE_AIRTELMM_BALANCE = "airtelmm_balance";
    public static String BALANCE_TYPE_SMS_BALANCE = "sms_balance";
    public static String BATCH_PAYMENT_UNPAID = "UNPAID";
    public static String BATCH_PAYMENT_PAID = "PAID";
    public static String BATCH_PAYMENT_INPROGRESS = "INPROGRESS";
    public static String BATCH_PAYMENT_FAILED = "FAILED";
    public static String BATCH_PAYMENTS_PENDING = "PENDING";
    public static String BATCH_PAYMENTS_PROCESSING = "PROCESSING";
    public static String BATCH_PAYMENTS_PAUSED = "PAUSED";
    public static String BATCH_PAYMENTS_DONE = "DONE";
    public static String BATCH_PAYMENTS_STOPPED = "STOPPED";

    long id;
    String merchant_id;
    String gateway_id;
    BigDecimal original_amount = BigDecimal.ZERO.setScale(MoneyAmount.SCALE);
    BigDecimal charges = BigDecimal.ZERO.setScale(MoneyAmount.SCALE);
    String status;
    String charging_method;
    String tx_request_trace;
    String tx_update_trace;
    String tx_description;
    String tx_merchant_description;
    String tx_unique_id;
    String tx_gateway_ref;
    String tx_merchant_ref;
    String created_on;
    String updated_on;
    String payer_number;
    String tx_type;
    BigDecimal tx_cost = BigDecimal.ZERO.setScale(MoneyAmount.SCALE);
    String callback_url;
    String callback_trace;
    Long merchant_batch_transactions_log_id;
    Long beneficiary_id;
    String resolved_by;
    String originate_ip = "";
    String safaricomRequestReference = "";
    boolean isFinalStatusSet = false;
    String currency = "";
    String callback_status = "PENDING";

    public static ArrayList<String> getCreditTxTypes() {
        ArrayList<String> r = new ArrayList<>();
        r.add(TX_TYPE_FLOAT_STOCK_CREDIT);
        r.add(TX_TYPE_PAYIN_REVENUE);
        r.add(TX_TYPE_PAYOUT_REVENUE);
        r.add(TX_TYPE_PAYIN);
        r.add(TX_TYPE_FLOAT_CREDIT);
        r.add(TX_TYPE_PAYOUT_REVERSAL);
        r.add(TX_TYPE_PAYOUT_CHARGE_REVERSAL);
        return r;
    }

    public static ArrayList<String> getDebitTxTypes() {
        ArrayList<String> r = new ArrayList<>();
        r.add(TX_TYPE_FLOAT_STOCK_DEBIT);
        r.add(TX_TYPE_PAYIN_CHARGE);
        r.add(TX_TYPE_PAYOUT_CHARGE);
        r.add(TX_TYPE_PAYOUT);
        r.add(TX_TYPE_FLOAT_STOCK_DEBIT_RESERVAL);
        r.add(TX_TYPE_FLOAT_DEDBIT);
        r.add(TX_TYPE_PAYOUT_CHARGE_SETTLEMENT);
        r.add(TX_TYPE_PAYOUT_SETTLEMENT);
        return r;
    }

    private String environment = "PRODUCTION";

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String value) {
        this.environment = value == null ? "PRODUCTION" : value;
    }

    private BigDecimal money(Double value) {
        return value == null
                ? BigDecimal.ZERO.setScale(MoneyAmount.SCALE)
                : MoneyAmount.normalize(BigDecimal.valueOf(value));
    }

    public boolean isFinalStatusSet() {
        return isFinalStatusSet;
    }

    public void setFinalStatusSet(boolean finalStatusSet) {
        isFinalStatusSet = finalStatusSet;
    }

    public String getSafaricomRequestReference() {
        return safaricomRequestReference;
    }

    public void setSafaricomRequestReference(String safaricomRequestReference) {
        this.safaricomRequestReference = safaricomRequestReference;
    }

    public String getOriginate_ip() {
        return originate_ip;
    }

    public void setOriginate_ip(String originate_ip) {
        this.originate_ip = originate_ip;
    }

    public Long getBeneficiary_id() {
        return beneficiary_id;
    }

    public void setBeneficiary_id(Long beneficiary_id) {
        this.beneficiary_id = beneficiary_id;
    }

    public String getCallback_trace() {
        return callback_trace;
    }

    public void setCallback_trace(String callback_trace) {
        this.callback_trace = callback_trace;
    }

    public String getCallback_url() {
        return callback_url;
    }

    public void setCallback_url(String callback_url) {
        this.callback_url = callback_url;
    }

    public Long getMerchant_batch_transactions_log_id() {
        return merchant_batch_transactions_log_id;
    }

    public void setMerchant_batch_transactions_log_id(Long merchant_batch_transactions_log_id) {
        this.merchant_batch_transactions_log_id = merchant_batch_transactions_log_id;
    }

    public Double getTx_cost() {
        return tx_cost.doubleValue();
    }

    public BigDecimal getTxCostDecimal() {
        return tx_cost;
    }

    public void setTx_cost(Double tx_cost) {
        this.tx_cost = money(tx_cost);
    }

    public void setTxCostDecimal(BigDecimal tx_cost) {
        this.tx_cost =
                tx_cost == null
                        ? BigDecimal.ZERO.setScale(MoneyAmount.SCALE)
                        : MoneyAmount.normalize(tx_cost);
    }

    public String getTx_type() {
        return tx_type;
    }

    public void setTx_type(String tx_type) {
        this.tx_type = tx_type;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getMerchant_id() {
        return merchant_id;
    }

    public void setMerchant_id(String merchant_id) {
        this.merchant_id = merchant_id;
    }

    public String getGateway_id() {
        return gateway_id;
    }

    public void setGateway_id(String gateway_id) {
        this.gateway_id = gateway_id;
    }

    public Double getOriginal_amount() {
        return original_amount.doubleValue();
    }

    public BigDecimal getOriginalAmountDecimal() {
        return original_amount;
    }

    public void setOriginal_amount(Double original_amount) {
        this.original_amount = money(original_amount);
    }

    public void setOriginalAmountDecimal(BigDecimal original_amount) {
        this.original_amount =
                original_amount == null
                        ? BigDecimal.ZERO.setScale(MoneyAmount.SCALE)
                        : MoneyAmount.normalize(original_amount);
    }

    public Double getCharges() {
        return charges.doubleValue();
    }

    public BigDecimal getChargesDecimal() {
        return charges;
    }

    public void setCharges(Double charges) {
        this.charges = money(charges);
    }

    public void setChargesDecimal(BigDecimal charges) {
        this.charges =
                charges == null
                        ? BigDecimal.ZERO.setScale(MoneyAmount.SCALE)
                        : MoneyAmount.normalize(charges);
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getCharging_method() {
        return charging_method;
    }

    public void setCharging_method(String charging_method) {
        this.charging_method = charging_method;
    }

    public String getTx_request_trace() {
        return tx_request_trace;
    }

    public void setTx_request_trace(String tx_request_trace) {
        this.tx_request_trace = tx_request_trace;
    }

    public String getTx_update_trace() {
        return tx_update_trace;
    }

    public void setTx_update_trace(String tx_update_trace) {
        this.tx_update_trace = tx_update_trace;
    }

    public String getTx_description() {
        return tx_description;
    }

    public void setTx_description(String tx_description) {
        this.tx_description = tx_description;
    }

    public String getTx_merchant_description() {
        return tx_merchant_description;
    }

    public void setTx_merchant_description(String tx_merchant_description) {
        this.tx_merchant_description = tx_merchant_description;
    }

    public String getTx_unique_id() {
        return tx_unique_id;
    }

    public void setTx_unique_id(String tx_unique_id) {
        this.tx_unique_id = tx_unique_id;
    }

    public String getTx_gateway_ref() {
        return tx_gateway_ref;
    }

    public void setTx_gateway_ref(String tx_gateway_ref) {
        this.tx_gateway_ref = tx_gateway_ref;
    }

    public String getTx_merchant_ref() {
        return tx_merchant_ref;
    }

    public void setTx_merchant_ref(String tx_merchant_ref) {
        this.tx_merchant_ref = tx_merchant_ref;
    }

    public String getCreated_on() {
        return created_on;
    }

    public void setCreated_on(String created_on) {
        this.created_on = created_on;
    }

    public String getUpdated_on() {
        return updated_on;
    }

    public void setUpdated_on(String updated_on) {
        this.updated_on = updated_on;
    }

    public String getPayer_number() {
        return payer_number;
    }

    public void setPayer_number(String payer_number) {
        this.payer_number = payer_number;
    }

    public String getResolved_by() {
        return resolved_by;
    }

    public void setResolved_by(String resolved_by) {
        this.resolved_by = resolved_by;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency != null ? currency : "";
    }

    public String getCallback_status() {
        return callback_status;
    }

    public void setCallback_status(String callback_status) {
        this.callback_status = callback_status != null ? callback_status : "PENDING";
    }
}
