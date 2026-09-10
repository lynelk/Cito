-- Add SMSMobilo as a dedicated SMS provider without enabling live traffic before credentials,
-- endpoint mapping, rates and operational approval are configured.

INSERT INTO communication_providers
  (provider_code, provider_name, channel, adapter_class, base_url, credentials_ref, enabled_flag)
VALUES
  ('SMSMOBILO_SMS', 'SMSMobilo SMS', 'SMS',
   'net.citotech.cito.communication.sms.SmsMobiloSmsGatewayAdapter',
   'https://smsmobilo.com/api/v1',
   'smsmobilo_sms_api_key/smsmobilo_sms_api_url/smsmobilo_sms_send_path/smsmobilo_sms_auth_mode',
   'NO')
ON DUPLICATE KEY UPDATE
  provider_name = VALUES(provider_name),
  adapter_class = VALUES(adapter_class),
  base_url = VALUES(base_url),
  credentials_ref = VALUES(credentials_ref);

-- The published API reference exposes delivery-status querying. Inbound SMS and webhook delivery
-- receipts are not assumed here; those capabilities must only be enabled after provider-contract
-- verification and callback certification.
INSERT INTO communication_provider_capabilities
  (provider_code, channel, capability, country_code, supports_templates,
   supports_delivery_receipts, supports_inbound, supports_status_query, enabled_flag)
SELECT 'SMSMOBILO_SMS', 'SMS', 'SEND', NULL, 'N', 'N', 'N', 'Y', 'Y'
WHERE NOT EXISTS (
  SELECT 1 FROM communication_provider_capabilities
  WHERE provider_code='SMSMOBILO_SMS' AND channel='SMS' AND capability='SEND' AND country_code IS NULL
);

-- Give the provider a deterministic platform priority for smart-routing once it is enabled.
INSERT INTO communication_routing_rules
  (channel, merchant_id, priority, provider_code, enabled_flag)
SELECT 'SMS', NULL, 400, 'SMSMOBILO_SMS', 'YES'
WHERE NOT EXISTS (
  SELECT 1 FROM communication_routing_rules
  WHERE channel='SMS' AND merchant_id IS NULL AND provider_code='SMSMOBILO_SMS'
);
