import React from 'react';
import { useQuery } from '@tanstack/react-query';
import { request } from '../../shared/api/httpClient';

type Metric = readonly [key: string, label: string];
const commercial: readonly Metric[] = [
  ['founding20Candidates', 'Founding 20 candidates'],
  ['founding20Active', 'Founding 20 active'],
  ['founding20Live', 'Founding 20 live'],
  ['activePackageAssignments', 'Active packages'],
  ['embeddedProgrammesLive', 'Embedded partners live'],
];
const developer: readonly Metric[] = [
  ['activeProjects', 'Active developer projects'],
  ['productionEligibleProjects', 'Production-eligible projects'],
  ['apiRequests7d', 'API requests · 7d'],
  ['activeApiMerchants30d', 'Active API merchants · 30d'],
  ['successfulApiRequests7d', 'API responses 2xx/3xx · 7d'],
];
const adoption: readonly Metric[] = [
  ['productionMerchants30d', 'Merchants with production attempts · 30d'],
  ['productionCommands30d', 'Production command attempts · 30d'],
  ['liveOnboardingWorkflows', 'Live onboarding workflows'],
  ['approvedGoLiveChecklists', 'Approved/live go-live checks'],
];

function record(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function count(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0;
}

function Group({ title, metrics, values }: { title: string; metrics: readonly Metric[]; values: unknown }): React.ReactElement {
  const data = record(values) ? values : {};
  const available = metrics.filter(([key]) => count(data[key])).length;
  const status = available === metrics.length ? 'Measured' : available ? 'Partial evidence' : 'Unavailable';
  return <article className="pm-card" aria-label={title}>
    <div className="pm-card__top"><span className="pm-chip pm-chip--developer">{title}</span><span className="pm-status pm-status--loading">{status}</span></div>
    {metrics.map(([key, label]) => {
      const value = data[key];
      return <div key={key} className="pm-evidence-row"><span>{label}</span><strong>{count(value) ? value.toLocaleString() : 'Unavailable'}</strong></div>;
    })}
    {available < metrics.length ? <p>Missing or invalid measurements remain unavailable; they are not zero.</p> : null}
  </article>;
}

type ProviderEvidence = { providerCode: string; channelCode: string; requiredScenarios: number; approvedScenarios: number };
function providerEvidence(value: unknown): value is ProviderEvidence {
  return record(value)
    && typeof value.providerCode === 'string' && value.providerCode.trim().length > 0 && value.providerCode !== '*'
    && typeof value.channelCode === 'string' && value.channelCode.trim().length > 0 && value.channelCode !== '*'
    && count(value.requiredScenarios) && count(value.approvedScenarios)
    && value.approvedScenarios <= value.requiredScenarios;
}

export default function PlatformEvidencePanel(): React.ReactElement {
  const query = useQuery({ queryKey: ['platform-evidence', 'scorecard'], queryFn: () => request<unknown>('/api/v2/admin/platform-evidence/scorecard') });
  if (query.isLoading) return <section className="pm-error" aria-live="polite">Loading durable adoption evidence…</section>;
  const data = query.data;
  if (query.error || !record(data) || data.evidenceBasis !== 'DURABLE_RECORDS_ONLY' || data.targetsReportedAsActuals !== false) {
    return <section className="pm-error" role="alert">Commercial and adoption evidence could not be loaded or its evidence basis could not be verified. No zero or success status is assumed.</section>;
  }
  const providers = Array.isArray(data.providerCertification) && data.providerCertification.every(providerEvidence)
    ? data.providerCertification as ProviderEvidence[] : null;
  return <section aria-label="Commercial and adoption evidence">
    <div className="cito-section-heading"><div><h3>Commercial &amp; adoption evidence</h3><p>Recorded activity, not targets, settlement totals or provider certification. Developer API activity includes sandbox and production.</p></div></div>
    <div className="pm-grid">
      <Group title="Commercial" metrics={commercial} values={data.commercial} />
      <Group title="Developer activation" metrics={developer} values={data.developer} />
      <Group title="Production adoption" metrics={adoption} values={data.adoption} />
      <article className="pm-card" aria-label="Provider certification evidence">
        <div className="pm-card__top"><span className="pm-chip pm-chip--operations">Provider evidence</span><span className="pm-status pm-status--loading">{providers === null ? 'Unavailable' : 'Recorded coverage'}</span></div>
        {providers === null ? <p>Provider evidence is unavailable. No zero count or certification status is assumed.</p>
          : providers.length === 0 ? <p>No provider-specific evidence groups are recorded. This does not establish provider readiness.</p>
            : providers.map((provider) => <div className="pm-evidence-row" key={`${provider.providerCode}:${provider.channelCode}`}>
              <span>{provider.providerCode} / {provider.channelCode}</span><strong>{provider.approvedScenarios.toLocaleString()} / {provider.requiredScenarios.toLocaleString()} reviewed</strong>
            </div>)}
        <p>Reviewed scenario records do not certify the current credentials, environment, live delivery or settlement.</p>
        <code>DURABLE_RECORDS_ONLY · targets-as-actuals=false</code>
      </article>
    </div>
    <style>{`.pm-evidence-row{display:flex;justify-content:space-between;gap:12px;border-bottom:1px solid rgba(148,163,184,.18);padding:7px 0}.pm-evidence-row:last-child{border-bottom:0}.pm-evidence-row span{color:var(--ios-text-muted,#64748b);font-size:.85rem}.pm-evidence-row strong{font-variant-numeric:tabular-nums;overflow-wrap:anywhere}`}</style>
  </section>;
}
