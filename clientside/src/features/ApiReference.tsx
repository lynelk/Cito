import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import React, { useEffect, useMemo, useState } from 'react';
import { apiFetch } from '../shared/api/httpClient';
import '../styles/api-reference.css';

type Json = Record<string, any>;
type Operation = { method: string; path: string; operation: Json; parameters: Json[] };
type Rate = { http_method: string; route_template: string; amount: string; currency: string; version_id: number };
const methods = ['get', 'post', 'put', 'patch', 'delete', 'head', 'options'];
export function operations(document: Json | null): Operation[] {
  return Object.entries(document?.paths || {}).flatMap(([path, item]) => Object.entries(item as Json)
    .filter(([method]) => methods.includes(method)).map(([method, operation]) => ({ method: method.toUpperCase(), path,
      operation: operation as Json, parameters: [...((item as Json).parameters || []), ...((operation as Json).parameters || [])] })));
}
export function safeRequestPath(path: string): boolean {
  const pathname = path.split('?')[0];
  if (!path.startsWith('/') || path.startsWith('//') || /[\\\r\n]/.test(path) || /[{}]/.test(pathname) || /%(?:2f|5c)/i.test(pathname)) return false;
  try { return !decodeURIComponent(pathname).split('/').some(segment => segment === '.' || segment === '..'); }
  catch { return false; }
}

function download(name: string, content: string, type: string) {
  const url = URL.createObjectURL(new Blob([content], { type }));
  const link = window.document.createElement('a'); link.href = url; link.download = name; link.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
function resolve(value: Json, document: Json | null): Json {
  if (!value?.$ref?.startsWith('#/')) return value || {};
  return value.$ref.slice(2).split('/').reduce((v: Json, key: string) => v?.[key.replace(/~1/g, '/').replace(/~0/g, '~')], document) || value;
}
export default function ApiReference({ admin = false }: { admin?: boolean }): React.ReactElement {
  const [document, setDocument] = useState<Json | null>(null);
  const [guide, setGuide] = useState(''); const [rates, setRates] = useState<Rate[]>([]);
  const [scope, setScope] = useState('commercial'); const [tab, setTab] = useState('reference');
  const [query, setQuery] = useState(''); const [selected, setSelected] = useState<Operation | null>(null);
  const [error, setError] = useState(''); const [notice, setNotice] = useState(''); const [busy, setBusy] = useState(false);
  const [requestPath, setRequestPath] = useState(''); const [requestBody, setRequestBody] = useState('{}');
  const [requestHeaders, setRequestHeaders] = useState('{}'); const [confirmed, setConfirmed] = useState(false);
  const [response, setResponse] = useState(''); const [rateAmount, setRateAmount] = useState('0.0000'); const [rateCurrency, setRateCurrency] = useState('UGX');
  const base = admin ? '/api/v2/admin/api-reference' : '/api/v2/portal/api-reference';
  useEffect(() => {
    let active = true;
    setBusy(true); setError(''); setDocument(null); setSelected(null); setRequestHeaders('{}'); setResponse('');
    Promise.all([apiFetch(admin && scope === 'system' ? '/v3/api-docs' : `${base}/${admin ? 'commercial' : 'openapi'}`), apiFetch(`${base}/guide`), apiFetch(`${base}/rates`)]).then(async responses => {
      if (responses.some(r => !r.ok)) throw new Error('Unable to load this reference. Check your portal session and documentation configuration.');
      const [doc, text, prices] = await Promise.all([responses[0].json(), responses[1].text(), responses[2].json()]);
      if (active) { setDocument(doc); setGuide(text); setRates(prices); }
    }).catch(err => { if (active) setError(err.message); }).finally(() => { if (active) setBusy(false); });
    return () => { active = false; };
  }, [admin, base, scope]);
  const list = useMemo(() => operations(document), [document]);
  const filtered = useMemo(() => list.filter(item => JSON.stringify(item).toLowerCase().includes(query.toLowerCase())), [list, query]);
  const selectedRate = rates.find(rate => rate.http_method === selected?.method && rate.route_template === selected?.path);
  const guideSections = guide.split(/(?=^## )/m).filter(section => section.toLowerCase().includes(query.toLowerCase()));
  const select = (item: Operation) => {
    setSelected(item); setRequestPath(item.path); setRequestBody('{}'); setRequestHeaders('{}'); setConfirmed(false); setResponse(''); setNotice('');
    const rate = rates.find(r => r.http_method === item.method && r.route_template === item.path);
    setRateAmount(String(rate?.amount ?? '0.0000')); setRateCurrency(rate?.currency ?? 'UGX');
  };
  const csrf = () => decodeURIComponent(window.document.cookie.split('; ').find(c => c.startsWith('XSRF-TOKEN='))?.slice(11) || '');
  async function publishRate() {
    if (!selected || !selectedRate || !window.confirm(`Publish ${rateCurrency} ${rateAmount} per authenticated request for ${selected.method} ${selected.path}? This takes effect immediately.`)) return;
    setBusy(true); setError(''); setNotice('');
    try {
      const result = await apiFetch(`${base}/rates`, { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrf() }, body: JSON.stringify({ method: selected.method, path: selected.path, amount: rateAmount, currency: rateCurrency, expectedVersion: selectedRate.version_id }) });
      if (!result.ok) throw new Error(result.status === 409 ? 'Another admin changed this rate. Reload before publishing.' : 'Rate was not published. Check the amount, currency and session.');
      const updated = await result.json(); setRates(old => old.map(r => r === selectedRate ? updated : r)); setNotice('Rate published. Previous rate history is retained.');
    } catch (err) { setError(err instanceof Error ? err.message : 'Unable to publish rate.'); } finally { setBusy(false); }
  }
  async function execute() {
    if (!selected || !confirmed || !safeRequestPath(requestPath)) return;
    setBusy(true); setError(''); setResponse('');
    try {
      const template = selected.path.split(/(\{[^}]+\})/).map(part => part.startsWith('{') ? '[^/?]+' : part.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('');
      if (!new RegExp(`^${template}$`).test(requestPath.split('?')[0])) throw new Error('Use the selected endpoint path with its required identifiers.');
      const headers = JSON.parse(requestHeaders);
      if (!headers || Array.isArray(headers) || typeof headers !== 'object' || Object.values(headers).some(v => typeof v !== 'string')) throw new Error('Headers must be a JSON object containing string values.');
      const result = await apiFetch(requestPath, { method: selected.method, redirect: 'error', headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrf(), ...headers }, ...(!['GET', 'HEAD'].includes(selected.method) ? { body: requestBody } : {}) });
      setResponse(`HTTP ${result.status}\n${(await result.text()).slice(0, 100000)}`);
    } catch (err) { setError(err instanceof Error ? err.message : 'Request failed.'); } finally { setRequestHeaders('{}'); setConfirmed(false); setBusy(false); }
  }
  return <section className="cito-api-reference" aria-label={admin ? 'Administrator API workbench' : 'Developer API reference'}>
    <header><p className="cito-api-eyebrow">Cito Gateway · Developer reference</p><h2>{admin ? 'API workbench' : 'Connect to Cito'}</h2><p>Search endpoints, read integration steps, inspect schemas and review API access rates.</p>
      {admin && <label>API collection <select value={scope} onChange={e => setScope(e.target.value)}><option value="commercial">Commercial and merchant APIs</option><option value="system">Full system · administrator only</option></select></label>}</header>
    <div className="cito-api-toolbar"><button type="button" onClick={() => setTab('reference')} aria-pressed={tab === 'reference'}>API reference</button><button type="button" onClick={() => setTab('guide')} aria-pressed={tab === 'guide'}>Integration guide</button>
      <button type="button" disabled={!document} onClick={() => download(`cito-${scope}-openapi.json`, JSON.stringify(document, null, 2), 'application/json')}>Download OpenAPI</button><button type="button" disabled={!guide} onClick={() => download('cito-integration-guide.md', guide, 'text/markdown')}>Download guide</button></div>
    <label>Search documentation and functions<input type="search" value={query} onChange={e => setQuery(e.target.value)} placeholder="Try collect, webhook, authentication or billing" /></label>
    {error && <p role="alert">{error}</p>}{notice && <p role="status">{notice}</p>}{busy && <p role="status">Loading…</p>}
    {tab === 'guide' ? <article className="cito-api-guide">{guideSections.map((section, index) => <div key={index}><ReactMarkdown remarkPlugins={[remarkGfm]}>{section}</ReactMarkdown></div>)}{!guideSections.length && <p>No matching guide sections.</p>}</article> : <div className="cito-api-grid"><nav aria-label="API endpoints"><p>{filtered.length} operations</p>{filtered.map(item => <button type="button" key={`${item.method} ${item.path}`} aria-current={selected?.path === item.path && selected?.method === item.method ? 'true' : undefined} onClick={() => select(item)}><strong>{item.method}</strong> <span>{item.path}</span><small>{item.operation.summary || item.operation.operationId}</small></button>)}{!filtered.length && !busy && <p>No matching operations.</p>}</nav>
      <article>{!selected ? <p>Select an endpoint to see its request, response and access requirements.</p> : <><h3>{selected.operation.summary || selected.operation.operationId || 'API operation'}</h3><p><strong>{selected.method}</strong> <code>{selected.path}</code></p><p>{selected.operation.description}</p>
        <p><strong>Access:</strong> {selected.operation['x-cito-audience'] === 'MERCHANT_WORKSPACE' ? 'Merchant portal session' : JSON.stringify(selected.operation.security ?? document?.security ?? 'See system authorization policy')}</p>
        <p><strong>API access rate:</strong> {selectedRate ? `${selectedRate.currency} ${selectedRate.amount} / authenticated request` : scope === 'system' ? 'System operation; see commercial collection for billable APIs.' : 'Not registered yet; reload after catalogue initialization.'}</p>
        {selected.parameters.length > 0 && <><h4>Parameters</h4><div className="cito-api-table"><table><thead><tr><th>Name</th><th>Location</th><th>Required</th><th>Description</th></tr></thead><tbody>{selected.parameters.map((raw, i) => { const p = resolve(raw, document); return <tr key={i}><td>{p.name}</td><td>{p.in}</td><td>{p.required ? 'Yes' : 'No'}</td><td>{p.description || JSON.stringify(p.schema)}</td></tr>; })}</tbody></table></div></>}
        {selected.operation.requestBody && <details open><summary>Request body and examples</summary><pre>{JSON.stringify(resolve(selected.operation.requestBody, document), null, 2)}</pre></details>}<details><summary>Responses and errors</summary><pre>{JSON.stringify(selected.operation.responses, null, 2)}</pre></details>
        <details><summary>Schema dictionary</summary>{Object.entries(document?.components?.schemas || {}).map(([name, schema]) => <details key={name}><summary>{name}</summary><pre>{JSON.stringify(schema, null, 2)}</pre></details>)}</details>
        {admin && selectedRate && <fieldset><legend>Set API access rate</legend><p>Applies immediately to future production admissions. Service fees remain separate.</p><label>Amount per request<input inputMode="decimal" value={rateAmount} onChange={e => setRateAmount(e.target.value)} /></label><label>Currency<input value={rateCurrency} maxLength={3} onChange={e => setRateCurrency(e.target.value.toUpperCase())} /></label><button type="button" disabled={busy} onClick={() => void publishRate()}>Publish rate</button></fieldset>}
        <details><summary>Build and run a request</summary><p><strong>Connected deployment: {window.location.host}. Calls can affect live data and incur fees.</strong> This workbench is not an isolated sandbox. Existing permissions and approvals apply.</p><p>Sign APIs on your server using the exact path, query and body. Paste only request headers, never a private key. Headers clear after execution.</p><label>Path and query<input value={requestPath} onChange={e => { setRequestPath(e.target.value); setConfirmed(false); }} /></label><label>Request headers (JSON)<textarea autoComplete="off" spellCheck={false} value={requestHeaders} onChange={e => { setRequestHeaders(e.target.value); setConfirmed(false); }} /></label>
          {!['GET', 'HEAD'].includes(selected.method) && <label>Request body<textarea value={requestBody} onChange={e => { setRequestBody(e.target.value); setConfirmed(false); }} /></label>}<label className="cito-api-confirm"><input type="checkbox" checked={confirmed} onChange={e => setConfirmed(e.target.checked)} />I have checked the deployment, request and possible charges, and authorize this call.</label><button type="button" disabled={busy || !confirmed || !safeRequestPath(requestPath)} onClick={() => void execute()}>Run {selected.method} request</button>{response && <pre aria-live="polite">{response}</pre>}</details>
      </>}</article></div>}
  </section>;
}
