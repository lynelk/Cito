package net.citotech.cito.Model;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Explicit status enum + state machine for {@code merchant_transactions_log.status} (audit B2). The
 * legacy money path still stores/reads this column as a free string, so this enum is additive - it
 * gives new/updated code (ledger dual-write, callback handling) a type-safe, validated status model
 * without requiring every existing literal-string call site to be rewritten at once.
 */
public enum TransactionStatus {
    RECEIVED,
    VALIDATED,
    AUTHORIZED,
    RESERVED,
    SENT_TO_PROVIDER,
    PENDING,
    SUCCESSFUL,
    FAILED,
    UNDETERMINED,
    REVERSED,
    CANCELLED;

    private static final Set<TransactionStatus> TERMINAL =
            EnumSet.of(SUCCESSFUL, FAILED, REVERSED, CANCELLED);

    private static final Map<TransactionStatus, Set<TransactionStatus>> ALLOWED_TRANSITIONS =
            new EnumMap<>(TransactionStatus.class);

    static {
        allow(RECEIVED, RECEIVED, VALIDATED, FAILED, CANCELLED);
        allow(VALIDATED, VALIDATED, AUTHORIZED, FAILED, CANCELLED);
        allow(AUTHORIZED, AUTHORIZED, RESERVED, SENT_TO_PROVIDER, PENDING, FAILED, CANCELLED);
        allow(RESERVED, RESERVED, SENT_TO_PROVIDER, PENDING, FAILED, CANCELLED);
        allow(SENT_TO_PROVIDER, SENT_TO_PROVIDER, PENDING, SUCCESSFUL, FAILED, UNDETERMINED);
        allow(PENDING, PENDING, SUCCESSFUL, FAILED, UNDETERMINED, CANCELLED);
        allow(UNDETERMINED, UNDETERMINED, PENDING, SUCCESSFUL, FAILED, REVERSED);
        // Provider callbacks/status polls are at-least-once evidence. Replaying the same terminal
        // state is idempotent; changing one terminal state into another remains prohibited.
        allow(SUCCESSFUL, SUCCESSFUL);
        allow(FAILED, FAILED);
        allow(REVERSED, REVERSED);
        allow(CANCELLED, CANCELLED);
    }

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    /**
     * Validates a transaction lifecycle transition. Re-applying the same state is allowed so
     * provider retries and status repairs remain idempotent. Once terminal, only the identical
     * terminal status may be replayed; corrections still require explicit reversal/correction flows
     * rather than mutation to a different terminal outcome.
     */
    public boolean canTransitionTo(TransactionStatus next) {
        if (next == null) {
            return false;
        }
        return ALLOWED_TRANSITIONS.getOrDefault(this, Set.of()).contains(next);
    }

    public static TransactionStatus fromString(String value) {
        if (value == null) {
            return null;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static void allow(TransactionStatus from, TransactionStatus... to) {
        ALLOWED_TRANSITIONS.put(from, to.length == 0 ? Set.of() : EnumSet.copyOf(Lists.of(to)));
    }

    private static final class Lists {
        private Lists() {}

        private static java.util.List<TransactionStatus> of(TransactionStatus... values) {
            return java.util.Arrays.asList(values);
        }
    }
}
