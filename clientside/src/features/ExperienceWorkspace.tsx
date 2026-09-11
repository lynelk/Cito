import React from 'react';
import MerchantReadinessPanel from './MerchantReadinessPanel';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { request } from '../shared/api/httpClient';
import { useAuth, type Portal } from '../shared/useAuth';
import {
  Alert,
  Button,
  Card,
  EmptyState,
  ErrorState,
  PageHeader,
  Section,
  Skeleton,
  StatusBadge,
  Stepper,
  Table,
  TextArea,
  TextField,
  Timeline,
  type Column,
  type StepperItem,
  type TimelineItem,
} from '../ui';

type ExperienceSection =
  | 'lifecycle'
  | 'search'
  | 'support'
  | 'notifications'
  | 'transaction-detail'
  | 'provider-incidents'
  | 'customers'
  | 'business';

interface ExperienceWorkspaceProps {
  portal: Portal;
  section: ExperienceSection;
}

interface SupportCase {
  case_reference: string;
  merchant_id?: number;
  subject: string;
  category: string;
  severity: string;
  status: string;
  transaction_reference?: string;
  first_response_due_at?: string;
  updated_at: string;
}

interface NotificationItem {
  notification_reference: string;
  notification_type: string;
  severity: string;
  title: string;
  message: string;
  action_url?: string;
  read_at?: string;
  created_at: string;
}

interface Incident {
  incident_reference: string;
  provider_code: string;
  severity: string;
  status: string;
  public_title: string;
  public_message: string;
  started_at: string;
}

function merchantIdFrom(user: Record<string, unknown>): number | null {
  const raw = user.merchant_id ?? user.merchantId;
  const value = Number(raw);
  return Number.isFinite(value) && value > 0 ? value : null;
}

function asMessage(error: unknown): string {
  return error instanceof Error ? error.message : 'The request could not be completed.';
}

function LifecycleWorkspace({ merchantId }: { merchantId: number | null }): React.ReactElement {
  const [state, setState] = React.useState<{ loading: boolean; error?: string; data?: Record<string, unknown> }>({ loading: true });
  const load = React.useCallback(async () => {
    if (!merchantId) return setState({ loading: false, error: 'The signed-in account has no merchant scope.' });
    setState({ loading: true });
    try {
      setState({ loading: false, data: await request(`/api/v2/merchants/${merchantId}/lifecycle`) });
    } catch (error) {
      setState({ loading: false, error: asMessage(error) });
    }
  }, [merchantId]);
  React.useEffect(() => { void load(); }, [load]);
  if (state.loading) return <Skeleton label="Loading activation lifecycle" />;
  if (state.error) return <ErrorState message={state.error} retry={() => void load()} />;
  const rows = Array.isArray(state.data?.steps) ? state.data.steps as Record<string, unknown>[] : [];
  const steps: StepperItem[] = rows.map((row) => ({
    code: String(row.step_code),
    name: String(row.step_name),
    status: String(row.status),
    guidance: row.guidance ? String(row.guidance) : undefined,
    responsibleParty: row.responsible_party ? String(row.responsible_party) : undefined,
  }));
  return (
    <div className="cito-workspace-stack">
      <MerchantReadinessPanel merchantId={merchantId} />
      {state.data?.blocked_reason ? <Alert variant="warning">{String(state.data.blocked_reason)}</Alert> : null}
      <Section title="Activation status" actions={<StatusBadge status={String(state.data?.status || 'UNKNOWN')} />}>
        <p><strong>Next action:</strong> {String(state.data?.next_action || 'No next action has been assigned.')}</p>
      </Section>
      <Section title="One authoritative activation journey"><Stepper steps={steps} /></Section>
    </div>
  );
}

function SearchWorkspace({ portal }: { portal: Portal }): React.ReactElement {
  const [query, setQuery] = React.useState('');
  const [loading, setLoading] = React.useState(false);
  const [error, setError] = React.useState('');
  const [results, setResults] = React.useState<Record<string, Record<string, unknown>[]>>({});
  const navigate = useNavigate();
  async function submit(event: React.FormEvent): Promise<void> {
    event.preventDefault();
    if (query.trim().length < 2) return;
    setLoading(true); setError('');
    try {
      const data = await request<Record<string, unknown>>(`/api/v2/search?q=${encodeURIComponent(query.trim())}`);
      setResults({
        merchants: Array.isArray(data.merchants) ? data.merchants as Record<string, unknown>[] : [],
        transactions: Array.isArray(data.transactions) ? data.transactions as Record<string, unknown>[] : [],
        supportCases: Array.isArray(data.supportCases) ? data.supportCases as Record<string, unknown>[] : [],
      });
    } catch (requestError) { setError(asMessage(requestError)); }
    finally { setLoading(false); }
  }
  const total = Object.values(results).reduce((sum, rows) => sum + rows.length, 0);
  return (
    <div className="cito-workspace-stack">
      <form className="cito-workspace-toolbar" role="search" onSubmit={(event) => void submit(event)}>
        <TextField id="global-search" label="Search references, merchants, and support cases" value={query} onValueChange={setQuery} />
        <Button type="submit" loading={loading} disabled={query.trim().length < 2}>Search</Button>
      </form>
      {error ? <ErrorState message={error} /> : null}
      {!loading && query && total === 0 && !error ? <EmptyState title="No scoped results" description="No records matched within your role and tenant boundary." /> : null}
      {Object.entries(results).map(([group, rows]) => rows.length ? (
        <Section title={group.replace(/([A-Z])/g, ' $1')} key={group}>
          <div className="cito-result-list">{rows.map((row, index) => {
            const reference = String(row.reference || row.case_reference || row.id || index);
            return <button type="button" className="cito-result-item" key={`${group}-${reference}`} onClick={() => group === 'transactions' && navigate(`/bo/${portal === 'admin' ? 'admin' : 'partner'}/transactions/${encodeURIComponent(reference)}`)}><strong>{reference}</strong><span>{String(row.name || row.subject || row.status || 'Record')}</span><small>{String(row.updated_on || row.updated_at || '')}</small></button>;
          })}</div>
        </Section>
      ) : null)}
    </div>
  );
}

function SupportWorkspace({ portal, merchantId }: { portal: Portal; merchantId: number | null }): React.ReactElement {
  const [cases, setCases] = React.useState<SupportCase[]>([]);
  const [loading, setLoading] = React.useState(true);
  const [error, setError] = React.useState('');
  const [notice, setNotice] = React.useState('');
  const [subject, setSubject] = React.useState('');
  const [description, setDescription] = React.useState('');
  const [transactionReference, setTransactionReference] = React.useState('');
  const load = React.useCallback(async () => {
    setLoading(true); setError('');
    try {
      const response = await request<{ cases: SupportCase[] }>('/api/v2/support/cases');
      setCases(response.cases || []);
    } catch (requestError) { setError(asMessage(requestError)); }
    finally { setLoading(false); }
  }, []);
  React.useEffect(() => { void load(); }, [load]);
  async function createCase(event: React.FormEvent): Promise<void> {
    event.preventDefault(); setNotice(''); setError('');
    try {
      const response = await request<{ caseReference: string }>('/api/v2/support/cases', {
        method: 'POST',
        body: JSON.stringify({ merchantId: portal === 'admin' ? merchantId : undefined, subject, description, transactionReference: transactionReference || undefined, category: 'GENERAL_SUPPORT', severity: 'MEDIUM' }),
      });
      setNotice(`Case ${response.caseReference} was created.`); setSubject(''); setDescription(''); setTransactionReference(''); await load();
    } catch (requestError) { setError(asMessage(requestError)); }
  }
  const columns: Column<SupportCase>[] = [
    { key: 'reference', header: 'Case', accessor: (row) => row.case_reference },
    { key: 'subject', header: 'Subject', accessor: (row) => row.subject },
    { key: 'severity', header: 'Severity', render: (row) => <StatusBadge status={row.severity} /> },
    { key: 'status', header: 'Status', render: (row) => <StatusBadge status={row.status} /> },
    { key: 'sla', header: 'First response due', accessor: (row) => row.first_response_due_at || 'Not assigned' },
  ];
  return (
    <div className="cito-workspace-stack">
      {notice ? <Alert variant="success">{notice}</Alert> : null}{error ? <ErrorState message={error} retry={() => void load()} /> : null}
      {portal === 'merchant' ? <Section title="Create a support case"><form className="cito-workspace-stack" onSubmit={(event) => void createCase(event)}><TextField id="case-subject" label="Subject" value={subject} required minLength={4} maxLength={240} onValueChange={setSubject} /><TextField id="case-transaction-reference" label="Transaction reference (optional)" value={transactionReference} maxLength={255} onValueChange={setTransactionReference} /><TextArea id="case-description" label="What happened?" value={description} required minLength={10} maxLength={5000} onValueChange={setDescription} /><Button type="submit">Create case</Button></form></Section> : null}
      <Section title={portal === 'admin' ? 'Support queue' : 'Your support cases'}>{loading ? <Skeleton /> : <Table columns={columns} rows={cases} rowKey={(row) => row.case_reference} emptyText="No support cases in this scope." />}</Section>
    </div>
  );
}

function NotificationWorkspace(): React.ReactElement {
  const [items, setItems] = React.useState<NotificationItem[]>([]);
  const [loading, setLoading] = React.useState(true);
  const [error, setError] = React.useState('');
  const load = React.useCallback(async () => {
    setLoading(true); setError('');
    try { const data = await request<{ notifications: NotificationItem[] }>('/api/v2/notifications'); setItems(data.notifications || []); }
    catch (requestError) { setError(asMessage(requestError)); }
    finally { setLoading(false); }
  }, []);
  React.useEffect(() => { void load(); }, [load]);
  async function markRead(reference: string): Promise<void> {
    try {
      await request(`/api/v2/notifications/${encodeURIComponent(reference)}/read`, { method: 'PATCH' });
      setItems((current) => current.map((item) => item.notification_reference === reference ? { ...item, read_at: new Date().toISOString() } : item));
    } catch (requestError) {
      setError(asMessage(requestError));
    }
  }
  if (loading) return <Skeleton label="Loading notifications" />;
  if (error) return <ErrorState message={error} retry={() => void load()} />;
  if (!items.length) return <EmptyState title="You are all caught up" description="Operational and account notifications will appear here." />;
  return <div className="cito-result-list">{items.map((item) => <Card className="cito-result-item" key={item.notification_reference}><div><StatusBadge status={item.severity} /> {!item.read_at ? <strong>Unread</strong> : null}</div><h3>{item.title}</h3><p>{item.message}</p><small>{item.created_at}</small>{!item.read_at ? <Button variant="ghost" onClick={() => void markRead(item.notification_reference)}>Mark read</Button> : null}</Card>)}</div>;
}

function TransactionWorkspace(): React.ReactElement {
  const [params, setParams] = useSearchParams();
  const pathReference = typeof window === 'undefined' ? '' : decodeURIComponent(window.location.pathname.split('/transactions/')[1]?.split('/')[0] || '');
  const initialReference = React.useRef(params.get('reference') || pathReference);
  const [reference, setReference] = React.useState(initialReference.current);
  const [timeline, setTimeline] = React.useState<TimelineItem[]>([]);
  const [summary, setSummary] = React.useState<Record<string, unknown> | null>(null);
  const [loading, setLoading] = React.useState(false);
  const [error, setError] = React.useState('');
  const load = React.useCallback(async (target: string) => {
    if (!target) return;
    setLoading(true); setError('');
    try {
      const data = await request<Record<string, unknown>>(`/api/v2/transactions/${encodeURIComponent(target)}/timeline`);
      const rows = Array.isArray(data.events) ? data.events as Record<string, unknown>[] : [];
      setTimeline(rows.map((row, index) => ({ id: index, event: String(row.event), status: row.status ? String(row.status) : undefined, occurredAt: row.occurredAt ? String(row.occurredAt) : undefined, detail: row.reason ? String(row.reason) : undefined })));
      setSummary(data); setParams({ reference: target });
    } catch (requestError) { setError(asMessage(requestError)); setSummary(null); setTimeline([]); }
    finally { setLoading(false); }
  }, [setParams]);
  React.useEffect(() => { if (initialReference.current) void load(initialReference.current); }, [load]);
  return <div className="cito-workspace-stack"><form className="cito-workspace-toolbar" onSubmit={(event) => { event.preventDefault(); void load(reference.trim()); }}><TextField id="transaction-reference" label="Transaction, merchant, or provider reference" value={reference} onValueChange={setReference} /><Button type="submit" loading={loading} disabled={!reference.trim()}>Load timeline</Button></form>{error ? <ErrorState message={error} /> : null}{summary ? <Section title="Transaction finality" actions={<StatusBadge status={String(summary.finality || 'UNKNOWN')} />}><p><strong>Cito reference:</strong> {String(summary.reference || '')}</p><p><strong>Provider reference:</strong> {String(summary.providerReference || 'Not recorded')}</p><p><strong>Settlement:</strong> {String(summary.settlementState || 'Not recorded')}</p></Section> : null}{timeline.length ? <Section title="Canonical processing timeline"><Timeline items={timeline} /></Section> : !loading && !error ? <EmptyState title="Find a transaction" description="Load a reference to see provider, finality, reconciliation, and settlement evidence." /> : null}</div>;
}

function IncidentWorkspace(): React.ReactElement {
  const [items, setItems] = React.useState<Incident[]>([]);
  const [error, setError] = React.useState('');
  const [loading, setLoading] = React.useState(true);
  const load = React.useCallback(async () => { setLoading(true); try { const data = await request<{ incidents: Incident[] }>('/api/v2/provider-incidents'); setItems(data.incidents || []); } catch (requestError) { setError(asMessage(requestError)); } finally { setLoading(false); } }, []);
  React.useEffect(() => { void load(); }, [load]);
  const columns: Column<Incident>[] = [
    { key: 'reference', header: 'Incident', accessor: (row) => row.incident_reference },
    { key: 'provider', header: 'Provider', accessor: (row) => row.provider_code },
    { key: 'title', header: 'Public title', accessor: (row) => row.public_title },
    { key: 'severity', header: 'Severity', render: (row) => <StatusBadge status={row.severity} /> },
    { key: 'status', header: 'Status', render: (row) => <StatusBadge status={row.status} /> },
  ];
  if (error) return <ErrorState message={error} retry={() => void load()} />;
  return <Section title="Provider incidents and certification context">{loading ? <Skeleton /> : <Table columns={columns} rows={items} rowKey={(row) => row.incident_reference} emptyText="No provider incidents have been recorded." />}</Section>;
}

export default function ExperienceWorkspace({ portal, section }: ExperienceWorkspaceProps): React.ReactElement {
  const { user } = useAuth(portal);
  const [params] = useSearchParams();
  const scoped = Number(params.get('merchantId'));
  const merchantId = portal === 'admin' && Number.isSafeInteger(scoped) && scoped > 0 ? scoped : merchantIdFrom(user as Record<string, unknown>);
  const titles: Record<ExperienceSection, [string, string]> = {
    lifecycle: ['Activation journey', 'One status, owner, blocker, and next action across Cito'],
    search: ['Global search', 'Role- and tenant-scoped results across operational records'],
    support: ['Support', 'Cases with references, context, ownership, and SLA visibility'],
    notifications: ['Notifications', 'Operational and account updates in one centre'],
    'transaction-detail': ['Transaction detail', 'Canonical finality, provider, reconciliation, and settlement evidence'],
    'provider-incidents': ['Provider incidents', 'Internal incident operations and safe public status context'],
    customers: ['Customers', 'Customer records created by real payment and billing journeys'],
    business: ['Business', 'Team, service, and commercial controls for this account'],
  };
  const [title, subtitle] = titles[section];
  return (
    <div className="cito-workspace-stack">
      <PageHeader title={title} subtitle={subtitle} />
      {section === 'lifecycle' ? <LifecycleWorkspace merchantId={merchantId} /> : null}
      {section === 'search' ? <SearchWorkspace portal={portal} /> : null}
      {section === 'support' ? <SupportWorkspace portal={portal} merchantId={merchantId} /> : null}
      {section === 'notifications' ? <NotificationWorkspace /> : null}
      {section === 'transaction-detail' ? <TransactionWorkspace /> : null}
      {section === 'provider-incidents' ? <IncidentWorkspace /> : null}
      {section === 'customers' ? <EmptyState title="No customer records in scope" description="Customers will appear when an actual payment, invoice, subscription, or verified profile creates one. Cito does not show sample customers in production." /> : null}
      {section === 'business' ? <EmptyState title="Business controls are contextual" description="Use team administration, service entitlements, billing, and settings for the signed-in merchant. Unentitled services remain hidden." /> : null}
    </div>
  );
}
