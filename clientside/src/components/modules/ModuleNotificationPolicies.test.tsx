import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import ModuleNotificationPolicies from './ModuleNotificationPolicies';

const { request } = vi.hoisted(() => ({ request: vi.fn() }));
vi.mock('../../shared/api/httpClient', () => ({ request }));

it('saves a phone recipient without an admin ID and can deactivate it', async () => {
  let active = false;
  request.mockImplementation(async (path: string, options?: { body: string }) => {
    if (options) { active = JSON.parse(options.body).active; return { saved: true }; }
    if (path.endsWith('/groups')) return {
      groups: [{ group_code: 'PLATFORM_OPERATIONS', display_name: 'Platform Operations' }],
      recipients: active ? [{ group_code: 'PLATFORM_OPERATIONS', admin_id: null, phone_e164: '+256700000002', phone: '+256700000002', name: 'Phone recipient', escalation_level: 0, active_flag: 'Y' }] : [],
    };
    return [];
  });
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><ModuleNotificationPolicies /></QueryClientProvider>);
  fireEvent.click(screen.getByText('Platform notifications and alerts'));
  await screen.findByRole('option', { name: 'Platform Operations' });
  fireEvent.change(screen.getByLabelText('International phone number'), { target: { value: '256700000002' } });
  fireEvent.click(screen.getByRole('button', { name: 'Save recipient' }));
  await waitFor(() => expect(request).toHaveBeenCalledWith(expect.stringContaining('/groups/PLATFORM_OPERATIONS/recipients'), { method: 'POST', body: JSON.stringify({ phone: '256700000002', escalationLevel: 0, active: true }) }));
  fireEvent.click(await screen.findByRole('button', { name: 'Deactivate' }));
  await waitFor(() => expect(request).toHaveBeenCalledWith(expect.stringContaining('/groups/PLATFORM_OPERATIONS/recipients'), { method: 'POST', body: JSON.stringify({ phone: '+256700000002', escalationLevel: 0, active: false }) }));
});
