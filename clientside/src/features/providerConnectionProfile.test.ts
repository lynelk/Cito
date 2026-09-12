import { describe, expect, it } from 'vitest';
import { changeProviderScope, mtnProfile, mtnWorkspacePath, providerTreasuryPath } from './providerConnectionProfile';

describe('MTN provider configuration', () => {
  it('retains environment and fragment when upgrading legacy treasury links', () => {
    expect(providerTreasuryPath('?channel=mtn_momo&environment=PRODUCTION', '#platform-provider-credentials'))
      .toBe('/bo/admin/mtn-momo?channel=mtn_momo&environment=PRODUCTION#platform-provider-credentials');
    expect(providerTreasuryPath('?channel=airtel_open_api', '#platform-provider-credentials'))
      .toBe('/bo/admin/provider-treasury?channel=airtel_open_api#platform-provider-credentials');
    expect(mtnWorkspacePath('production')).toBe('/bo/admin/mtn-momo?environment=PRODUCTION');
    expect(mtnWorkspacePath('sandbox')).toBe('/bo/admin/mtn-momo?environment=SANDBOX');
  });
  it('uses the provider API origin, target and currency for each environment', () => {
    expect(mtnProfile('SANDBOX')).toEqual({ baseUrl: 'https://sandbox.momodeveloper.mtn.com', targetEnvironment: 'sandbox', baseCurrency: 'EUR', currencyCode: 'EUR', countryCode: 'UG' });
    expect(mtnProfile('PRODUCTION')).toEqual({ baseUrl: 'https://proxy.momoapi.mtn.com', targetEnvironment: 'mtnuganda', baseCurrency: 'UGX', currencyCode: 'UGX', countryCode: 'UG' });
    expect(() => mtnProfile('production')).toThrow();
  });
  it('clears unsaved credentials and callbacks across environment and provider scopes', () => {
    const current = { channelCode: 'mtn_momo', environment: 'SANDBOX', collectionApiKey: 'test-only', disbursementApiUser: 'test-user', collectionSecondarySubscriptionKey: 'test-secondary', callbackUrl: 'https://example.invalid/callback' };
    const next = changeProviderScope(current, 'mtn_momo', 'PRODUCTION');
    expect(next.collectionApiKey).toBe('');
    expect(next.disbursementApiUser).toBe('');
    expect(next.collectionSecondarySubscriptionKey).toBe('');
    expect(next.callbackUrl).toBe('');
    expect(current.collectionApiKey).toBe('test-only');
    expect(changeProviderScope(current, 'airtel_open_api', 'SANDBOX').collectionApiKey).toBe('');
  });
  it('preserves unsaved material when scope has not changed', () => {
    const current = { channelCode: 'mtn_momo', environment: 'SANDBOX', collectionApiKey: 'test-only' };
    expect(changeProviderScope(current, 'mtn_momo', 'SANDBOX').collectionApiKey).toBe('test-only');
  });
});
