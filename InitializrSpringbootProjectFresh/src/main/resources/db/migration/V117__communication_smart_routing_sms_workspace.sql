-- Cito Communications: smart SMS routing, sender identities, contacts and two-way conversations.
-- Additive to V50/V56-V60/V77/V82. Provider cost is effective-dated and never assumed when unknown.

CREATE TABLE IF NOT EXISTS communication_provider_rates (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  provider_code VARCHAR(50) NOT NULL,
  channel VARCHAR(20) NOT NULL DEFAULT 'SMS',
  country_code VARCHAR(3) NULL,
  currency_code VARCHAR(3) NOT NULL DEFAULT 'UGX',
  billing_unit VARCHAR(30) NOT NULL DEFAULT 'SMS_SEGMENT',
  provider_cost_per_unit DECIMAL(18,6) NULL,
  customer_price_per_unit DECIMAL(18,6) NULL,
  valid_from DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  valid_to DATETIME NULL,
  enabled_flag CHAR(1) NOT NULL DEFAULT 'Y',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_comm_rate_lookup (provider_code, channel, country_code, currency_code, enabled_flag, valid_from),
  KEY idx_comm_rate_effective (channel, enabled_flag, valid_from, valid_to)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS communication_smart_routing_policies (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  merchant_id BIGINT NULL COMMENT 'NULL = platform default',
  channel VARCHAR(20) NOT NULL DEFAULT 'SMS',
  strategy VARCHAR(32) NOT NULL DEFAULT 'BALANCED',
  cost_weight DECIMAL(8,5) NOT NULL DEFAULT 0.55000,
  reliability_weight DECIMAL(8,5) NOT NULL DEFAULT 0.35000,
  priority_weight DECIMAL(8,5) NOT NULL DEFAULT 0.10000,
  fallback_enabled CHAR(1) NOT NULL DEFAULT 'Y',
  require_delivery_receipts CHAR(1) NOT NULL DEFAULT 'N',
  require_inbound CHAR(1) NOT NULL DEFAULT 'N',
  max_provider_cost_per_unit DECIMAL(18,6) NULL,
  currency_code VARCHAR(3) NOT NULL DEFAULT 'UGX',
  enabled_flag CHAR(1) NOT NULL DEFAULT 'Y',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_comm_smart_policy (channel, merchant_id, enabled_flag, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS communication_routing_decisions (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  decision_reference VARCHAR(64) NOT NULL,
  communication_id BIGINT NULL,
  merchant_id BIGINT NOT NULL,
  channel VARCHAR(20) NOT NULL DEFAULT 'SMS',
  strategy VARCHAR(32) NOT NULL,
  selected_provider_code VARCHAR(50) NULL,
  country_code VARCHAR(3) NULL,
  currency_code VARCHAR(3) NULL,
  sms_segments INT NOT NULL DEFAULT 1,
  expected_provider_cost DECIMAL(18,6) NULL,
  candidate_providers_json JSON NULL,
  explanation VARCHAR(1000) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_comm_route_decision_ref (decision_reference),
  KEY idx_comm_route_decision_message (communication_id, created_at),
  KEY idx_comm_route_decision_merchant (merchant_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS communication_sender_identities (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  merchant_id BIGINT NOT NULL,
  sender_id VARCHAR(32) NOT NULL,
  sender_type VARCHAR(24) NOT NULL DEFAULT 'ALPHANUMERIC',
  provider_code VARCHAR(50) NULL,
  country_code VARCHAR(3) NULL,
  approval_status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
  two_way_capable CHAR(1) NOT NULL DEFAULT 'N',
  default_flag CHAR(1) NOT NULL DEFAULT 'N',
  notes VARCHAR(500) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_comm_sender_identity (merchant_id, sender_id, provider_code),
  KEY idx_comm_sender_merchant (merchant_id, approval_status, default_flag)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS communication_contacts (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  merchant_id BIGINT NOT NULL,
  display_name VARCHAR(160) NOT NULL,
  phone_e164 VARCHAR(32) NOT NULL,
  email VARCHAR(255) NULL,
  attributes_json JSON NULL,
  active_flag CHAR(1) NOT NULL DEFAULT 'Y',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_comm_contact_phone (merchant_id, phone_e164),
  KEY idx_comm_contact_name (merchant_id, display_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS communication_contact_groups (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  merchant_id BIGINT NOT NULL,
  group_name VARCHAR(160) NOT NULL,
  description VARCHAR(500) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_comm_contact_group (merchant_id, group_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS communication_contact_group_members (
  group_id BIGINT NOT NULL,
  contact_id BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (group_id, contact_id),
  CONSTRAINT fk_comm_group_member_group FOREIGN KEY (group_id) REFERENCES communication_contact_groups(id) ON DELETE CASCADE,
  CONSTRAINT fk_comm_group_member_contact FOREIGN KEY (contact_id) REFERENCES communication_contacts(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS communication_conversations (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  public_id VARCHAR(64) NOT NULL,
  merchant_id BIGINT NOT NULL,
  contact_id BIGINT NULL,
  phone_e164 VARCHAR(32) NOT NULL,
  sender_identity_id BIGINT NULL,
  provider_code VARCHAR(50) NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
  unread_count INT NOT NULL DEFAULT 0,
  last_message_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_comm_conversation_public (public_id),
  KEY idx_comm_conversation_merchant (merchant_id, status, last_message_at),
  KEY idx_comm_conversation_phone (merchant_id, phone_e164)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS communication_conversation_messages (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  public_id VARCHAR(64) NOT NULL,
  conversation_id BIGINT NOT NULL,
  merchant_id BIGINT NOT NULL,
  direction VARCHAR(12) NOT NULL,
  provider_code VARCHAR(50) NULL,
  provider_message_id VARCHAR(160) NULL,
  message_reference VARCHAR(64) NULL,
  from_address VARCHAR(64) NULL,
  to_address VARCHAR(64) NULL,
  body TEXT NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'RECEIVED',
  raw_metadata_json JSON NULL,
  occurred_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_comm_conversation_message_public (public_id),
  UNIQUE KEY uk_comm_conversation_provider_message (provider_code, provider_message_id),
  KEY idx_comm_conversation_messages (conversation_id, occurred_at),
  CONSTRAINT fk_comm_conversation_message FOREIGN KEY (conversation_id) REFERENCES communication_conversations(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- A default policy is intentionally cost-aware but does not invent provider rates.
-- Providers with unknown costs remain eligible and fall back to health + configured routing priority.
INSERT INTO communication_smart_routing_policies
  (merchant_id, channel, strategy, cost_weight, reliability_weight, priority_weight,
   fallback_enabled, require_delivery_receipts, require_inbound, currency_code, enabled_flag)
SELECT NULL, 'SMS', 'BALANCED', 0.55000, 0.35000, 0.10000, 'Y', 'N', 'N', 'UGX', 'Y'
WHERE NOT EXISTS (
  SELECT 1 FROM communication_smart_routing_policies
  WHERE merchant_id IS NULL AND channel='SMS' AND enabled_flag='Y'
);
