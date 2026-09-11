package net.citotech.cito.treasury;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.citotech.cito.Model.Merchant;
import net.citotech.cito.gateway.PaymentGatewayException;
import net.citotech.cito.sharedprovider.SharedProviderAccessService.CredentialContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Authoritative accounting service for CPay-owned provider float.
 *
 * <p>The service is deliberately separate from legacy merchant balance columns. Shared-provider
 * payouts reserve float atomically before provider execution; confirmed payouts consume book
 * balance, failures release it and pending provider responses move the reservation to pending.
 * Collections increase provider float only when confirmed. All value movements emit balanced,
 * hash-chained journal pairs and merchant exposure is maintained independently from platform float.
 */
@Service
public class ProviderTreasuryService {
    private static final BigDecimal ZERO = new BigDecimal("0.0000");
    private final NamedParameterJdbcTemplate jdbc;

    public ProviderTreasuryService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> listAccounts() {
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT id, channel_code AS channelCode, environment, country_code AS"
                                + " countryCode, currency_code AS currencyCode, account_role AS"
                                + " accountRole, display_name AS displayName, parent_account_id AS"
                                + " parentAccountId, prefund_required AS prefundRequired, book_balance"
                                + " AS bookBalance, reserved_balance AS reservedBalance,"
                                + " pending_outgoing_balance AS pendingOutgoingBalance,"
                                + " pending_incoming_balance AS pendingIncomingBalance,"
                                + " provider_reported_balance AS providerReportedBalance,"
                                + " provider_balance_status AS providerBalanceStatus,"
                                + " provider_balance_updated_at AS providerBalanceUpdatedAt,"
                                + " provider_balance_message AS providerBalanceMessage,"
                                + " TIMESTAMPDIFF(SECOND,provider_balance_updated_at,CURRENT_TIMESTAMP(6)) AS providerBalanceAgeSeconds,"
                                + " low_float_threshold AS lowFloatThreshold, reconciliation_state AS"
                                + " reconciliationState, updated_at AS updatedAt FROM"
                                + " provider_treasury_accounts ORDER BY environment, country_code,"
                                + " currency_code, channel_code,"
                                + " FIELD(account_role,'MASTER','COLLECTION','DISBURSEMENT')",
                        Map.of());
        for (Map<String, Object> row : rows) enrichAccount(row);
        return rows;
    }

    public List<Map<String, Object>> listAdjustments() {
        return jdbc.queryForList(
                "SELECT id, idempotency_key AS idempotencyKey, adjustment_type AS adjustmentType,"
                        + " source_account_id AS sourceAccountId, destination_account_id AS"
                        + " destinationAccountId, amount, reason, external_reference AS"
                        + " externalReference, evidence_reference AS evidenceReference, value_date AS"
                        + " valueDate, status, requested_by AS requestedBy, requested_at AS"
                        + " requestedAt, approved_by AS approvedBy, approved_at AS approvedAt,"
                        + " rejected_by AS rejectedBy, rejected_at AS rejectedAt, posted_at AS postedAt"
                        + " FROM provider_treasury_adjustments ORDER BY requested_at DESC LIMIT 500",
                Map.of());
    }

    public List<Map<String, Object>> listReservations() {
        return jdbc.queryForList(
                "SELECT id, treasury_account_id AS treasuryAccountId, merchant_id AS merchantId,"
                        + " merchant_number AS merchantNumber, operation, direction, amount,"
                        + " currency_code AS currencyCode, merchant_reference AS merchantReference,"
                        + " provider_reference AS providerReference, status, created_at AS createdAt,"
                        + " updated_at AS updatedAt FROM provider_treasury_reservations ORDER BY"
                        + " created_at DESC LIMIT 500",
                Map.of());
    }

    public List<Map<String, Object>> listExposures() {
        return jdbc.queryForList(
                "SELECT id, merchant_id AS merchantId, channel_code AS channelCode, environment,"
                        + " country_code AS countryCode, currency_code AS currencyCode,"
                        + " reserved_outgoing AS reservedOutgoing, pending_outgoing AS pendingOutgoing,"
                        + " pending_incoming AS pendingIncoming, settled_net AS settledNet, updated_at"
                        + " AS updatedAt FROM merchant_provider_exposures ORDER BY updated_at DESC"
                        + " LIMIT 500",
                Map.of());
    }

    public List<Map<String, Object>> listJournal() {
        return jdbc.queryForList(
                "SELECT id, entry_group AS entryGroup, sequence_no AS sequenceNo,"
                        + " treasury_account_id AS treasuryAccountId, ledger_account_code AS"
                        + " ledgerAccountCode, merchant_id AS merchantId, reservation_id AS"
                        + " reservationId, adjustment_id AS adjustmentId, transaction_reference AS"
                        + " transactionReference, entry_type AS entryType, entry_side AS entrySide,"
                        + " amount, currency_code AS currencyCode, reason, external_reference AS"
                        + " externalReference, previous_hash AS previousHash, entry_hash AS entryHash,"
                        + " actor, created_at AS createdAt FROM provider_treasury_journal ORDER BY id"
                        + " DESC LIMIT 1000",
                Map.of());
    }

    public List<Map<String, Object>> listReconciliations() {
        return jdbc.queryForList(
                "SELECT id, treasury_account_id AS treasuryAccountId, statement_reference AS"
                        + " statementReference, evidence_reference AS evidenceReference, book_balance"
                        + " AS bookBalance, provider_reported_balance AS providerReportedBalance,"
                        + " variance, state, notes, reconciled_by AS reconciledBy, reconciled_at AS"
                        + " reconciledAt FROM provider_treasury_reconciliations ORDER BY reconciled_at"
                        + " DESC LIMIT 500",
                Map.of());
    }

    /**
     * Begin accounting for an actual shared-provider request. Merchant-owned execution returns
     * null.
     */
    @Transactional
    public Reservation beginShared(
            CredentialContext context,
            Merchant merchant,
            String channelCode,
            String environment,
            BigDecimal amount,
            String reference) {
        if (context == null || !context.shared()) return null;
        requireMerchant(merchant);
        BigDecimal money = money(amount);
        String operation = context.operation();
        Map<String, Object> account =
                lockAccount(
                        channelCode,
                        environment,
                        context.countryCode(),
                        context.currencyCode(),
                        operation);
        long accountId = number(account.get("id"));
        String idempotency =
                "SHARED:"
                        + operation
                        + ":"
                        + merchant.getId()
                        + ":"
                        + channelCode
                        + ":"
                        + required(reference, "reference");
        List<Map<String, Object>> existing =
                jdbc.queryForList(
                        "SELECT id, treasury_account_id, entitlement_id, merchant_id,"
                                + " merchant_number, operation, direction, amount, currency_code,"
                                + " merchant_reference, status FROM provider_treasury_reservations"
                                + " WHERE idempotency_key=:key LIMIT 1",
                        new MapSqlParameterSource().addValue("key", idempotency));
        if (!existing.isEmpty()) {
            Reservation previous = reservation(existing.get(0));
            if (previous.treasuryAccountId() != accountId
                    || previous.amount().compareTo(money) != 0
                    || !previous.currencyCode().equals(context.currencyCode())
                    || !previous.operation().equals(operation))
                throw new PaymentGatewayException(
                        "Shared-provider reference conflicts with its original financial attributes");
            return previous;
        }

        String direction = "PAYOUT".equals(operation) ? "OUTGOING" : "INCOMING";
        String status = "PAYOUT".equals(operation) ? "RESERVED" : "INITIATED";
        if ("PAYOUT".equals(operation)) {
            BigDecimal available = available(account);
            if (available.compareTo(money) < 0) {
                throw new PaymentGatewayException(
                        "Insufficient CPay provider float for "
                                + channelCode
                                + ": available="
                                + available.toPlainString());
            }
            jdbc.update(
                    "UPDATE provider_treasury_accounts SET"
                            + " reserved_balance=reserved_balance+:amount, lock_version=lock_version+1"
                            + " WHERE id=:id",
                    params(accountId, money));
            upsertExposure(
                    merchant.getId(),
                    channelCode,
                    environment,
                    context.countryCode(),
                    context.currencyCode(),
                    money,
                    ZERO,
                    ZERO,
                    ZERO);
        }

        MapSqlParameterSource p =
                new MapSqlParameterSource()
                        .addValue("key", idempotency)
                        .addValue("account", accountId)
                        .addValue("entitlement", context.entitlementId())
                        .addValue("merchant", merchant.getId())
                        .addValue("merchant_number", merchant.getAccount_number())
                        .addValue("operation", operation)
                        .addValue("direction", direction)
                        .addValue("amount", money)
                        .addValue("currency", context.currencyCode())
                        .addValue("reference", required(reference, "reference"))
                        .addValue("status", status);
        jdbc.update(
                "INSERT INTO provider_treasury_reservations (idempotency_key, treasury_account_id,"
                        + " entitlement_id, merchant_id, merchant_number, operation, direction, amount,"
                        + " currency_code, merchant_reference, status) VALUES"
                        + " (:key,:account,:entitlement,:merchant,:merchant_number,:operation,:direction,:amount,:currency,:reference,:status)",
                p);
        long id =
                jdbc.queryForObject(
                        "SELECT id FROM provider_treasury_reservations WHERE idempotency_key=:key",
                        p,
                        Long.class);
        if ("PAYOUT".equals(operation)) {
            appendBalanced(
                    "RESERVE:" + id,
                    "RESERVATION",
                    money,
                    context.currencyCode(),
                    new JournalLeg(
                            null,
                            "PROVIDER_FLOAT_RESERVED:" + accountId,
                            merchant.getId(),
                            id,
                            null,
                            reference,
                            "DEBIT",
                            "Reserve provider float for shared payout"),
                    new JournalLeg(
                            accountId,
                            "PROVIDER_FLOAT_AVAILABLE:" + accountId,
                            merchant.getId(),
                            id,
                            null,
                            reference,
                            "CREDIT",
                            "Reserve provider float for shared payout"),
                    "SYSTEM");
        }
        return new Reservation(
                id,
                accountId,
                context.entitlementId(),
                merchant.getId(),
                merchant.getAccount_number(),
                operation,
                direction,
                money,
                context.currencyCode(),
                reference,
                status);
    }

    @Transactional
    public void completeShared(
            Reservation reservation, String providerStatus, String providerReference) {
        if (reservation == null) return;
        Outcome outcome = classify(providerStatus);
        Map<String, Object> row = lockReservation(reservation.id());
        String current = text(row.get("status"));
        if ("SETTLED".equals(current) || "RELEASED".equals(current) || "FAILED".equals(current))
            return;
        if (outcome == Outcome.SUCCESS) settle(row, providerReference, "SYSTEM");
        else if (outcome == Outcome.FAILURE) fail(row, providerReference, "SYSTEM");
        else movePending(row, providerReference, "SYSTEM");
    }

    /**
     * Resolve a provider-pending reservation after callback/status/reconciliation evidence arrives.
     */
    @Transactional
    public Map<String, Object> resolvePending(
            long reservationId, boolean success, String providerReference, String actor) {
        Map<String, Object> row = lockReservation(reservationId);
        if (!"PENDING".equals(text(row.get("status")))) {
            throw new PaymentGatewayException(
                    "Only PENDING provider treasury reservations can be resolved");
        }
        if (success) settle(row, providerReference, required(actor, "actor"));
        else fail(row, providerReference, required(actor, "actor"));
        return reservationById(reservationId);
    }

    /**
     * Resolve an MTN asynchronous result using both the unguessable provider UUID and externalId.
     */
    @Transactional
    public Map<String, Object> resolveProviderCallback(
            String channelCode,
            String providerReference,
            String externalId,
            String providerStatus,
            String financialTransactionId) {
        List<Map<String, Object>> matches =
                jdbc.queryForList(
                        "SELECT r.id FROM provider_treasury_reservations r JOIN"
                                + " provider_treasury_accounts a ON a.id=r.treasury_account_id WHERE"
                                + " a.channel_code=:channel AND"
                                + " r.provider_reference=:provider_reference LIMIT 1",
                        new MapSqlParameterSource()
                                .addValue("channel", required(channelCode, "channelCode"))
                                .addValue(
                                        "provider_reference",
                                        required(providerReference, "providerReference")));
        if (matches.isEmpty()) {
            throw new PaymentGatewayException("Provider callback reference was not found");
        }
        long reservationId = number(matches.get(0).get("id"));
        Map<String, Object> row = lockReservation(reservationId);
        if (!required(externalId, "externalId").equals(text(row.get("merchant_reference")))) {
            throw new PaymentGatewayException("Provider callback externalId does not match");
        }
        String current = text(row.get("status"));
        if ("SETTLED".equals(current) || "RELEASED".equals(current) || "FAILED".equals(current)) {
            return reservationById(reservationId);
        }
        Outcome outcome = classify(providerStatus);
        // Keep the MTN X-Reference-Id as the durable correlation key so a duplicate callback to
        // the transaction-specific URL remains idempotent. The financial transaction id is
        // provider evidence, not a replacement for the correlation UUID.
        String finalReference = providerReference;
        if (outcome == Outcome.SUCCESS) {
            settle(row, finalReference, "PROVIDER_CALLBACK:" + channelCode);
        } else if (outcome == Outcome.FAILURE) {
            fail(row, finalReference, "PROVIDER_CALLBACK:" + channelCode);
        }
        return reservationById(reservationId);
    }

    @Transactional
    public Map<String, Object> requestAdjustment(Map<String, Object> body, String actor) {
        String type =
                required(body.get("adjustmentType"), "adjustmentType").toUpperCase(Locale.ROOT);
        if (!type.equals("CREDIT") && !type.equals("DEBIT") && !type.equals("REBALANCE")) {
            throw new PaymentGatewayException("adjustmentType must be CREDIT, DEBIT or REBALANCE");
        }
        Long source = optionalLong(body.get("sourceAccountId"));
        Long destination = optionalLong(body.get("destinationAccountId"));
        if (type.equals("CREDIT") && destination == null)
            throw new PaymentGatewayException("destinationAccountId is required for CREDIT");
        if (type.equals("DEBIT") && source == null)
            throw new PaymentGatewayException("sourceAccountId is required for DEBIT");
        if (type.equals("REBALANCE")
                && (source == null || destination == null || source.equals(destination))) {
            throw new PaymentGatewayException(
                    "REBALANCE requires different sourceAccountId and destinationAccountId");
        }
        BigDecimal amount = money(value(body, "amount"));
        String reason = required(body.get("reason"), "reason");
        String externalRef = required(body.get("externalReference"), "externalReference");
        String evidence = text(body.get("evidenceReference"));
        LocalDate valueDate = date(body.get("valueDate"));
        String who = required(actor, "actor");
        String idempotency = text(body.get("idempotencyKey"));
        if (idempotency.isBlank()) idempotency = "ADJ:" + UUID.randomUUID();
        String requestHash =
                sha256(
                        type
                                + "|"
                                + source
                                + "|"
                                + destination
                                + "|"
                                + amount
                                + "|"
                                + reason
                                + "|"
                                + externalRef
                                + "|"
                                + valueDate);

        List<Map<String, Object>> prior =
                jdbc.queryForList(
                        "SELECT * FROM provider_treasury_adjustments WHERE idempotency_key=:key OR"
                                + " request_hash=:hash LIMIT 1",
                        new MapSqlParameterSource()
                                .addValue("key", idempotency)
                                .addValue("hash", requestHash));
        if (!prior.isEmpty()) return adjustment(prior.get(0));
        try {
            jdbc.update(
                    "INSERT INTO provider_treasury_adjustments (idempotency_key, adjustment_type,"
                            + " source_account_id, destination_account_id, amount, reason,"
                            + " external_reference, evidence_reference, value_date, status,"
                            + " request_hash, requested_by) VALUES"
                            + " (:key,:type,:source,:destination,:amount,:reason,:external,:evidence,:value_date,'PENDING',:hash,:actor)",
                    new MapSqlParameterSource()
                            .addValue("key", idempotency)
                            .addValue("type", type)
                            .addValue("source", source)
                            .addValue("destination", destination)
                            .addValue("amount", amount)
                            .addValue("reason", reason)
                            .addValue("external", externalRef)
                            .addValue("evidence", evidence)
                            .addValue("value_date", valueDate)
                            .addValue("hash", requestHash)
                            .addValue("actor", who));
        } catch (DuplicateKeyException e) {
            throw new PaymentGatewayException("Duplicate treasury adjustment request");
        }
        return adjustmentByKey(idempotency);
    }

    @Transactional
    public Map<String, Object> approveAdjustment(long adjustmentId, String actor) {
        String checker = required(actor, "actor");
        Map<String, Object> adjustment = lockAdjustment(adjustmentId);
        if (!"PENDING".equals(text(adjustment.get("status"))))
            throw new PaymentGatewayException("Only PENDING adjustments can be approved");
        if (checker.equalsIgnoreCase(text(adjustment.get("requested_by")))) {
            throw new PaymentGatewayException(
                    "Maker-checker violation: requester cannot approve the same treasury"
                            + " adjustment");
        }
        String type = text(adjustment.get("adjustment_type"));
        BigDecimal amount = money(adjustment.get("amount"));
        Long source = optionalLong(adjustment.get("source_account_id"));
        Long destination = optionalLong(adjustment.get("destination_account_id"));
        String reason = text(adjustment.get("reason"));
        String external = text(adjustment.get("external_reference"));
        String group = "ADJUSTMENT:" + adjustmentId;

        if ("CREDIT".equals(type)) {
            Map<String, Object> dest = lockAccount(destination);
            jdbc.update(
                    "UPDATE provider_treasury_accounts SET book_balance=book_balance+:amount,"
                            + " lock_version=lock_version+1 WHERE id=:id",
                    params(destination, amount));
            String currency = text(dest.get("currency_code"));
            appendBalanced(
                    group,
                    "FLOAT_CREDIT",
                    amount,
                    currency,
                    new JournalLeg(
                            destination,
                            "PROVIDER_FLOAT:" + destination,
                            null,
                            null,
                            adjustmentId,
                            external,
                            "DEBIT",
                            reason),
                    new JournalLeg(
                            null,
                            "TREASURY_CLEARING:" + currency,
                            null,
                            null,
                            adjustmentId,
                            external,
                            "CREDIT",
                            reason),
                    checker);
        } else if ("DEBIT".equals(type)) {
            Map<String, Object> src = lockAccount(source);
            ensureAvailable(src, amount);
            jdbc.update(
                    "UPDATE provider_treasury_accounts SET book_balance=book_balance-:amount,"
                            + " lock_version=lock_version+1 WHERE id=:id",
                    params(source, amount));
            String currency = text(src.get("currency_code"));
            appendBalanced(
                    group,
                    "FLOAT_DEBIT",
                    amount,
                    currency,
                    new JournalLeg(
                            null,
                            "TREASURY_CLEARING:" + currency,
                            null,
                            null,
                            adjustmentId,
                            external,
                            "DEBIT",
                            reason),
                    new JournalLeg(
                            source,
                            "PROVIDER_FLOAT:" + source,
                            null,
                            null,
                            adjustmentId,
                            external,
                            "CREDIT",
                            reason),
                    checker);
        } else {
            Map<String, Object> src =
                    source < destination ? lockAccount(source) : lockAccount(destination);
            Map<String, Object> dst =
                    source < destination ? lockAccount(destination) : lockAccount(source);
            if (number(src.get("id")) != source) {
                Map<String, Object> tmp = src;
                src = dst;
                dst = tmp;
            }
            if (!text(src.get("currency_code")).equalsIgnoreCase(text(dst.get("currency_code")))) {
                throw new PaymentGatewayException(
                        "REBALANCE source and destination currency must match");
            }
            ensureAvailable(src, amount);
            jdbc.update(
                    "UPDATE provider_treasury_accounts SET book_balance=book_balance-:amount,"
                            + " lock_version=lock_version+1 WHERE id=:id",
                    params(source, amount));
            jdbc.update(
                    "UPDATE provider_treasury_accounts SET book_balance=book_balance+:amount,"
                            + " lock_version=lock_version+1 WHERE id=:id",
                    params(destination, amount));
            String currency = text(src.get("currency_code"));
            appendBalanced(
                    group,
                    "FLOAT_REBALANCE",
                    amount,
                    currency,
                    new JournalLeg(
                            destination,
                            "PROVIDER_FLOAT:" + destination,
                            null,
                            null,
                            adjustmentId,
                            external,
                            "DEBIT",
                            reason),
                    new JournalLeg(
                            source,
                            "PROVIDER_FLOAT:" + source,
                            null,
                            null,
                            adjustmentId,
                            external,
                            "CREDIT",
                            reason),
                    checker);
        }
        jdbc.update(
                "UPDATE provider_treasury_adjustments SET status='POSTED', approved_by=:actor,"
                        + " approved_at=CURRENT_TIMESTAMP(6), posted_at=CURRENT_TIMESTAMP(6) WHERE"
                        + " id=:id",
                new MapSqlParameterSource()
                        .addValue("id", adjustmentId)
                        .addValue("actor", checker));
        return adjustmentById(adjustmentId);
    }

    @Transactional
    public Map<String, Object> rejectAdjustment(long adjustmentId, String actor) {
        String checker = required(actor, "actor");
        Map<String, Object> adjustment = lockAdjustment(adjustmentId);
        if (!"PENDING".equals(text(adjustment.get("status"))))
            throw new PaymentGatewayException("Only PENDING adjustments can be rejected");
        if (checker.equalsIgnoreCase(text(adjustment.get("requested_by")))) {
            throw new PaymentGatewayException(
                    "Maker-checker violation: requester cannot reject the same treasury"
                            + " adjustment");
        }
        jdbc.update(
                "UPDATE provider_treasury_adjustments SET status='REJECTED', rejected_by=:actor,"
                        + " rejected_at=CURRENT_TIMESTAMP(6) WHERE id=:id",
                new MapSqlParameterSource()
                        .addValue("id", adjustmentId)
                        .addValue("actor", checker));
        return adjustmentById(adjustmentId);
    }

    @Transactional
    public Map<String, Object> reconcile(long accountId, Map<String, Object> body, String actor) {
        Map<String, Object> account = lockAccount(accountId);
        BigDecimal reported = money(value(body, "providerReportedBalance"));
        BigDecimal book = money(account.get("book_balance"));
        BigDecimal variance = reported.subtract(book).setScale(4, RoundingMode.HALF_UP);
        BigDecimal tolerance = decimal(body.get("tolerance"), new BigDecimal("0.0100")).abs();
        String state = variance.abs().compareTo(tolerance) <= 0 ? "MATCHED" : "VARIANCE";
        String statement = required(body.get("statementReference"), "statementReference");
        String evidence = text(body.get("evidenceReference"));
        jdbc.update(
                "INSERT INTO provider_treasury_reconciliations (treasury_account_id,"
                        + " statement_reference, evidence_reference, book_balance,"
                        + " provider_reported_balance, variance, state, notes, reconciled_by) VALUES"
                        + " (:account,:statement,:evidence,:book,:reported,:variance,:state,:notes,:actor)",
                new MapSqlParameterSource()
                        .addValue("account", accountId)
                        .addValue("statement", statement)
                        .addValue("evidence", evidence)
                        .addValue("book", book)
                        .addValue("reported", reported)
                        .addValue("variance", variance)
                        .addValue("state", state)
                        .addValue("notes", text(body.get("notes")))
                        .addValue("actor", required(actor, "actor")));
        jdbc.update(
                "UPDATE provider_treasury_accounts SET provider_reported_balance=:reported,"
                        + " provider_balance_status='AVAILABLE',"
                        + " provider_balance_updated_at=CURRENT_TIMESTAMP(6),"
                        + " provider_balance_message='Updated from reconciliation evidence',"
                        + " reconciliation_state=:state, lock_version=lock_version+1 WHERE id=:id",
                new MapSqlParameterSource()
                        .addValue("id", accountId)
                        .addValue("reported", reported)
                        .addValue("state", state));
        return accountById(accountId);
    }

    @Transactional
    public Map<String, Object> setLowFloatThreshold(long accountId, BigDecimal threshold) {
        BigDecimal value = threshold == null ? ZERO : threshold.setScale(4, RoundingMode.HALF_UP);
        if (value.signum() < 0)
            throw new PaymentGatewayException("lowFloatThreshold cannot be negative");
        lockAccount(accountId);
        jdbc.update(
                "UPDATE provider_treasury_accounts SET low_float_threshold=:value,"
                        + " lock_version=lock_version+1 WHERE id=:id",
                new MapSqlParameterSource().addValue("id", accountId).addValue("value", value));
        return accountById(accountId);
    }

    private void settle(Map<String, Object> reservation, String providerReference, String actor) {
        long id = number(reservation.get("id"));
        long accountId = number(reservation.get("treasury_account_id"));
        long merchantId = number(reservation.get("merchant_id"));
        String operation = text(reservation.get("operation"));
        String status = text(reservation.get("status"));
        BigDecimal amount = money(reservation.get("amount"));
        Map<String, Object> account = lockAccount(accountId);
        String channel = text(account.get("channel_code"));
        String environment = text(account.get("environment"));
        String country = text(account.get("country_code"));
        String currency = text(account.get("currency_code"));
        String reference = text(reservation.get("merchant_reference"));
        String providerRef = text(providerReference);
        closePendingJournal(reservation, currency, actor);

        if ("PAYOUT".equals(operation)) {
            if ("RESERVED".equals(status)) {
                jdbc.update(
                        "UPDATE provider_treasury_accounts SET"
                                + " reserved_balance=reserved_balance-:amount,"
                                + " book_balance=book_balance-:amount, lock_version=lock_version+1"
                                + " WHERE id=:id",
                        params(accountId, amount));
                upsertExposure(
                        merchantId,
                        channel,
                        environment,
                        country,
                        currency,
                        amount.negate(),
                        ZERO,
                        ZERO,
                        amount);
            } else if ("PENDING".equals(status)) {
                jdbc.update(
                        "UPDATE provider_treasury_accounts SET"
                                + " pending_outgoing_balance=pending_outgoing_balance-:amount,"
                                + " book_balance=book_balance-:amount, lock_version=lock_version+1"
                                + " WHERE id=:id",
                        params(accountId, amount));
                upsertExposure(
                        merchantId,
                        channel,
                        environment,
                        country,
                        currency,
                        ZERO,
                        amount.negate(),
                        ZERO,
                        amount);
            }
            appendBalanced(
                    "SETTLE:" + id,
                    "SHARED_PAYOUT",
                    amount,
                    currency,
                    new JournalLeg(
                            null,
                            "MERCHANT_EXPOSURE:" + merchantId,
                            merchantId,
                            id,
                            null,
                            reference,
                            "DEBIT",
                            "Confirmed shared-provider payout"),
                    new JournalLeg(
                            accountId,
                            "PROVIDER_FLOAT:" + accountId,
                            merchantId,
                            id,
                            null,
                            reference,
                            "CREDIT",
                            "Confirmed shared-provider payout"),
                    actor);
        } else {
            if ("PENDING".equals(status)) {
                jdbc.update(
                        "UPDATE provider_treasury_accounts SET"
                                + " pending_incoming_balance=pending_incoming_balance-:amount,"
                                + " book_balance=book_balance+:amount, lock_version=lock_version+1"
                                + " WHERE id=:id",
                        params(accountId, amount));
                upsertExposure(
                        merchantId,
                        channel,
                        environment,
                        country,
                        currency,
                        ZERO,
                        ZERO,
                        amount.negate(),
                        amount.negate());
            } else {
                jdbc.update(
                        "UPDATE provider_treasury_accounts SET book_balance=book_balance+:amount,"
                                + " lock_version=lock_version+1 WHERE id=:id",
                        params(accountId, amount));
                upsertExposure(
                        merchantId,
                        channel,
                        environment,
                        country,
                        currency,
                        ZERO,
                        ZERO,
                        ZERO,
                        amount.negate());
            }
            appendBalanced(
                    "SETTLE:" + id,
                    "SHARED_COLLECTION",
                    amount,
                    currency,
                    new JournalLeg(
                            accountId,
                            "PROVIDER_FLOAT:" + accountId,
                            merchantId,
                            id,
                            null,
                            reference,
                            "DEBIT",
                            "Confirmed shared-provider collection"),
                    new JournalLeg(
                            null,
                            "MERCHANT_EXPOSURE:" + merchantId,
                            merchantId,
                            id,
                            null,
                            reference,
                            "CREDIT",
                            "Confirmed shared-provider collection"),
                    actor);
        }
        jdbc.update(
                "UPDATE provider_treasury_reservations SET status='SETTLED',"
                        + " provider_reference=:provider, settled_at=CURRENT_TIMESTAMP(6) WHERE id=:id",
                new MapSqlParameterSource().addValue("id", id).addValue("provider", providerRef));
    }

    private void movePending(
            Map<String, Object> reservation, String providerReference, String actor) {
        long id = number(reservation.get("id"));
        String current = text(reservation.get("status"));
        if ("PENDING".equals(current)) return;
        long accountId = number(reservation.get("treasury_account_id"));
        long merchantId = number(reservation.get("merchant_id"));
        BigDecimal amount = money(reservation.get("amount"));
        Map<String, Object> account = lockAccount(accountId);
        String operation = text(reservation.get("operation"));
        String channel = text(account.get("channel_code"));
        String environment = text(account.get("environment"));
        String country = text(account.get("country_code"));
        String currency = text(account.get("currency_code"));
        String reference = text(reservation.get("merchant_reference"));
        if ("PAYOUT".equals(operation)) {
            jdbc.update(
                    "UPDATE provider_treasury_accounts SET"
                            + " reserved_balance=reserved_balance-:amount,"
                            + " pending_outgoing_balance=pending_outgoing_balance+:amount,"
                            + " lock_version=lock_version+1 WHERE id=:id",
                    params(accountId, amount));
            upsertExposure(
                    merchantId,
                    channel,
                    environment,
                    country,
                    currency,
                    amount.negate(),
                    amount,
                    ZERO,
                    ZERO);
            appendBalanced(
                    "PENDING:" + id,
                    "PAYOUT_PENDING",
                    amount,
                    currency,
                    new JournalLeg(
                            null,
                            "PROVIDER_FLOAT_PENDING:" + accountId,
                            merchantId,
                            id,
                            null,
                            reference,
                            "DEBIT",
                            "Provider payout pending"),
                    new JournalLeg(
                            null,
                            "PROVIDER_FLOAT_RESERVED:" + accountId,
                            merchantId,
                            id,
                            null,
                            reference,
                            "CREDIT",
                            "Provider payout pending"),
                    actor);
        } else {
            jdbc.update(
                    "UPDATE provider_treasury_accounts SET"
                            + " pending_incoming_balance=pending_incoming_balance+:amount,"
                            + " lock_version=lock_version+1 WHERE id=:id",
                    params(accountId, amount));
            upsertExposure(
                    merchantId, channel, environment, country, currency, ZERO, ZERO, amount, ZERO);
            appendBalanced(
                    "PENDING:" + id,
                    "COLLECTION_PENDING",
                    amount,
                    currency,
                    new JournalLeg(
                            null,
                            "PROVIDER_RECEIVABLE:" + accountId,
                            merchantId,
                            id,
                            null,
                            reference,
                            "DEBIT",
                            "Provider collection pending"),
                    new JournalLeg(
                            null,
                            "MERCHANT_PENDING_INFLOW:" + merchantId,
                            merchantId,
                            id,
                            null,
                            reference,
                            "CREDIT",
                            "Provider collection pending"),
                    actor);
        }
        jdbc.update(
                "UPDATE provider_treasury_reservations SET status='PENDING',"
                        + " provider_reference=:provider WHERE id=:id",
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("provider", text(providerReference)));
    }

    private void fail(Map<String, Object> reservation, String providerReference, String actor) {
        long id = number(reservation.get("id"));
        long accountId = number(reservation.get("treasury_account_id"));
        long merchantId = number(reservation.get("merchant_id"));
        BigDecimal amount = money(reservation.get("amount"));
        Map<String, Object> account = lockAccount(accountId);
        String operation = text(reservation.get("operation"));
        String status = text(reservation.get("status"));
        String channel = text(account.get("channel_code"));
        String environment = text(account.get("environment"));
        String country = text(account.get("country_code"));
        String currency = text(account.get("currency_code"));
        String reference = text(reservation.get("merchant_reference"));
        if ("PAYOUT".equals(operation)) {
            if ("RESERVED".equals(status)) {
                jdbc.update(
                        "UPDATE provider_treasury_accounts SET"
                                + " reserved_balance=reserved_balance-:amount,"
                                + " lock_version=lock_version+1 WHERE id=:id",
                        params(accountId, amount));
                upsertExposure(
                        merchantId,
                        channel,
                        environment,
                        country,
                        currency,
                        amount.negate(),
                        ZERO,
                        ZERO,
                        ZERO);
            } else if ("PENDING".equals(status)) {
                jdbc.update(
                        "UPDATE provider_treasury_accounts SET"
                                + " pending_outgoing_balance=pending_outgoing_balance-:amount,"
                                + " lock_version=lock_version+1 WHERE id=:id",
                        params(accountId, amount));
                upsertExposure(
                        merchantId,
                        channel,
                        environment,
                        country,
                        currency,
                        ZERO,
                        amount.negate(),
                        ZERO,
                        ZERO);
            }
            appendBalanced(
                    "RELEASE:" + id,
                    "PAYOUT_RELEASE",
                    amount,
                    currency,
                    new JournalLeg(
                            null,
                            "PROVIDER_FLOAT_AVAILABLE:" + accountId,
                            merchantId,
                            id,
                            null,
                            reference,
                            "DEBIT",
                            "Release failed shared payout"),
                    new JournalLeg(
                            null,
                            ("PENDING".equals(status)
                                            ? "PROVIDER_FLOAT_PENDING:"
                                            : "PROVIDER_FLOAT_RESERVED:")
                                    + accountId,
                            merchantId,
                            id,
                            null,
                            reference,
                            "CREDIT",
                            "Release failed shared payout"),
                    actor);
        } else if ("PENDING".equals(status)) {
            closePendingJournal(reservation, currency, actor);
            jdbc.update(
                    "UPDATE provider_treasury_accounts SET"
                            + " pending_incoming_balance=pending_incoming_balance-:amount,"
                            + " lock_version=lock_version+1 WHERE id=:id",
                    params(accountId, amount));
            upsertExposure(
                    merchantId,
                    channel,
                    environment,
                    country,
                    currency,
                    ZERO,
                    ZERO,
                    amount.negate(),
                    ZERO);
        }
        jdbc.update(
                "UPDATE provider_treasury_reservations SET status='FAILED',"
                        + " provider_reference=:provider, released_at=CURRENT_TIMESTAMP(6) WHERE"
                        + " id=:id",
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("provider", text(providerReference)));
    }

    /** Clear reservation/receivable control legs when confirmed settlement replaces them. */
    private void closePendingJournal(
            Map<String, Object> reservation, String currency, String actor) {
        long id = number(reservation.get("id")),
                account = number(reservation.get("treasury_account_id")),
                merchant = number(reservation.get("merchant_id"));
        String status = text(reservation.get("status")),
                reference = text(reservation.get("merchant_reference"));
        boolean payout = "PAYOUT".equals(reservation.get("operation"));
        if (!("PENDING".equals(status) || payout && "RESERVED".equals(status))) return;
        String debit =
                payout
                        ? "PROVIDER_FLOAT_AVAILABLE:" + account
                        : "MERCHANT_PENDING_INFLOW:" + merchant;
        String credit =
                payout
                        ? ("PENDING".equals(status)
                                        ? "PROVIDER_FLOAT_PENDING:"
                                        : "PROVIDER_FLOAT_RESERVED:")
                                + account
                        : "PROVIDER_RECEIVABLE:" + account;
        appendBalanced(
                "HOLD_CLOSE:" + id,
                "PENDING_CONTROL_CLOSE",
                money(reservation.get("amount")),
                currency,
                new JournalLeg(
                        null,
                        debit,
                        merchant,
                        id,
                        null,
                        reference,
                        "DEBIT",
                        "Close pending provider control"),
                new JournalLeg(
                        null,
                        credit,
                        merchant,
                        id,
                        null,
                        reference,
                        "CREDIT",
                        "Close pending provider control"),
                actor);
    }

    private void upsertExposure(
            Long merchantId,
            String channel,
            String environment,
            String country,
            String currency,
            BigDecimal reservedDelta,
            BigDecimal pendingOutDelta,
            BigDecimal pendingInDelta,
            BigDecimal settledDelta) {
        MapSqlParameterSource p =
                new MapSqlParameterSource()
                        .addValue("merchant", merchantId)
                        .addValue("channel", channel)
                        .addValue("environment", environment)
                        .addValue("country", country)
                        .addValue("currency", currency)
                        .addValue("reserved", reservedDelta)
                        .addValue("pending_out", pendingOutDelta)
                        .addValue("pending_in", pendingInDelta)
                        .addValue("settled", settledDelta);
        jdbc.update(
                "INSERT INTO merchant_provider_exposures (merchant_id, channel_code, environment,"
                        + " country_code, currency_code, reserved_outgoing, pending_outgoing,"
                        + " pending_incoming, settled_net) VALUES"
                        + " (:merchant,:channel,:environment,:country,:currency,:reserved,:pending_out,:pending_in,:settled)"
                        + " ON DUPLICATE KEY UPDATE"
                        + " reserved_outgoing=GREATEST(0,reserved_outgoing+:reserved),"
                        + " pending_outgoing=GREATEST(0,pending_outgoing+:pending_out),"
                        + " pending_incoming=GREATEST(0,pending_incoming+:pending_in),"
                        + " settled_net=settled_net+:settled",
                p);
    }

    private void appendBalanced(
            String group,
            String entryType,
            BigDecimal amount,
            String currency,
            JournalLeg debit,
            JournalLeg credit,
            String actor) {
        if (!"DEBIT".equals(debit.side()) || !"CREDIT".equals(credit.side()))
            throw new IllegalArgumentException("Balanced journal pair requires DEBIT then CREDIT");
        appendJournal(group, 1, entryType, amount, currency, debit, actor);
        appendJournal(group, 2, entryType, amount, currency, credit, actor);
    }

    private void appendJournal(
            String group,
            int sequence,
            String type,
            BigDecimal amount,
            String currency,
            JournalLeg leg,
            String actor) {
        String previous = latestHash(leg.ledgerAccountCode());
        String canonical =
                group
                        + "|"
                        + sequence
                        + "|"
                        + leg.ledgerAccountCode()
                        + "|"
                        + leg.side()
                        + "|"
                        + amount.toPlainString()
                        + "|"
                        + currency
                        + "|"
                        + leg.transactionReference()
                        + "|"
                        + previous;
        String hash = sha256(canonical);
        jdbc.update(
                "INSERT INTO provider_treasury_journal (entry_group, sequence_no,"
                        + " treasury_account_id, ledger_account_code, merchant_id, reservation_id,"
                        + " adjustment_id, transaction_reference, entry_type, entry_side, amount,"
                        + " currency_code, reason, external_reference, previous_hash, entry_hash,"
                        + " actor) VALUES"
                        + " (:group,:sequence,:account,:ledger,:merchant,:reservation,:adjustment,:reference,:type,:side,:amount,:currency,:reason,:external,:previous,:hash,:actor)",
                new MapSqlParameterSource()
                        .addValue("group", group)
                        .addValue("sequence", sequence)
                        .addValue("account", leg.treasuryAccountId())
                        .addValue("ledger", leg.ledgerAccountCode())
                        .addValue("merchant", leg.merchantId())
                        .addValue("reservation", leg.reservationId())
                        .addValue("adjustment", leg.adjustmentId())
                        .addValue("reference", leg.transactionReference())
                        .addValue("type", type)
                        .addValue("side", leg.side())
                        .addValue("amount", amount)
                        .addValue("currency", currency)
                        .addValue("reason", leg.reason())
                        .addValue("external", leg.transactionReference())
                        .addValue("previous", previous.isBlank() ? null : previous)
                        .addValue("hash", hash)
                        .addValue("actor", actor));
    }

    private String latestHash(String ledgerCode) {
        List<String> rows =
                jdbc.query(
                        "SELECT entry_hash FROM provider_treasury_journal WHERE"
                                + " ledger_account_code=:ledger ORDER BY id DESC LIMIT 1",
                        new MapSqlParameterSource().addValue("ledger", ledgerCode),
                        (rs, i) -> rs.getString(1));
        return rows.isEmpty() ? "" : rows.get(0);
    }

    private Map<String, Object> lockAccount(
            String channel, String environment, String country, String currency, String operation) {
        String accountRole = "PAYOUT".equalsIgnoreCase(operation) ? "DISBURSEMENT" : "COLLECTION";
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT * FROM provider_treasury_accounts WHERE channel_code=:channel AND"
                                + " environment=:environment AND country_code=:country AND"
                                + " currency_code=:currency AND account_role=:account_role FOR UPDATE",
                        new MapSqlParameterSource()
                                .addValue("channel", channel)
                                .addValue("environment", environment.toUpperCase(Locale.ROOT))
                                .addValue("country", country.toUpperCase(Locale.ROOT))
                                .addValue("currency", currency.toUpperCase(Locale.ROOT))
                                .addValue("account_role", accountRole));
        if (rows.isEmpty())
            throw new PaymentGatewayException(
                    "CPay provider treasury account is not configured for "
                            + channel
                            + "/"
                            + country
                            + "/"
                            + currency
                            + "/"
                            + accountRole);
        return rows.get(0);
    }

    private Map<String, Object> lockAccount(Long id) {
        if (id == null) throw new PaymentGatewayException("Treasury account is required");
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT * FROM provider_treasury_accounts WHERE id=:id FOR UPDATE",
                        new MapSqlParameterSource().addValue("id", id));
        if (rows.isEmpty())
            throw new PaymentGatewayException("Provider treasury account not found: " + id);
        return rows.get(0);
    }

    private Map<String, Object> accountById(long id) {
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT id, channel_code AS channelCode, environment, country_code AS"
                                + " countryCode, currency_code AS currencyCode, account_role AS"
                                + " accountRole, display_name AS displayName, parent_account_id AS"
                                + " parentAccountId, prefund_required AS prefundRequired, book_balance"
                                + " AS bookBalance, reserved_balance AS reservedBalance,"
                                + " pending_outgoing_balance AS pendingOutgoingBalance,"
                                + " pending_incoming_balance AS pendingIncomingBalance,"
                                + " provider_reported_balance AS providerReportedBalance,"
                                + " provider_balance_status AS providerBalanceStatus,"
                                + " provider_balance_updated_at AS providerBalanceUpdatedAt,"
                                + " provider_balance_message AS providerBalanceMessage,"
                                + " TIMESTAMPDIFF(SECOND,provider_balance_updated_at,CURRENT_TIMESTAMP(6)) AS providerBalanceAgeSeconds,"
                                + " low_float_threshold AS lowFloatThreshold, reconciliation_state AS"
                                + " reconciliationState, updated_at AS updatedAt FROM"
                                + " provider_treasury_accounts WHERE id=:id",
                        new MapSqlParameterSource().addValue("id", id));
        if (rows.isEmpty())
            throw new PaymentGatewayException("Provider treasury account not found: " + id);
        enrichAccount(rows.get(0));
        return rows.get(0);
    }

    private void enrichAccount(Map<String, Object> row) {
        BigDecimal book = money(row.get("bookBalance"));
        BigDecimal reserved = money(row.get("reservedBalance"));
        BigDecimal pending = money(row.get("pendingOutgoingBalance"));
        BigDecimal available =
                book.subtract(reserved).subtract(pending).setScale(4, RoundingMode.HALF_UP);
        BigDecimal threshold = money(row.get("lowFloatThreshold"));
        row.put("availableBalance", available);
        row.put("lowFloat", available.compareTo(threshold) <= 0);
        Object reported = row.get("providerReportedBalance");
        row.put(
                "providerBalanceAvailable",
                reported != null && "AVAILABLE".equals(row.get("providerBalanceStatus")));
        row.put(
                "reconciliationVariance",
                reported == null
                        ? null
                        : money(reported).subtract(book).setScale(4, RoundingMode.HALF_UP));
    }

    private Map<String, Object> lockReservation(long id) {
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT * FROM provider_treasury_reservations WHERE id=:id FOR UPDATE",
                        new MapSqlParameterSource().addValue("id", id));
        if (rows.isEmpty())
            throw new PaymentGatewayException("Provider treasury reservation not found: " + id);
        return rows.get(0);
    }

    private Map<String, Object> lockAdjustment(long id) {
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT * FROM provider_treasury_adjustments WHERE id=:id FOR UPDATE",
                        new MapSqlParameterSource().addValue("id", id));
        if (rows.isEmpty())
            throw new PaymentGatewayException("Provider treasury adjustment not found: " + id);
        return rows.get(0);
    }

    private Map<String, Object> adjustmentById(long id) {
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT * FROM provider_treasury_adjustments WHERE id=:id",
                        new MapSqlParameterSource().addValue("id", id));
        if (rows.isEmpty())
            throw new PaymentGatewayException("Provider treasury adjustment not found: " + id);
        return adjustment(rows.get(0));
    }

    private Map<String, Object> adjustmentByKey(String key) {
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT * FROM provider_treasury_adjustments WHERE idempotency_key=:key",
                        new MapSqlParameterSource().addValue("key", key));
        if (rows.isEmpty())
            throw new PaymentGatewayException("Provider treasury adjustment not found");
        return adjustment(rows.get(0));
    }

    private Map<String, Object> adjustment(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", row.get("id"));
        result.put("idempotencyKey", row.get("idempotency_key"));
        result.put("adjustmentType", row.get("adjustment_type"));
        result.put("sourceAccountId", row.get("source_account_id"));
        result.put("destinationAccountId", row.get("destination_account_id"));
        result.put("amount", row.get("amount"));
        result.put("reason", row.get("reason"));
        result.put("externalReference", row.get("external_reference"));
        result.put("evidenceReference", row.get("evidence_reference"));
        result.put("valueDate", row.get("value_date"));
        result.put("status", row.get("status"));
        result.put("requestedBy", row.get("requested_by"));
        result.put("requestedAt", row.get("requested_at"));
        result.put("approvedBy", row.get("approved_by"));
        result.put("approvedAt", row.get("approved_at"));
        result.put("rejectedBy", row.get("rejected_by"));
        result.put("rejectedAt", row.get("rejected_at"));
        result.put("postedAt", row.get("posted_at"));
        return result;
    }

    private Map<String, Object> reservationById(long id) {
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT * FROM provider_treasury_reservations WHERE id=:id",
                        new MapSqlParameterSource().addValue("id", id));
        if (rows.isEmpty())
            throw new PaymentGatewayException("Provider treasury reservation not found: " + id);
        Map<String, Object> row = rows.get(0);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", row.get("id"));
        result.put("treasuryAccountId", row.get("treasury_account_id"));
        result.put("merchantId", row.get("merchant_id"));
        result.put("operation", row.get("operation"));
        result.put("amount", row.get("amount"));
        result.put("status", row.get("status"));
        result.put("merchantReference", row.get("merchant_reference"));
        result.put("providerReference", row.get("provider_reference"));
        return result;
    }

    private Reservation reservation(Map<String, Object> row) {
        return new Reservation(
                number(row.get("id")),
                number(row.get("treasury_account_id")),
                optionalLong(row.get("entitlement_id")),
                number(row.get("merchant_id")),
                text(row.get("merchant_number")),
                text(row.get("operation")),
                text(row.get("direction")),
                money(row.get("amount")),
                text(row.get("currency_code")),
                text(row.get("merchant_reference")),
                text(row.get("status")));
    }

    private BigDecimal available(Map<String, Object> account) {
        return money(account.get("book_balance"))
                .subtract(money(account.get("reserved_balance")))
                .subtract(money(account.get("pending_outgoing_balance")));
    }

    private void ensureAvailable(Map<String, Object> account, BigDecimal amount) {
        BigDecimal available = available(account);
        if (available.compareTo(amount) < 0)
            throw new PaymentGatewayException(
                    "Treasury debit would make available provider float negative");
    }

    private MapSqlParameterSource params(long id, BigDecimal amount) {
        return new MapSqlParameterSource().addValue("id", id).addValue("amount", amount);
    }

    private void requireMerchant(Merchant merchant) {
        if (merchant == null || merchant.getId() == null)
            throw new PaymentGatewayException("Merchant is required");
    }

    private BigDecimal money(Object value) {
        if (value == null) return ZERO;
        try {
            BigDecimal result =
                    value instanceof BigDecimal b ? b : new BigDecimal(String.valueOf(value));
            result = result.setScale(4, RoundingMode.HALF_UP);
            if (result.signum() < 0)
                throw new PaymentGatewayException("Monetary amount cannot be negative");
            return result;
        } catch (NumberFormatException e) {
            throw new PaymentGatewayException("Invalid monetary amount");
        }
    }

    private Object value(Map<String, Object> body, String key) {
        if (body == null || !body.containsKey(key))
            throw new PaymentGatewayException(key + " is required");
        return body.get(key);
    }

    private BigDecimal decimal(Object value, BigDecimal fallback) {
        if (value == null || text(value).isBlank()) return fallback;
        try {
            return new BigDecimal(text(value));
        } catch (Exception e) {
            throw new PaymentGatewayException("Invalid decimal value");
        }
    }

    private String required(Object value, String field) {
        String s = text(value);
        if (s.isBlank()) throw new PaymentGatewayException(field + " is required");
        return s;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private long number(Object value) {
        return value instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(value));
    }

    private Long optionalLong(Object value) {
        if (value == null || text(value).isBlank()) return null;
        return number(value);
    }

    private LocalDate date(Object value) {
        if (value == null || text(value).isBlank()) return LocalDate.now();
        try {
            return LocalDate.parse(text(value));
        } catch (Exception e) {
            throw new PaymentGatewayException("valueDate must be YYYY-MM-DD");
        }
    }

    private String sha256(String value) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(d.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private Outcome classify(String value) {
        String status = text(value).toUpperCase(Locale.ROOT);
        if (status.equals("SUCCESS")
                || status.equals("SUCCESSFUL")
                || status.equals("COMPLETED")
                || status.equals("COMPLETE")
                || status.equals("000")) return Outcome.SUCCESS;
        if (status.equals("FAILED")
                || status.equals("FAILURE")
                || status.equals("ERROR")
                || status.equals("DECLINED")
                || status.equals("CANCELLED")
                || status.equals("CANCELED")) return Outcome.FAILURE;
        return Outcome.PENDING;
    }

    private enum Outcome {
        SUCCESS,
        FAILURE,
        PENDING
    }

    private record JournalLeg(
            Long treasuryAccountId,
            String ledgerAccountCode,
            Long merchantId,
            Long reservationId,
            Long adjustmentId,
            String transactionReference,
            String side,
            String reason) {}

    public record Reservation(
            long id,
            long treasuryAccountId,
            Long entitlementId,
            long merchantId,
            String merchantNumber,
            String operation,
            String direction,
            BigDecimal amount,
            String currencyCode,
            String merchantReference,
            String status) {}
}
