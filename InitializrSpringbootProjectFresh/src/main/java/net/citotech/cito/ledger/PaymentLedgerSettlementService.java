package net.citotech.cito.ledger;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.citotech.cito.Common;
import net.citotech.cito.Model.Merchant;
import net.citotech.cito.Model.Transaction;
import net.citotech.cito.gateway.PaymentGatewayException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies the canonical double-entry payment lifecycle only when provider evidence is terminal.
 *
 * <p>Provider acceptance (for example MTN HTTP 202) is not settlement. Collections therefore do
 * not post until provider-confirmed success, while payouts remain reserved until success or
 * failure is confirmed. The service also repairs ledger entries written by the older eager-posting
 * path by reversing them if the provider later confirms failure.
 */
@Service
public class PaymentLedgerSettlementService {
    private final DoubleEntryLedgerService ledgerService;
    private final NamedParameterJdbcTemplate jdbcTemplate;

    public PaymentLedgerSettlementService(
            DoubleEntryLedgerService ledgerService,
            NamedParameterJdbcTemplate jdbcTemplate) {
        this.ledgerService = ledgerService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public String reservePayout(Transaction tx, Merchant merchant) {
        requireTransaction(tx);
        requireMerchant(merchant);
        String reference = reservationReference(tx, merchant);
        ledgerService.reserve(
                reference,
                merchant.getId(),
                merchantReference(tx),
                tx.getOriginalAmountDecimal().add(tx.getChargesDecimal()),
                currency(tx));
        return reference;
    }

    /**
     * Finalize after an asynchronous reconciler has only the stored transaction and must resolve
     * its merchant from persistence.
     */
    @Transactional
    public void applyTerminalProviderOutcome(Transaction tx, String providerStatus) {
        requireTransaction(tx);
        String status = normalize(providerStatus);
        if (!isTerminal(status)) {
            return;
        }
        Merchant merchant = Common.getMerchantById(tx.getMerchant_id(), jdbcTemplate);
        requireMerchant(merchant);
        applyTerminalProviderOutcome(tx, providerStatus, merchant);
    }

    /** Finalize with an already-authenticated/loaded merchant, avoiding a redundant DB lookup. */
    @Transactional
    public void applyTerminalProviderOutcome(
            Transaction tx, String providerStatus, Merchant merchant) {
        requireTransaction(tx);
        requireMerchant(merchant);
        String status = normalize(providerStatus);
        if (!isTerminal(status)) {
            return;
        }

        if ("SUCCESSFUL".equals(status)) {
            postSuccessful(tx, merchant);
            if (Transaction.TX_TYPE_PAYOUT.equalsIgnoreCase(tx.getTx_type())) {
                ledgerService.captureReservation(reservationReference(tx, merchant));
            }
            return;
        }

        // Failure must never leave value posted as if it settled. reverse() is immutable and
        // idempotent, so this also safely repairs transactions written by the former eager path.
        reversePrematurePostingIfPresent(tx);
        if (Transaction.TX_TYPE_PAYOUT.equalsIgnoreCase(tx.getTx_type())) {
            releaseFailedPayoutReservation(reservationReference(tx, merchant));
        }
    }

    /**
     * Release a reservation only when the provider call is known not to have been submitted.
     * Ambiguous transport errors after a persisted provider request must remain reserved.
     */
    @Transactional
    public void releaseUnsubmittedPayout(Transaction tx, Merchant merchant) {
        if (tx == null || merchant == null) {
            return;
        }
        releaseFailedPayoutReservation(reservationReference(tx, merchant));
    }

    private void postSuccessful(Transaction tx, Merchant merchant) {
        String currency = currency(tx);
        BigDecimal amount = tx.getOriginalAmountDecimal();
        BigDecimal charges = tx.getChargesDecimal();
        String gatewayId = required(tx.getGateway_id(), "gateway id");
        String merchantReference = merchantReference(tx);
        String providerAccount = "provider:" + gatewayId + ":" + currency + ":float";
        boolean payout = Transaction.TX_TYPE_PAYOUT.equalsIgnoreCase(tx.getTx_type());
        String merchantAccount =
                "merchant:"
                        + merchant.getId()
                        + ":"
                        + currency
                        + ":"
                        + (payout ? "payouts_payable" : "collections_payable");

        List<LedgerEntryCommand> entries = new ArrayList<>();
        if (payout) {
            entries.add(
                    entry(
                            merchantAccount,
                            "Merchant payout payable",
                            "MERCHANT_LIABILITY",
                            "MERCHANT",
                            merchant.getId(),
                            "DR",
                            amount,
                            currency,
                            merchantReference));
            entries.add(
                    entry(
                            providerAccount,
                            "Provider float",
                            "PROVIDER_FLOAT",
                            "PROVIDER",
                            null,
                            "CR",
                            amount,
                            currency,
                            merchantReference));
            if (charges != null && charges.signum() > 0) {
                entries.add(
                        entry(
                                "merchant:" + merchant.getId() + ":" + currency + ":fees",
                                "Merchant transaction fees",
                                "MERCHANT_EXPENSE",
                                "MERCHANT",
                                merchant.getId(),
                                "DR",
                                charges,
                                currency,
                                merchantReference));
                entries.add(
                        entry(
                                "cpay:" + currency + ":fee_revenue",
                                "CPay fee revenue",
                                "REVENUE",
                                "SYSTEM",
                                null,
                                "CR",
                                charges,
                                currency,
                                merchantReference));
            }
        } else if (Transaction.TX_TYPE_PAYIN.equalsIgnoreCase(tx.getTx_type())) {
            entries.add(
                    entry(
                            providerAccount,
                            "Provider float",
                            "PROVIDER_FLOAT",
                            "PROVIDER",
                            null,
                            "DR",
                            amount,
                            currency,
                            merchantReference));
            entries.add(
                    entry(
                            merchantAccount,
                            "Merchant collection payable",
                            "MERCHANT_LIABILITY",
                            "MERCHANT",
                            merchant.getId(),
                            "CR",
                            amount,
                            currency,
                            merchantReference));
        } else {
            throw new PaymentGatewayException(
                    "Unsupported payment transaction type for ledger finalization: "
                            + tx.getTx_type());
        }

        ledgerService.post(
                paymentReference(tx),
                "PAYMENT",
                tx.getTx_unique_id(),
                (payout ? "PAYOUT " : "COLLECT ") + merchantReference,
                entries);
    }

    private void reversePrematurePostingIfPresent(Transaction tx) {
        String original = paymentReference(tx);
        Integer count =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM ledger_transactions WHERE transaction_reference=:ref",
                        new MapSqlParameterSource().addValue("ref", original),
                        Integer.class);
        if (count != null && count > 0) {
            ledgerService.reverse(
                    original,
                    "payment-reversal:" + tx.getTx_unique_id(),
                    "Provider-confirmed failed payment " + merchantReference(tx));
        }
    }

    /**
     * RESERVED is the normal failure path. CAPTURED is included solely to repair historical rows
     * produced by the old eager-capture implementation after their ledger posting is reversed.
     */
    private void releaseFailedPayoutReservation(String reservationReference) {
        jdbcTemplate.update(
                "UPDATE ledger_reservations SET reservation_status='RELEASED' "
                        + "WHERE reservation_reference=:ref "
                        + "AND reservation_status IN ('RESERVED','CAPTURED')",
                new MapSqlParameterSource().addValue("ref", reservationReference));
    }

    private String reservationReference(Transaction tx, Merchant merchant) {
        return "payout-reserve:"
                + required(merchant.getAccount_number(), "merchant account number")
                + ":"
                + merchantReference(tx);
    }

    private String paymentReference(Transaction tx) {
        return "payment:" + required(tx.getTx_unique_id(), "transaction id");
    }

    private String merchantReference(Transaction tx) {
        String value = tx.getTx_merchant_ref();
        return value == null || value.isBlank()
                ? required(tx.getTx_unique_id(), "transaction id")
                : value.trim();
    }

    private String currency(Transaction tx) {
        String value = tx.getCurrency();
        // Historical MTN rows predate explicit transaction currency persistence; the schema's
        // original/default currency is UGX. New submissions always set currency explicitly.
        return value == null || value.isBlank() ? "UGX" : value.trim().toUpperCase(Locale.ROOT);
    }

    private LedgerEntryCommand entry(
            String accountCode,
            String accountName,
            String accountType,
            String ownerType,
            Long ownerId,
            String direction,
            BigDecimal amount,
            String currency,
            String memo) {
        return new LedgerEntryCommand(
                accountCode,
                accountName,
                accountType,
                ownerType,
                ownerId,
                direction,
                amount,
                currency,
                memo);
    }

    private void requireTransaction(Transaction tx) {
        if (tx == null) {
            throw new PaymentGatewayException("Payment transaction is required");
        }
        required(tx.getTx_unique_id(), "transaction id");
        required(tx.getMerchant_id(), "merchant id");
    }

    private void requireMerchant(Merchant merchant) {
        if (merchant == null || merchant.getId() == null) {
            throw new PaymentGatewayException(
                    "Cannot finalize payment ledger because merchant was not found");
        }
    }

    private boolean isTerminal(String status) {
        return "SUCCESSFUL".equals(status) || "FAILED".equals(status);
    }

    private String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new PaymentGatewayException("Payment ledger requires " + field);
        }
        return value.trim();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
