-- Extend the existing canonical execution journal; do not create a competing Airtel ledger.
-- Nullable leases preserve all existing submissions and encrypted account snapshots.
ALTER TABLE mobile_money_executions
 ADD COLUMN recovery_claim_token VARCHAR(36) NULL,
 ADD COLUMN recovery_claim_until DATETIME(6) NULL,
 ADD COLUMN recovery_attempt_count INT NOT NULL DEFAULT 0,
 ADD COLUMN recovery_last_code VARCHAR(64) NULL,
 ADD COLUMN recovery_last_signal_at DATETIME(6) NULL,
 ADD KEY ix_mobile_money_fenced_recovery (environment,next_poll_at,recovery_claim_until);
