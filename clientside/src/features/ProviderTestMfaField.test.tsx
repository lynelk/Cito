import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { request } from '../shared/api/httpClient';
import ProviderTestMfaField from './ProviderTestMfaField';

vi.mock('../shared/api/httpClient', async importOriginal => ({ ...await importOriginal<typeof import('../shared/api/httpClient')>(), request: vi.fn() }));
let client: QueryClient;
beforeEach(() => {
  localStorage.setItem('user', JSON.stringify({ email: 'operator@example.com' }));
  client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  vi.mocked(request).mockReset();
});
afterEach(() => { cleanup(); client.clear(); localStorage.clear(); });
const open = (operation = 'COLLECT', value = '', onChange = vi.fn()) => render(
  <MemoryRouter><QueryClientProvider client={client}><ProviderTestMfaField operation={operation} label="Your MFA code" value={value} onChange={onChange} /></QueryClientProvider></MemoryRouter>,
);
const suspend = () => vi.mocked(request).mockResolvedValue({ mtnCollectionMfaRequired: false, suspendedUntil: new Date(Date.now() + 600_000).toISOString() } as never);

describe('temporary collection-test MFA presentation', () => {
  it('removes the required input only when the server grants a future exception', async () => {
    suspend(); open();
    expect(await screen.findByRole('status')).toHaveTextContent('MFA is temporarily suspended');
    expect(screen.queryByLabelText('Your MFA code')).not.toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('merchant approval and transaction limits still apply');
  });

  it('clears a previously entered code when the exception is active', async () => {
    suspend(); const changed = vi.fn(); open('COLLECT', 'old-code', changed);
    await waitFor(() => expect(changed).toHaveBeenCalledWith(''));
  });

  it('keeps payout MFA required even while collection MFA is suspended', () => {
    suspend(); open('PAYOUT');
    expect(screen.getByLabelText('Your MFA code')).toBeRequired();
    expect(request).not.toHaveBeenCalled();
  });

  it('keeps MFA required when policy cannot be loaded', async () => {
    vi.mocked(request).mockRejectedValue(new Error('Policy unavailable')); open();
    await waitFor(() => expect(request).toHaveBeenCalled());
    expect(screen.getByLabelText('Your MFA code')).toBeRequired();
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });

  it.each(['', 'invalid-date', '2000-01-01T00:00:00Z'])('rejects an invalid or expired exception: %s', async suspendedUntil => {
    vi.mocked(request).mockResolvedValue({ mtnCollectionMfaRequired: false, suspendedUntil } as never); open();
    await waitFor(() => expect(request).toHaveBeenCalled());
    expect(screen.getByLabelText('Your MFA code')).toBeRequired();
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });

  it('keeps MFA required for the normal server policy', async () => {
    vi.mocked(request).mockResolvedValue({ mtnCollectionMfaRequired: true, suspendedUntil: '' } as never); open();
    await waitFor(() => expect(request).toHaveBeenCalled());
    expect(screen.getByLabelText('Your MFA code')).toBeRequired();
  });
});
