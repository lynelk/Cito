package net.citotech.cito.api.v2;

import java.util.List;
import net.citotech.cito.Common;
import net.citotech.cito.Model.Merchant;
import net.citotech.cito.Model.Transaction;
import net.citotech.cito.api.v2.dto.PaymentStatusResponse;
import net.citotech.cito.gateway.AirtelOpenApiAdapter;
import net.citotech.cito.gateway.AirtelRecoveryStore;
import net.citotech.cito.gateway.PaymentGatewayException;
import net.citotech.cito.repository.TransactionRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class PaymentStatusService {
    private final TransactionRepository transactionRepository;
    private final ObjectProvider<AirtelRecoveryStore> airtelRecovery;

    public PaymentStatusService(
            TransactionRepository transactionRepository,
            ObjectProvider<AirtelRecoveryStore> airtelRecovery) {
        this.transactionRepository = transactionRepository;
        this.airtelRecovery = airtelRecovery;
    }

    public PaymentStatusResponse getStatus(Merchant merchant, String reference) {
        validateMerchant(merchant);
        if (reference == null || reference.trim().isEmpty()) {
            throw new PaymentGatewayException("reference is required");
        }
        Transaction transaction =
                transactionRepository
                        .findByMerchantReference(merchant.getId(), reference.trim())
                        .orElse(null);
        AirtelRecoveryStore recovery = airtelRecovery.getIfAvailable();
        List<AirtelRecoveryStore.Entry> matches =
                recovery == null
                        ? List.of()
                        : recovery.findByMerchantReference(merchant.getId(), reference.trim());
        if (matches.size() > 1
                || (!matches.isEmpty()
                        && transaction != null
                        && !Long.valueOf(transaction.getId())
                                .equals(matches.get(0).transactionId())))
            throw new PaymentGatewayException("Reference is ambiguous across payment environments");
        if (transaction == null && matches.size() == 1) {
            AirtelRecoveryStore.Entry entry = matches.get(0);
            return new PaymentStatusResponse(
                    entry.merchantReference(),
                    entry.terminalStatus() == null ? "PENDING" : entry.terminalStatus(),
                    AirtelOpenApiAdapter.CHANNEL_CODE,
                    entry.provider(),
                    "SANDBOX".equals(entry.environment())
                            ? "Sandbox payment status"
                            : "Payment status");
        }
        if (transaction == null)
            throw new PaymentGatewayException("Transaction reference was not found");
        return new PaymentStatusResponse(
                transaction.getTx_merchant_ref(),
                transaction.getStatus(),
                transaction.getGateway_id(),
                transaction.getTx_gateway_ref(),
                transaction.getTx_unique_id());
    }

    private void validateMerchant(Merchant merchant) {
        if (merchant == null) {
            throw new PaymentGatewayException("Merchant was not found");
        }
        if (!"ACTIVE".equalsIgnoreCase(merchant.getStatus())) {
            throw new PaymentGatewayException("Merchant is not active");
        }
        boolean allowed = false;
        String[] allowedApis = merchant.getAllowed_apis();
        if (allowedApis != null) {
            for (String api : allowedApis) {
                if (Common.API_TRANSACTION_CHECKSTATUS.equals(api)) {
                    allowed = true;
                    break;
                }
            }
        }
        if (!allowed) {
            throw new PaymentGatewayException(
                    "Merchant is not allowed to access " + Common.API_TRANSACTION_CHECKSTATUS);
        }
    }
}
