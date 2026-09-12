-- Normalize the two non-secret MTN MoMo provider origins to the origins enforced by
-- MtnMomoCredentialSchema / ProviderEndpointPolicy. Credentials, subscription keys,
-- environment selection, currency and transaction data are deliberately untouched.

UPDATE settings
SET setting_value = 'https://proxy.momoapi.mtn.com'
WHERE name = 'gw_mtn_api_url'
  AND COALESCE(setting_value, '') <> 'https://proxy.momoapi.mtn.com';

UPDATE settings
SET setting_value = 'https://sandbox.momodeveloper.mtn.com'
WHERE name = 'gw_mtn_api_url_sandbox'
  AND COALESCE(setting_value, '') <> 'https://sandbox.momodeveloper.mtn.com';
