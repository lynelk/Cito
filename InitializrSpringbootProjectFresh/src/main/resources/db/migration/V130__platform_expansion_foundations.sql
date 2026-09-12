-- Cito platform expansion foundations.
-- Additive only. Existing Payments, Communications, Identity, Billing and Integrations records remain authoritative.

CREATE TABLE platform_customers (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  merchant_id BIGINT NOT NULL,
  customer_reference VARCHAR(80) NOT NULL,
  external_reference VARCHAR(190) NULL,
  display_name VARCHAR(190) NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  metadata_json JSON NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_platform_customer_reference (customer_reference),
  UNIQUE KEY uk_platform_customer_external (merchant_id, external_reference),
  KEY idx_platform_customer_merchant (merchant_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE platform_customer_identifiers (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  customer_id BIGINT NOT NULL,
  identifier_type VARCHAR(64) NOT NULL,
  identifier_value_hash VARCHAR(128) NOT NULL,
  masked_value VARCHAR(190) NULL,
  verification_status VARCHAR(32) NOT NULL DEFAULT 'UNVERIFIED',
  verified_by_service VARCHAR(64) NULL,
  verified_at TIMESTAMP NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_platform_customer_identifier (customer_id, identifier_type, identifier_value_hash),
  KEY idx_platform_customer_identifier_hash (identifier_type, identifier_value_hash),
  CONSTRAINT fk_platform_customer_identifier_customer FOREIGN KEY (customer_id) REFERENCES platform_customers(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE platform_customer_consents (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  customer_id BIGINT NOT NULL,
  purpose_code VARCHAR(80) NOT NULL,
  channel_code VARCHAR(32) NULL,
  consent_version VARCHAR(32) NOT NULL,
  status VARCHAR(24) NOT NULL,
  source VARCHAR(80) NOT NULL,
  evidence_reference VARCHAR(190) NULL,
  obtained_at TIMESTAMP NULL,
  withdrawn_at TIMESTAMP NULL,
  expires_at TIMESTAMP NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_platform_customer_consent (customer_id, purpose_code, channel_code, consent_version),
  KEY idx_platform_customer_consent_status (customer_id, purpose_code, status),
  CONSTRAINT fk_platform_customer_consent_customer FOREIGN KEY (customer_id) REFERENCES platform_customers(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE integration_connectors
  ADD COLUMN owning_domain VARCHAR(64) NULL AFTER required_service_code,
  ADD COLUMN capabilities_json JSON NULL AFTER owning_domain,
  ADD COLUMN countries_json JSON NULL AFTER capabilities_json,
  ADD COLUMN environments_json JSON NULL AFTER countries_json,
  ADD COLUMN credential_schema_json JSON NULL AFTER environments_json,
  ADD COLUMN maturity_state VARCHAR(32) NOT NULL DEFAULT 'SANDBOX_ONLY' AFTER credential_schema_json,
  ADD COLUMN commercial_mode VARCHAR(32) NOT NULL DEFAULT 'INCLUDED' AFTER maturity_state,
  ADD COLUMN health_status VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN' AFTER commercial_mode,
  ADD COLUMN health_checked_at TIMESTAMP NULL AFTER health_status;

ALTER TABLE integration_installations
  ADD COLUMN connection_health VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN' AFTER status,
  ADD COLUMN last_health_at TIMESTAMP NULL AFTER connection_health,
  ADD COLUMN credential_expires_at TIMESTAMP NULL AFTER last_health_at,
  ADD COLUMN revoked_at TIMESTAMP NULL AFTER credential_expires_at;

UPDATE integration_connectors
SET owning_domain=COALESCE(owning_domain, 'INTEGRATIONS'),
    capabilities_json=COALESCE(capabilities_json, JSON_ARRAY('SYNC')),
    countries_json=COALESCE(countries_json, JSON_ARRAY('UG')),
    environments_json=COALESCE(environments_json, JSON_ARRAY('SANDBOX','PRODUCTION')),
    credential_schema_json=COALESCE(credential_schema_json, JSON_OBJECT()),
    maturity_state=CASE WHEN status='ACTIVE' THEN 'PRODUCTION_READY' ELSE 'SANDBOX_ONLY' END
WHERE owning_domain IS NULL;

INSERT INTO integration_connectors
  (connector_code, connector_name, connector_category, description, publisher, auth_type,
   required_service_code, owning_domain, capabilities_json, countries_json, environments_json,
   credential_schema_json, maturity_state, commercial_mode, health_status, status)
VALUES
  ('WABA_CLOUD_API', 'WhatsApp Business Cloud API', 'COMMUNICATIONS',
   'WhatsApp channel connection governed by Cito Communications.', 'Core-Synergies', 'SECRET_REFERENCE',
   'COMMUNICATIONS', 'COMMUNICATIONS', JSON_ARRAY('WHATSAPP_SEND','WHATSAPP_TEMPLATE','WHATSAPP_INBOUND','DELIVERY_RECEIPTS'),
   JSON_ARRAY('UG'), JSON_ARRAY('SANDBOX','PRODUCTION'),
   JSON_OBJECT('required', JSON_ARRAY('access_token','phone_number_id')), 'CERTIFICATION_PENDING', 'INCLUDED', 'UNKNOWN', 'ACTIVE'),
  ('QUICKBOOKS_ONLINE', 'QuickBooks Online', 'ACCOUNTING',
   'Accounting connector definition. Execution remains disabled until OAuth and certification evidence are complete.',
   'Core-Synergies', 'OAUTH2', 'INTEGRATIONS_MARKETPLACE', 'INTEGRATIONS',
   JSON_ARRAY('CUSTOMER_EXPORT','INVOICE_EXPORT','PAYMENT_EXPORT','REFUND_EXPORT'), JSON_ARRAY('UG'),
   JSON_ARRAY('SANDBOX','PRODUCTION'), JSON_OBJECT('oauth2', true), 'SANDBOX_ONLY', 'PREMIUM', 'UNKNOWN', 'ACTIVE'),
  ('CITO_VALIDATE', 'Cito Validate', 'REGULATORY_DATA',
   'Internal and third-party regulatory/data-quality validation connector.', 'Core-Synergies', 'CITO_SESSION_OR_API_KEY',
   'CITO_VALIDATE', 'VALIDATION', JSON_ARRAY('DATASET_VALIDATE','FINDINGS_EXPORT','SUBMISSION_EVIDENCE'),
   JSON_ARRAY('UG'), JSON_ARRAY('SANDBOX','PRODUCTION'), JSON_OBJECT(), 'SANDBOX_ONLY', 'PREMIUM', 'UNKNOWN', 'ACTIVE')
ON DUPLICATE KEY UPDATE
  connector_name=VALUES(connector_name), description=VALUES(description), owning_domain=VALUES(owning_domain),
  capabilities_json=VALUES(capabilities_json), countries_json=VALUES(countries_json), environments_json=VALUES(environments_json),
  credential_schema_json=VALUES(credential_schema_json), commercial_mode=VALUES(commercial_mode);

INSERT INTO integration_connector_versions(connector_id, version_number, manifest_json, status)
SELECT id, '1.0.0',
       JSON_OBJECT('capabilities', capabilities_json, 'maturityState', maturity_state, 'owningDomain', owning_domain),
       'ACTIVE'
FROM integration_connectors c
WHERE c.connector_code IN ('WABA_CLOUD_API','QUICKBOOKS_ONLINE','CITO_VALIDATE')
  AND NOT EXISTS (
    SELECT 1 FROM integration_connector_versions v
    WHERE v.connector_id=c.id AND v.version_number='1.0.0'
  );

CREATE TABLE validation_rule_packs (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  pack_code VARCHAR(80) NOT NULL,
  pack_name VARCHAR(190) NOT NULL,
  jurisdiction VARCHAR(64) NOT NULL,
  authority_name VARCHAR(190) NULL,
  dataset_type VARCHAR(80) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
  content_status VARCHAR(32) NOT NULL DEFAULT 'CONTENT_REQUIRED',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_validation_rule_pack_code (pack_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE validation_rule_pack_versions (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  rule_pack_id BIGINT NOT NULL,
  version_number VARCHAR(32) NOT NULL,
  rules_json JSON NOT NULL,
  engine_version VARCHAR(32) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
  effective_from TIMESTAMP NULL,
  effective_to TIMESTAMP NULL,
  created_by VARCHAR(160) NULL,
  approved_by VARCHAR(160) NULL,
  approved_at TIMESTAMP NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_validation_rule_pack_version (rule_pack_id, version_number),
  CONSTRAINT fk_validation_rule_version_pack FOREIGN KEY (rule_pack_id) REFERENCES validation_rule_packs(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE validation_mapping_profiles (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  merchant_id BIGINT NOT NULL,
  rule_pack_id BIGINT NOT NULL,
  mapping_code VARCHAR(80) NOT NULL,
  display_name VARCHAR(190) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_validation_mapping_profile (merchant_id, mapping_code),
  CONSTRAINT fk_validation_mapping_rule_pack FOREIGN KEY (rule_pack_id) REFERENCES validation_rule_packs(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE validation_mapping_versions (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  mapping_profile_id BIGINT NOT NULL,
  version_number VARCHAR(32) NOT NULL,
  mapping_json JSON NOT NULL,
  source_schema_hash VARCHAR(128) NULL,
  created_by VARCHAR(160) NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_validation_mapping_version (mapping_profile_id, version_number),
  CONSTRAINT fk_validation_mapping_version_profile FOREIGN KEY (mapping_profile_id) REFERENCES validation_mapping_profiles(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE validation_jobs (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  merchant_id BIGINT NOT NULL,
  job_reference VARCHAR(80) NOT NULL,
  rule_pack_version_id BIGINT NOT NULL,
  mapping_version_id BIGINT NULL,
  source_reference VARCHAR(255) NOT NULL,
  source_sha256 VARCHAR(64) NOT NULL,
  engine_version VARCHAR(32) NOT NULL,
  idempotency_key VARCHAR(120) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'QUEUED',
  total_records BIGINT NOT NULL DEFAULT 0,
  valid_records BIGINT NOT NULL DEFAULT 0,
  error_records BIGINT NOT NULL DEFAULT 0,
  warning_records BIGINT NOT NULL DEFAULT 0,
  requested_by VARCHAR(160) NULL,
  requested_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  started_at TIMESTAMP NULL,
  completed_at TIMESTAMP NULL,
  failure_code VARCHAR(80) NULL,
  failure_detail VARCHAR(1000) NULL,
  UNIQUE KEY uk_validation_job_reference (job_reference),
  UNIQUE KEY uk_validation_job_idempotency (merchant_id, idempotency_key),
  KEY idx_validation_job_merchant (merchant_id, requested_at),
  KEY idx_validation_job_queue (status, requested_at),
  CONSTRAINT fk_validation_job_rule_version FOREIGN KEY (rule_pack_version_id) REFERENCES validation_rule_pack_versions(id),
  CONSTRAINT fk_validation_job_mapping_version FOREIGN KEY (mapping_version_id) REFERENCES validation_mapping_versions(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE validation_findings (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  job_id BIGINT NOT NULL,
  record_number BIGINT NULL,
  field_name VARCHAR(190) NULL,
  rule_code VARCHAR(120) NOT NULL,
  severity VARCHAR(16) NOT NULL,
  category VARCHAR(64) NOT NULL,
  message VARCHAR(1000) NOT NULL,
  remediation VARCHAR(1000) NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'OPEN',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_validation_finding_job (job_id, severity, status),
  CONSTRAINT fk_validation_finding_job FOREIGN KEY (job_id) REFERENCES validation_jobs(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE validation_artifacts (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  job_id BIGINT NOT NULL,
  artifact_type VARCHAR(64) NOT NULL,
  storage_reference VARCHAR(500) NOT NULL,
  sha256 VARCHAR(64) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_validation_artifact_job (job_id, artifact_type),
  CONSTRAINT fk_validation_artifact_job FOREIGN KEY (job_id) REFERENCES validation_jobs(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE validation_submissions (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  job_id BIGINT NOT NULL,
  recipient_code VARCHAR(80) NOT NULL,
  submission_reference VARCHAR(120) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'PREPARED',
  approved_by VARCHAR(160) NULL,
  approved_at TIMESTAMP NULL,
  submitted_at TIMESTAMP NULL,
  evidence_reference VARCHAR(500) NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_validation_submission_reference (submission_reference),
  KEY idx_validation_submission_job (job_id, status),
  CONSTRAINT fk_validation_submission_job FOREIGN KEY (job_id) REFERENCES validation_jobs(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO validation_rule_packs
  (pack_code, pack_name, jurisdiction, authority_name, dataset_type, status, content_status)
VALUES
  ('UG_BOU_MANAGED', 'Uganda Bank of Uganda Managed Validation Pack', 'UG', 'Bank of Uganda', 'REGULATORY_REPORTING', 'DRAFT', 'CONTENT_REQUIRED'),
  ('UG_CRB_MANAGED', 'Uganda Credit Reporting Managed Validation Pack', 'UG', 'Credit Reporting', 'CREDIT_REPORTING', 'DRAFT', 'CONTENT_REQUIRED')
ON DUPLICATE KEY UPDATE pack_name=VALUES(pack_name), authority_name=VALUES(authority_name);
