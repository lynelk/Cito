import { beforeEach, expect, it, vi } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import MerchantModulePaymentChannelsV2 from './MerchantModulePaymentChannelsV2';
import { apiFetch } from '../../../shared/api/httpClient';
vi.mock('../../../shared/api/httpClient', () => ({ apiFetch: vi.fn() }));
const credentials = { baseUrl: 'https://openapiuat.airtel.africa', clientId: '****', clientSecret: '****', country: 'UG', currency: 'UGX', publicKey: 'PUBLIC KEY' };
beforeEach(() => {
  vi.mocked(apiFetch).mockReset();
  vi.mocked(apiFetch).mockImplementation(async (url) => ({ ok: true, json: async () => String(url).endsWith('/environment') ? { environment: 'SANDBOX' } : String(url).endsWith('/channels') ? [{ channelCode: 'airtel_open_api', displayName: 'Airtel', countryCode: 'UG', currencyCode: 'UGX', environments: { SANDBOX: { status: 'CONFIGURED', credentials, revision: 7 } } }] : {} }));
});
it('keeps stored secrets out of editable inputs and sends the current revision on save', async () => {
  render(<MerchantModulePaymentChannelsV2 />);
  const secret = await screen.findByLabelText('Client secret (stored; leave blank to keep)');
  expect(secret).toHaveValue('');
  expect(screen.getByLabelText('Airtel country code')).toHaveValue('UG');
  expect(screen.getByLabelText('Airtel RSA public key').tagName).toBe('TEXTAREA');
  fireEvent.click(screen.getByRole('button', { name: 'Save' }));
  await waitFor(() => expect(apiFetch).toHaveBeenCalledWith(expect.stringContaining('/channels/save'), expect.objectContaining({ body: expect.any(String) })));
  const call = vi.mocked(apiFetch).mock.calls.find(([url]) => String(url).includes('/channels/save'));
  const body = JSON.parse(call[1].body);
  expect(body.revision).toBe(7);
  expect(body.credentials.clientSecret).toBe('');
  expect(JSON.stringify(body)).not.toContain('****');
});
