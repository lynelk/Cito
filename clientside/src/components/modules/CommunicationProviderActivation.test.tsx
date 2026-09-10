import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import CommunicationProviderActivation from './CommunicationProviderActivation';
const { request } = vi.hoisted(() => ({ request: vi.fn() }));
vi.mock('../../shared/api/httpClient', () => ({ request }));
beforeEach(() => vi.clearAllMocks());
it('activates through the admin API and refreshes provider state', async () => {
  request.mockResolvedValue({ enabledFlag: 'YES' });
  const onSaved = vi.fn().mockResolvedValue(undefined);
  render(<CommunicationProviderActivation providerCode="SMSMOBILO_SMS" providerName="SMSMobilo SMS" enabled={false} onSaved={onSaved} />);
  fireEvent.click(screen.getByRole('button', { name: 'Enable SMSMobilo SMS' }));
  await waitFor(() => expect(onSaved).toHaveBeenCalledOnce());
  expect(request).toHaveBeenCalledWith('/api/v2/admin/communication/routing/providers/SMSMOBILO_SMS/activation', { method: 'POST', body: '{"enabled":true}' });
});
it('shows activation errors without claiming a successful save', async () => {
  request.mockRejectedValue(new Error('Provider send configuration is incomplete'));
  const onSaved = vi.fn();
  render(<CommunicationProviderActivation providerCode="SMSMOBILO_SMS" providerName="SMSMobilo SMS" enabled={false} onSaved={onSaved} />);
  fireEvent.click(screen.getByRole('button', { name: 'Enable SMSMobilo SMS' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('Provider send configuration is incomplete');
  expect(onSaved).not.toHaveBeenCalled();
});
