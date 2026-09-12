/** Environment profiles for the canonical provider credential editor. */
export function mtnEnvironment(value?: string | null): 'SANDBOX' | 'PRODUCTION' {
  return value?.toUpperCase() === 'PRODUCTION' ? 'PRODUCTION' : 'SANDBOX';
}

export function mtnWorkspacePath(environment?: string | null): string {
  return `/bo/admin/mtn-momo?environment=${mtnEnvironment(environment)}`;
}

export function providerTreasuryPath(search: string, hash = ''): string {
  const query = new URLSearchParams(search);
  return `${query.get('channel') === 'mtn_momo' ? '/bo/admin/mtn-momo' : '/bo/admin/provider-treasury'}${search}${hash}`;
}

export function mtnProfile(environment: string) {
  if (!['SANDBOX', 'PRODUCTION'].includes(environment)) throw new Error('Unsupported provider environment');
  const sandbox = environment === 'SANDBOX';
  return {
    baseUrl: sandbox ? 'https://sandbox.momodeveloper.mtn.com' : 'https://proxy.momoapi.mtn.com',
    targetEnvironment: sandbox ? 'sandbox' : 'mtnuganda',
    baseCurrency: sandbox ? 'EUR' : 'UGX',
    currencyCode: sandbox ? 'EUR' : 'UGX',
    countryCode: 'UG',
  };
}

const privateOrScopedFields = [
  'collectionApiUser', 'collectionApiKey', 'collectionSubscriptionKey', 'collectionSecondarySubscriptionKey',
  'disbursementApiUser', 'disbursementApiKey', 'disbursementSubscriptionKey', 'disbursementSecondarySubscriptionKey',
  'airtelClientId', 'airtelClientSecret', 'airtelApiPin', 'airtelPublicKey',
  'authHeaderName', 'authHeaderValue', 'tokenAlias', 'callbackHost', 'callbackUrl', 'collectUrl', 'payoutUrl',
];

export function changeProviderScope<T extends Record<string, string>>(current: T, channelCode: string, environment: string): T {
  if (!['SANDBOX', 'PRODUCTION'].includes(environment)) throw new Error('Unsupported provider environment');
  const next: Record<string, string> = { ...current, channelCode, environment };
  if (channelCode !== current.channelCode || environment !== current.environment) {
    for (const key of privateOrScopedFields) next[key] = '';
  }
  if (channelCode === 'mtn_momo') Object.assign(next, mtnProfile(environment));
  else if (channelCode === 'airtel_open_api') Object.assign(next, {
    baseUrl: environment === 'SANDBOX' ? 'https://openapiuat.airtel.africa' : 'https://openapi.airtel.africa',
    countryCode: 'UG', currencyCode: 'UGX', airtelCountry: 'UG', airtelCurrency: 'UGX',
    targetEnvironment: '', baseCurrency: 'UGX',
  });
  else Object.assign(next, { baseUrl: '', targetEnvironment: '', baseCurrency: 'UGX', currencyCode: 'UGX', countryCode: 'UG' });
  return next as T;
}
