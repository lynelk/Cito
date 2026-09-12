import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import PlatformEvidencePanel from './PlatformEvidencePanel';
import { request } from '../../shared/api/httpClient';

vi.mock('../../shared/api/httpClient', () => ({ request: vi.fn() }));
afterEach(() => { cleanup(); vi.resetAllMocks(); });

function renderPanel() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={client}><PlatformEvidencePanel /></QueryClientProvider>);
}

describe('commercial and adoption evidence', () => {
  it('shows durable metrics and never labels targets as actuals', async () => {
    vi.mocked(request).mockResolvedValue({
      evidenceBasis: 'DURABLE_RECORDS_ONLY', targetsReportedAsActuals: false,
      commercial: { founding20Candidates: 4, founding20Active: 2, founding20Live: 1, activePackageAssignments: 2, embeddedProgrammesLive: 0 },
      developer: { activeProjects: 3, productionEligibleProjects: 1, apiRequests7d: 15, activeApiMerchants30d: 2, successfulApiRequests7d: 12 },
      adoption: { productionMerchants30d: 2, productionCommands30d: 8, liveOnboardingWorkflows: 1, approvedGoLiveChecklists: 1 },
      providerCertification: [],
    });
    renderPanel();
    expect(await screen.findByText('Commercial & adoption evidence')).toBeTruthy();
    expect(screen.getByText('API requests · 7d')).toBeTruthy();
    expect(screen.getByText(/targets-as-actuals=false/)).toBeTruthy();
    expect(request).toHaveBeenCalledWith('/api/v2/admin/platform-evidence/scorecard');
  });

  it('does not turn missing evidence into zero or success', async () => {
    vi.mocked(request).mockRejectedValue(new Error('unavailable'));
    renderPanel();
    expect(await screen.findByText(/No zero or success status is assumed/)).toBeTruthy();
  });
});
