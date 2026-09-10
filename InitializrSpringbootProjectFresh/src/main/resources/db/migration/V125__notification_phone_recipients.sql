-- Extend existing alert memberships without granting administrator access.
ALTER TABLE notification_admin_recipients
 ADD UNIQUE KEY uq_notification_group_admin (group_code,admin_id);
ALTER TABLE notification_admin_recipients
 DROP PRIMARY KEY,
 ADD COLUMN id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY FIRST,
 MODIFY COLUMN admin_id BIGINT UNSIGNED NULL,
 ADD COLUMN phone_e164 VARCHAR(16) NULL,
 ADD UNIQUE KEY uq_notification_group_phone (group_code,phone_e164),
 ADD CONSTRAINT chk_notification_recipient_identity CHECK (
   (admin_id IS NOT NULL AND phone_e164 IS NULL) OR
   (admin_id IS NULL AND phone_e164 IS NOT NULL AND phone_e164 REGEXP '^[+][1-9][0-9]{7,14}$')
 );
