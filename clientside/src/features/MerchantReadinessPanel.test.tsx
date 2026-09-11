import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import MerchantReadinessPanel from './MerchantReadinessPanel';
import { request } from '../shared/api/httpClient';
vi.mock('../shared/api/httpClient', () => ({ request: vi.fn() }));
afterEach(() => { cleanup(); vi.resetAllMocks(); });

describe('merchant readiness evidence', () => {
  it('shows the server assessment without equating entitlement with provider activation', async () => {
    vi.mocked(request).mockResolvedValue({ readinessAssessment: { state: 'CERTIFICATION_PENDING', nextAction: 'Complete provider certification.', scope: 'MERCHANT_ONBOARDING' } });
    render(<MerchantReadinessPanel merchantId={42} />);
    expect(await screen.findByText('Certification pending')).toBeTruthy();
    expect(screen.getByText(/do not certify or enable every provider/)).toBeTruthy();
    expect(request).toHaveBeenCalledWith('/api/v2/merchants/42/onboarding');
  });
  it('does not invent readiness after a failed request', async () => {
    vi.mocked(request).mockRejectedValue(new Error('unavailable'));
    render(<MerchantReadinessPanel merchantId={42} />);
    expect(await screen.findByText(/No ready status is assumed/)).toBeTruthy();
    expect(screen.queryByText('Production enabled')).toBeNull();
  });
  it('does not request another tenant when scope is absent', async () => {
    render(<MerchantReadinessPanel merchantId={null} />);
    await waitFor(() => expect(screen.getByText(/Select a merchant account/)).toBeTruthy());
    expect(request).not.toHaveBeenCalled();
  });
});
