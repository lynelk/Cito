import { useEffect } from 'react';
import { withRouter } from '../../shared/router/compat';
import LinearChart from './LinearChart';
import {
  Badge,
  Button,
  Table,
  WorkspaceMetricGrid,
  WorkspaceMetric,
  WorkspaceGrid,
  WorkspacePanel,
  WorkspaceQuickActions,
  WorkspaceStatusList,
} from '../../ui';
import { useAuth } from '../../shared/useAuth';
import {
  useAdminDashboardCharts,
  useAdminTransactions,
  usePortalDashboardSummary,
  useLoaderSync,
  useRefreshSignal,
  SessionExpiredError,
} from '../../shared/api/hooks';

const RECENT_ACTIVITY_LIMIT = 5;
const READY_CHANNEL_STATUSES = new Set(['ACTIVE', 'SANDBOX_TESTED', 'SUBMITTED_FOR_APPROVAL']);

function numberValue(value) {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : 0;
}

function formatAmount(value) {
  const amount = numberValue(value);
  if (amount <= 0) return 'No activity';
  return `UGX ${new Intl.NumberFormat('en-US', {
    notation: 'compact',
    maximumFractionDigits: 1,
  }).format(amount)}`;
}

function formatCount(value) {
  return new Intl.NumberFormat('en-US').format(numberValue(value));
}

function serviceState(status) {
  switch (status) {
    case 'ACTIVE': return { label: 'Operating', tone: 'success' };
    case 'SANDBOX_TESTED':
    case 'SUBMITTED_FOR_APPROVAL': return { label: 'Ready', tone: 'info' };
    case 'DEGRADED': return { label: 'Degraded', tone: 'warning' };
    case 'FAILED':
    case 'DISABLED':
    case 'SUSPENDED': return { label: 'Needs attention', tone: 'danger' };
    default: return { label: 'Setup required', tone: 'neutral' };
  }
}

function transactionTone(status) {
  if (status === 'SUCCESSFUL') return 'success';
  if (status === 'FAILED') return 'danger';
  if (status === 'PENDING') return 'warning';
  return 'neutral';
}

function friendlyError(error) {
  const message = error?.message || 'Review the data source and retry.';
  return /internal application error|internal server error|something went wrong/i.test(message)
    ? 'A live dashboard source could not be refreshed. Cito has not substituted fallback values.'
    : message;
}

function ModuleInsightsC(props) {
  const { loader, refreshSignal, sessionExpired, history } = props;
  const { hasPrivilege } = useAuth('admin');
  const canViewTransactions = hasPrivilege('ACCESS_TRANSACTION_LOG');

  const summaryQuery = usePortalDashboardSummary();
  const charts = useAdminDashboardCharts();
  const recentActivityQuery = useAdminTransactions(
    { value: '', category: 'all' },
    RECENT_ACTIVITY_LIMIT,
    canViewTransactions,
  );

  const busy = summaryQuery.isFetching
    || charts.payinsVsPayouts.isFetching
    || (canViewTransactions && recentActivityQuery.isFetching);
  useLoaderSync(loader, busy);

  const refreshers = [summaryQuery.refetch, charts.payinsVsPayouts.refetch];
  if (canViewTransactions) refreshers.push(recentActivityQuery.refetch);
  useRefreshSignal(refreshSignal, refreshers);

  const errors = [
    summaryQuery.error,
    charts.payinsVsPayouts.error,
    canViewTransactions ? recentActivityQuery.error : null,
  ].filter(Boolean);
  const hasSessionExpiredError = errors.some((error) => error instanceof SessionExpiredError);

  useEffect(() => {
    if (hasSessionExpiredError) sessionExpired?.();
  }, [hasSessionExpiredError, sessionExpired]);

  const summary = summaryQuery.data || {};
  const channels = Array.isArray(summary.activeChannels) ? summary.activeChannels : [];
  const failedTransactions = numberValue(summary.failedTransactions);
  const transactionCount = numberValue(summary.transactions);
  const successRate = transactionCount > 0
    ? `${Math.max(0, ((transactionCount - failedTransactions) / transactionCount) * 100).toFixed(1)}%`
    : 'No activity';

  const attentionChannels = channels.filter((channel) => !READY_CHANNEL_STATUSES.has(channel.status));
  const statusItems = [
    {
      label: 'Live data sources',
      value: errors.length ? `${errors.length} need review` : 'Available',
      tone: errors.length ? 'danger' : 'success',
    },
    {
      label: 'Failed transactions',
      value: formatCount(failedTransactions),
      tone: failedTransactions > 0 ? 'warning' : 'success',
    },
    {
      label: 'Payment channels',
      value: channels.length ? `${channels.length} reported` : 'None reported',
      tone: channels.length ? 'info' : 'neutral',
    },
    {
      label: 'Channels needing attention',
      value: formatCount(attentionChannels.length),
      tone: attentionChannels.length ? 'warning' : 'success',
    },
  ];

  const recentRows = Array.isArray(recentActivityQuery.data?.rows)
    ? [...recentActivityQuery.data.rows]
      .sort((a, b) => String(b.created_on || '').localeCompare(String(a.created_on || '')))
      .slice(0, RECENT_ACTIVITY_LIMIT)
    : [];

  const metrics = [
    { label: 'Collections', value: formatAmount(summary.payIns), meta: 'Incoming payments', tone: 'info' },
    { label: 'Disbursements', value: formatAmount(summary.payOuts), meta: 'Outgoing payments', tone: 'info' },
    { label: 'Transactions', value: formatCount(transactionCount), meta: 'Recorded in this view', tone: 'neutral' },
    { label: 'Success rate', value: successRate, meta: 'Recorded transactions', tone: failedTransactions > 0 ? 'warning' : 'success' },
  ];

  const recentColumns = [
    { key: 'created_on', header: 'Time', accessor: (row) => row.created_on || '-' },
    { key: 'merchant', header: 'Merchant / reference', accessor: (row) => row.merchant_name || row.tx_merchant_ref || row.tx_gateway_ref || '-' },
    { key: 'type', header: 'Type', accessor: (row) => row.tx_type || '-' },
    { key: 'status', header: 'Status', render: (row) => <Badge tone={transactionTone(row.status)}>{row.status || 'Unknown'}</Badge> },
  ];

  const quickActions = [
    { label: 'Payments', description: 'Review collections, payouts and transactions', onClick: () => history.push('/bo/admin/money-operations') },
    { label: 'Communications', description: 'Manage communication providers and routing', onClick: () => history.push('/bo/admin/communicationrouting') },
    { label: 'Vending & Utilities', description: 'Review vending estate and fulfilment', onClick: () => history.push('/bo/admin/vending') },
    { label: 'KYC & Identity', description: 'Identity, Credit & Scoring, KYB and compliance operations', onClick: () => history.push('/bo/admin/risk-compliance') },
    { label: 'Billing & BaaS', description: 'Manage service access and monetisation', onClick: () => history.push('/bo/admin/platform') },
  ];

  return (
    <div className="cito-service-workspace" data-testid="admin-insights">
      <WorkspaceMetricGrid>
        {metrics.map((metric) => (
          <WorkspaceMetric key={metric.label} label={metric.label} value={metric.value} meta={metric.meta} tone={metric.tone} />
        ))}
      </WorkspaceMetricGrid>

      <WorkspaceGrid>
        <WorkspacePanel
          eyebrow="Trend"
          title="Money movement"
          actions={<Button variant="ghost" className="ios-btn--sm" onClick={() => history.push('/bo/admin/money-operations')}>Open payments</Button>}
        >
          <div className="cito-chart-shell">
            {charts.payinsVsPayouts.data
              ? <LinearChart data={charts.payinsVsPayouts.data} title="Collections vs disbursements" />
              : <div className="cito-empty-compact">No performance series is available yet.</div>}
          </div>
        </WorkspacePanel>

        <WorkspacePanel eyebrow="Status" title="Needs attention">
          <WorkspaceStatusList items={statusItems} />
          {errors.length ? <p className="cito-inline-note">{friendlyError(errors[0])}</p> : null}
          {attentionChannels.slice(0, 3).map((channel, index) => {
            const state = serviceState(channel.status);
            return (
              <p className="cito-inline-note" key={`${channel.channel_code || channel.display_name}-${index}`}>
                <strong>{channel.display_name || channel.channel_code || 'Payment channel'}:</strong> {state.label}
                {channel.environment ? ` · ${channel.environment}` : ''}
              </p>
            );
          })}
        </WorkspacePanel>
      </WorkspaceGrid>

      <WorkspaceGrid>
        <WorkspacePanel
          eyebrow="Latest"
          title="Recent activity"
          className="cito-workspace-panel--table"
          actions={canViewTransactions ? <Button variant="ghost" className="ios-btn--sm" onClick={() => history.push('/bo/admin/money-operations')}>View all</Button> : null}
        >
          {!canViewTransactions
            ? <div className="cito-empty-compact">Your role does not include access to the transaction log.</div>
            : recentRows.length === 0
              ? <div className="cito-empty-compact">No recent transaction activity is available.</div>
              : <Table columns={recentColumns} rows={recentRows} rowKey={(row, index) => row.id || `${row.tx_merchant_ref}-${index}`} />}
        </WorkspacePanel>

        <WorkspacePanel eyebrow="Navigate" title="Quick actions">
          <WorkspaceQuickActions actions={quickActions} />
        </WorkspacePanel>
      </WorkspaceGrid>
    </div>
  );
}

const ModuleInsights = withRouter(ModuleInsightsC);
export default ModuleInsights;
