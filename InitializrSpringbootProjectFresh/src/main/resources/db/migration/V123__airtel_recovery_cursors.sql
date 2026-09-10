-- Scan progress only. Existing payment and treasury records remain the source of truth.
-- No provider credentials, balances or customer identifiers are copied into this table.
CREATE TABLE airtel_recovery_cursors (
    scope VARCHAR(32) NOT NULL PRIMARY KEY,
    last_id BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT chk_airtel_recovery_last_id CHECK (last_id >= 0)
) ENGINE=InnoDB;

INSERT INTO airtel_recovery_cursors (scope, last_id)
VALUES ('LEGACY', 0), ('PLATFORM_SHARED', 0);
