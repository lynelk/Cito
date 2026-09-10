CREATE TABLE IF NOT EXISTS cito_commercial_packages (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  package_code VARCHAR(64) NOT NULL,
  package_name VARCHAR(160) NOT NULL,
  target_segment VARCHAR(120) NULL,
  description VARCHAR(1200) NULL,
  service_codes_json JSON NOT NULL,
  onboarding_support_level VARCHAR(32) NOT NULL DEFAULT 'STANDARD',
  commercial_terms_json JSON NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
  version_number INT NOT NULL DEFAULT 1,
  created_by VARCHAR(160) NULL,
  approved_by VARCHAR(160) NULL,
  approved_at TIMESTAMP NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_cito_commercial_package_code (package_code),
  KEY idx_cito_commercial_package_status (status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS merchant_commercial_package_assignments (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  merchant_id BIGINT NOT NULL,
  package_id BIGINT NOT NULL,
  environment VARCHAR(16) NOT NULL DEFAULT 'PRODUCTION',
  status VARCHAR(32) NOT NULL DEFAULT 'PROPOSED',
  effective_from TIMESTAMP NULL,
  effective_to TIMESTAMP NULL,
  assigned_by VARCHAR(160) NULL,
  approved_by VARCHAR(160) NULL,
  notes VARCHAR(1200) NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_merchant_commercial_package (merchant_id, package_id, environment),
  KEY idx_merchant_commercial_package_status (merchant_id, environment, status),
  CONSTRAINT fk_merchant_commercial_package FOREIGN KEY (package_id) REFERENCES cito_commercial_packages(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS founding20_merchants (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  merchant_id BIGINT NOT NULL,
  cohort_slot TINYINT UNSIGNED NOT NULL,
  programme_status VARCHAR(32) NOT NULL DEFAULT 'CANDIDATE',
  commercial_owner VARCHAR(160) NULL,
  customer_success_owner VARCHAR(160) NULL,
  target_go_live_at TIMESTAMP NULL,
  enrolled_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  exited_at TIMESTAMP NULL,
  exit_reason VARCHAR(500) NULL,
  notes VARCHAR(1200) NULL,
  created_by VARCHAR(160) NULL,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_founding20_merchant (merchant_id),
  UNIQUE KEY uk_founding20_slot (cohort_slot),
  KEY idx_founding20_status (programme_status, target_go_live_at),
  CONSTRAINT chk_founding20_slot CHECK (cohort_slot BETWEEN 1 AND 20)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS embedded_partner_programmes (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  embedded_partner_id BIGINT NOT NULL,
  merchant_id BIGINT NOT NULL,
  programme_status VARCHAR(32) NOT NULL DEFAULT 'CANDIDATE',
  programme_tier VARCHAR(64) NULL,
  commercial_owner VARCHAR(160) NULL,
  target_downstream_merchants INT NULL,
  target_go_live_at TIMESTAMP NULL,
  notes VARCHAR(1200) NULL,
  created_by VARCHAR(160) NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_embedded_partner_programme_partner (embedded_partner_id),
  UNIQUE KEY uk_embedded_partner_programme_merchant (merchant_id),
  KEY idx_embedded_partner_programme_status (programme_status, target_go_live_at),
  CONSTRAINT fk_embedded_partner_programme_partner FOREIGN KEY (embedded_partner_id) REFERENCES embedded_partners(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
