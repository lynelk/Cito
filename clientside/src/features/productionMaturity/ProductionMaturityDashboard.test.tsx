import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import ProductionMaturityDashboard from './ProductionMaturityDashboard';

function renderDashboard() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <ProductionMaturityDashboard />
    </QueryClientProvider>,
  );
}

function response(body: unknown, ok = true, statusText = 'OK') {
  return Promise.resolve({ ok, statusText, text: () => Promise.resolve(JSON.stringify(body)) });
}

function successfulFetch() {
  return vi.fn((input: RequestInfo | URL) => {
    const url = String(input);
    if (url.includes('/api/v2/admin/platform-evidence/scorecard')) {
      return response({
        evidenceBasis: 'DURABLE_RECORDS_ONLY',
        targetsReportedAsActuals: false,
        commercial: {},
        developer: {},
        adoption: {},
        providerDefinitions: [],
        providerCertification: [],
      });
    }
    return response({ rows: [] });
  });
}

describe('ProductionMaturityDashboard', () => {
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('renders loading state and calls the real production maturity endpoints', async () => {
    vi.stubGlobal('fetch', successfulFetch());

    renderDashboard();

    expect(screen.getByLabelText(/production maturity loading state/i)).toBeInTheDocument();
    await waitFor(() => {
      expect(globalThis.fetch).toHaveBeenCalledWith(
        '/api/v2/product-experience/dashboard/widgets?audience=ADMIN',
        expect.objectContaining({ credentials: 'include' }),
      );
      expect(globalThis.fetch).toHaveBeenCalledWith(
        '/api/v2/production-maturity/validation/runs?limit=10',
        expect.objectContaining({ credentials: 'include' }),
      );
    });
  });

  it('renders workflow sections and the durable platform evidence panel', async () => {
    vi.stubGlobal('fetch', successfulFetch());

    renderDashboard();

    expect(await screen.findByRole('heading', { name: /production maturity/i })).toBeInTheDocument();
    expect(await screen.findByText(/commercial & adoption evidence/i)).toBeInTheDocument();
    expect(await screen.findByText(/merchant onboarding/i)).toBeInTheDocument();
    expect(screen.getByText(/developer portal/i)).toBeInTheDocument();
    expect(screen.getByText(/finance operations/i)).toBeInTheDocument();
    expect(screen.getByText(/compliance operations/i)).toBeInTheDocument();
    expect(screen.getByText(/cross-border readiness/i)).toBeInTheDocument();
    expect(screen.getByText(/automation validation/i)).toBeInTheDocument();
    expect(screen.getAllByText('Response received')).toHaveLength(7);
    expect(screen.queryByText('Live', { exact: true })).not.toBeInTheDocument();
  });

  it('renders explicit failure states instead of manufacturing zeros', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => response({ message: 'Forbidden' }, false, 'Forbidden')),
    );

    renderDashboard();

    // The independent evidence query can fail before the summary query. Await
    // the summary's actual error instead of inspecting whichever alert wins.
    await screen.findByText(/Production maturity APIs could not be loaded:.*Forbidden/i);
    const alerts = screen.getAllByRole('alert');
    expect(alerts.length).toBeGreaterThan(0);
    const combined = alerts.map((alert) => alert.textContent || '').join(' ');
    expect(combined).toMatch(/could not be loaded/i);
    expect(combined).toMatch(/forbidden/i);
    const totals = screen.getByLabelText('Production maturity summary');
    expect(within(totals).getAllByText('Unavailable')).toHaveLength(2);
    expect(within(totals).queryByText('0', { exact: true })).not.toBeInTheDocument();
    expect(screen.queryByText('Live', { exact: true })).not.toBeInTheDocument();
  });

  it('does not label an empty-body response as live or readable evidence', async () => {
    const fetch = successfulFetch();
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL) => String(input).includes('/api/v2/product-experience/dashboard/widgets')
      ? response(null) : fetch(input)));
    renderDashboard();
    await screen.findByText('Merchant onboarding');
    const card = screen.getByText('Merchant onboarding').closest('article');
    expect(card).not.toBeNull();
    expect(within(card!).getAllByText('Unavailable')).toHaveLength(2);
    const totals = screen.getByLabelText('Production maturity summary');
    expect(within(totals).getByText('6', { exact: true })).toBeInTheDocument();
    expect(screen.queryByText('Live', { exact: true })).not.toBeInTheDocument();
  });
});
