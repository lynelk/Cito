-- Additive API access pricing. Existing service and payment fees are unchanged.
-- A row lock serializes rate publication with admission. Old price books remain evidence.
CREATE TABLE api_endpoint_rates (
 endpoint_key CHAR(36) NOT NULL PRIMARY KEY,
 http_method VARCHAR(10) NOT NULL,
 route_template VARCHAR(512) NOT NULL,
 amount DECIMAL(19,4) NOT NULL DEFAULT 0,
 currency CHAR(3) NOT NULL DEFAULT 'UGX',
 version_id BIGINT UNSIGNED NULL,
 CONSTRAINT chk_api_rate_nonnegative CHECK (amount >= 0),
 UNIQUE KEY uq_api_endpoint (http_method,route_template)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
