import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import MerchantCredentialReviews from './MerchantCredentialReviews';
import { request } from '../shared/api/httpClient';
vi.mock('../shared/api/httpClient', () => ({ request: vi.fn() }));
const row = { id: 1, merchantNumber: 'M10', channelCode: 'mtn_momo', environment: 'PRODUCTION', revision: 7, status: 'SUBMITTED_FOR_APPROVAL', lastTestStatus: 'CONNECTIVITY_VERIFIED', requestedBy: 'maker@example.com' };
beforeEach(() => { vi.mocked(request).mockReset(); vi.mocked(request).mockResolvedValue([row]); });
describe('merchant credential review', () => {
  it('requires a reason and submits the displayed revision without credential values', async () => {
    render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><MerchantCredentialReviews /></QueryClientProvider>);
    const approve = await screen.findByRole('button', { name: 'Approve' });
    expect(approve).toBeDisabled();
    fireEvent.change(screen.getByLabelText('Decision reason'), { target: { value: 'Connection and scope reviewed' } });
    fireEvent.click(approve);
    await waitFor(() => expect(request).toHaveBeenCalledWith('/api/v2/admin/shared-provider/merchant-credentials/1/decision', expect.objectContaining({ method: 'POST', body: JSON.stringify({ revision: 7, decision: 'ACTIVE', reason: 'Connection and scope reviewed' }) })));
  });
  it('shows a failed load and does not present approval actions', async () => {
    vi.mocked(request).mockRejectedValue(new Error('Review access denied'));
    render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><MerchantCredentialReviews /></QueryClientProvider>);
    expect(await screen.findByRole('alert')).toHaveTextContent('Review access denied');
    expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument();
  });
});
