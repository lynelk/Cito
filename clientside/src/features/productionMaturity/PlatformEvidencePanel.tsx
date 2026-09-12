import React from 'react';
import { useQuery } from '@tanstack/react-query';
import { request } from '../../shared/api/httpClient';

type MetricGroup = Record<string, number>;
type Evidence = {
  evidenceBasis: string;
  targetsReportedAsActuals: boolean;
  commercial: MetricGroup;
  developer: MetricGroup;
  adoption: MetricGroup;
  providerCertification: Array<Record<string, unknown>>;
};

const labels: Record<string, string> = {
  founding20Candidates: 'Founding 20 candidates', founding20Active: 'Founding 20 active', founding20Live: 'Founding 20 live',
  activePackageAssignments: 'Active packages', embeddedProgrammesLive: 'Embedded partners live',
  activeProjects: 'Active developer projects', productionEligibleProjects: 'Production-eligible projects', apiRequests7d: 'API requests · 7d',
  activeApiMerchants30d: 'Active API merchants · 30d', successfulApiRequests7d: 'Successful API requests · 7d',
  productionMerchants30d: 'Production merchants · 30d', productionCommands30d: 'Production commands · 30d',
  liveOnboardingWorkflows: 'Live onboarding workflows', approvedGoLiveChecklists: 'Approved/live go-live checks',
};

function Group({ title, values }: { title: string; values: MetricGroup }): React.ReactElement {
  return <article className="pm-card" aria-label={title}>
    <div className="pm-card__top"><span className="pm-chip pm-chip--developer">{title}</span><span className="pm-status pm-status--ready">Measured</span></div>
    {Object.entries(values).map(([key, value]) => <div key={key} className="pm-evidence-row"><span>{labels[key] || key}</span><strong>{value.toLocaleString()}</strong></div>)}
  </article>;
}

export default function PlatformEvidencePanel(): React.ReactElement {
  const query = useQuery({ queryKey: ['platform-evidence', 'scorecard'], queryFn: () => request<Evidence>('/api/v2/admin/platform-evidence/scorecard') });
  if (query.isLoading) return <section className="pm-error" aria-live="polite">Loading durable adoption evidence…</section>;
  if (query.error || !query.data) return <section className="pm-error" role="alert">Commercial and adoption evidence is unavailable. No zero or success status is assumed.</section>;
  const data = query.data;
  return <section aria-label="Commercial and adoption evidence">
    <div className="cito-section-heading"><div><h3>Commercial & adoption evidence</h3><p>Computed from durable Cito records. Targets are never displayed as achieved results.</p></div></div>
    <div className="pm-grid">
      <Group title="Commercial" values={data.commercial} />
      <Group title="Developer activation" values={data.developer} />
      <Group title="Production adoption" values={data.adoption} />
      <article className="pm-card" aria-label="Provider certification evidence">
        <div className="pm-card__top"><span className="pm-chip pm-chip--operations">Provider evidence</span><span className="pm-status pm-status--ready">Recorded only</span></div>
        <div className="pm-card__metric">{data.providerCertification.length}</div>
        <p>Provider/channel evidence groups. A group count is not certification; approved scenarios remain explicit in the underlying records.</p>
        <code>{data.evidenceBasis} · targets-as-actuals={String(data.targetsReportedAsActuals)}</code>
      </article>
    </div>
    <style>{`.pm-evidence-row{display:flex;justify-content:space-between;gap:12px;border-bottom:1px solid rgba(148,163,184,.18);padding:7px 0}.pm-evidence-row:last-child{border-bottom:0}.pm-evidence-row span{color:var(--ios-text-muted,#64748b);font-size:.85rem}.pm-evidence-row strong{font-variant-numeric:tabular-nums}`}</style>
  </section>;
}
