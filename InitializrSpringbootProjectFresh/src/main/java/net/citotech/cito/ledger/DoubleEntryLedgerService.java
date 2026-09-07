package net.citotech.cito.ledger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.citotech.cito.gateway.PaymentGatewayException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DoubleEntryLedgerService {
    private static final int MONEY_SCALE = 4;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public DoubleEntryLedgerService(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public long post(
            String transactionReference,
            String sourceType,
            String sourceReference,
            String description,
            List<LedgerEntryCommand> entries) {
        validateEntries(entries);
        Map<String, Totals> totals = totalsByCurrency(entries);
        for (Map.Entry<String, Totals> total : totals.entrySet()) {
            if (total.getValue().debits.compareTo(total.getValue().credits) != 0) {
                throw new PaymentGatewayException(
                        "Ledger transaction is not balanced for " + total.getKey());
            }
        }

        Long existing = findTransaction(transactionReference);
        if (existing != null) {
            return existing;
        }
        checkPeriodsNotLocked(totals.keySet());

        long txId =
                insertTransaction(transactionReference, sourceType, sourceReference, description);
        for (LedgerEntryCommand entry : entries) {
            long accountId = ensureAccount(entry);
            insertEntry(txId, accountId, entry);
        }
        return txId;
    }

    /**
     * Posts a mirror-image balanced group of entries under {@code newTransactionReference},
     * flipping each of {@code originalTransactionReference}'s entries' direction (DR&lt;-&gt;CR)
     * while keeping the same account/amount/currency - the only way this ledger corrects a posting
     * (see {@code Docs/Money-ledger-and-orchestration-roadmap.md}'s "propose matched corrections
     * rather than mutating ledger entries directly" rule and ADR 0004). Idempotent like {@link
     * #post}: replaying the same {@code newTransactionReference} returns the existing reversal
     * transaction id rather than posting a second time.
     */
    @Transactional
    public long reverse(
            String originalTransactionReference, String newTransactionReference, String reason) {
        if (blank(originalTransactionReference) || blank(newTransactionReference)) {
            throw new PaymentGatewayException(
                    "Ledger reversal requires an original and a new transaction reference");
        }
        Long existingReversal = findTransaction(newTransactionReference);
        if (existingReversal != null) {
            return existingReversal;
        }
        Long originalTxId = findTransaction(originalTransactionReference);
        if (originalTxId == null) {
            throw new PaymentGatewayException(
                    "Original ledger transaction not found: " + originalTransactionReference);
        }

        String description = blank(reason) ? "Reversal of " + originalTransactionReference : reason;
        List<LedgerEntryCommand> mirrored = mirrorEntries(originalTxId, description);
        if (mirrored.isEmpty()) {
            throw new PaymentGatewayException(
                    "Original ledger transaction has no entries: " + originalTransactionReference);
        }

        return post(
                newTransactionReference,
                "REVERSAL",
                originalTransactionReference,
                description,
                mirrored);
    }

    @Transactional
    public TrialBalanceResult runTrialBalance(LocalDate runDate, String currency) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("run_date", runDate);
        p.addValue("currency", currency);
        Map<String, Object> row =
                jdbcTemplate.queryForMap(
                        "SELECT COALESCE(SUM(CASE WHEN le.entry_direction='DR' THEN le.amount ELSE"
                            + " 0 END), 0) AS debits, COALESCE(SUM(CASE WHEN"
                            + " le.entry_direction='CR' THEN le.amount ELSE 0 END), 0) AS credits"
                            + " FROM ledger_entries le JOIN ledger_transactions lt ON lt.id ="
                            + " le.ledger_transaction_id WHERE DATE(lt.created_at) <= :run_date AND"
                            + " le.currency=:currency",
                        p);
        BigDecimal debits = decimal(row.get("debits"));
        BigDecimal credits = decimal(row.get("credits"));
        TrialBalanceResult result = new TrialBalanceResult(runDate, currency, debits, credits);

        MapSqlParameterSource write = new MapSqlParameterSource();
        write.addValue("run_date", runDate);
        write.addValue("currency", currency);
        write.addValue("debits", debits);
        write.addValue("credits", credits);
        write.addValue("balanced", result.isBalanced() ? "YES" : "NO");
        write.addValue("message", result.isBalanced() ? "balanced" : "debits and credits differ");
        jdbcTemplate.update(
                "INSERT INTO ledger_trial_balance_runs (run_date, currency, total_debits,"
                        + " total_credits, balanced_flag, message) VALUES (:run_date, :currency,"
                        + " :debits, :credits, :balanced, :message) ON DUPLICATE KEY UPDATE"
                        + " total_debits=:debits, total_credits=:credits, balanced_flag=:balanced,"
                        + " message=:message, created_at=CURRENT_TIMESTAMP",
                write);
        return result;
    }

    public List<String> activeCurrencies() {
        return jdbcTemplate.query(
                "SELECT DISTINCT currency FROM ledger_entries ORDER BY currency",
                new MapSqlParameterSource(),
                (rs, rowNum) -> rs.getString("currency"));
    }

    @Transactional
    public void reserve(
            String reservationReference,
            long merchantId,
            String sourceReference,
            BigDecimal amount,
            String currency) {
        NormalizedReservation reservation =
                normalizeReservation(
                        merchantId,
                        currency,
                        new ReservationCommand(reservationReference, sourceReference, amount));
        ExistingReservation existing = findReservation(reservation.reservationReference());
        if (existing != null) {
            if (existing.matches(
                    merchantId,
                    reservation.sourceReference(),
                    reservation.amount(),
                    reservation.currency())) {
                return;
            }
            throw new PaymentGatewayException(
                    "Ledger reservation reference already exists with different attributes");
        }

        lockReservationScope(merchantId, reservation.currency());
        BigDecimal availableBalance = availableMerchantBalance(merchantId, reservation.currency());
        if (availableBalance.compareTo(reservation.amount()) < 0) {
            throw new PaymentGatewayException(
                    "Insufficient ledger-derived available balance for reservation");
        }
        insertReservation(reservation);
    }

    /**
     * Atomically reserves an entire payout slice before any external provider is called.
     *
     * <p>Every missing reservation is checked against one locked merchant/currency balance. If the
     * aggregate cannot be funded, no reservation is inserted. Existing matching reservations are
     * treated as idempotent replays and are already reflected in {@link
     * #availableMerchantBalance(long, String)}.
     */
    @Transactional
    public BatchReservationResult reserveAll(
            long merchantId, String currency, List<ReservationCommand> commands) {
        if (merchantId <= 0 || blank(currency)) {
            throw new PaymentGatewayException(
                    "Ledger batch reservation requires merchant and currency");
        }
        if (commands == null || commands.isEmpty()) {
            BigDecimal zero = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            return new BatchReservationResult(true, zero, zero);
        }

        Map<String, NormalizedReservation> unique = new LinkedHashMap<>();
        for (ReservationCommand command : commands) {
            NormalizedReservation reservation = normalizeReservation(merchantId, currency, command);
            if (unique.putIfAbsent(reservation.reservationReference(), reservation) != null) {
                throw new PaymentGatewayException(
                        "Ledger batch reservation contains a duplicate reference");
            }
        }

        String normalizedCurrency = currency.trim().toUpperCase();
        lockReservationScope(merchantId, normalizedCurrency);

        List<NormalizedReservation> missing = new ArrayList<>();
        BigDecimal required = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        for (NormalizedReservation reservation : unique.values()) {
            ExistingReservation existing = findReservation(reservation.reservationReference());
            if (existing == null) {
                missing.add(reservation);
                required = required.add(reservation.amount());
            } else if (!existing.matches(
                    merchantId,
                    reservation.sourceReference(),
                    reservation.amount(),
                    normalizedCurrency)) {
                throw new PaymentGatewayException(
                        "Ledger reservation reference already exists with different attributes");
            }
        }

        BigDecimal available = availableMerchantBalance(merchantId, normalizedCurrency);
        if (required.signum() > 0 && available.compareTo(required) < 0) {
            return new BatchReservationResult(false, required, available);
        }
        for (NormalizedReservation reservation : missing) {
            insertReservation(reservation);
        }
        return new BatchReservationResult(true, required, available);
    }

    public BigDecimal availableMerchantBalance(long merchantId, String currency) {
        if (merchantId <= 0 || blank(currency)) {
            throw new PaymentGatewayException("merchantId and currency are required");
        }
        String normalizedCurrency = currency.trim().toUpperCase();
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("merchant_id", merchantId);
        p.addValue("currency", normalizedCurrency);
        Map<String, Object> row =
                jdbcTemplate.queryForMap(
                        "SELECT COALESCE(SUM(CASE WHEN la.account_type='MERCHANT_LIABILITY' AND"
                            + " le.entry_direction='CR' THEN le.amount WHEN"
                            + " la.account_type='MERCHANT_LIABILITY' AND le.entry_direction='DR'"
                            + " THEN -le.amount ELSE 0 END), 0) AS posted_balance, COALESCE((SELECT"
                            + " SUM(amount) FROM ledger_reservations lr WHERE"
                            + " lr.merchant_id=:merchant_id AND lr.currency=:currency AND"
                            + " lr.reservation_status='RESERVED'), 0) AS active_reservations FROM"
                            + " ledger_entries le JOIN ledger_accounts la ON la.id = le.account_id"
                            + " WHERE la.owner_type='MERCHANT' AND la.owner_id=:merchant_id AND"
                            + " le.currency=:currency",
                        p);
        return decimal(row.get("posted_balance")).subtract(decimal(row.get("active_reservations")));
    }

    @Transactional
    public int captureReservation(String reservationReference) {
        return updateReservation(reservationReference, "CAPTURED");
    }

    @Transactional
    public int releaseReservation(String reservationReference) {
        return updateReservation(reservationReference, "RELEASED");
    }

    private int updateReservation(String reservationReference, String status) {
        if (blank(reservationReference)) {
            throw new PaymentGatewayException("Ledger reservation reference is required");
        }
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("reservation_reference", reservationReference);
        p.addValue("status", status);
        return jdbcTemplate.update(
                "UPDATE ledger_reservations SET reservation_status=:status WHERE"
                        + " reservation_reference=:reservation_reference AND"
                        + " reservation_status='RESERVED'",
                p);
    }

    private void lockReservationScope(long merchantId, String currency) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("merchant_id", merchantId);
        p.addValue("currency", currency);
        jdbcTemplate.update(
                "INSERT INTO ledger_reservation_controls "
                        + "(merchant_id, currency, control_status) "
                        + "VALUES (:merchant_id, :currency, 'ACTIVE') "
                        + "ON DUPLICATE KEY UPDATE updated_at=CURRENT_TIMESTAMP",
                p);
        jdbcTemplate.query(
                "SELECT id FROM ledger_reservation_controls "
                        + "WHERE merchant_id=:merchant_id AND currency=:currency FOR UPDATE",
                p,
                (rs, rowNum) -> rs.getLong("id"));
    }

    private NormalizedReservation normalizeReservation(
            long merchantId, String currency, ReservationCommand command) {
        if (command == null
                || blank(command.reservationReference())
                || merchantId <= 0
                || blank(command.sourceReference())
                || blank(currency)) {
            throw new PaymentGatewayException(
                    "Ledger reservation requires reference, merchant, source, and currency");
        }
        if (command.amount() == null || command.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new PaymentGatewayException(
                    "Ledger reservation amount must be greater than zero");
        }
        return new NormalizedReservation(
                command.reservationReference(),
                merchantId,
                command.sourceReference(),
                command.amount().setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                currency.trim().toUpperCase());
    }

    private void insertReservation(NormalizedReservation reservation) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("reservation_reference", reservation.reservationReference());
        p.addValue("merchant_id", reservation.merchantId());
        p.addValue("source_reference", reservation.sourceReference());
        p.addValue("amount", reservation.amount());
        p.addValue("currency", reservation.currency());
        try {
            jdbcTemplate.update(
                    "INSERT INTO ledger_reservations (reservation_reference, merchant_id,"
                            + " source_reference, amount, currency, reservation_status) VALUES"
                            + " (:reservation_reference, :merchant_id, :source_reference, :amount,"
                            + " :currency, 'RESERVED')",
                    p);
        } catch (DuplicateKeyException ignored) {
            ExistingReservation existing = findReservation(reservation.reservationReference());
            if (existing != null
                    && existing.matches(
                            reservation.merchantId(),
                            reservation.sourceReference(),
                            reservation.amount(),
                            reservation.currency())) {
                return;
            }
            throw new PaymentGatewayException(
                    "Ledger reservation reference already exists with different attributes");
        }
    }

    private void validateEntries(List<LedgerEntryCommand> entries) {
        if (entries == null || entries.size() < 2) {
            throw new PaymentGatewayException("Ledger transaction requires at least two entries");
        }
        for (LedgerEntryCommand entry : entries) {
            if (blank(entry.accountCode()) || blank(entry.direction()) || blank(entry.currency())) {
                throw new PaymentGatewayException(
                        "Ledger entry account, direction, and currency are required");
            }
            if (!"DR".equalsIgnoreCase(entry.direction())
                    && !"CR".equalsIgnoreCase(entry.direction())) {
                throw new PaymentGatewayException("Ledger entry direction must be DR or CR");
            }
            if (entry.amount() == null || entry.amount().compareTo(BigDecimal.ZERO) <= 0) {
                throw new PaymentGatewayException("Ledger entry amount must be greater than zero");
            }
        }
    }

    private Map<String, Totals> totalsByCurrency(List<LedgerEntryCommand> entries) {
        Map<String, Totals> totals = new LinkedHashMap<>();
        for (LedgerEntryCommand entry : entries) {
            String currency = entry.currency().trim().toUpperCase();
            Totals total = totals.computeIfAbsent(currency, ignored -> new Totals());
            BigDecimal amount = entry.amount().setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            if ("DR".equalsIgnoreCase(entry.direction())) {
                total.debits = total.debits.add(amount);
            } else {
                total.credits = total.credits.add(amount);
            }
        }
        return totals;
    }

    /**
     * Rejects a posting whose currency has an active {@code ledger_period_locks} row covering today
     * (ADR 0004) - {@code post()}/{@code reverse()} always post as of "now", so a lock only ever
     * blocks *today's* postings, never arbitrary historical periods. Fail-open by construction: an
     * empty (or fully released/expired) lock table means this query returns no rows for every
     * currency and the method returns normally, which is the default and expected state - {@code
     * post()} has 9 real production dependents and an accidental lock must never silently halt
     * payment processing platform-wide.
     */
    private void checkPeriodsNotLocked(Set<String> currencies) {
        for (String currency : currencies) {
            List<String> lockedBy =
                    jdbcTemplate.query(
                            "SELECT locked_by FROM ledger_period_locks WHERE currency = :currency"
                                + " AND released_at IS NULL AND period_start <= CURRENT_DATE AND"
                                + " period_end >= CURRENT_DATE LIMIT 1",
                            new MapSqlParameterSource("currency", currency),
                            (rs, rowNum) -> rs.getString("locked_by"));
            if (!lockedBy.isEmpty()) {
                throw new PaymentGatewayException(
                        "Ledger postings for "
                                + currency
                                + " are locked for the current period (locked by "
                                + lockedBy.get(0)
                                + ")");
            }
        }
    }

    private List<LedgerEntryCommand> mirrorEntries(long originalTxId, String memo) {
        MapSqlParameterSource p = new MapSqlParameterSource("ledger_transaction_id", originalTxId);
        return jdbcTemplate.query(
                "SELECT la.account_code, la.account_name, la.account_type, la.owner_type,"
                    + " la.owner_id, le.entry_direction, le.amount, le.currency FROM ledger_entries"
                    + " le JOIN ledger_accounts la ON la.id = le.account_id WHERE"
                    + " le.ledger_transaction_id = :ledger_transaction_id",
                p,
                (rs, rowNum) -> {
                    String flipped =
                            "DR".equalsIgnoreCase(rs.getString("entry_direction")) ? "CR" : "DR";
                    Object ownerIdObj = rs.getObject("owner_id");
                    return new LedgerEntryCommand(
                            rs.getString("account_code"),
                            rs.getString("account_name"),
                            rs.getString("account_type"),
                            rs.getString("owner_type"),
                            ownerIdObj == null ? null : rs.getLong("owner_id"),
                            flipped,
                            rs.getBigDecimal("amount"),
                            rs.getString("currency"),
                            memo);
                });
    }

    private ExistingReservation findReservation(String reservationReference) {
        List<Map<String, Object>> reservations =
                jdbcTemplate.queryForList(
                        "SELECT merchant_id, source_reference, amount, currency, reservation_status"
                                + " FROM ledger_reservations WHERE"
                                + " reservation_reference=:reservation_reference",
                        new MapSqlParameterSource("reservation_reference", reservationReference));
        if (reservations.isEmpty()) {
            return null;
        }
        Map<String, Object> row = reservations.get(0);
        return new ExistingReservation(
                ((Number) row.get("merchant_id")).longValue(),
                (String) row.get("source_reference"),
                decimal(row.get("amount")),
                (String) row.get("currency"),
                (String) row.get("reservation_status"));
    }

    private Long findTransaction(String transactionReference) {
        List<Long> ids =
                jdbcTemplate.query(
                        "SELECT id FROM ledger_transactions WHERE transaction_reference=:reference",
                        new MapSqlParameterSource("reference", transactionReference),
                        (rs, rowNum) -> rs.getLong("id"));
        return ids.isEmpty() ? null : ids.get(0);
    }

    private long insertTransaction(
            String transactionReference,
            String sourceType,
            String sourceReference,
            String description) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("reference", transactionReference);
        p.addValue("source_type", sourceType);
        p.addValue("source_reference", sourceReference);
        p.addValue("description", description);
        try {
            jdbcTemplate.update(
                    "INSERT INTO ledger_transactions "
                            + "(transaction_reference, source_type, source_reference, description) "
                            + "VALUES (:reference, :source_type, :source_reference, :description)",
                    p);
        } catch (DuplicateKeyException ignored) {
            Long existing = findTransaction(transactionReference);
            if (existing != null) {
                return existing;
            }
            throw ignored;
        }
        Long id =
                jdbcTemplate.queryForObject(
                        "SELECT LAST_INSERT_ID()", new MapSqlParameterSource(), Long.class);
        return id == null ? 0L : id;
    }

    private long ensureAccount(LedgerEntryCommand entry) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("account_code", entry.accountCode());
        p.addValue(
                "account_name",
                blank(entry.accountName()) ? entry.accountCode() : entry.accountName());
        p.addValue("account_type", blank(entry.accountType()) ? "CONTROL" : entry.accountType());
        p.addValue("owner_type", blank(entry.ownerType()) ? "SYSTEM" : entry.ownerType());
        p.addValue("owner_id", entry.ownerId());
        p.addValue("owner_scope_id", entry.ownerId() == null ? 0L : entry.ownerId());
        p.addValue("currency", entry.currency().trim().toUpperCase());
        jdbcTemplate.update(
                "INSERT INTO ledger_accounts (account_code, account_name, account_type, owner_type,"
                    + " owner_id, owner_scope_id, currency) VALUES (:account_code, :account_name,"
                    + " :account_type, :owner_type, :owner_id, :owner_scope_id, :currency) ON"
                    + " DUPLICATE KEY UPDATE account_name=:account_name, account_status='ACTIVE'",
                p);
        Long id =
                jdbcTemplate.queryForObject(
                        "SELECT id FROM ledger_accounts WHERE account_code=:account_code "
                                + "AND owner_type=:owner_type "
                                + "AND owner_scope_id=:owner_scope_id "
                                + "AND currency=:currency",
                        p,
                        Long.class);
        return id == null ? 0L : id;
    }

    private void insertEntry(long txId, long accountId, LedgerEntryCommand entry) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("ledger_transaction_id", txId);
        p.addValue("account_id", accountId);
        p.addValue("direction", entry.direction().trim().toUpperCase());
        p.addValue("amount", entry.amount().setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        p.addValue("currency", entry.currency().trim().toUpperCase());
        p.addValue("memo", entry.memo());
        jdbcTemplate.update(
                "INSERT INTO ledger_entries (ledger_transaction_id, account_id, entry_direction,"
                    + " amount, currency, entry_memo) VALUES (:ledger_transaction_id, :account_id,"
                    + " :direction, :amount, :currency, :memo)",
                p);
    }

    private BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal bd) {
            return bd.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        }
        if (value == null) {
            return BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        }
        return new BigDecimal(String.valueOf(value)).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    public record ReservationCommand(
            String reservationReference, String sourceReference, BigDecimal amount) {}

    public record BatchReservationResult(
            boolean reserved, BigDecimal required, BigDecimal available) {}

    private record NormalizedReservation(
            String reservationReference,
            long merchantId,
            String sourceReference,
            BigDecimal amount,
            String currency) {}

    private record ExistingReservation(
            long merchantId,
            String sourceReference,
            BigDecimal amount,
            String currency,
            String reservationStatus) {
        private boolean matches(
                long expectedMerchantId,
                String expectedSourceReference,
                BigDecimal expectedAmount,
                String expectedCurrency) {
            return merchantId == expectedMerchantId
                    && Objects.equals(sourceReference, expectedSourceReference)
                    && decimalValue(amount).compareTo(expectedAmount) == 0
                    && expectedCurrency.equalsIgnoreCase(currency)
                    && "RESERVED".equalsIgnoreCase(reservationStatus);
        }

        private static BigDecimal decimalValue(BigDecimal value) {
            return value == null
                    ? BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP)
                    : value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        }
    }

    private static class Totals {
        private BigDecimal debits = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        private BigDecimal credits = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
