ALTER TABLE validation_jobs
  ADD COLUMN source_payload JSON NULL AFTER source_sha256,
  ADD COLUMN progress_percent INT NOT NULL DEFAULT 0 AFTER status;

CREATE TABLE integration_health_checks (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  connector_id BIGINT NOT NULL,
  installation_id BIGINT NULL,
  environment VARCHAR(16) NOT NULL,
  outcome VARCHAR(32) NOT NULL,
  response_code VARCHAR(120) NULL,
  response_summary VARCHAR(1000) NULL,
  checked_by VARCHAR(160) NULL,
  checked_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_integration_health_connector (connector_id, checked_at),
  KEY idx_integration_health_installation (installation_id, checked_at),
  CONSTRAINT fk_integration_health_connector FOREIGN KEY (connector_id) REFERENCES integration_connectors(id),
  CONSTRAINT fk_integration_health_installation FOREIGN KEY (installation_id) REFERENCES integration_installations(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
