package net.citotech.cito.ledger;

import net.citotech.cito.Model.Transaction;
import org.springframework.stereotype.Component;

/** Bridges the compatibility finalizer to the canonical ledger in its database transaction. */
@Component
public class PaymentSettlementRegistry {
    private static volatile PaymentLedgerSettlementService service;

    public PaymentSettlementRegistry(PaymentLedgerSettlementService service) {
        PaymentSettlementRegistry.service = service;
    }

    public static void apply(Transaction tx) {
        if (service == null)
            throw new IllegalStateException("Payment ledger finalizer is unavailable");
        service.applyTerminalProviderOutcome(tx, tx.getStatus());
    }
}
