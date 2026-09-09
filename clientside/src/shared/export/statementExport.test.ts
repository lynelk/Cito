import { downloadStatementExport } from './statementExport';
import { apiFetch } from '../api/httpClient';

vi.mock('../api/httpClient', () => ({
  apiFetch: vi.fn(),
}));

const mockedApiFetch = vi.mocked(apiFetch);

function mockResponse(overrides: Partial<Response> = {}): Response {
  return {
    ok: true,
    status: 200,
    headers: new Headers({ 'Content-Disposition': 'attachment; filename="cpay-statement-1000003.csv"' }),
    blob: async () => new Blob(['id,amount\n1,100\n'], { type: 'text/csv' }),
    ...overrides,
  } as Response;
}

describe('downloadStatementExport', () => {
  let createObjectURLSpy = vi.fn<(obj: Blob | MediaSource) => string>();
  let revokeObjectURLSpy = vi.fn<(url: string) => void>();
  let clickSpy = vi.fn<() => void>();

  beforeEach(() => {
    mockedApiFetch.mockReset();
    createObjectURLSpy = vi.fn<(obj: Blob | MediaSource) => string>(() => 'blob:mock-url');
    revokeObjectURLSpy = vi.fn<(url: string) => void>();
    URL.createObjectURL = createObjectURLSpy;
    URL.revokeObjectURL = revokeObjectURLSpy;
    clickSpy = vi.fn<() => void>();
    const originalCreateElement = document.createElement.bind(document);
    vi.spyOn(document, 'createElement').mockImplementation((tag: string) => {
      const el = originalCreateElement(tag);
      if (tag === 'a') {
        el.click = clickSpy;
      }
      return el;
    });
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('rejects when startDate or endDate is missing, without making a network call', async () => {
    await expect(
      downloadStatementExport({ startDate: '', endDate: '2026-01-31', format: 'csv' }),
    ).rejects.toThrow(/select a start and end date/i);

    expect(mockedApiFetch).not.toHaveBeenCalled();
  });

  it('requests the merchant self-service endpoint with startDate/endDate/format/limit', async () => {
    mockedApiFetch.mockResolvedValue(mockResponse());

    await downloadStatementExport({ startDate: '2026-01-01', endDate: '2026-01-31', format: 'csv' });

    expect(mockedApiFetch).toHaveBeenCalledTimes(1);
    const [url, init] = mockedApiFetch.mock.calls[0];
    expect(url).toContain('/api/v2/merchant-self-service/statements?');
    expect(url).toContain('startDate=2026-01-01');
    expect(url).toContain('endDate=2026-01-31');
    expect(url).toContain('format=csv');
    expect(url).toContain('limit=5000');
    expect(init).toEqual({ method: 'GET' });
  });

  it('supports a custom path and limit override (for other export surfaces)', async () => {
    mockedApiFetch.mockResolvedValue(mockResponse());

    await downloadStatementExport({
      startDate: '2026-01-01',
      endDate: '2026-01-31',
      format: 'xlsx',
      path: '/api/v2/statements',
      limit: 100,
    });

    const [url] = mockedApiFetch.mock.calls[0];
    expect(url).toContain('/api/v2/statements?');
    expect(url).toContain('format=xlsx');
    expect(url).toContain('limit=100');
  });

  it('triggers a download using the filename from Content-Disposition', async () => {
    mockedApiFetch.mockResolvedValue(mockResponse());

    await downloadStatementExport({ startDate: '2026-01-01', endDate: '2026-01-31', format: 'csv' });

    expect(createObjectURLSpy).toHaveBeenCalledTimes(1);
    expect(clickSpy).toHaveBeenCalledTimes(1);
    expect(revokeObjectURLSpy).toHaveBeenCalledWith('blob:mock-url');
  });

  it('falls back to a default filename when no Content-Disposition header is present', async () => {
    mockedApiFetch.mockResolvedValue(mockResponse({ headers: new Headers() }));

    await downloadStatementExport({ startDate: '2026-01-01', endDate: '2026-01-31', format: 'xlsx' });

    expect(clickSpy).toHaveBeenCalledTimes(1);
  });

  it('throws when the response is not ok', async () => {
    mockedApiFetch.mockResolvedValue(mockResponse({ ok: false, status: 401 }));

    await expect(
      downloadStatementExport({ startDate: '2026-01-01', endDate: '2026-01-31', format: 'csv' }),
    ).rejects.toThrow(/401/);
  });
});
