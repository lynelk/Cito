-- Close merchant SMS workspace gaps and make delivery-receipt correlation durable.

ALTER TABLE communication_message_deliveries
  ADD COLUMN provider_message_id VARCHAR(160) NULL AFTER provider_code,
  ADD COLUMN delivered_at DATETIME NULL AFTER billed_flag,
  ADD KEY idx_cmd_provider_message (provider_code, provider_message_id);

ALTER TABLE communication_campaign_items
  ADD COLUMN message_reference VARCHAR(80) NULL AFTER recipient,
  ADD KEY idx_citem_message_reference (message_reference);

ALTER TABLE communication_sender_identities
  ADD COLUMN use_case TEXT NULL AFTER country_code,
  ADD COLUMN supporting_document_ref VARCHAR(500) NULL AFTER use_case;