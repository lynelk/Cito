import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { request } from '../shared/api/httpClient';
import MtnMomoWorkspace from './MtnMomoWorkspace';
import type { PlatformCredential, ProviderLiveTest } from '../shared/api/providerTreasury';

vi.mock('../shared/api/httpClient', async importOriginal => ({ ...await importOriginal<typeof import('../shared/api/httpClient')>(), request: vi.fn() }));
let credentials: PlatformCredential[];
let history: Partial<ProviderLiveTest>[];
let client: QueryClient;
const path = '/api/v2/admin/shared-provider/credentials';
const masked = Object.fromEntries(['collection', 'disbursement'].flatMap(prefix => ['ApiUser', 'ApiKey', 'SubscriptionKey'].map(suffix => [prefix + suffix, '****'])));
const saved = (environment = 'SANDBOX'): PlatformCredential => ({ id: 1, revision: 7, channelCode: 'mtn_momo', environment, countryCode: 'UG', currencyCode: environment === 'SANDBOX' ? 'EUR' : 'UGX', status: 'ACTIVE', lastTestStatus: 'CONNECTIVITY_VERIFIED', updatedBy: 'maker@example.com', credentials: { ...masked, callbackHost: 'pay.example.com', callbackUrl: 'https://pay.example.com/api/v2/provider-callbacks/mtn' } });
const result = (status = 'FAILED'): Partial<ProviderLiveTest> => ({ id: 4, testReference: 'LIVE-123', idempotencyKey: 'original-test-key', merchantId: 42, merchantName: 'Test shop', merchantNumber: 'M42', channelCode: 'mtn_momo', environment: 'SANDBOX', countryCode: 'UG', currencyCode: 'EUR', operation: 'COLLECT', amount: 1, partyMask: '467****450', status, resultMessage: status === 'FAILED' ? 'Provider rejected this payment' : 'Awaiting approval', requestedBy: 'maker@example.com', events: [] });
beforeEach(() => {
  credentials = []; history = [];
  localStorage.setItem('user', JSON.stringify({ email: 'checker@example.com' }));
  client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  vi.mocked(request).mockReset();
  vi.mocked(request).mockImplementation(async (url, options) => {
    if (url === path && !options) return credentials as never;
    if (url.endsWith('/merchants')) return [{ id: 42, name: 'Test shop', merchantNumber: 'M42', status: 'ACTIVE' }] as never;
    if (url.endsWith('/entitlements') && !options) return ['COLLECT', 'PAYOUT'].map(operation => ({ ...saved(credentials[0]?.environment), id: operation === 'COLLECT' ? 10 : 11, merchantId: 42, operation, status: 'ACTIVE' })) as never;
    if (url.endsWith('/live-tests') && !options) return history as never;
    return [] as never;
  });
});
afterEach(() => { cleanup(); client.clear(); localStorage.clear(); });
const open = (environment = 'SANDBOX') => render(<QueryClientProvider client={client}><MemoryRouter initialEntries={[`/bo/admin/mtn-momo?environment=${environment}`]}><MtnMomoWorkspace /></MemoryRouter></QueryClientProvider>);
const change = (name: string, value: string) => fireEvent.change(screen.getByLabelText(name), { target: { value } });
async function openTests(operation = 'COLLECT') {
  await screen.findByText('Connection status');
  fireEvent.click(screen.getByRole('tab', { name: '3. Payment tests' }));
  await screen.findByRole('option', { name: 'Test shop · M42' });
  change('Merchant', '42'); change('Payment operation', operation);
}

describe('MTN configuration and payment journey', () => {
  it('preserves production scope and clears unsaved secrets when changing environment', async () => {
    open('PRODUCTION');
    await screen.findByRole('button', { name: 'Save credentials' });
    expect(screen.getByText('https://proxy.momoapi.mtn.com')).toBeInTheDocument();
    change('Collection API key', 'unsaved-secret');
    change('MTN environment', 'SANDBOX');
    expect(await screen.findByText('https://sandbox.momodeveloper.mtn.com')).toBeInTheDocument();
    expect(screen.getByLabelText('Collection API key')).toHaveValue('');
    expect(screen.getByText('UG / EUR · sandbox')).toBeInTheDocument();
  });

  it('keeps the edit revision and omits blank secrets instead of sending masks after a concurrent refresh', async () => {
    credentials = [saved()]; open();
    fireEvent.click(await screen.findByRole('button', { name: 'Edit credentials' }));
    expect(screen.getByLabelText('Collection API key')).toHaveValue('');
    credentials = [{ ...saved(), revision: 8 }];
    client.setQueryData(['shared-provider', 'credentials'], credentials);
    change('Registered callback host', 'new.example.com');
    change('Callback address', 'https://new.example.com/api/v2/provider-callbacks/mtn');
    fireEvent.click(screen.getByRole('button', { name: 'Save credentials' }));
    await waitFor(() => expect(request).toHaveBeenCalledWith(path, expect.objectContaining({ method: 'POST' })));
    const call = vi.mocked(request).mock.calls.find(([url, options]) => url === path && options?.method === 'POST')!;
    const body = JSON.parse(String(call[1]?.body));
    expect(body.revision).toBe(7);
    expect(body.credentials.collectionApiKey).toBeUndefined();
    expect(body.credentials.callbackHost).toBe('new.example.com');
    expect(JSON.stringify(body)).not.toContain('****');
  });

  it('shows each product verification failure and keeps approval disabled', async () => {
    credentials = [{ ...saved(), status: 'CONFIGURED', lastTestStatus: undefined }];
    const initial = vi.mocked(request).getMockImplementation()!;
    vi.mocked(request).mockImplementation(async (url, options) => {
      if (url.endsWith('/verify')) {
        credentials = [{ ...credentials[0], lastTestStatus: 'CONNECTIVITY_FAILED' }];
        return { ...credentials[0], verificationChecks: [{ operation: 'COLLECT', status: 'VERIFIED', message: 'Collection authenticated' }, { operation: 'PAYOUT', status: 'FAILED', message: 'Check payout subscription key (HTTP 401)' }] } as never;
      }
      return initial(url, options);
    });
    open(); fireEvent.click(await screen.findByRole('button', { name: 'Verify both products' }));
    expect(await screen.findByText('Check payout subscription key (HTTP 401)')).toBeInTheDocument();
    expect(screen.getByText('Collection authenticated')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Approve connection' })).toBeDisabled();
    expect(vi.mocked(request).mock.calls.some(([url]) => url.endsWith('/live-tests'))).toBe(false);
  });

  it('submits sandbox collections in EUR and renders a returned failure truthfully', async () => {
    credentials = [saved()]; const initial = vi.mocked(request).getMockImplementation()!;
    vi.mocked(request).mockImplementation(async (url, options) => {
      if (url.endsWith('/live-tests') && options?.method === 'POST') { history = [result()]; return history[0] as never; }
      return initial(url, options);
    });
    open(); await openTests(); change('Amount (EUR)', '1'); change('Payer phone number', '46733123450');
    fireEvent.click(screen.getByRole('button', { name: 'Submit collection test' }));
    expect((await screen.findAllByText('Provider rejected this payment')).length).toBeGreaterThan(0);
    const call = vi.mocked(request).mock.calls.find(([url, options]) => url.endsWith('/live-tests') && options?.method === 'POST')!;
    expect(JSON.parse(String(call[1]?.body))).toMatchObject({ environment: 'SANDBOX', currencyCode: 'EUR', operation: 'COLLECT', amount: '1', party: '46733123450', merchantId: 42 });
    expect(screen.queryByText(/Collection submitted/)).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Start a new test' })).toBeInTheDocument();
  });

  it('retains identical financial details and the request key across an ambiguous retry', async () => {
    credentials = [saved()]; const initial = vi.mocked(request).getMockImplementation()!;
    vi.mocked(request).mockImplementation(async (url, options) => {
      if (url.endsWith('/live-tests') && options?.method === 'POST') throw new Error('Connection lost');
      return initial(url, options);
    });
    open(); await openTests('PAYOUT'); change('Amount (EUR)', '1'); change('Recipient phone number', '46733123450');
    fireEvent.click(screen.getByRole('button', { name: 'Request payout test' }));
    await screen.findByText(/Connection lost/);
    expect(screen.getByLabelText('Amount (EUR)')).toBeDisabled();
    fireEvent.click(screen.getByRole('tab', { name: '1. Connection' }));
    fireEvent.click(screen.getByRole('tab', { name: '3. Payment tests' }));
    expect(screen.getByLabelText('Amount (EUR)')).toHaveValue(1);
    expect(screen.getByLabelText('Amount (EUR)')).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: 'Retry the same request safely' }));
    await waitFor(() => expect(vi.mocked(request).mock.calls.filter(([url, options]) => url.endsWith('/live-tests') && options?.method === 'POST')).toHaveLength(2));
    const calls = vi.mocked(request).mock.calls.filter(([url, options]) => url.endsWith('/live-tests') && options?.method === 'POST');
    expect(calls[0][1]?.body).toBe(calls[1][1]?.body);
  });

  it('requires production confirmation and MFA while sandbox payout approval needs an independent operator only', async () => {
    credentials = [saved()]; history = [{ ...result('PENDING_APPROVAL'), operation: 'PAYOUT' }];
    open(); await openTests('PAYOUT');
    const approve = await screen.findByRole('button', { name: 'Approve payout test' });
    expect(screen.queryByLabelText(/Approval MFA/)).not.toBeInTheDocument();
    fireEvent.click(approve);
    await waitFor(() => expect(request).toHaveBeenCalledWith('/api/v2/admin/provider-treasury/live-tests/4/approve', expect.objectContaining({ body: JSON.stringify({ mfaCode: '', confirmProduction: false }) })));
    credentials = [saved('PRODUCTION')]; history = [{ ...result('PENDING_APPROVAL'), environment: 'PRODUCTION', currencyCode: 'UGX', operation: 'PAYOUT' }];
    client.setQueryData(['shared-provider', 'credentials'], credentials); client.setQueryData(['provider-treasury', 'live-tests'], history);
    change('MTN environment', 'PRODUCTION'); await openTests('PAYOUT');
    expect(screen.getByLabelText('Your MFA code')).toBeRequired();
    expect(screen.getByLabelText('Approval MFA code for LIVE-123')).toBeRequired();
    expect(screen.getByLabelText(/I approve this real payout/)).toBeRequired();
  });

  it('prevents the credential maker and payout requester from approving their own work', async () => {
    localStorage.setItem('user', JSON.stringify({ email: 'maker@example.com' }));
    credentials = [{ ...saved(), status: 'CONFIGURED' }]; history = [{ ...result('PENDING_APPROVAL'), operation: 'PAYOUT' }];
    open(); expect(await screen.findByRole('button', { name: 'Approve connection' })).toBeDisabled();
    await openTests(); expect(await screen.findByText('A different operator must approve your payout request.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Approve payout test' })).not.toBeInTheDocument();
  });

  it('shows a configuration load error without presenting an empty credential editor', async () => {
    vi.mocked(request).mockRejectedValue(new Error('Credential access denied')); open();
    expect(await screen.findByRole('alert')).toHaveTextContent('Credential access denied');
    expect(screen.queryByRole('button', { name: 'Save credentials' })).not.toBeInTheDocument();
  });
});
