import React, { useEffect, useState } from 'react';
import {
  Alert,
  Button,
  Spinner,
  Table,
  TextField,
  WorkspaceMetricGrid,
  WorkspaceMetric,
  WorkspaceGrid,
  WorkspacePanel,
  WorkspaceStatusList,
  WorkspaceDisclosure,
} from '../../ui';
import type { Column } from '../../ui';
import { ApiError, request } from '../../shared/api/httpClient';

type Row = Record<string, unknown>;
type Overview = Record<string, unknown> & { recentRentals?: Row[] };

interface Props {
  loader?: (op: 'START' | 'STOP') => void;
  refreshSignal?: unknown;
  sessionExpired?: () => void;
}

const text = (value: unknown): string => value == null ? '' : String(value);
const count = (value: unknown): number => {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : 0;
};
const errorMessage = (error: unknown): string => error instanceof Error ? error.message : 'Unable to load vending operations.';

export default function ModuleVending({ loader, refreshSignal, sessionExpired }: Props): React.ReactElement {
  const [merchantId, setMerchantId] = useState('');
  const [overview, setOverview] = useState<Overview>({});
  const [callbacks, setCallbacks] = useState<Row[]>([]);
  const [commands, setCommands] = useState<Row[]>([]);
  const [events, setEvents] = useState<Row[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);

  async function load() {
    setBusy(true);
    loader?.('START');
    setError(null);
    const query = merchantId.trim() && Number(merchantId) > 0 ? `?merchantId=${encodeURIComponent(merchantId.trim())}` : '';
    const suffix = query ? `${query}&limit=100` : '?limit=100';
    try {
      const [overviewResponse, callbackResponse, commandResponse, eventResponse] = await Promise.all([
        request<Overview>(`/api/v2/admin/vending/overview${query}`),
        request<Row[]>(`/api/v2/admin/vending/callbacks${suffix}`),
        request<Row[]>(`/api/v2/admin/vending/commands${suffix}`),
        request<Row[]>(`/api/v2/admin/vending/events${suffix}`),
      ]);
      setOverview(overviewResponse);
      setCallbacks(callbackResponse);
      setCommands(commandResponse);
      setEvents(eventResponse);
    } catch (requestError) {
      setError(requestError);
      if (requestError instanceof ApiError && requestError.status === 401) sessionExpired?.();
    } finally {
      setBusy(false);
      loader?.('STOP');
    }
  }

  useEffect(() => { void load(); }, [refreshSignal]); // eslint-disable-line react-hooks/exhaustive-deps

  const rentalColumns: Column<Row>[] = [
    { key: 'merchant', header: 'Merchant', accessor: (row) => text(row.merchant_id) },
    { key: 'ref', header: 'Rental', accessor: (row) => text(row.rental_reference) },
    { key: 'device', header: 'Device', accessor: (row) => text(row.device_code) },
    { key: 'customer', header: 'Customer', accessor: (row) => text(row.customer_mask) },
    { key: 'status', header: 'Status', accessor: (row) => text(row.status) },
    { key: 'amount', header: 'Deposit', accessor: (row) => `${text(row.currency)} ${text(row.deposit_amount)}` },
    { key: 'created', header: 'Created', accessor: (row) => text(row.created_at) },
  ];
  const callbackColumns: Column<Row>[] = [
    { key: 'merchant', header: 'Merchant', accessor: (row) => text(row.merchant_id) },
    { key: 'connector', header: 'Connector', accessor: (row) => text(row.connector_code) },
    { key: 'event', header: 'Event', accessor: (row) => text(row.event_type) },
    { key: 'external', header: 'External event', accessor: (row) => text(row.external_event_id) },
    { key: 'sig', header: 'Signature', accessor: (row) => text(row.signature_status) },
    { key: 'status', header: 'Processing', accessor: (row) => text(row.processing_status) },
    { key: 'error', header: 'Error', accessor: (row) => text(row.error_message) },
  ];
  const commandColumns: Column<Row>[] = [
    { key: 'merchant', header: 'Merchant', accessor: (row) => text(row.merchant_id) },
    { key: 'ref', header: 'Command', accessor: (row) => text(row.command_reference) },
    { key: 'type', header: 'Type', accessor: (row) => text(row.command_type) },
    { key: 'connector', header: 'Connector', accessor: (row) => text(row.connector_code) },
    { key: 'status', header: 'Status', accessor: (row) => text(row.status) },
    { key: 'provider', header: 'Provider ref', accessor: (row) => text(row.provider_reference) },
  ];
  const eventColumns: Column<Row>[] = [
    { key: 'merchant', header: 'Merchant', accessor: (row) => text(row.merchant_id) },
    { key: 'event', header: 'Event', accessor: (row) => text(row.event_type) },
    { key: 'entity', header: 'Entity', accessor: (row) => `${text(row.entity_type)} · ${text(row.entity_reference)}` },
    { key: 'actor', header: 'Actor', accessor: (row) => text(row.actor) },
    { key: 'created', header: 'Created', accessor: (row) => text(row.created_at) },
  ];

  const offlineDevices = count(overview.offlineDevices);
  const pendingPayments = count(overview.pendingPayments);
  const failedCallbacks = count(overview.failedCallbacks);
  const refundPending = count(overview.refundPending);

  return (
    <div className="cito-service-workspace cpay-vending-admin">
      {error ? <Alert variant="error">{errorMessage(error)}</Alert> : null}
      {busy && !Object.keys(overview).length ? <Spinner label="Loading vending estate" /> : null}

      <WorkspaceMetricGrid>
        <WorkspaceMetric label="Devices" value={text(overview.devices || 0)} meta="Devices in scope" tone="info" />
        <WorkspaceMetric label="Active rentals" value={text(overview.activeRentals || 0)} meta="Currently active" tone="success" />
        <WorkspaceMetric label="Payment pending" value={text(overview.pendingPayments || 0)} meta="Awaiting payment completion" tone={pendingPayments ? 'warning' : 'success'} />
        <WorkspaceMetric label="Offline devices" value={text(overview.offlineDevices || 0)} meta="Require connectivity review" tone={offlineDevices ? 'danger' : 'success'} />
      </WorkspaceMetricGrid>

      <WorkspaceGrid>
        <WorkspacePanel eyebrow="Latest" title="Recent rentals" className="cito-workspace-panel--table">
          <Table
            columns={rentalColumns}
            rows={overview.recentRentals ?? []}
            rowKey={(row) => text(row.id)}
            pageSize={10}
            emptyText="No vending rentals are available in this scope."
          />
        </WorkspacePanel>

        <WorkspacePanel eyebrow="Status" title="Operational health">
          <WorkspaceStatusList items={[
            { label: 'Failed callbacks', value: String(failedCallbacks), tone: failedCallbacks ? 'danger' : 'success' },
            { label: 'Refund attention', value: String(refundPending), tone: refundPending ? 'warning' : 'success' },
            { label: 'Commands in view', value: String(commands.length), tone: 'info' },
            { label: 'Events in view', value: String(events.length), tone: 'neutral' },
          ]} />
          <div className="cito-form-stack" style={{ marginTop: 18 }}>
            <TextField id="vending-admin-merchant" label="Merchant id filter" value={merchantId} onValueChange={setMerchantId} placeholder="Blank = all tenants" />
            <Button variant="primary" onClick={() => void load()} loading={busy} loadingLabel="Refreshing…">Apply filter</Button>
          </div>
          <p className="cito-inline-note">The dashboard shows live operational data only. Provider or device capability is not treated as certified merely because an adapter exists.</p>
        </WorkspacePanel>
      </WorkspaceGrid>

      <WorkspaceDisclosure summary={`Manufacturer callbacks · ${callbacks.length} in view`}>
        <Table columns={callbackColumns} rows={callbacks} rowKey={(row) => text(row.id)} pageSize={20} emptyText="No device callbacks." />
      </WorkspaceDisclosure>

      <WorkspaceDisclosure summary={`Device commands · ${commands.length} in view`}>
        <Table columns={commandColumns} rows={commands} rowKey={(row) => text(row.id)} pageSize={20} emptyText="No device commands." />
      </WorkspaceDisclosure>

      <WorkspaceDisclosure summary={`Operational events · ${events.length} in view`}>
        <Table columns={eventColumns} rows={events} rowKey={(row) => text(row.id)} pageSize={20} emptyText="No vending events." />
      </WorkspaceDisclosure>
    </div>
  );
}
