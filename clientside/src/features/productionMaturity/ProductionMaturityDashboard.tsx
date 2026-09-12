import React, { useMemo } from 'react';
import { useProductionMaturitySummary, type ProductionMaturityEndpointSummary } from '../../shared/api/hooks';
import PlatformEvidencePanel from './PlatformEvidencePanel';

type CardStatus = 'ready' | 'loading' | 'blocked';

const ownerTone: Record<ProductionMaturityEndpointSummary['owner'], string> = {
  Merchant: 'pm-chip--merchant',
  Finance: 'pm-chip--finance',
  Compliance: 'pm-chip--compliance',
  Operations: 'pm-chip--operations',
  Developer: 'pm-chip--developer',
};

function payloadCount(data: unknown): string {
  if (Array.isArray(data)) return `${data.length} records`;
  if (data && typeof data === 'object') {
    const values = Object.values(data as Record<string, unknown>);
    const firstArray = values.find(Array.isArray);
    if (Array.isArray(firstArray)) return `${firstArray.length} records`;
    return `${Object.keys(data as Record<string, unknown>).length} fields`;
  }
  return 'No payload';
}

function StatusPill({ status }: { status: CardStatus }): React.ReactElement {
  const label = status === 'ready' ? 'Live' : status === 'loading' ? 'Loading' : 'Needs attention';
  return <span className={`pm-status pm-status--${status}`}>{label}</span>;
}

function WorkflowCard({ item }: { item: ProductionMaturityEndpointSummary }): React.ReactElement {
  return (
    <article className="pm-card">
      <div className="pm-card__top">
        <span className={`pm-chip ${ownerTone[item.owner]}`}>{item.owner}</span>
        <StatusPill status="ready" />
      </div>
      <h3>{item.label}</h3>
      <div className="pm-card__metric">{payloadCount(item.data)}</div>
      <code>{item.endpoint}</code>
    </article>
  );
}

function LoadingGrid(): React.ReactElement {
  return (
    <section className="pm-grid" aria-label="Production maturity loading state">
      {Array.from({ length: 6 }, (_, index) => (
        <article className="pm-card pm-card--skeleton" key={index}>
          <div />
          <span />
          <p />
        </article>
      ))}
    </section>
  );
}

export default function ProductionMaturityDashboard(): React.ReactElement {
  const summary = useProductionMaturitySummary();
  const cards = useMemo(() => summary.data ?? [], [summary.data]);
  const totals = useMemo(() => {
    const live = cards.length;
    const owners = new Set(cards.map((card) => card.owner)).size;
    return { live, owners };
  }, [cards]);

  return (
    <main className="pm-shell">
      <style>{`
        .pm-shell { color: var(--ios-text, #0f172a); display: grid; gap: 16px; }
        .pm-hero { background: rgba(255,255,255,.82); border: 1px solid rgba(148,163,184,.26); border-radius: 24px; box-shadow: 0 16px 40px rgba(15,23,42,.08); display: grid; grid-template-columns: minmax(0,1fr) auto; gap: 20px; padding: 22px; }
        .pm-hero h2 { font-size: clamp(1.55rem,2.5vw,2.25rem); margin: 0 0 6px 0; }
        .pm-hero p { color: var(--ios-text-muted,#64748b); margin: 0; max-width: 760px; }
        .pm-kpis { display: grid; grid-template-columns: repeat(2,minmax(110px,1fr)); gap: 10px; }
        .pm-kpi { background: linear-gradient(180deg,rgba(245,249,255,.96),rgba(255,255,255,.96)); border:1px solid rgba(148,163,184,.22); border-radius:18px; padding:14px; min-width:120px; }
        .pm-kpi strong { display:block; font-size:1.45rem; line-height:1; }
        .pm-kpi span { color:var(--ios-text-muted,#64748b); font-size:.82rem; }
        .pm-grid { display:grid; grid-template-columns:repeat(auto-fit,minmax(230px,1fr)); gap:14px; }
        .pm-card { background:rgba(255,255,255,.88); border:1px solid rgba(148,163,184,.22); border-radius:20px; box-shadow:0 12px 30px rgba(15,23,42,.07); display:flex; flex-direction:column; gap:12px; min-height:190px; overflow:hidden; padding:16px; }
        .pm-card__top { align-items:center; display:flex; justify-content:space-between; gap:10px; }
        .pm-card h3 { font-size:1.05rem; margin:0; }
        .pm-card__metric { font-size:1.75rem; font-weight:800; letter-spacing:0; }
        .pm-card code { background:rgba(241,245,249,.85); border-radius:12px; color:#475569; display:block; font-size:.78rem; line-height:1.35; margin-top:auto; overflow-wrap:anywhere; padding:10px; }
        .pm-chip,.pm-status { border-radius:999px; font-size:.75rem; font-weight:700; padding:6px 10px; white-space:nowrap; }
        .pm-chip--merchant { background:#e0f2fe; color:#075985; }
        .pm-chip--finance { background:#ecfdf5; color:#047857; }
        .pm-chip--compliance { background:#fef3c7; color:#92400e; }
        .pm-chip--operations { background:#ede9fe; color:#5b21b6; }
        .pm-chip--developer { background:#fce7f3; color:#9d174d; }
        .pm-status--ready { background:#dcfce7; color:#166534; }
        .pm-status--loading { background:#e2e8f0; color:#475569; }
        .pm-status--blocked { background:#fee2e2; color:#991b1b; }
        .pm-error { background:#fff7ed; border:1px solid #fed7aa; border-radius:18px; color:#9a3412; padding:16px; }
        .pm-card--skeleton div,.pm-card--skeleton span,.pm-card--skeleton p { background:linear-gradient(90deg,#e2e8f0,#f8fafc,#e2e8f0); border-radius:999px; min-height:18px; }
        .pm-card--skeleton div { width:45%; } .pm-card--skeleton span { width:70%; min-height:28px; } .pm-card--skeleton p { width:100%; min-height:64px; margin-top:auto; }
        @media (max-width:720px) { .pm-hero{grid-template-columns:1fr}.pm-kpis{grid-template-columns:repeat(2,minmax(0,1fr))}.pm-grid{grid-template-columns:1fr} }
      `}</style>
      <section className="pm-hero">
        <div>
          <h2>Production maturity</h2>
          <p>Finance, compliance, cross-border, developer-experience, commercial adoption and automation readiness in one evidence-based operations view.</p>
        </div>
        <div className="pm-kpis" aria-label="Production maturity summary">
          <div className="pm-kpi"><strong>{summary.isLoading ? '...' : totals.live}</strong><span>live surfaces</span></div>
          <div className="pm-kpi"><strong>{summary.isLoading ? '...' : totals.owners}</strong><span>teams covered</span></div>
        </div>
      </section>

      <PlatformEvidencePanel />
      {summary.isLoading ? <LoadingGrid /> : null}
      {summary.error ? <section className="pm-error" role="alert">Production maturity APIs could not be loaded: {(summary.error as Error).message}</section> : null}
      {!summary.isLoading && !summary.error ? <section className="pm-grid" aria-label="Production maturity workflow endpoint status">{cards.map((item) => <WorkflowCard key={item.key} item={item} />)}</section> : null}
    </main>
  );
}
