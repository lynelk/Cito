import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import PlatformEvidencePanel from './PlatformEvidencePanel';
import { request } from '../../shared/api/httpClient';

vi.mock('../../shared/api/httpClient', () => ({ request: vi.fn() }));
afterEach(() => { cleanup(); vi.resetAllMocks(); });

function renderPanel() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={client}><PlatformEvidencePanel /></QueryClientProvider>);
}

function evidence() {
  return {
    evidenceBasis: 'DURABLE_RECORDS_ONLY', targetsReportedAsActuals: false,
    commercial: { founding20Candidates: 4, founding20Active: 2, founding20Live: 1, activePackageAssignments: 2, embeddedProgrammesLive: 0 },
    developer: { activeProjects: 3, productionEligibleProjects: 1, apiRequests7d: 15, activeApiMerchants30d: 2, successfulApiRequests7d: 12 },
    adoption: { productionMerchants30d: 2, productionCommands30d: 8, liveOnboardingWorkflows: 1, approvedGoLiveChecklists: 1 },
    providerDefinitions: [], providerCertification: [],
  };
}

describe('commercial and adoption evidence', () => {
  it('shows durable metrics without turning records into certification or targets', async () => {
    vi.mocked(request).mockResolvedValue(evidence());
    renderPanel();
    expect(await screen.findByText('Commercial & adoption evidence')).toBeTruthy();
    expect(screen.getByText('API requests · 7d')).toBeTruthy();
    expect(screen.getAllByText('Measured')).toHaveLength(3);
    expect(screen.getByText(/targets-as-actuals=false/)).toBeTruthy();
    expect(screen.getByText(/does not establish provider readiness/)).toBeTruthy();
    expect(request).toHaveBeenCalledWith('/api/v2/admin/platform-evidence/scorecard');
  });

  it('does not turn a failed request into zero or success', async () => {
    vi.mocked(request).mockRejectedValue(new Error('unavailable'));
    renderPanel();
    expect(await screen.findByText(/No zero or success status is assumed/)).toBeTruthy();
  });

  it.each([{}, { ...evidence(), targetsReportedAsActuals: undefined }, { ...evidence(), targetsReportedAsActuals: true }])(
    'rejects a missing or contradictory evidence-basis contract', async (payload) => {
      vi.mocked(request).mockResolvedValue(payload);
      renderPanel();
      expect(await screen.findByRole('alert')).toBeTruthy();
      expect(screen.queryByText(/targets-as-actuals=false/)).toBeNull();
      expect(screen.queryByText('Measured')).toBeNull();
    },
  );

  it('keeps partial groups and missing provider evidence explicitly unavailable', async () => {
    vi.mocked(request).mockResolvedValue({ ...evidence(), commercial: { founding20Candidates: 4 }, providerCertification: undefined });
    renderPanel();
    const group = await screen.findByRole('article', { name: 'Commercial' });
    expect(within(group).getByText('Partial evidence')).toBeTruthy();
    expect(within(group).getAllByText('Unavailable')).toHaveLength(4);
    const provider = screen.getByRole('article', { name: 'Provider certification evidence' });
    expect(within(provider).getByText('Unavailable')).toBeTruthy();
    expect(within(provider).queryByText('0')).toBeNull();
  });

  it('does not crash or show malformed counters as measured', async () => {
    vi.mocked(request).mockResolvedValue({ ...evidence(), commercial: { ...evidence().commercial, founding20Candidates: null, founding20Active: -1, founding20Live: '1' } });
    renderPanel();
    const group = await screen.findByRole('article', { name: 'Commercial' });
    expect(within(group).getByText('Partial evidence')).toBeTruthy();
    expect(within(group).getAllByText('Unavailable')).toHaveLength(3);
  });

  it('rejects impossible or wildcard provider coverage instead of inferring certification', async () => {
    vi.mocked(request).mockResolvedValue({ ...evidence(), providerCertification: [{ providerCode: '*', channelCode: '*', requiredScenarios: 1, approvedScenarios: 2 }] });
    renderPanel();
    const provider = await screen.findByRole('article', { name: 'Provider certification evidence' });
    expect(within(provider).getByText('Unavailable')).toBeTruthy();
    expect(within(provider).queryByText(/2 \/ 1 reviewed/)).toBeNull();
  });

  it('shows valid recorded zeros and provider-specific reviewed counts without green readiness', async () => {
    vi.mocked(request).mockResolvedValue({ ...evidence(), commercial: Object.fromEntries(Object.keys(evidence().commercial).map((key) => [key, 0])), providerCertification: [{ providerCode: 'TEST_A', channelCode: 'SMS', requiredScenarios: 3, approvedScenarios: 1 }] });
    renderPanel();
    const group = await screen.findByRole('article', { name: 'Commercial' });
    expect(within(group).getByText('Measured')).toBeTruthy();
    expect(within(group).getAllByText('0')).toHaveLength(5);
    expect(screen.getByText('1 / 3 reviewed')).toBeTruthy();
    expect(screen.queryByText('Certified')).toBeNull();
  });
});
