import React, { useEffect, useState } from 'react';
import {
  Table,
  Alert,
  Spinner,
  TextField,
  Select,
  Button,
  WorkspaceMetricGrid,
  WorkspaceMetric,
  WorkspaceGrid,
  WorkspacePanel,
  WorkspaceDisclosure,
} from '../../ui';
import type { Column } from '../../ui';
import {
  useCommunicationProviders,
  useCommunicationRoutingRules,
  useCommunicationEffectiveRule,
  useCommunicationRuleUpsertMutation,
  useCommunicationRuleDeleteMutation,
  useLoaderSync,
  useRefreshSignal,
} from '../../shared/api/hooks';
import type { CommunicationProviderRow, CommunicationRuleRow } from '../../shared/api/hooks';
import { ApiError } from '../../shared/api/httpClient';

/**
 * Admin communications routing surface. Business behavior and API contracts are
 * unchanged; the screen is organised around status, routing rules, one focused
 * configuration panel, and progressive disclosure for the provider catalog.
 */

const CHANNELS = [{ value: 'SMS', label: 'SMS' }];

const YES_NO = [
  { value: 'YES', label: 'YES' },
  { value: 'NO', label: 'NO' },
];

function errorMessage(error: unknown): string {
  if (error instanceof ApiError) return error.message;
  if (error instanceof Error) return error.message;
  return 'Something went wrong.';
}

interface ModuleCommunicationRoutingProps {
  loader?: (op: 'START' | 'STOP') => void;
  refreshSignal?: unknown;
  sessionExpired?: () => void;
}

function ModuleCommunicationRouting({
  loader,
  refreshSignal,
  sessionExpired,
}: ModuleCommunicationRoutingProps): React.ReactElement {
  const [merchantId, setMerchantId] = useState('');
  const [channel, setChannel] = useState('SMS');
  const [providerCode, setProviderCode] = useState('LEGACY_SETTINGS');
  const [priority, setPriority] = useState('100');
  const [enabledFlag, setEnabledFlag] = useState('YES');
  const [feedback, setFeedback] = useState<{ tone: 'success' | 'error'; message: string } | null>(null);
  const [saveError, setSaveError] = useState<unknown>(null);

  const providersQuery = useCommunicationProviders();
  const rulesQuery = useCommunicationRoutingRules();
  const effectiveQuery = useCommunicationEffectiveRule(merchantId, channel);
  const upsertMutation = useCommunicationRuleUpsertMutation();
  const deleteMutation = useCommunicationRuleDeleteMutation();

  useLoaderSync(
    loader,
    providersQuery.isFetching ||
      rulesQuery.isFetching ||
      effectiveQuery.isFetching ||
      upsertMutation.isPending ||
      deleteMutation.isPending,
  );
  useRefreshSignal(refreshSignal, [providersQuery.refetch, rulesQuery.refetch, effectiveQuery.refetch]);

  useEffect(() => {
    if (providersQuery.error instanceof ApiError && providersQuery.error.status === 401) {
      sessionExpired?.();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [providersQuery.error]);

  const providers = providersQuery.data ?? [];
  const rules = rulesQuery.data ?? [];
  const effective = effectiveQuery.data;
  const enabledProviders = providers.filter((provider) => provider.enabledFlag === 'YES');
  const enabledRules = rules.filter((rule) => rule.enabledFlag === 'YES');
  const providerOptions = providers.map((provider) => ({
    value: provider.providerCode ?? '',
    label: `${provider.providerName ?? provider.providerCode} (${provider.providerCode ?? ''}${provider.enabledFlag === 'YES' ? '' : ' — disabled'})`,
  }));

  function handleSave() {
    const merchantNumeric = merchantId.trim() === '' ? null : Number(merchantId.trim());
    if (merchantId.trim() !== '' && (!Number.isFinite(merchantNumeric) || (merchantNumeric ?? 0) <= 0)) {
      setSaveError(new Error('merchantId must be a positive number or blank for the platform default'));
      return;
    }
    const priorityNumeric = Number(priority.trim());
    if (!Number.isFinite(priorityNumeric)) {
      setSaveError(new Error('priority must be a number (lower wins)'));
      return;
    }
    setSaveError(null);
    setFeedback(null);
    upsertMutation.mutate(
      {
        id: null,
        channel,
        merchantId: merchantNumeric,
        priority: priorityNumeric,
        providerCode,
        enabledFlag,
      },
      {
        onSuccess: () => {
          setFeedback({
            tone: 'success',
            message: merchantId.trim()
              ? `Rule saved for merchant ${merchantNumeric} (${channel} → ${providerCode}).`
              : `Platform default saved for ${channel} → ${providerCode}.`,
          });
        },
        onError: (error) => setFeedback({ tone: 'error', message: errorMessage(error) }),
      },
    );
  }

  function handleDelete(ruleId: number | undefined) {
    if (!ruleId) return;
    setSaveError(null);
    setFeedback(null);
    deleteMutation.mutate(ruleId, {
      onSuccess: () => setFeedback({ tone: 'success', message: `Routing rule #${ruleId} deleted.` }),
      onError: (error) => setFeedback({ tone: 'error', message: errorMessage(error) }),
    });
  }

  const ruleColumns: Column<CommunicationRuleRow>[] = [
    { key: 'channel', header: 'Channel', accessor: (row) => row.channel ?? '' },
    {
      key: 'merchant_id',
      header: 'Merchant',
      accessor: (row) => (row.merchantId == null ? 'Platform default' : String(row.merchantId)),
    },
    { key: 'priority', header: 'Priority', accessor: (row) => String(row.priority ?? '') },
    { key: 'provider_code', header: 'Provider', accessor: (row) => row.providerCode ?? '' },
    { key: 'enabled_flag', header: 'Enabled', accessor: (row) => row.enabledFlag ?? '' },
    {
      key: 'actions',
      header: '',
      render: (row) => (
        <Button variant="ghost" className="ios-btn--sm" onClick={() => handleDelete(row.id)}>
          Delete
        </Button>
      ),
    },
  ];

  const providerColumns: Column<CommunicationProviderRow>[] = [
    { key: 'provider_name', header: 'Provider', accessor: (row) => row.providerName ?? '' },
    { key: 'provider_code', header: 'Code', accessor: (row) => row.providerCode ?? '' },
    { key: 'channel', header: 'Channel', accessor: (row) => row.channel ?? '' },
    { key: 'enabled_flag', header: 'Enabled', accessor: (row) => row.enabledFlag ?? '' },
    { key: 'adapter_class', header: 'Adapter', accessor: (row) => row.adapterClass ?? '' },
  ];

  return (
    <div className="cito-service-workspace cpay-communication-routing">
      {feedback ? <Alert variant={feedback.tone === 'success' ? 'success' : 'error'}>{feedback.message}</Alert> : null}
      {saveError ? <Alert variant="error">{errorMessage(saveError)}</Alert> : null}
      {rulesQuery.error ? <Alert variant="error">{errorMessage(rulesQuery.error)}</Alert> : null}
      {rulesQuery.isLoading ? <Spinner label="Loading routing rules" /> : null}

      <WorkspaceMetricGrid>
        <WorkspaceMetric label="Providers" value={String(providers.length)} meta="Registered communication providers" tone="info" />
        <WorkspaceMetric label="Active providers" value={String(enabledProviders.length)} meta="Enabled for routing" tone={enabledProviders.length ? 'success' : 'warning'} />
        <WorkspaceMetric label="Routing rules" value={String(rules.length)} meta="Configured rules" tone="neutral" />
        <WorkspaceMetric label="Active rules" value={String(enabledRules.length)} meta="Enabled rules" tone={enabledRules.length ? 'success' : 'warning'} />
      </WorkspaceMetricGrid>

      <WorkspaceGrid>
        <WorkspacePanel eyebrow="Communications" title="Routing rules" className="cito-workspace-panel--table">
          <div className="cito-panel-toolbar">
            <div className="cito-panel-toolbar__grow">
              <TextField id="cr-effective-merchant" label="Preview merchant id" value={merchantId} onValueChange={setMerchantId} placeholder="Blank = platform default" />
            </div>
            <Button variant="ghost" className="ios-btn--sm" onClick={() => effectiveQuery.refetch()}>
              Resolve route
            </Button>
          </div>
          {effectiveQuery.isLoading ? <Spinner label="Resolving rule" /> : null}
          {effective && effective.resolved && effective.rule ? (
            <div style={{ padding: '12px 20px 0' }}>
              <Alert variant="success">
                {merchantId.trim() ? `Merchant ${merchantId.trim()}` : 'Platform default'} uses{' '}
                <strong>{effective.provider?.providerName ?? effective.rule.providerCode}</strong> via rule #{effective.rule.id}.
              </Alert>
            </div>
          ) : null}
          {effective && !effective.resolved ? (
            <div style={{ padding: '12px 20px 0' }}>
              <Alert variant="error">No enabled rule resolves for this merchant and channel. The legacy gateway remains the fallback.</Alert>
            </div>
          ) : null}
          <Table
            columns={ruleColumns}
            rows={rules}
            rowKey={(row) => row.id ?? 0}
            pageSize={20}
            emptyText="No routing rules configured."
          />
        </WorkspacePanel>

        <WorkspacePanel eyebrow="Configuration" title="Routing control">
          <div className="cito-form-stack">
            <Select id="cr-channel" label="Channel" value={channel} options={CHANNELS} onValueChange={setChannel} />
            <Select id="cr-provider" label="Provider" value={providerCode} options={providerOptions} onValueChange={setProviderCode} />
            <TextField id="cr-merchant" label="Merchant id" value={merchantId} onValueChange={setMerchantId} placeholder="Blank = platform default" />
            <TextField id="cr-priority" label="Priority" value={priority} onValueChange={setPriority} placeholder="Lower wins" />
            <Select id="cr-enabled" label="Enabled" value={enabledFlag} options={YES_NO} onValueChange={setEnabledFlag} />
            <Button variant="primary" onClick={handleSave} loading={upsertMutation.isPending} loadingLabel="Saving…">
              Save rule
            </Button>
          </div>
          <p className="cito-inline-note">Saved rules take effect on the next pending-send sweep. Provider activation and production certification remain separate controls.</p>
        </WorkspacePanel>
      </WorkspaceGrid>

      <WorkspaceDisclosure summary={`Provider catalog · ${providers.length} registered`}>
        <Table
          columns={providerColumns}
          rows={providers}
          rowKey={(provider) => provider.id ?? 0}
          pageSize={20}
          emptyText="No providers registered."
        />
      </WorkspaceDisclosure>
    </div>
  );
}

export default ModuleCommunicationRouting;
