package net.citotech.cito.gateway;

import net.citotech.cito.*;
import net.citotech.cito.Model.*;
import net.citotech.cito.api.v2.AdapterNativePaymentService;
import net.citotech.cito.api.v2.dto.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/** Compatibility APIs and merchant batches enter the same durable adapter lifecycle. */
@Component
public class MobileMoneyCompatibilityBridge {
    private static volatile MobileMoneyCompatibilityBridge instance;
    private final AdapterNativePaymentService payments;
    private final String environment;
    private final org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc;

    private final org.springframework.transaction.support.TransactionTemplate reads;

    public MobileMoneyCompatibilityBridge(
            @Lazy AdapterNativePaymentService payments,
            @Value("${custom.gatewaystate:SANDBOX}") String environment,
            org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc,
            org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.reads =
                new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        this.reads.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.payments = payments;
        this.environment = environment;
        instance = this;
    }

    public static boolean manages(Transaction tx) {
        return LegacyGatewayIds.MTN_MOMO.equals(tx.getGateway_id())
                || LegacyGatewayIds.AIRTEL_OPEN_API.equals(tx.getGateway_id());
    }

    public static String submit(Transaction tx, Merchant merchant, boolean payout) {
        MobileMoneyCompatibilityBridge bridge = instance;
        if (bridge == null)
            throw new PaymentGatewayException("Mobile-money payment lifecycle is unavailable");
        PaymentRequest request = new PaymentRequest();
        request.setMerchantNumber(merchant.getAccount_number());
        request.setReference(tx.getTx_merchant_ref());
        request.setAmount(tx.getOriginalAmountDecimal().toPlainString());
        request.setCurrency(
                tx.getCurrency() == null || tx.getCurrency().isBlank() ? "UGX" : tx.getCurrency());
        request.setCountry("UG");
        request.setChannel(
                LegacyGatewayIds.MTN_MOMO.equals(tx.getGateway_id())
                        ? "mtn_momo"
                        : "airtel_open_api");
        request.setDescription(tx.getTx_merchant_description());
        request.setCallbackUrl(tx.getCallback_url());
        PaymentPartyRequest party = new PaymentPartyRequest();
        party.setValue(tx.getPayer_number());
        if (payout) request.setPayee(party);
        else request.setPayer(party);
        PaymentResult result =
                payout
                        ? bridge.payments.payout(
                                request,
                                merchant,
                                bridge.environment.toUpperCase(java.util.Locale.ROOT))
                        : bridge.payments.collect(
                                request,
                                merchant,
                                bridge.environment.toUpperCase(java.util.Locale.ROOT));
        tx.setTx_unique_id(result.getTransactionId());
        tx.setStatus(result.getStatus());
        Transaction persisted =
                result.getTransactionId() == null
                        ? null
                        : bridge.reads.execute(
                                ignored ->
                                        Common.getTxByRef(result.getTransactionId(), bridge.jdbc));
        if (persisted != null) {
            tx.setId(persisted.getId());
            tx.setTx_gateway_ref(persisted.getTx_gateway_ref());
            tx.setCharges(persisted.getCharges());
            tx.setTx_cost(persisted.getTx_cost());
        }
        GateWayResponse response = new GateWayResponse();
        response.setOurUniqueTxId(result.getTransactionId());
        response.setTransactionStatus(result.getStatus());
        response.setMessage(result.getMessage());
        response.setStatus("OK");
        return GeneralSuccessResponse.getApiTxMessage("000", result.getMessage(), response);
    }
}
