-- Preserve exact MTN callback ownership across merchants and operations.
-- The generic provider_conversation_references table predates merchant-scoped MTN execution and
-- stores only a transaction reference, which is not globally unique across merchants.
CREATE TABLE IF NOT EXISTS `mtn_momo_correlations` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `provider_reference` VARCHAR(64) NOT NULL,
  `merchant_number` VARCHAR(255) NOT NULL,
  `merchant_reference` VARCHAR(255) NOT NULL,
  `operation` ENUM('COLLECT','PAYOUT') NOT NULL,
  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_mtn_provider_reference` (`provider_reference`),
  KEY `idx_mtn_merchant_reference` (`merchant_number`, `merchant_reference`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
