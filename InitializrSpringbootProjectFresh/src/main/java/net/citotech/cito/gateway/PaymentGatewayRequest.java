package net.citotech.cito.gateway;

import java.util.Collections;
import java.util.Map;

/** Request object used by channel adapters for collect and payout operations. */
public class PaymentGatewayRequest {
    private final String merchantNumber;
    private final String accountIdentifier;
    private final java.math.BigDecimal amount;
    private final String reference;
    private final String description;
    private final String callbackUrl;
    private final Map<String, String> metadata;

    public PaymentGatewayRequest(
            String merchantNumber,
            String accountIdentifier,
            Double amount,
            String reference,
            String description,
            String callbackUrl,
            Map<String, String> metadata) {
        this(
                merchantNumber,
                accountIdentifier,
                java.math.BigDecimal.valueOf(amount),
                reference,
                description,
                callbackUrl,
                metadata);
    }

    public PaymentGatewayRequest(
            String merchantNumber,
            String accountIdentifier,
            java.math.BigDecimal amount,
            String reference,
            String description,
            String callbackUrl,
            Map<String, String> metadata) {
        this.merchantNumber = merchantNumber;
        this.accountIdentifier = accountIdentifier;
        this.amount = amount;
        this.reference = reference;
        this.description = description;
        this.callbackUrl = callbackUrl;
        this.metadata =
                metadata == null ? Collections.emptyMap() : Collections.unmodifiableMap(metadata);
    }

    public String getMerchantNumber() {
        return merchantNumber;
    }

    public String getAccountIdentifier() {
        return accountIdentifier;
    }

    public Double getAmount() {
        return amount.doubleValue();
    }

    public java.math.BigDecimal getAmountDecimal() {
        return amount;
    }

    public String getReference() {
        return reference;
    }

    public String getDescription() {
        return description;
    }

    public String getCallbackUrl() {
        return callbackUrl;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }
}
