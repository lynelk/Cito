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

-- Immutable application attribution captured before sending an Airtel request.
-- No PIN, client secret, access token or customer number is stored.
CREATE TABLE airtel_recovery_scopes (
    merchant_id BIGINT NOT NULL,
    transaction_reference VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    operation VARCHAR(12) NOT NULL,
    identity_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (merchant_id, transaction_reference, operation)
) ENGINE=InnoDB;
