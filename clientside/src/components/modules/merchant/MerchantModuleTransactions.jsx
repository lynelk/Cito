import React, { useEffect, useRef, useState } from 'react';
import Messager from '../../StableMessager';
import PaymentRecoveryNotice from './PaymentRecoveryNotice';
import { withRouter } from '../../../shared/router/compat';
import common from "../../Common";
import strings from '../../locale';
import ReactExport from "../../../shared/export/ExcelExport";
import {
  Card, Toolbar, Table, Select, SearchField, Checkbox, Badge, Sheet, Button,
  TextField, TextArea, DateField, Icons,
} from '../../../ui';

import { useAuth } from '../../../shared/useAuth';
import {
  useMerchantTransactions,
  useAddPayInTransactionMutation,
  useLoaderSync,
  SessionExpiredError,
  AccessDeniedError,
  LegacyRequestError,
} from '../../../shared/api/hooks';

const ExcelFile = ReactExport.ExcelFile;
const ExcelSheet = ReactExport.ExcelFile.ExcelSheet;
const ExcelColumn = ReactExport.ExcelFile.ExcelColumn;

const SEARCH_CATEGORIES = [
  { value: 'all', label: 'All Fields' },
  { value: 'tx_type', label: 'Type' },
  { value: 'status', label: 'Status' },
  { value: 'original_amount', label: 'Amount' },
];

const PAGE_SIZE = 50;

function statusTone(s) {
  if (s === 'SUCCESSFUL') return 'success';
  if (s === 'FAILED') return 'danger';
  if (s === 'PENDING') return 'warning';
  return 'neutral';
}

const traceHtml = (value, pre) => ({
  __html: pre
    ? "<pre>" + common.encodeHTML(value || "") + "</pre>"
    : (value ? common.encodeHTML(value).replace(/\n/g, "<br/>") : ""),
});

function defaultSearchRules() {
  return {
    start_date: common.formatDate(common.getDateMonthsBefore(new Date(), 6)),
    end_date: common.formatDate(new Date()),
    status: "",
    tx_type: "",
  };
}

/** Mirrors `Table`'s own `rowKey` so selection state can be looked up by the same key. */
function rowKeyFor(row, index) {
  return row.id ?? `${row.tx_gateway_ref}-${index}`;
}

function MerchantModuleTransactionsC(props) {
    const { loader, history } = props;
    const messagerRef = useRef(null);

    const { hasPrivilege } = useAuth('merchant');
    // Mirrors the old componentDidMount access check: evaluated once, like the
    // class component's constructor-time `hasAccess: false` + one-time mount check.
    const [accessGranted] = useState(() => hasPrivilege('ACCESS_TRANSACTION_LOG'));
    const [serverDeniedAccess, setServerDeniedAccess] = useState(false);
    const hasAccess = accessGranted && !serverDeniedAccess;

    const [searchingValue, setSearchingValue] = useState({ value: "", category: "all" });
    // Only updated when a search is actually submitted (matches the original,
    // where picking a category alone, or editing the search dialog fields,
    // didn't re-fetch until Enter/submit or the dialog's "Go" button).
    const [committedSearch, setCommittedSearch] = useState({ value: "", category: "all" });
    const [searchRulesForm, setSearchRulesForm] = useState(defaultSearchRules);
    const [committedSearchRules, setCommittedSearchRules] = useState(defaultSearchRules);
    const [searchOpen, setSearchOpen] = useState(false);

    const [selectedKeys, setSelectedKeys] = useState(() => new Set());
    const [txDetailsRow, setTxDetailsRow] = useState({});
    const [detailsOpen, setDetailsOpen] = useState(false);
    const [payInOpen, setPayInOpen] = useState(false);
    const [payInForm, setPayInForm] = useState({ account: "", tx_description: "", amount: "0" });
    const [payInErrors, setPayInErrors] = useState({});

    const transactionsQuery = useMerchantTransactions(committedSearch, committedSearchRules, PAGE_SIZE, hasAccess);
    const addPayInMutation = useAddPayInTransactionMutation();

    useLoaderSync(loader, transactionsQuery.isFetching || addPayInMutation.isPending);

    useEffect(() => {
        if (!accessGranted) {
            messagerRef.current?.alert({ title: "Access denied!", icon: "info", msg: "You are not allowed access to this section." });
        }
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    function sessionExpired() {
        messagerRef.current?.alert({ title: "Session Expired!", icon: "info", msg: "Your session expired", result: () => history.push("/") });
    }

    // Resets whenever a fresh successful fetch lands, the same way the old
    // `getData()` success handler always replaced `data` (and its `selected`
    // flags) wholesale via `setState({ data: res.data, ..., allChecked: false })`.
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
        messagerRef.current?.alert({ title: "Error " + code, icon: "error", msg: error.message });
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [transactionsQuery.error]);

    function submitPayment(data) {
        messagerRef.current?.confirm({
            title: "Confirm to Initiate inbound Payment", icon: "info",
            msg: "Are you sure you want to continue to initiate a mobile money payment on " + data.account + "?",
            result: (r) => {
                if (!r) return;
                addPayInMutation.mutate(data, {
                    onSuccess: (res) => {
                        setPayInOpen(false);
                        setPayInForm({ account: "", tx_description: "", amount: "0" });
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

    // A fetch happens at exactly three points, same as the old class:
    // mount, Enter/submit in the search field, and the search dialog's "Go"
    // button — and each one used the *entire* live `state` (searchingValue
    // AND search_rules together), not just whichever piece just changed.
    function handleSearch(value) {
        const next = { ...searchingValue, value };
        setSearchingValue(next);
        setCommittedSearch(next);
        setCommittedSearchRules(searchRulesForm);
    }

    function handleSearchFormChange(name, value) {
        setSearchRulesForm((prev) => ({ ...prev, [name]: value }));
    }

    function clearSearch() {
        setSearchRulesForm(defaultSearchRules());
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

    function openPayIn() {
        setPayInOpen(true);
        setPayInErrors({});
        setPayInForm({ account: "", tx_description: "", amount: "0" });
    }

    function payInChange(name, value) {
        setPayInForm((prev) => ({ ...prev, [name]: value }));
    }

    function savePayIn() {
        const f = payInForm;
        const errors = {};
        if (!f.account) errors.account = 'Account is required';
        if (!f.tx_description) errors.tx_description = 'Description is required';
        if (f.amount === '' || Number.isNaN(Number(f.amount))) errors.amount = 'Enter a valid amount';
        setPayInErrors(errors);
        if (Object.keys(errors).length === 0) submitPayment(f);
    }

    function searchDialog() {
        const s = searchRulesForm;
        return (
            <Sheet
                open={searchOpen}
                onClose={() => setSearchOpen(false)}
                title="Search"
                size="sm"
                footer={<>
                    <Button variant="ghost" className="ios-btn--sm" onClick={() => clearSearch()}>{strings.clear}</Button>
                    <Button variant="ghost" className="ios-btn--sm" onClick={() => setSearchOpen(false)}>{strings.close}</Button>
                    <Button variant="primary" className="ios-btn--sm" onClick={() => { setSearchOpen(false); setCommittedSearchRules(searchRulesForm); setCommittedSearch(searchingValue); }}>{strings.go}</Button>
                </>}
            >
                <div className="ios-form">
                    <DateField id="tx-start" label="Start Date" kind="date" value={s.start_date} onValueChange={(v) => handleSearchFormChange('start_date', v)} />
                    <DateField id="tx-end" label="End Date" kind="date" value={s.end_date} onValueChange={(v) => handleSearchFormChange('end_date', v)} />
                    <TextField id="tx-status" label="Status" value={s.status} onValueChange={(v) => handleSearchFormChange('status', v)} />
                    <TextField id="tx-type" label="Type" value={s.tx_type} onValueChange={(v) => handleSearchFormChange('tx_type', v)} />
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
        return (
            <Sheet
                open={detailsOpen}
                onClose={() => setDetailsOpen(false)}
                title={"Transaction Details: " + (r.merchant_name || '')}
                size="lg"
                footer={<Button variant="ghost" className="ios-btn--sm" onClick={() => setDetailsOpen(false)}>{strings.close}</Button>}
            >
                {detailRow('Merchant Name', r.merchant_name)}
                {detailRow('Merchant number', r.merchant_number)}
                {detailRow('Gateway ID', r.gateway_id)}
                {detailRow('Status', <Badge tone={statusTone(r.status)}>{r.status}</Badge>)}
                <PaymentRecoveryNotice status={r.status} />
                {detailRow('Amount', "UGX " + (r.original_amount_formatted || ''))}
                {detailRow('Merchant Reference', r.tx_merchant_ref)}
                {detailRow('Network Ref', r.tx_gateway_ref)}
                {detailRow('Payer/Payee Number', r.payer_number)}
                {detailRow('Merchant Description', traceBlock(r.tx_merchant_description, false))}
                {detailRow('Our Description', traceBlock(r.tx_description, false))}
                {detailRow('Charges', "UGX " + (r.charges_formatted || ''))}
                {detailRow('Created On', r.created_on)}
                {detailRow('Callback Trace', traceBlock(r.callback_trace, true))}
            </Sheet>
        );
    }

    function payInDialog() {
        const f = payInForm;
        const e = payInErrors;
        return (
            <Sheet
                open={payInOpen}
                onClose={() => setPayInOpen(false)}
                title={strings.add_payin}
                size="sm"
                footer={<>
                    <Button variant="ghost" className="ios-btn--sm" onClick={() => setPayInOpen(false)}>Close</Button>
                    <Button variant="primary" className="ios-btn--sm" onClick={() => savePayIn()}>{strings.submit}</Button>
                </>}
            >
                <div className="ios-form">
                    <TextField id="payin-account" label="Account (e.g 256772123456)" value={f.account} invalid={Boolean(e.account)} onValueChange={(v) => payInChange('account', v)} />
                    {e.account ? <span style={{ color: 'var(--ios-danger)', fontSize: 'var(--ios-fs-caption)' }}>{e.account}</span> : null}
                    <TextField id="payin-amount" label="Amount" value={f.amount} invalid={Boolean(e.amount)} onValueChange={(v) => payInChange('amount', v)} />
                    {e.amount ? <span style={{ color: 'var(--ios-danger)', fontSize: 'var(--ios-fs-caption)' }}>{e.amount}</span> : null}
                    <TextArea id="payin-desc" label="Description" rows={3} value={f.tx_description} invalid={Boolean(e.tx_description)} onValueChange={(v) => payInChange('tx_description', v)} />
                    {e.tx_description ? <span style={{ color: 'var(--ios-danger)', fontSize: 'var(--ios-fs-caption)' }}>{e.tx_description}</span> : null}
                </div>
            </Sheet>
        );
    }

    if (!hasAccess) {
        return <div><Messager ref={messagerRef}></Messager></div>;
    }

    const rows = transactionsQuery.data?.rows ?? [];
    const allChecked = rows.every((row, i) => selectedKeys.has(rowKeyFor(row, i)));

    const columns = [
        {
            key: 'ck', width: 44,
            header: <Checkbox checked={allChecked} onCheckedChange={(c) => handleAllCheck(c, rows)} />,
            render: (row, index) => <Checkbox checked={selectedKeys.has(rowKeyFor(row, index))} onCheckedChange={(c) => handleRowCheck(rowKeyFor(row, index), c)} />,
        },
        { key: 'created_on', header: 'Created On', accessor: (r) => r.created_on, sortable: true, sortValue: (r) => r.created_on || '' },
        { key: 'merchant_id', header: 'Network ID', render: (r) => r.tx_gateway_ref },
        { key: 'payer_number', header: 'Payer Number', accessor: (r) => r.payer_number },
        { key: 'status', header: 'Status', render: (r) => <Badge tone={statusTone(r.status)}>{r.status}</Badge>, sortable: true, sortValue: (r) => r.status || '' },
        { key: 'tx_type', header: 'Type', accessor: (r) => r.tx_type },
        { key: 'original_amount_formatted', header: 'Amount', numeric: true, render: (r) => "UGX " + (r.original_amount_formatted || ''), sortable: true, sortValue: (r) => Number(r.original_amount) || 0 },
        {
            key: 'actions', header: 'Actions', align: 'center',
            render: (row) => <Button variant="ghost" className="ios-btn--sm" onClick={() => { setTxDetailsRow(row); setDetailsOpen(true); }}>Details</Button>,
        },
    ];

    return (
        <Card flush>
            <div style={{ padding: 'var(--ios-space-4)' }}>
                <Toolbar>
                    <Button variant="primary" className="ios-btn--sm" onClick={() => openPayIn()}>
                        <Icons.PlusIcon size={16} />{strings.add_payin}
                    </Button>
                    <Toolbar.Spacer />
                    <div style={{ minWidth: 150 }}>
                        <Select id="mtx-category" value={searchingValue.category} options={SEARCH_CATEGORIES} onValueChange={(v) => setSearchingValue((prev) => ({ ...prev, category: v }))} />
                    </div>
                    <SearchField
                        value={searchingValue.value}
                        onValueChange={(v) => setSearchingValue((prev) => ({ ...prev, value: v }))}
                        onSubmit={(v) => handleSearch(v)}
                        placeholder={strings.search_merchant}
                    />
                    <Button variant="ghost" className="ios-btn--sm" onClick={() => setSearchOpen(true)}>
                        <Icons.SearchIcon size={16} />{strings.search}
                    </Button>
                    <Download data={rows} />
                </Toolbar>
            </div>
            <Table
                columns={columns}
                rows={rows}
                rowKey={(row, i) => rowKeyFor(row, i)}
                pageSize={PAGE_SIZE}
                isRowSelected={(row) => (row.id != null ? selectedKeys.has(row.id) : false)}
                emptyText="No transactions to display."
            />
            {searchDialog()}
            {recordTxDetailsDialog()}
            {payInDialog()}
            <Messager ref={messagerRef}></Messager>
        </Card>
    );
}

class Download extends React.Component {
    render() {
        return (
            <ExcelFile
                filename="Account_Transactions"
                ref={ref => this.excelRef = ref}
                element={
                    <Button variant="ghost" className="ios-btn--sm" onClick={() => this.excelRef.download()}>
                        <Icons.DownloadIcon size={16} />{strings.download}
                    </Button>
                }>
                <ExcelSheet data={this.props.data} name="Transactions">
                    <ExcelColumn label="Date time" value="created_on" />
                    <ExcelColumn label="Network ID" value="tx_gateway_ref" />
                    <ExcelColumn label="Payer Number" value="payer_number" />
                    <ExcelColumn label="Status" value="status" />
                    <ExcelColumn label="Type" value={"tx_type"} />
                    <ExcelColumn label="Merchant Reference" value="tx_merchant_ref" />
                    <ExcelColumn label="Description" value="tx_merchant_description" />
                    <ExcelColumn label="Amount" value="original_amount" />
                    <ExcelColumn label="Charges" value="charges" />
                </ExcelSheet>
            </ExcelFile>
        );
    }
}

const MerchantModuleTransactions = withRouter(MerchantModuleTransactionsC);

export default MerchantModuleTransactions;
