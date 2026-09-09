import { useEffect, useRef, useState } from 'react';
import Messager from '../StableMessager';
import { withRouter } from '../../shared/router/compat';
import common from "../Common";
import strings from '../locale';
import {
  Card,
  Table,
  Select,
  SearchField,
  Checkbox,
  Badge,
  Sheet,
  Button,
  TextField,
  WorkspaceMetricGrid,
  WorkspaceMetric,
  WorkspaceGrid,
  WorkspacePanel,
  WorkspaceQuickActions,
} from '../../ui';

import { useAuth } from '../../shared/useAuth';
import {
  useAdminTransactions,
  usePortalDashboardSummary,
  useResolveTransactionMutation,
  useLoaderSync,
  useRefreshSignal,
  SessionExpiredError,
  AccessDeniedError,
  LegacyRequestError,
} from '../../shared/api/hooks';

const SEARCH_CATEGORIES = [
  { value: 'all', label: 'All Fields' },
  { value: 'tx_type', label: 'Type' },
  { value: 'status', label: 'Status' },
  { value: 'original_amount', label: 'Amount' },
  { value: 'merchant_id', label: 'Merchant ID' },
];

const RESOLVE_STATUS = [
  { value: 'SUCCESSFUL', label: 'SUCCESSFUL' },
  { value: 'FAILED', label: 'FAILED' },
];

const PAGE_SIZE = 50;

function statusTone(s) {
  if (s === 'SUCCESSFUL') return 'success';
  if (s === 'FAILED') return 'danger';
  if (s === 'PENDING') return 'warning';
  return 'neutral';
}

function numberValue(value) {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : 0;
}

function formatAmount(value) {
  const amount = numberValue(value);
  if (amount <= 0) return 'No activity';
  return `UGX ${new Intl.NumberFormat('en-US', { notation: 'compact', maximumFractionDigits: 1 }).format(amount)}`;
}

function formatCount(value) {
  return new Intl.NumberFormat('en-US').format(numberValue(value));
}

const traceHtml = (value, pre) => ({
  __html: pre
    ? "<pre>" + common.encodeHTML(value || "") + "</pre>"
    : (value ? common.encodeHTML(value).replace(/\n/g, "<br/>") : ""),
});

/** Mirrors `Table`'s own `rowKey` so selection state can be looked up by the same key. */
function rowKeyFor(row, index) {
  return row.id ?? `${row.tx_merchant_ref}-${index}`;
}

function ModuleTransactionsC(props) {
    const { loader, history, refreshSignal } = props;
    const messagerRef = useRef(null);

    const { hasPrivilege } = useAuth('admin');
    const [accessGranted] = useState(() => hasPrivilege('ACCESS_TRANSACTION_LOG'));
    const [serverDeniedAccess, setServerDeniedAccess] = useState(false);
    const hasAccess = accessGranted && !serverDeniedAccess;

    const [searchingValue, setSearchingValue] = useState({ value: "", category: "all" });
    const [committedSearch, setCommittedSearch] = useState({ value: "", category: "all" });
    const [selectedKeys, setSelectedKeys] = useState(() => new Set());
    const [txDetailsRow, setTxDetailsRow] = useState({});
    const [detailsOpen, setDetailsOpen] = useState(false);
    const [resolveOpen, setResolveOpen] = useState(false);
    const [rowResolveForm, setRowResolveForm] = useState({ tx_gateway_ref: "", resolve_status: "", id: "" });

    const transactionsQuery = useAdminTransactions(committedSearch, PAGE_SIZE, hasAccess);
    const summaryQuery = usePortalDashboardSummary();
    const resolveMutation = useResolveTransactionMutation();

    useLoaderSync(loader, transactionsQuery.isFetching || summaryQuery.isFetching || resolveMutation.isPending);
    useRefreshSignal(refreshSignal, [transactionsQuery.refetch, summaryQuery.refetch]);

    useEffect(() => {
        if (!accessGranted) {
            messagerRef.current?.alert({ title: "Access denied!", icon: "info", msg: "You are not allowed access to this section." });
        }
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    function sessionExpired() {
        messagerRef.current?.alert({ title: "Session Expired!", icon: "info", msg: "Your session expired", result: () => history.push("/") });
    }

    useEffect(() => {
        setSelectedKeys(new Set());
    }, [transactionsQuery.dataUpdatedAt]);

    useEffect(() => {
        const error = transactionsQuery.error;
        if (!error) return;
        if (error instanceof SessionExpiredError) { sessionExpired(); return; }
        if (error instanceof AccessDeniedError) {
            messagerRef.current?.alert({ title: "Access denied!", icon: "info", msg: error.message, result: () => setServerDeniedAccess(true) });
            return;
        }
        const code = error instanceof LegacyRequestError ? error.code : undefined;
        messagerRef.current?.alert({ title: "Error " + (code || ''), icon: "error", msg: error.message });
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [transactionsQuery.error]);

    useEffect(() => {
        if (summaryQuery.error instanceof SessionExpiredError) sessionExpired();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [summaryQuery.error]);

    function resolveTransaction(row) {
        messagerRef.current?.confirm({
            title: "Resolve Transaction", icon: "info",
            msg: "Are you sure you want to resolve this transaction to " + row.resolve_status + "?",
            result: (r) => {
                if (!r) return;
                setResolveOpen(false);
                resolveMutation.mutate(row, {
                    onSuccess: (res) => {
                        messagerRef.current?.alert({ title: "Success!", icon: "info", msg: res.message });
                    },
                    onError: (error) => {
                        if (error instanceof SessionExpiredError) { sessionExpired(); return; }
                        const code = error instanceof LegacyRequestError ? error.code : undefined;
                        messagerRef.current?.alert({ title: "Error " + (code || error.message), icon: "error", msg: error.message });
                    },
                });
            }
        });
    }

    function handleSearch(value) {
        const next = { ...searchingValue, value };
        setSearchingValue(next);
        setCommittedSearch(next);
    }

    function handleFormChangeResolve(name, value) {
        setRowResolveForm((prev) => ({ ...prev, [name]: value }));
    }

    function handleRowCheck(key, checked) {
        setSelectedKeys((prev) => {
            const next = new Set(prev);
            if (checked) next.add(key); else next.delete(key);
            return next;
        });
    }

    function handleAllCheck(checked, rows) {
        setSelectedKeys(checked ? new Set(rows.map((row, i) => rowKeyFor(row, i))) : new Set());
    }

    function openDetails(row) {
        setTxDetailsRow(row);
        setDetailsOpen(true);
    }

    function openResolve() {
        setResolveOpen(true);
        setDetailsOpen(false);
        setRowResolveForm((prev) => ({ ...prev, id: txDetailsRow.id }));
    }

    function renderResolveDialog() {
        const f = rowResolveForm;
        return (
            <Sheet
                open={resolveOpen}
                onClose={() => setResolveOpen(false)}
                title="Resolve Transaction"
                size="sm"
                footer={<>
                    <Button variant="ghost" className="ios-btn--sm" onClick={() => setResolveOpen(false)}>Close</Button>
                    <Button variant="primary" className="ios-btn--sm" onClick={() => resolveTransaction(rowResolveForm)}>Submit</Button>
                </>}
            >
                <div className="ios-form">
                    <TextField id="resolve-ref" label="Network Ref" value={f.tx_gateway_ref || ''} onValueChange={(v) => handleFormChangeResolve('tx_gateway_ref', v)} />
                    <Select
                        id="resolve-status" label="Resolve to Status"
                        value={f.resolve_status || ''} placeholder="Select status"
                        options={RESOLVE_STATUS}
                        onValueChange={(v) => handleFormChangeResolve('resolve_status', v)}
                    />
                </div>
            </Sheet>
        );
    }

    function detailRow(label, value) {
        return (
            <div className="cpay-detail-row">
                <span className="cpay-detail-label">{label}</span>
                <span className="cpay-detail-value">{value}</span>
            </div>
        );
    }

    function traceBlock(value, pre) {
        return (
            <div
                className="cpay-trace-block"
                dangerouslySetInnerHTML={traceHtml(value, pre)}
            />
        );
    }

    function recordTxDetailsDialog() {
        const r = txDetailsRow;
        const canResolve = r.status !== "SUCCESSFUL" && r.status !== "FAILED";
        return (
            <Sheet
                open={detailsOpen}
                onClose={() => setDetailsOpen(false)}
                title={"Transaction Details: " + (r.merchant_name || '')}
                size="lg"
                footer={<>
                    {canResolve ? <Button variant="primary" className="ios-btn--sm" onClick={() => openResolve()}>{strings.resolve}</Button> : null}
                    <Button variant="ghost" className="ios-btn--sm" onClick={() => setDetailsOpen(false)}>{strings.close}</Button>
                </>}
            >
                {detailRow('Merchant Name', r.merchant_name)}
                {detailRow('Merchant number', r.merchant_number)}
                {detailRow('Gateway ID', r.gateway_id)}
                {detailRow('Status', <Badge tone={statusTone(r.status)}>{r.status}</Badge>)}
                {detailRow('Amount', "UGX " + (r.original_amount_formatted || ''))}
                {detailRow('Merchant Reference', r.tx_merchant_ref)}
                {detailRow('Network Ref', r.tx_gateway_ref)}
                {detailRow('Payer/Payee Number', r.payer_number)}
                {detailRow('Merchant Description', traceBlock(r.tx_merchant_description, false))}
                {detailRow('Our Description', traceBlock(r.tx_description, false))}
                {detailRow('Charges', "UGX " + (r.charges_formatted || ''))}
                {detailRow('Created On', r.created_on)}
                {detailRow('Request Trace', traceBlock(r.tx_request_trace, true))}
                {detailRow('Updated Trace', traceBlock(r.tx_update_trace, true))}
                {detailRow('Callback Trace', traceBlock(r.callback_trace, true))}
            </Sheet>
        );
    }

    if (!hasAccess) {
        return <div><Messager ref={messagerRef}></Messager></div>;
    }

    const rows = transactionsQuery.data?.rows ?? [];
    const allChecked = rows.length > 0 && rows.every((row, i) => selectedKeys.has(rowKeyFor(row, i)));
    const summary = summaryQuery.data || {};

    const columns = [
        {
            key: 'ck', width: 44,
            header: <Checkbox checked={allChecked} onCheckedChange={(c) => handleAllCheck(c, rows)} />,
            render: (row, index) => <Checkbox checked={selectedKeys.has(rowKeyFor(row, index))} onCheckedChange={(c) => handleRowCheck(rowKeyFor(row, index), c)} />,
        },
        { key: 'created_on', header: 'Created On', accessor: (r) => r.created_on, sortable: true, sortValue: (r) => r.created_on || '' },
        { key: 'merchant_id', header: 'Merchant', render: (r) => r.merchant_name, sortable: true, sortValue: (r) => r.merchant_name || '' },
        { key: 'payer_number', header: 'Payer Number', accessor: (r) => r.payer_number },
        { key: 'tx_merchant_ref', header: 'Merchant Ref', accessor: (r) => r.tx_merchant_ref },
        { key: 'status', header: 'Status', render: (r) => <Badge tone={statusTone(r.status)}>{r.status}</Badge>, sortable: true, sortValue: (r) => r.status || '' },
        { key: 'tx_type', header: 'Type', accessor: (r) => r.tx_type },
        { key: 'original_amount_formatted', header: 'Amount', numeric: true, render: (r) => "UGX " + (r.original_amount_formatted || ''), sortable: true, sortValue: (r) => Number(r.original_amount) || 0 },
        {
            key: 'actions', header: '', align: 'center',
            render: (row) => <Button variant="ghost" className="ios-btn--sm" onClick={() => openDetails(row)}>Details</Button>,
        },
    ];

    const metrics = [
        { label: 'Collections', value: formatAmount(summary.payIns), meta: 'Incoming payments', tone: 'info' },
        { label: 'Disbursements', value: formatAmount(summary.payOuts), meta: 'Outgoing payments', tone: 'info' },
        { label: 'Transactions', value: formatCount(summary.transactions), meta: 'Recorded transactions', tone: 'neutral' },
        { label: 'Failed', value: formatCount(summary.failedTransactions), meta: 'Require review', tone: numberValue(summary.failedTransactions) > 0 ? 'danger' : 'success' },
    ];

    const quickActions = [
        { label: 'Reconciliation', description: 'Review matching and exceptions', onClick: () => history.push('/bo/admin/reconciliation') },
        { label: 'Treasury / Float', description: 'Review provider and currency positions', onClick: () => history.push('/bo/admin/treasury') },
        { label: 'Payout approvals', description: 'Review maker-checker payout queue', onClick: () => history.push('/bo/admin/payoutapprovals') },
        { label: 'Provider health', description: 'Review credentials and certification', onClick: () => history.push('/bo/admin/providers-integrations') },
    ];

    return (
        <div className="cito-service-workspace">
            <WorkspaceMetricGrid>
                {metrics.map((metric) => <WorkspaceMetric key={metric.label} {...metric} />)}
            </WorkspaceMetricGrid>

            <WorkspaceGrid>
                <WorkspacePanel
                    eyebrow="Payments"
                    title="Transactions"
                    className="cito-workspace-panel--table"
                    actions={<span>{rows.length ? `${rows.length} loaded` : 'No rows loaded'}</span>}
                >
                    <div className="cito-panel-toolbar">
                        <div>
                            <Select id="tx-category" value={searchingValue.category} options={SEARCH_CATEGORIES} onValueChange={(v) => setSearchingValue((prev) => ({ ...prev, category: v }))} />
                        </div>
                        <div className="cito-panel-toolbar__grow">
                            <SearchField
                                value={searchingValue.value}
                                onValueChange={(v) => setSearchingValue((prev) => ({ ...prev, value: v }))}
                                onSubmit={(v) => handleSearch(v)}
                                placeholder="Search reference, merchant, status or amount"
                            />
                        </div>
                    </div>
                    <Table
                        columns={columns}
                        rows={rows}
                        rowKey={(row, i) => rowKeyFor(row, i)}
                        pageSize={PAGE_SIZE}
                        isRowSelected={(row) => (row.id != null ? selectedKeys.has(row.id) : false)}
                        emptyText="No transactions to display."
                    />
                </WorkspacePanel>

                <WorkspacePanel eyebrow="Navigate" title="Quick actions">
                    <WorkspaceQuickActions actions={quickActions} />
                    {summaryQuery.error ? <p className="cito-inline-note">Payment summary is currently unavailable. Transaction records remain authoritative for this view.</p> : null}
                </WorkspacePanel>
            </WorkspaceGrid>

            {recordTxDetailsDialog()}
            {renderResolveDialog()}
            <Messager ref={messagerRef}></Messager>
        </div>
    );
}

const ModuleTransactions = withRouter(ModuleTransactionsC);

export default ModuleTransactions;
