-- Close merchant SMS workspace gaps without duplicating delivery correlation fields.
-- provider_message_id, delivered_at and idx_cmd_provider_message are already introduced by V77.

ALTER TABLE communication_campaign_items
  ADD COLUMN message_reference VARCHAR(80) NULL AFTER recipient,
  ADD KEY idx_citem_message_reference (message_reference);

ALTER TABLE communication_sender_identities
  ADD COLUMN use_case TEXT NULL AFTER country_code,
  ADD COLUMN supporting_document_ref VARCHAR(500) NULL AFTER use_case;
