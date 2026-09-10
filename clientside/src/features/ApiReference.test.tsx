import React from 'react';
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, cleanup } from '@testing-library/react';
import ApiReference, { operations, safeRequestPath } from './ApiReference';
import { apiFetch } from '../shared/api/httpClient';
vi.mock('../shared/api/httpClient', () => ({ apiFetch: vi.fn() }));
const document = { paths: { '/api/v2/payments/{reference}': { get: { summary: 'Look up a payment', security: [{ Signature: [] }], responses: { 200: { description: 'Payment state' } } } }, '/api/v2/native/payments/collect': { post: { summary: 'Collect a payment', responses: {} } } } };
afterEach(() => { cleanup(); vi.resetAllMocks(); });
function mockApi() {
  vi.mocked(apiFetch).mockImplementation(async (path) => new Response(String(path).endsWith('/guide') ? '## Authentication\nUse your merchant signature.\n## Webhooks\nVerify the event.' : JSON.stringify(String(path).endsWith('/rates') ? [{ http_method: 'GET', route_template: '/api/v2/payments/{reference}', amount: '0.0000', currency: 'UGX', version_id: 1 }] : document), { status: 200 }));
}
describe('API reference', () => {
  it('extracts operations without confusing shared parameters with methods', () => {
    expect(operations({ paths: { '/x': { parameters: [], get: { summary: 'Read' } } } })).toHaveLength(1);
  });
  it('rejects cross-origin and unresolved request paths', () => {
    for (const path of ['https://example.com', '//example.com', '/\\example.com', '/api/{id}', '/api\n/x', '/api/%2e%2e', '/api/..', '/api/%2fadmin']) expect(safeRequestPath(path)).toBe(false);
    expect(safeRequestPath('/api/v2/payments/123?merchantNumber=1')).toBe(true);
  });
  it('searches operations, displays zero rates and does not request system documentation for merchants', async () => {
    mockApi(); render(<ApiReference />);
    await screen.findByText('Look up a payment');
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'Look up' } });
    expect(screen.queryByText('Collect a payment')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: /GET.*Look up a payment/ }));
    expect(screen.getByText(/UGX 0.0000/)).toBeTruthy();
    expect(screen.queryByText('Set API access rate')).toBeNull();
    expect(vi.mocked(apiFetch).mock.calls.some(([url]) => String(url).includes('/v3/'))).toBe(false);
  });
  it('loads the full-system reference only after an admin selects it', async () => {
    mockApi(); render(<ApiReference admin />);
    await screen.findByText('Look up a payment');
    fireEvent.change(screen.getByRole('combobox'), { target: { value: 'system' } });
    await waitFor(() => expect(apiFetch).toHaveBeenCalledWith('/v3/api-docs'));
  });
  it('shows an intentional access error instead of rendering a failed response as documentation', async () => {
    vi.mocked(apiFetch).mockResolvedValue(new Response('{}', { status: 403 }));
    render(<ApiReference />);
    expect(await screen.findByRole('alert')).toHaveTextContent('Unable to load');
  });
});
