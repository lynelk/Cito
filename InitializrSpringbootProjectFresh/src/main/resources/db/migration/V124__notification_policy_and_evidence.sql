-- Extends existing notification preferences, templates and communication delivery outbox.
-- V123 is reserved by the concurrent API reference/rating work. V122 remains untouched.
CREATE TABLE notification_events (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 event_id VARCHAR(128) NOT NULL,
 merchant_id BIGINT NOT NULL,
 event_type VARCHAR(120) NOT NULL,
 source_reference VARCHAR(128) NOT NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 processed_at DATETIME NULL,
 acknowledged_at DATETIME NULL,
 status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
 last_error_safe VARCHAR(255) NULL,
 UNIQUE KEY uk_notification_event (merchant_id,event_id),
 KEY idx_notification_due (status,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE notification_admin_groups (
 group_code VARCHAR(40) PRIMARY KEY,
 display_name VARCHAR(80) NOT NULL,
 quiet_start TIME NULL,
 quiet_end TIME NULL,
 timezone VARCHAR(80) NOT NULL DEFAULT 'UTC'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO notification_admin_groups(group_code,display_name) VALUES
 ('PLATFORM_OPERATIONS','Platform Operations'),('FINANCE','Finance'),('SECURITY','Security'),
 ('COMPLIANCE','Compliance'),('EXECUTIVE','Executive');
CREATE TABLE notification_admin_recipients (
 group_code VARCHAR(40) NOT NULL,
 admin_id BIGINT UNSIGNED NOT NULL,
 escalation_level INT NOT NULL DEFAULT 0,
 active_flag CHAR(1) NOT NULL DEFAULT 'Y',
 PRIMARY KEY (group_code,admin_id),
 FOREIGN KEY (group_code) REFERENCES notification_admin_groups(group_code),
 FOREIGN KEY (admin_id) REFERENCES admins(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE notification_alert_policies (
 event_type VARCHAR(120) PRIMARY KEY,
 group_code VARCHAR(40) NOT NULL,
 enabled_flag CHAR(1) NOT NULL DEFAULT 'Y',
 dedup_seconds INT NOT NULL DEFAULT 300,
 max_per_hour INT NOT NULL DEFAULT 12,
 escalation_seconds INT NOT NULL DEFAULT 900,
 last_dispatched_at DATETIME NULL,
 window_started_at DATETIME NULL,
 window_count INT NOT NULL DEFAULT 0,
 FOREIGN KEY (group_code) REFERENCES notification_admin_groups(group_code),
 CHECK (dedup_seconds BETWEEN 0 AND 86400),
 CHECK (max_per_hour BETWEEN 1 AND 1000),
 CHECK (escalation_seconds BETWEEN 60 AND 86400)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE notification_evidence (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 event_row_id BIGINT NOT NULL,
 audience VARCHAR(40) NOT NULL,
 recipient VARCHAR(255) NOT NULL,
 template_key VARCHAR(120) NOT NULL,
 template_version INT NOT NULL,
 template_snapshot TEXT NOT NULL,
 communication_id BIGINT NULL,
 outcome VARCHAR(32) NOT NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_notification_recipient (event_row_id,audience,recipient),
 FOREIGN KEY (event_row_id) REFERENCES notification_events(id),
 FOREIGN KEY (communication_id) REFERENCES communication_messages(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO communication_message_templates(template_key,channel,body_template,status) VALUES ('notification.account.created.v1','SMS','Cito: Account created. Ref: {reference}. Sign in to Cito for details.','ACTIVE');

INSERT INTO communication_message_templates(template_key,channel,body_template,status) VALUES ('notification.account.suspended.v1','SMS','Cito: Account suspended. Ref: {reference}. Sign in to Cito for details.','ACTIVE');
INSERT INTO notification_alert_policies(event_type,group_code) VALUES ('account.suspended','COMPLIANCE');

INSERT INTO communication_message_templates(template_key,channel,body_template,status) VALUES ('notification.security.password.changed.v1','SMS','Cito: Password changed. Ref: {reference}. Sign in to Cito for details.','ACTIVE');
INSERT INTO notification_alert_policies(event_type,group_code) VALUES ('security.password.changed','SECURITY');

INSERT INTO communication_message_templates(template_key,channel,body_template,status) VALUES ('notification.security.mfa.changed.v1','SMS','Cito: Authentication settings changed. Ref: {reference}. Sign in to Cito for details.','ACTIVE');
INSERT INTO notification_alert_policies(event_type,group_code) VALUES ('security.mfa.changed','SECURITY');

INSERT INTO communication_message_templates(template_key,channel,body_template,status) VALUES ('notification.security.authentication.suspicious.v1','SMS','Cito: Suspicious authentication activity. Ref: {reference}. Sign in to Cito for details.','ACTIVE');
INSERT INTO notification_alert_policies(event_type,group_code) VALUES ('security.authentication.suspicious','SECURITY');

INSERT INTO communication_message_templates(template_key,channel,body_template,status) VALUES ('notification.api.credentials.changed.v1','SMS','Cito: API credentials changed. Ref: {reference}. Sign in to Cito for details.','ACTIVE');
INSERT INTO notification_alert_policies(event_type,group_code) VALUES ('api.credentials.changed','SECURITY');

INSERT INTO communication_message_templates(template_key,channel,body_template,status) VALUES ('notification.production.configuration.changed.v1','SMS','Cito: Production configuration changed. Ref: {reference}. Sign in to Cito for details.','ACTIVE');
INSERT INTO notification_alert_policies(event_type,group_code) VALUES ('production.configuration.changed','SECURITY');

INSERT INTO communication_message_templates(template_key,channel,body_template,status) VALUES ('notification.settlement.completed.v1','SMS','Cito: Settlement completed. Ref: {reference}. Sign in to Cito for details.','ACTIVE');
INSERT INTO notification_alert_policies(event_type,group_code) VALUES ('settlement.completed','FINANCE');

INSERT INTO communication_message_templates(template_key,channel,body_template,status) VALUES ('notification.reconciliation.break.v1','SMS','Cito: Reconciliation requires attention. Ref: {reference}. Sign in to Cito for details.','ACTIVE');
INSERT INTO notification_alert_policies(event_type,group_code) VALUES ('reconciliation.break','FINANCE');

INSERT INTO communication_message_templates(template_key,channel,body_template,status) VALUES ('notification.ledger.imbalance.v1','SMS','Cito: Ledger imbalance detected. Ref: {reference}. Sign in to Cito for details.','ACTIVE');
INSERT INTO notification_alert_policies(event_type,group_code) VALUES ('ledger.imbalance','FINANCE');

INSERT INTO communication_message_templates(template_key,channel,body_template,status) VALUES ('notification.compliance.critical.v1','SMS','Cito: Critical compliance event. Ref: {reference}. Sign in to Cito for details.','ACTIVE');
INSERT INTO notification_alert_policies(event_type,group_code) VALUES ('compliance.critical','COMPLIANCE');

INSERT INTO communication_message_templates(template_key,channel,body_template,status) VALUES ('notification.provider.outage.v1','SMS','Cito: Provider outage. Ref: {reference}. Sign in to Cito for details.','ACTIVE');
INSERT INTO notification_alert_policies(event_type,group_code) VALUES ('provider.outage','PLATFORM_OPERATIONS');

INSERT INTO communication_message_templates(template_key,channel,body_template,status) VALUES ('notification.sms.failure_rate.high.v1','SMS','Cito: SMS failure rate exceeded threshold. Ref: {reference}. Sign in to Cito for details.','ACTIVE');
INSERT INTO notification_alert_policies(event_type,group_code) VALUES ('sms.failure_rate.high','PLATFORM_OPERATIONS');

INSERT INTO communication_message_templates(template_key,channel,body_template,status) VALUES ('notification.sms.queue.backlog.v1','SMS','Cito: SMS queue backlog exceeded threshold. Ref: {reference}. Sign in to Cito for details.','ACTIVE');
INSERT INTO notification_alert_policies(event_type,group_code) VALUES ('sms.queue.backlog','PLATFORM_OPERATIONS');

INSERT INTO communication_message_templates(template_key,channel,body_template,status) VALUES ('notification.sms.callback.failure.v1','SMS','Cito: SMS callback or delivery report failure. Ref: {reference}. Sign in to Cito for details.','ACTIVE');
INSERT INTO notification_alert_policies(event_type,group_code) VALUES ('sms.callback.failure','PLATFORM_OPERATIONS');

INSERT INTO communication_message_templates(template_key,channel,body_template,status) VALUES ('notification.api.degradation.sustained.v1','SMS','Cito: Sustained API degradation. Ref: {reference}. Sign in to Cito for details.','ACTIVE');
INSERT INTO notification_alert_policies(event_type,group_code) VALUES ('api.degradation.sustained','PLATFORM_OPERATIONS');

INSERT INTO communication_message_templates(template_key,channel,body_template,status) VALUES ('notification.balance.threshold.v1','SMS','Cito: Balance threshold reached. Ref: {reference}. Sign in to Cito for details.','ACTIVE');
INSERT INTO notification_alert_policies(event_type,group_code) VALUES ('balance.threshold','FINANCE');

INSERT INTO communication_message_templates(template_key,channel,body_template,status) VALUES ('notification.report.scheduled.v1','SMS','Cito: Scheduled report ready. Ref: {reference}. Sign in to Cito for details.','ACTIVE');

INSERT INTO communication_message_templates(template_key,channel,body_template,status) VALUES ('notification.marketing.campaign.v1','SMS','Cito: Optional marketing communication. Ref: {reference}. Sign in to Cito for details.','ACTIVE');

INSERT INTO communication_message_templates(template_key,channel,body_template,status) VALUES ('notification.payment.pending.v1','SMS','Cito: A collection request was submitted to the provider and is awaiting confirmation. Ref: {reference}. Sign in to Cito for details.','ACTIVE');

INSERT INTO communication_message_templates(template_key,channel,body_template,status) VALUES ('notification.payment.completed.v1','SMS','Cito: A collection was confirmed successful by the provider. Ref: {reference}. Sign in to Cito for details.','ACTIVE');

INSERT INTO communication_message_templates(template_key,channel,body_template,status) VALUES ('notification.payment.failed.v1','SMS','Cito: A collection was confirmed failed by the provider. Ref: {reference}. Sign in to Cito for details.','ACTIVE');

INSERT INTO communication_message_templates(template_key,channel,body_template,status) VALUES ('notification.payout.pending.v1','SMS','Cito: A payout request was submitted to the provider and is awaiting confirmation. Ref: {reference}. Sign in to Cito for details.','ACTIVE');

INSERT INTO communication_message_templates(template_key,channel,body_template,status) VALUES ('notification.payout.completed.v1','SMS','Cito: A payout was confirmed successful by the provider. Ref: {reference}. Sign in to Cito for details.','ACTIVE');

INSERT INTO communication_message_templates(template_key,channel,body_template,status) VALUES ('notification.payout.failed.v1','SMS','Cito: A payout was confirmed failed by the provider. Ref: {reference}. Sign in to Cito for details.','ACTIVE');

INSERT INTO communication_message_templates(template_key,channel,body_template,status) VALUES ('notification.refund.completed.v1','SMS','Cito: A refund was confirmed successful by the provider. Ref: {reference}. Sign in to Cito for details.','ACTIVE');

INSERT INTO communication_message_templates(template_key,channel,body_template,status) VALUES ('notification.refund.failed.v1','SMS','Cito: A refund was confirmed failed by the provider. Ref: {reference}. Sign in to Cito for details.','ACTIVE');

INSERT INTO communication_message_templates(template_key,channel,body_template,status) VALUES ('notification.invoice.issued.v1','SMS','Cito: A billing invoice was issued to the merchant. Ref: {reference}. Sign in to Cito for details.','ACTIVE');

INSERT INTO communication_message_templates(template_key,channel,body_template,status) VALUES ('notification.validation.case.created.v1','SMS','Cito: A validation case was created for a merchant. Ref: {reference}. Sign in to Cito for details.','ACTIVE');

INSERT INTO communication_message_templates(template_key,channel,body_template,status) VALUES ('notification.validation.case.processing.v1','SMS','Cito: A validation case moved into processing. Ref: {reference}. Sign in to Cito for details.','ACTIVE');

INSERT INTO communication_message_templates(template_key,channel,body_template,status) VALUES ('notification.validation.check.updated.v1','SMS','Cito: A validation check changed status (passed, failed, pending, error). Ref: {reference}. Sign in to Cito for details.','ACTIVE');

INSERT INTO communication_message_templates(template_key,channel,body_template,status) VALUES ('notification.validation.case.review_required.v1','SMS','Cito: A validation case requires manual review. Ref: {reference}. Sign in to Cito for details.','ACTIVE');

INSERT INTO communication_message_templates(template_key,channel,body_template,status) VALUES ('notification.validation.case.verified.v1','SMS','Cito: A validation case completed with a verified decision. Ref: {reference}. Sign in to Cito for details.','ACTIVE');

INSERT INTO communication_message_templates(template_key,channel,body_template,status) VALUES ('notification.validation.case.rejected.v1','SMS','Cito: A validation case completed with a rejected decision. Ref: {reference}. Sign in to Cito for details.','ACTIVE');

INSERT INTO communication_message_templates(template_key,channel,body_template,status) VALUES ('notification.validation.case.inconclusive.v1','SMS','Cito: A validation case completed inconclusively (evidence insufficient or technical error). Ref: {reference}. Sign in to Cito for details.','ACTIVE');

INSERT INTO communication_message_templates(template_key,channel,body_template,status) VALUES ('notification.validation.case.expired.v1','SMS','Cito: A validation case expired before completion. Ref: {reference}. Sign in to Cito for details.','ACTIVE');

-- Runtime support must not advertise an unimplemented status-query contract.
UPDATE communication_provider_capabilities SET supports_status_query='N' WHERE provider_code='SMSMOBILO_SMS';
UPDATE communication_providers SET credentials_ref='communication_provider_credentials:SMSMOBILO_SMS/api_key' WHERE provider_code='SMSMOBILO_SMS';

-- Capture committed state changes, including legacy write paths, in the same database transaction.
-- No credentials, OTPs, balances or message bodies are copied into the event journal.
DELIMITER $$
CREATE TRIGGER notification_merchant_account_created AFTER INSERT ON merchant_admins FOR EACH ROW
BEGIN
 IF NEW.merchant_id IS NOT NULL THEN
  INSERT INTO notification_events(event_id,merchant_id,event_type,source_reference)
  VALUES (UUID(),NEW.merchant_id,'account.created',CONCAT('merchant-user-',NEW.id));
 END IF;
END$$
CREATE TRIGGER notification_merchant_security_changed AFTER UPDATE ON merchant_admins FOR EACH ROW
BEGIN
 IF NOT (NEW.password <=> OLD.password) AND NEW.merchant_id IS NOT NULL THEN
  INSERT INTO notification_events(event_id,merchant_id,event_type,source_reference)
  VALUES (UUID(),NEW.merchant_id,'security.password.changed',CONCAT('merchant-user-',NEW.id));
 END IF;
 IF NEW.status='SUSPENDED' AND OLD.status<>'SUSPENDED' AND NEW.merchant_id IS NOT NULL THEN
  INSERT INTO notification_events(event_id,merchant_id,event_type,source_reference)
  VALUES (UUID(),NEW.merchant_id,'account.suspended',CONCAT('merchant-user-',NEW.id));
 END IF;
END$$
CREATE TRIGGER notification_admin_security_changed AFTER UPDATE ON admins FOR EACH ROW
BEGIN
 IF NOT (NEW.password <=> OLD.password) THEN
  INSERT INTO notification_events(event_id,merchant_id,event_type,source_reference)
  VALUES (UUID(),0,'security.password.changed',CONCAT('admin-',NEW.id));
 END IF;
END$$
CREATE TRIGGER notification_admin_mfa_changed AFTER UPDATE ON admin_mfa_totp FOR EACH ROW
BEGIN
 IF NOT (NEW.enabled_flag <=> OLD.enabled_flag) THEN
  INSERT INTO notification_events(event_id,merchant_id,event_type,source_reference)
  VALUES (UUID(),0,'security.mfa.changed',CONCAT('admin-',NEW.admin_id));
 END IF;
END$$
CREATE TRIGGER notification_merchant_mfa_changed AFTER UPDATE ON merchant_mfa_totp FOR EACH ROW
BEGIN
 IF NOT (NEW.enabled_flag <=> OLD.enabled_flag) THEN
  INSERT INTO notification_events(event_id,merchant_id,event_type,source_reference)
  SELECT UUID(),merchant_id,'security.mfa.changed',CONCAT('merchant-user-',NEW.merchant_admin_id)
  FROM merchant_admins WHERE id=NEW.merchant_admin_id AND merchant_id IS NOT NULL;
 END IF;
END$$
CREATE TRIGGER notification_provider_credential_changed AFTER UPDATE ON communication_provider_credentials FOR EACH ROW
BEGIN
 IF NOT (NEW.credential_value_encrypted <=> OLD.credential_value_encrypted) THEN
  INSERT INTO notification_events(event_id,merchant_id,event_type,source_reference)
  VALUES (UUID(),0,'production.configuration.changed',CONCAT('credential-',NEW.id));
 END IF;
END$$
CREATE TRIGGER notification_provider_credential_created AFTER INSERT ON communication_provider_credentials FOR EACH ROW
BEGIN
 INSERT INTO notification_events(event_id,merchant_id,event_type,source_reference)
 VALUES (UUID(),0,'production.configuration.changed',CONCAT('credential-',NEW.id));
END$$
CREATE TRIGGER notification_provider_credential_deleted AFTER DELETE ON communication_provider_credentials FOR EACH ROW
BEGIN
 INSERT INTO notification_events(event_id,merchant_id,event_type,source_reference)
 VALUES (UUID(),0,'production.configuration.changed',CONCAT('credential-',OLD.id));
END$$
CREATE TRIGGER notification_api_key_created AFTER INSERT ON developer_portal_api_keys FOR EACH ROW
BEGIN
 INSERT INTO notification_events(event_id,merchant_id,event_type,source_reference)
 SELECT UUID(),merchant_id,'api.credentials.changed',CONCAT('api-key-',NEW.id)
 FROM developer_portal_applications WHERE id=NEW.application_id AND merchant_id IS NOT NULL;
END$$
CREATE TRIGGER notification_api_key_changed AFTER UPDATE ON developer_portal_api_keys FOR EACH ROW
BEGIN
 IF NOT (NEW.status <=> OLD.status) OR NOT (NEW.public_key_pem <=> OLD.public_key_pem) THEN
  INSERT INTO notification_events(event_id,merchant_id,event_type,source_reference)
  SELECT UUID(),merchant_id,'api.credentials.changed',CONCAT('api-key-',NEW.id)
  FROM developer_portal_applications WHERE id=NEW.application_id AND merchant_id IS NOT NULL;
 END IF;
END$$
CREATE TRIGGER notification_report_completed AFTER UPDATE ON finance_report_exports FOR EACH ROW
BEGIN
 IF NEW.status='COMPLETED' AND OLD.status<>'COMPLETED' THEN
  INSERT INTO notification_events(event_id,merchant_id,event_type,source_reference)
  VALUES (UUID(),COALESCE(NEW.merchant_id,0),'report.scheduled',CONCAT('report-',NEW.id));
 END IF;
END$$
CREATE TRIGGER notification_settlement_completed AFTER UPDATE ON finance_settlement_batches FOR EACH ROW
BEGIN
 IF NEW.status='CLOSED' AND OLD.status<>'CLOSED' THEN
  INSERT INTO notification_events(event_id,merchant_id,event_type,source_reference)
  VALUES (UUID(),COALESCE(NEW.merchant_id,0),'settlement.completed',CONCAT('settlement-',NEW.id));
 END IF;
END$$
DELIMITER ;
