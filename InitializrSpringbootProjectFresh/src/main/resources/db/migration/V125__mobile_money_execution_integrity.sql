-- Durable claims are committed before an outbound request. Credential snapshots are AES-GCM encrypted.
CREATE TABLE mobile_money_executions (
 transaction_id VARCHAR(64) NOT NULL PRIMARY KEY,
 merchant_id BIGINT NOT NULL,
 merchant_reference VARCHAR(255) NOT NULL,
 channel_code VARCHAR(64) NOT NULL,
 environment VARCHAR(16) NOT NULL,
 operation VARCHAR(16) NOT NULL,
 country_code VARCHAR(8) NOT NULL,
 currency_code VARCHAR(8) NOT NULL,
 request_hash VARCHAR(64) NOT NULL,
 provider_reference VARCHAR(64) NOT NULL,
 financial_reference VARCHAR(191) NULL,
 credential_source VARCHAR(32) NOT NULL,
 credential_snapshot MEDIUMTEXT NOT NULL,
 treasury_reservation_id BIGINT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 last_polled_at TIMESTAMP NULL,
 next_poll_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uq_mobile_money_reference (merchant_id, merchant_reference),
 UNIQUE KEY uq_mobile_money_provider (channel_code, provider_reference),
 KEY ix_mobile_money_recovery (next_poll_at, transaction_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE merchant_channel_credentials
 ADD COLUMN revision BIGINT NOT NULL DEFAULT 1,
 ADD COLUMN tested_revision BIGINT NULL,
 ADD COLUMN decision_reason VARCHAR(1000) NULL;
-- Local-only historical checks are not evidence of provider connectivity.
UPDATE merchant_channel_credentials SET last_test_status='STRUCTURE_VALID'
 WHERE last_test_status='PASSED' AND last_test_message LIKE '%no provider request%';

ALTER TABLE platform_channel_credentials
 ADD COLUMN revision BIGINT NOT NULL DEFAULT 1,
 ADD COLUMN tested_revision BIGINT NULL,
 ADD COLUMN last_test_status VARCHAR(40) NULL,
 ADD COLUMN last_tested_at TIMESTAMP NULL;

ALTER TABLE payout_approval_queue DROP INDEX uk_payout_approval_reference,
 ADD UNIQUE KEY uk_payout_approval_reference (merchant_id,payout_reference);

-- Preserve historical production reads while recording sandbox executions in the canonical model.
ALTER TABLE merchant_transactions_log ADD COLUMN execution_environment VARCHAR(16) NOT NULL DEFAULT 'PRODUCTION',
 ADD KEY ix_payment_environment_status (execution_environment,status,created_on);
CREATE VIEW merchant_production_transactions AS SELECT * FROM merchant_transactions_log WHERE execution_environment='PRODUCTION';
CREATE VIEW merchant_sandbox_transactions AS SELECT * FROM merchant_transactions_log WHERE execution_environment='SANDBOX';

-- Airtel shared sandbox requires the same explicitly separated, zero-balance product wallets.
-- This seeds accounting scopes only; credentials and entitlements still require approval.
INSERT INTO provider_treasury_accounts
 (channel_code,environment,country_code,currency_code,account_role,display_name,prefund_required,low_float_threshold)
VALUES ('airtel_open_api','SANDBOX','UG','UGX','MASTER','Airtel sandbox master account','NO',0.0000)
ON DUPLICATE KEY UPDATE display_name=VALUES(display_name);
INSERT INTO provider_treasury_accounts
 (channel_code,environment,country_code,currency_code,account_role,display_name,parent_account_id,prefund_required,low_float_threshold)
SELECT channel_code,environment,country_code,currency_code,'COLLECTION','Airtel sandbox collection account',id,'NO',0.0000
FROM provider_treasury_accounts WHERE channel_code='airtel_open_api' AND environment='SANDBOX' AND account_role='MASTER'
ON DUPLICATE KEY UPDATE display_name=VALUES(display_name);
INSERT INTO provider_treasury_accounts
 (channel_code,environment,country_code,currency_code,account_role,display_name,parent_account_id,prefund_required,low_float_threshold)
SELECT channel_code,environment,country_code,currency_code,'DISBURSEMENT','Airtel sandbox disbursement account',id,'YES',0.0000
FROM provider_treasury_accounts WHERE channel_code='airtel_open_api' AND environment='SANDBOX' AND account_role='MASTER'
ON DUPLICATE KEY UPDATE display_name=VALUES(display_name);
