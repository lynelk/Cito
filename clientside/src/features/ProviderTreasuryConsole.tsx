import { filterProviderRows, filterProviderAdjustments } from './providerScope';
import { FormEvent, useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Badge, Button, Card, Section, Table, Toolbar } from '../ui';
import { useAuth } from '../shared/useAuth';
import {
  PlatformCredential,
  ProviderLiveTest,
  SharedProviderEntitlement,
  TreasuryAccount,
  TreasuryAdjustment,
  useApprovePlatformCredential,
  useApproveProviderLiveTest,
  useApproveSharedEntitlement,
  useApproveTreasuryAdjustment,
  useCreateSharedEntitlement,
  useCreateProviderLiveTest,
  useCreateTreasuryAdjustment,
  usePlatformCredentials,
  useProviderLiveTests,
  useProviderTestMerchants,
  useReconcileTreasuryAccount,
  useRefreshProviderBalance,
  useRejectSharedEntitlement,
  useRejectTreasuryAdjustment,
  useSavePlatformCredential,
  useSetLowFloatThreshold,
  useSharedProviderEntitlements,
  useTreasuryAccounts,
  useTreasuryAdjustments,
} from '../shared/api/providerTreasury';

const fieldStyle: React.CSSProperties = {
  minWidth: 150,
  padding: '10px 12px',
  border: '1px solid var(--ios-separator)',
  borderRadius: 10,
  background: 'var(--ios-card)',
};

const gridStyle: React.CSSProperties = {
  display: 'grid',
  gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))',
  gap: 12,
  alignItems: 'end',
};

function money(value: unknown, currency?: string): string {
  const n = Number(value ?? 0);
  return `${currency ?? ''} ${Number.isFinite(n) ? n.toLocaleString(undefined, { maximumFractionDigits: 2 }) : '0'}`.trim();
}

function statusTone(status?: string): 'neutral' | 'success' | 'warning' | 'danger' | 'info' {
  const s = (status ?? '').toUpperCase();
  if (s === 'ACTIVE' || s === 'POSTED' || s === 'MATCHED' || s === 'SUCCEEDED' || s === 'AVAILABLE') return 'success';
  if (s === 'PENDING' || s === 'CONFIGURED' || s === 'UNRECONCILED' || s === 'PENDING_APPROVAL' || s === 'PENDING_PROVIDER' || s === 'PROCESSING') return 'warning';
  if (s === 'REJECTED' || s === 'DISABLED' || s === 'VARIANCE') return 'danger';
  return 'neutral';
}

function age(seconds?: number | null): string {
  if (seconds == null) return 'Never synchronized';
  if (seconds < 60) return `${seconds}s ago`;
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m ago`;
  return `${Math.floor(seconds / 3600)}h ago`;
}

export default function ProviderTreasuryConsole({ channelScope }: { channelScope?: string } = {}): React.ReactElement {
  const navigate = useNavigate();
  const { isAuthenticated } = useAuth('admin');
  const accountsQuery = useTreasuryAccounts();
  const adjustmentsQuery = useTreasuryAdjustments();
  const entitlementsQuery = useSharedProviderEntitlements();
  const credentialsQuery = usePlatformCredentials();
  const testMerchants = useProviderTestMerchants();
  const liveTestsQuery = useProviderLiveTests();
  const createAdjustment = useCreateTreasuryAdjustment();
  const approveAdjustment = useApproveTreasuryAdjustment();
  const rejectAdjustment = useRejectTreasuryAdjustment();
  const createEntitlement = useCreateSharedEntitlement();
  const approveEntitlement = useApproveSharedEntitlement();
  const rejectEntitlement = useRejectSharedEntitlement();
  const saveCredential = useSavePlatformCredential();
  const approveCredential = useApprovePlatformCredential();
  const setThreshold = useSetLowFloatThreshold();
  const reconcile = useReconcileTreasuryAccount();
  const refreshProviderBalance = useRefreshProviderBalance();
  const createLiveTest = useCreateProviderLiveTest();
  const approveLiveTest = useApproveProviderLiveTest();

  const accounts = { ...accountsQuery, data: filterProviderRows(accountsQuery.data, channelScope) };
  const credentials = { ...credentialsQuery, data: filterProviderRows(credentialsQuery.data, channelScope) };
  const entitlements = { ...entitlementsQuery, data: filterProviderRows(entitlementsQuery.data, channelScope) };
  const liveTests = { ...liveTestsQuery, data: filterProviderRows(liveTestsQuery.data, channelScope) };
  const adjustments = { ...adjustmentsQuery, data: filterProviderAdjustments(adjustmentsQuery.data, accounts.data, channelScope) };
  const [notice, setNotice] = useState('');
  const [adjustment, setAdjustment] = useState({ adjustmentType: 'CREDIT', sourceAccountId: '', destinationAccountId: '', amount: '', reason: '', externalReference: '', evidenceReference: '', valueDate: new Date().toISOString().slice(0, 10) });
  const [entitlement, setEntitlement] = useState({ merchantId: '', channelCode: channelScope || 'airtel_money', environment: 'PRODUCTION', countryCode: 'UG', currencyCode: 'UGX', operation: 'COLLECT', perTransactionLimit: '', dailyLimit: '', notes: '' });
  const [credential, setCredential] = useState({
    channelCode: channelScope || 'airtel_money', environment: 'PRODUCTION', countryCode: 'UG', currencyCode: 'UGX',
    collectUrl: '', payoutUrl: '', authHeaderName: '', authHeaderValue: '', tokenAlias: '',
    baseUrl: '', targetEnvironment: '', baseCurrency: 'UGX', callbackHost: '', callbackUrl: '',
    collectionApiUser: '', collectionApiKey: '', collectionSubscriptionKey: '', collectionSecondarySubscriptionKey: '',
    disbursementApiUser: '', disbursementApiKey: '', disbursementSubscriptionKey: '', disbursementSecondarySubscriptionKey: '',
    airtelClientId: '', airtelClientSecret: '', airtelApiPin: '', airtelPublicKey: '',
    airtelCountry: 'UG', airtelCurrency: 'UGX', tokenPath: '/auth/oauth2/token',
    collectionPath: '/merchant/v2/payments/', payoutPath: '/standard/v2/disbursements/',
    balancePath: '/standard/v2/users/balance',
  });
  const [filters, setFilters] = useState({ environment: '', channel: channelScope || '', currency: '', role: '', state: '' });
  const [liveTest, setLiveTest] = useState({
    merchantId: '', channelCode: channelScope || 'mtn_momo', environment: 'SANDBOX', countryCode: 'UG',
    currencyCode: 'UGX', operation: 'COLLECT', amount: '', party: '', mfaCode: '',
    confirmProduction: false, idempotencyKey: crypto.randomUUID() as string,
  });
  const [approvalMfaCode, setApprovalMfaCode] = useState('');

  useEffect(() => {
    if (!isAuthenticated) navigate('/admin');
  }, [isAuthenticated, navigate]);

  const allErrors = [accounts.error, adjustments.error, entitlements.error, credentials.error, testMerchants.error, liveTests.error].filter(Boolean) as Error[];
  const firstError = allErrors[0]?.message;
  useEffect(() => {
    if (firstError) setNotice(firstError);
  }, [firstError]);

  const pendingAdjustments = useMemo(() => (adjustments.data ?? []).filter((row) => row.status === 'PENDING'), [adjustments.data]);
  const pendingEntitlements = useMemo(() => (entitlements.data ?? []).filter((row) => row.status === 'PENDING'), [entitlements.data]);
  const filteredAccounts = useMemo(() => (accounts.data ?? []).filter((row) =>
    (!filters.environment || row.environment === filters.environment)
    && (!filters.channel || row.channelCode === filters.channel)
    && (!filters.currency || row.currencyCode === filters.currency)
    && (!filters.role || row.accountRole === filters.role)
    && (!filters.state || row.reconciliationState === filters.state)
  ), [accounts.data, filters]);
  const totals = useMemo(() => filteredAccounts.reduce((summary, row) => ({
    book: summary.book + Number(row.bookBalance || 0),
    available: summary.available + Number(row.availableBalance || 0),
    reserved: summary.reserved + Number(row.reservedBalance || 0),
    pending: summary.pending + Number(row.pendingOutgoingBalance || 0) + Number(row.pendingIncomingBalance || 0),
  }), { book: 0, available: 0, reserved: 0, pending: 0 }), [filteredAccounts]);

  const submitAdjustment = (event: FormEvent) => {
    event.preventDefault();
    const payload: Record<string, unknown> = {
      adjustmentType: adjustment.adjustmentType,
      amount: adjustment.amount,
      reason: adjustment.reason,
      externalReference: adjustment.externalReference,
      evidenceReference: adjustment.evidenceReference,
      valueDate: adjustment.valueDate,
    };
    if (adjustment.sourceAccountId) payload.sourceAccountId = Number(adjustment.sourceAccountId);
    if (adjustment.destinationAccountId) payload.destinationAccountId = Number(adjustment.destinationAccountId);
    createAdjustment.mutate(payload, { onSuccess: () => setNotice('Treasury adjustment submitted for independent approval.'), onError: (e) => setNotice((e as Error).message) });
  };

  const submitEntitlement = (event: FormEvent) => {
    event.preventDefault();
    createEntitlement.mutate({
      ...entitlement,
      channelCode: channelScope || entitlement.channelCode,
      merchantId: Number(entitlement.merchantId),
      perTransactionLimit: entitlement.perTransactionLimit || null,
      dailyLimit: entitlement.dailyLimit || null,
    }, { onSuccess: () => setNotice('Shared-provider entitlement submitted for independent approval.'), onError: (e) => setNotice((e as Error).message) });
  };

  const submitCredential = (event: FormEvent) => {
    event.preventDefault();
    const providerCredentials: Record<string, string> = credential.channelCode === 'mtn_momo'
      ? {
        baseUrl: credential.baseUrl,
        targetEnvironment: credential.targetEnvironment,
        baseCurrency: credential.baseCurrency,
        callbackHost: credential.callbackHost,
        callbackUrl: credential.callbackUrl,
        collectionApiUser: credential.collectionApiUser,
        collectionApiKey: credential.collectionApiKey,
        collectionSubscriptionKey: credential.collectionSubscriptionKey,
        disbursementApiUser: credential.disbursementApiUser,
        disbursementApiKey: credential.disbursementApiKey,
        disbursementSubscriptionKey: credential.disbursementSubscriptionKey,
      }
      : credential.channelCode === 'airtel_open_api'
        ? {
          baseUrl: credential.baseUrl,
          clientId: credential.airtelClientId,
          clientSecret: credential.airtelClientSecret,
          country: credential.airtelCountry,
          currency: credential.airtelCurrency,
          apiPin: credential.airtelApiPin,
          publicKey: credential.airtelPublicKey,
          tokenPath: credential.tokenPath,
          collectionPath: credential.collectionPath,
          payoutPath: credential.payoutPath,
          balancePath: credential.balancePath,
        }
        : { collectUrl: credential.collectUrl, payoutUrl: credential.payoutUrl };
    if (credential.channelCode === 'mtn_momo') {
      if (credential.collectionSecondarySubscriptionKey) providerCredentials.collectionSecondarySubscriptionKey = credential.collectionSecondarySubscriptionKey;
      if (credential.disbursementSecondarySubscriptionKey) providerCredentials.disbursementSecondarySubscriptionKey = credential.disbursementSecondarySubscriptionKey;
    } else if (credential.channelCode !== 'airtel_open_api') {
      if (credential.authHeaderName) providerCredentials.authHeaderName = credential.authHeaderName;
      if (credential.authHeaderValue) providerCredentials.authHeaderValue = credential.authHeaderValue;
      if (credential.tokenAlias) providerCredentials.tokenAlias = credential.tokenAlias;
    }
    saveCredential.mutate({
      channelCode: channelScope || credential.channelCode,
      environment: credential.environment,
      countryCode: credential.countryCode,
      currencyCode: credential.currencyCode,
      credentials: providerCredentials,
    }, { onSuccess: () => setNotice('Platform credential saved in encrypted form. A different operator must approve it.'), onError: (e) => setNotice((e as Error).message) });
  };

  const submitLiveTest = (event: FormEvent) => {
    event.preventDefault();
    createLiveTest.mutate({
      ...liveTest,
      channelCode: channelScope || liveTest.channelCode,
      merchantId: Number(liveTest.merchantId),
    }, {
      onSuccess: (result) => {
        setNotice(result.operation === 'PAYOUT'
          ? 'Payout test recorded. A different administrator must approve it before execution.'
          : `Collection test submitted with reference ${result.testReference}.`);
        setLiveTest({ ...liveTest, amount: '', party: '', mfaCode: '', confirmProduction: false, idempotencyKey: crypto.randomUUID() });
      },
      onError: (e) => setNotice((e as Error).message),
    });
  };

  const accountColumns = [
    { key: 'provider', header: 'Provider', render: (row: TreasuryAccount) => <strong>{row.channelCode}</strong> },
    { key: 'account', header: 'Account', render: (row: TreasuryAccount) => <span>{row.accountRole}{row.prefundRequired === 'YES' ? ' · prefund' : ''}</span> },
    { key: 'scope', header: 'Scope', render: (row: TreasuryAccount) => `${row.environment} · ${row.countryCode} · ${row.currencyCode}` },
    { key: 'book', header: 'Book', render: (row: TreasuryAccount) => money(row.bookBalance, row.currencyCode) },
    { key: 'reserved', header: 'Reserved', render: (row: TreasuryAccount) => money(row.reservedBalance, row.currencyCode) },
    { key: 'pending', header: 'Pending out / in', render: (row: TreasuryAccount) => `${money(row.pendingOutgoingBalance, row.currencyCode)} / ${money(row.pendingIncomingBalance, row.currencyCode)}` },
    { key: 'available', header: 'Available', render: (row: TreasuryAccount) => <Badge tone={row.lowFloat ? 'danger' : 'success'}>{money(row.availableBalance, row.currencyCode)}</Badge> },
    { key: 'reported', header: 'Provider reported', render: (row: TreasuryAccount) => row.providerBalanceAvailable
      ? <span>{money(row.providerReportedBalance, row.currencyCode)}<br /><small>{age(row.providerBalanceAgeSeconds)}</small></span>
      : <span><Badge tone="warning">Unavailable</Badge><br /><small>{row.providerBalanceMessage || 'Not synchronized'}</small></span> },
    { key: 'recon', header: 'Reconciliation', render: (row: TreasuryAccount) => <Badge tone={statusTone(row.reconciliationState)}>{row.reconciliationState}</Badge> },
    {
      key: 'controls', header: 'Controls', render: (row: TreasuryAccount) => (
        <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
          {row.accountRole !== 'MASTER' ? <Button variant="ghost" className="ios-btn--sm" onClick={() => refreshProviderBalance.mutate(row.id, { onSuccess: (result) => setNotice(result.providerBalanceStatus === 'AVAILABLE' ? 'Provider balance synchronized.' : result.providerBalanceMessage || 'Provider balance is unavailable.'), onError: (e) => setNotice((e as Error).message) })}>Sync provider</Button> : null}
          <Button variant="ghost" className="ios-btn--sm" onClick={() => {
            const raw = window.prompt('Low-float threshold', String(row.lowFloatThreshold ?? 0));
            if (raw != null) setThreshold.mutate({ id: row.id, lowFloatThreshold: Number(raw) }, { onError: (e) => setNotice((e as Error).message) });
          }}>Threshold</Button>
          <Button variant="ghost" className="ios-btn--sm" onClick={() => {
            const providerReportedBalance = window.prompt('Provider-reported balance');
            const statementReference = window.prompt('Provider statement/reference');
            if (providerReportedBalance && statementReference) reconcile.mutate({ id: row.id, body: { providerReportedBalance, statementReference } }, { onError: (e) => setNotice((e as Error).message) });
          }}>Reconcile</Button>
        </div>
      ),
    },
  ];

  const adjustmentColumns = [
    { key: 'type', header: 'Type', accessor: (row: TreasuryAdjustment) => row.adjustmentType },
    { key: 'accounts', header: 'From → To', render: (row: TreasuryAdjustment) => `${row.sourceAccountId ?? 'External'} → ${row.destinationAccountId ?? 'External'}` },
    { key: 'amount', header: 'Amount', accessor: (row: TreasuryAdjustment) => row.amount },
    { key: 'reference', header: 'Reference', accessor: (row: TreasuryAdjustment) => row.externalReference },
    { key: 'status', header: 'Status', render: (row: TreasuryAdjustment) => <Badge tone={statusTone(row.status)}>{row.status}</Badge> },
    { key: 'maker', header: 'Maker', accessor: (row: TreasuryAdjustment) => row.requestedBy },
    {
      key: 'actions', header: 'Checker action', render: (row: TreasuryAdjustment) => row.status !== 'PENDING' ? '—' : (
        <div style={{ display: 'flex', gap: 6 }}>
          <Button variant="primary" className="ios-btn--sm" onClick={() => approveAdjustment.mutate(row.id, { onError: (e) => setNotice((e as Error).message) })}>Approve</Button>
          <Button variant="ghost" className="ios-btn--sm" onClick={() => rejectAdjustment.mutate(row.id, { onError: (e) => setNotice((e as Error).message) })}>Reject</Button>
        </div>
      ),
    },
  ];

  const entitlementColumns = [
    { key: 'merchant', header: 'Merchant', render: (row: SharedProviderEntitlement) => <span><strong>{row.merchantName || row.merchantId}</strong><br /><small>{row.merchantNumber}</small></span> },
    { key: 'channel', header: 'Channel', accessor: (row: SharedProviderEntitlement) => row.channelCode },
    { key: 'scope', header: 'Scope', render: (row: SharedProviderEntitlement) => `${row.operation} · ${row.environment} · ${row.countryCode}/${row.currencyCode}` },
    { key: 'limits', header: 'Per tx / used / day', render: (row: SharedProviderEntitlement) => `${row.perTransactionLimit ?? '∞'} / ${row.usedToday ?? 0} / ${row.dailyLimit ?? '∞'}` },
    { key: 'status', header: 'Status', render: (row: SharedProviderEntitlement) => <Badge tone={statusTone(row.status)}>{row.status}</Badge> },
    { key: 'maker', header: 'Maker', accessor: (row: SharedProviderEntitlement) => row.requestedBy ?? '—' },
    {
      key: 'actions', header: 'Checker action', render: (row: SharedProviderEntitlement) => row.status !== 'PENDING' ? '—' : (
        <div style={{ display: 'flex', gap: 6 }}>
          <Button variant="primary" className="ios-btn--sm" onClick={() => approveEntitlement.mutate(row.id, { onError: (e) => setNotice((e as Error).message) })}>Approve</Button>
          <Button variant="ghost" className="ios-btn--sm" onClick={() => rejectEntitlement.mutate(row.id, { onError: (e) => setNotice((e as Error).message) })}>Reject</Button>
        </div>
      ),
    },
  ];

  const credentialColumns = [
    { key: 'channel', header: 'Channel', accessor: (row: PlatformCredential) => row.channelCode },
    { key: 'scope', header: 'Scope', render: (row: PlatformCredential) => `${row.environment} · ${row.countryCode}/${row.currencyCode}` },
    { key: 'status', header: 'Status', render: (row: PlatformCredential) => <Badge tone={statusTone(row.status)}>{row.status}</Badge> },
    { key: 'masked', header: 'Credential material', render: (row: PlatformCredential) => Object.entries(row.credentials ?? {}).map(([k, v]) => `${k}=${v}`).join(' · ') || '—' },
    { key: 'maker', header: 'Editor', accessor: (row: PlatformCredential) => row.updatedBy ?? '—' },
    { key: 'checker', header: 'Approver', accessor: (row: PlatformCredential) => row.approvedBy ?? '—' },
    { key: 'action', header: 'Action', render: (row: PlatformCredential) => row.status === 'CONFIGURED' ? <Button variant="primary" className="ios-btn--sm" onClick={() => approveCredential.mutate(row.id, { onError: (e) => setNotice((e as Error).message) })}>Approve</Button> : '—' },
  ];

  const liveTestColumns = [
    { key: 'reference', header: 'Reference', render: (row: ProviderLiveTest) => <span><strong>{row.testReference}</strong><br /><small>{row.idempotencyKey}</small></span> },
    { key: 'merchant', header: 'Merchant', render: (row: ProviderLiveTest) => <span>{row.merchantName}<br /><small>{row.merchantNumber}</small></span> },
    { key: 'scope', header: 'Test', render: (row: ProviderLiveTest) => `${row.operation} · ${row.channelCode} · ${row.environment}` },
    { key: 'amount', header: 'Amount / party', render: (row: ProviderLiveTest) => <span>{money(row.amount, row.currencyCode)}<br /><small>{row.partyMask}</small></span> },
    { key: 'status', header: 'Live status', render: (row: ProviderLiveTest) => <span><Badge tone={statusTone(row.status)}>{row.status}</Badge>{row.treasuryStatus ? <><br /><small>Treasury: {row.treasuryStatus}</small></> : null}</span> },
    { key: 'timeline', header: 'Latest update', render: (row: ProviderLiveTest) => {
      const latest = row.events?.[row.events.length - 1];
      return latest ? <span>{latest.eventType}<br /><small>{latest.message}</small></span> : '—';
    } },
    { key: 'maker', header: 'Maker / checker', render: (row: ProviderLiveTest) => `${row.requestedBy}${row.approvedBy ? ` / ${row.approvedBy}` : ''}` },
    { key: 'action', header: 'Action', render: (row: ProviderLiveTest) => row.status === 'PENDING_APPROVAL'
      ? <Button variant="primary" className="ios-btn--sm" disabled={!approvalMfaCode || approveLiveTest.isPending} onClick={() => approveLiveTest.mutate({ id: row.id, body: { mfaCode: approvalMfaCode, confirmProduction: row.environment === 'PRODUCTION' } }, { onSuccess: () => { setApprovalMfaCode(''); setNotice('Payout test approved and submitted to the provider.'); }, onError: (e) => setNotice((e as Error).message) })}>Approve & execute</Button>
      : '—' },
  ];

  return (
    <div style={{ padding: 'var(--ios-space-6)' }}>
      <Toolbar>
        <div>
          <h2 style={{ margin: 0 }}>Provider Treasury & Shared Channels</h2>
          <p style={{ margin: '4px 0 0', color: 'var(--ios-secondary)' }}>CPay-owned Airtel, MTN and Safaricom credentials, float, merchant permissions and reconciliation.</p>
        </div>
        <Toolbar.Spacer />
        <Button variant="ghost" onClick={() => navigate('/admin/operations')}>Operations</Button>
      </Toolbar>

      {notice ? <Card><strong>{notice}</strong></Card> : null}

      <Section title="Provider Float Accounts">
        <Card>
          <div style={gridStyle}>
            <label>Environment<select style={fieldStyle} value={filters.environment} onChange={(e) => setFilters({ ...filters, environment: e.target.value })}><option value="">All</option><option>PRODUCTION</option><option>SANDBOX</option></select></label>
            <label>Provider<select disabled={Boolean(channelScope)} style={fieldStyle} value={filters.channel} onChange={(e) => setFilters({ ...filters, channel: e.target.value })}><option value="">All</option><option value="mtn_momo">MTN MoMo</option><option value="airtel_open_api">Airtel Open API</option><option value="airtel_money">Airtel Money</option><option value="safaricom_mpesa">Safaricom M-Pesa</option></select></label>
            <label>Currency<input style={fieldStyle} value={filters.currency} placeholder="All" onChange={(e) => setFilters({ ...filters, currency: e.target.value.toUpperCase() })} /></label>
            <label>Account role<select style={fieldStyle} value={filters.role} onChange={(e) => setFilters({ ...filters, role: e.target.value })}><option value="">All</option><option>MASTER</option><option>COLLECTION</option><option>DISBURSEMENT</option></select></label>
            <label>Reconciliation<select style={fieldStyle} value={filters.state} onChange={(e) => setFilters({ ...filters, state: e.target.value })}><option value="">All</option><option>MATCHED</option><option>VARIANCE</option><option>UNRECONCILED</option></select></label>
            <Button variant="ghost" onClick={() => accounts.refetch()}>Refresh balances</Button>
          </div>
          <div style={{ ...gridStyle, marginTop: 16 }}>
            <Card><small>Book balance</small><h3>{money(totals.book)}</h3></Card>
            <Card><small>Available</small><h3>{money(totals.available)}</h3></Card>
            <Card><small>Reserved</small><h3>{money(totals.reserved)}</h3></Card>
            <Card><small>Pending movement</small><h3>{money(totals.pending)}</h3></Card>
          </div>
          <p style={{ color: 'var(--ios-secondary)' }}>Totals are grouped across the selected scopes; use a single currency filter before treating them as a monetary total. “Unavailable” means CPay has no synchronized provider figure—it never means zero.</p>
        </Card>
        <Card flush><Table<TreasuryAccount> columns={accountColumns} rows={filteredAccounts} rowKey={(row) => row.id} emptyText="No provider treasury accounts match these filters." /></Card>
      </Section>

      <Section title="Live Collection & Disbursement Tests">
        <Card>
          <p style={{ color: 'var(--ios-secondary)' }}>Tests always use CPay-managed provider credentials. Production tests can move real money, require MFA, and are capped by the configured test limit. Payouts also require approval by a different administrator.</p>
          <form onSubmit={submitLiveTest} style={gridStyle}>
            <label>Merchant<select required style={fieldStyle} value={liveTest.merchantId} onChange={(e) => setLiveTest({ ...liveTest, merchantId: e.target.value })}><option value="">Select an active merchant</option>{(testMerchants.data ?? []).map((merchant) => <option key={merchant.id} value={merchant.id}>{merchant.name} · {merchant.merchantNumber}</option>)}</select></label>
            <label>Operation<select style={fieldStyle} value={liveTest.operation} onChange={(e) => setLiveTest({ ...liveTest, operation: e.target.value })}><option>COLLECT</option><option>PAYOUT</option></select></label>
            <label>Provider<select disabled={Boolean(channelScope)} style={fieldStyle} value={liveTest.channelCode} onChange={(e) => setLiveTest({ ...liveTest, channelCode: e.target.value })}><option value="mtn_momo">MTN MoMo</option><option value="airtel_open_api">Airtel Open API</option></select></label>
            <label>Environment<select style={fieldStyle} value={liveTest.environment} onChange={(e) => setLiveTest({ ...liveTest, environment: e.target.value, currencyCode: e.target.value === 'SANDBOX' && liveTest.channelCode === 'mtn_momo' ? 'EUR' : 'UGX', mfaCode: '', confirmProduction: false })}><option>SANDBOX</option><option>PRODUCTION</option></select></label>
            <label>Country<input required style={fieldStyle} value={liveTest.countryCode} onChange={(e) => setLiveTest({ ...liveTest, countryCode: e.target.value.toUpperCase() })} /></label>
            <label>Currency<input required style={fieldStyle} value={liveTest.currencyCode} onChange={(e) => setLiveTest({ ...liveTest, currencyCode: e.target.value.toUpperCase() })} /></label>
            <label>Amount<input required style={fieldStyle} inputMode="decimal" value={liveTest.amount} onChange={(e) => setLiveTest({ ...liveTest, amount: e.target.value })} /></label>
            <label>{liveTest.operation === 'COLLECT' ? 'Payer' : 'Payee'} MSISDN<input required style={fieldStyle} autoComplete="off" placeholder="2567…" value={liveTest.party} onChange={(e) => setLiveTest({ ...liveTest, party: e.target.value })} /></label>
            <label>Idempotency key<input required style={fieldStyle} value={liveTest.idempotencyKey} onChange={(e) => setLiveTest({ ...liveTest, idempotencyKey: e.target.value })} /></label>
            {liveTest.environment === 'PRODUCTION' ? <>
              <label>Admin MFA code<input required type="password" inputMode="numeric" autoComplete="one-time-code" style={fieldStyle} value={liveTest.mfaCode} onChange={(e) => setLiveTest({ ...liveTest, mfaCode: e.target.value })} /></label>
              <label style={{ display: 'flex', gap: 8, alignItems: 'center' }}><input required type="checkbox" checked={liveTest.confirmProduction} onChange={(e) => setLiveTest({ ...liveTest, confirmProduction: e.target.checked })} />I understand this production test can move real money.</label>
            </> : null}
            <Button variant="primary" type="submit" disabled={createLiveTest.isPending}>{liveTest.operation === 'PAYOUT' ? 'Request payout test' : 'Run collection test'}</Button>
          </form>
          <label style={{ display: 'block', marginTop: 16 }}>Checker MFA code for pending payouts<input type="password" inputMode="numeric" autoComplete="one-time-code" style={{ ...fieldStyle, marginLeft: 8 }} value={approvalMfaCode} onChange={(e) => setApprovalMfaCode(e.target.value)} /></label>
        </Card>
        <Card flush><Table<ProviderLiveTest> columns={liveTestColumns} rows={liveTests.data ?? []} rowKey={(row) => row.id} emptyText="No provider transaction tests have been run." /></Card>
      </Section>

      <Section title="Credit, Debit or Rebalance Float">
        <Card>
          <form onSubmit={submitAdjustment} style={gridStyle}>
            <label>Action<select style={fieldStyle} value={adjustment.adjustmentType} onChange={(e) => setAdjustment({ ...adjustment, adjustmentType: e.target.value })}><option>CREDIT</option><option>DEBIT</option><option>REBALANCE</option></select></label>
            <label>Source account<select style={fieldStyle} value={adjustment.sourceAccountId} onChange={(e) => setAdjustment({ ...adjustment, sourceAccountId: e.target.value })}><option value="">External / none</option>{(accounts.data ?? []).map((a) => <option key={a.id} value={a.id}>{a.id} · {a.channelCode} · {a.accountRole} · {a.currencyCode}</option>)}</select></label>
            <label>Destination account<select style={fieldStyle} value={adjustment.destinationAccountId} onChange={(e) => setAdjustment({ ...adjustment, destinationAccountId: e.target.value })}><option value="">External / none</option>{(accounts.data ?? []).map((a) => <option key={a.id} value={a.id}>{a.id} · {a.channelCode} · {a.accountRole} · {a.currencyCode}</option>)}</select></label>
            <label>Amount<input required style={fieldStyle} inputMode="decimal" value={adjustment.amount} onChange={(e) => setAdjustment({ ...adjustment, amount: e.target.value })} /></label>
            <label>Reason<input required style={fieldStyle} value={adjustment.reason} onChange={(e) => setAdjustment({ ...adjustment, reason: e.target.value })} /></label>
            <label>Bank/provider reference<input required style={fieldStyle} value={adjustment.externalReference} onChange={(e) => setAdjustment({ ...adjustment, externalReference: e.target.value })} /></label>
            <label>Evidence reference<input style={fieldStyle} value={adjustment.evidenceReference} onChange={(e) => setAdjustment({ ...adjustment, evidenceReference: e.target.value })} /></label>
            <label>Value date<input required type="date" style={fieldStyle} value={adjustment.valueDate} onChange={(e) => setAdjustment({ ...adjustment, valueDate: e.target.value })} /></label>
            <Button variant="primary" type="submit" disabled={createAdjustment.isPending}>Submit for approval</Button>
          </form>
        </Card>
        <Card flush><Table<TreasuryAdjustment> columns={adjustmentColumns} rows={adjustments.data ?? []} rowKey={(row) => row.id} emptyText="No treasury adjustments." /></Card>
      </Section>

      <Section title={`Shared-Provider Merchant Entitlements${pendingEntitlements.length ? ` · ${pendingEntitlements.length} pending` : ''}`}>
        <Card>
          <form onSubmit={submitEntitlement} style={gridStyle}>
            <label>Merchant ID<input required style={fieldStyle} inputMode="numeric" value={entitlement.merchantId} onChange={(e) => setEntitlement({ ...entitlement, merchantId: e.target.value })} /></label>
            <label>Channel<select disabled={Boolean(channelScope)} style={fieldStyle} value={entitlement.channelCode} onChange={(e) => setEntitlement({ ...entitlement, channelCode: e.target.value })}><option value="airtel_money">Airtel Money</option><option value="airtel_open_api">Airtel Open API</option><option value="mtn_momo">MTN MoMo</option><option value="safaricom_mpesa">Safaricom M-Pesa</option></select></label>
            <label>Operation<select style={fieldStyle} value={entitlement.operation} onChange={(e) => setEntitlement({ ...entitlement, operation: e.target.value })}><option>COLLECT</option><option>PAYOUT</option></select></label>
            <label>Environment<select style={fieldStyle} value={entitlement.environment} onChange={(e) => setEntitlement({ ...entitlement, environment: e.target.value })}><option>PRODUCTION</option><option>SANDBOX</option></select></label>
            <label>Country<input required style={fieldStyle} value={entitlement.countryCode} onChange={(e) => setEntitlement({ ...entitlement, countryCode: e.target.value.toUpperCase() })} /></label>
            <label>Currency<input required style={fieldStyle} value={entitlement.currencyCode} onChange={(e) => setEntitlement({ ...entitlement, currencyCode: e.target.value.toUpperCase() })} /></label>
            <label>Per-transaction limit<input style={fieldStyle} inputMode="decimal" value={entitlement.perTransactionLimit} onChange={(e) => setEntitlement({ ...entitlement, perTransactionLimit: e.target.value })} /></label>
            <label>Daily limit<input style={fieldStyle} inputMode="decimal" value={entitlement.dailyLimit} onChange={(e) => setEntitlement({ ...entitlement, dailyLimit: e.target.value })} /></label>
            <label>Notes<input style={fieldStyle} value={entitlement.notes} onChange={(e) => setEntitlement({ ...entitlement, notes: e.target.value })} /></label>
            <Button variant="primary" type="submit" disabled={createEntitlement.isPending}>Submit entitlement</Button>
          </form>
        </Card>
        <Card flush><Table<SharedProviderEntitlement> columns={entitlementColumns} rows={entitlements.data ?? []} rowKey={(row) => row.id} emptyText="No shared-provider entitlements." /></Card>
      </Section>

      <Section title="CPay Platform Provider Credentials">
        <Card>
          <p style={{ color: 'var(--ios-secondary)' }}>Secrets are encrypted at rest. After save, only masked values are returned. Credential editor and approver must be different operators.</p>
          <form onSubmit={submitCredential} style={gridStyle}>
            <label>Channel<select disabled={Boolean(channelScope)} style={fieldStyle} value={credential.channelCode} onChange={(e) => {
              const channelCode = e.target.value;
              setCredential({ ...credential, channelCode,
                ...(channelCode === 'mtn_momo' ? { baseCurrency: credential.environment === 'SANDBOX' ? 'EUR' : 'UGX', currencyCode: credential.environment === 'SANDBOX' ? 'EUR' : 'UGX', baseUrl: credential.environment === 'SANDBOX' ? 'https://sandbox.momodeveloper.mtn.com' : '', targetEnvironment: credential.environment === 'SANDBOX' ? 'sandbox' : 'mtnuganda' } : {}),
                ...(channelCode === 'airtel_open_api' ? { baseUrl: credential.environment === 'SANDBOX' ? 'https://openapiuat.airtel.africa' : 'https://openapi.airtel.africa', currencyCode: 'UGX', airtelCountry: 'UG', airtelCurrency: 'UGX' } : {}),
              });
            }}><option value="airtel_money">Airtel Money</option><option value="airtel_open_api">Airtel Open API</option><option value="mtn_momo">MTN MoMo</option><option value="safaricom_mpesa">Safaricom M-Pesa</option></select></label>
            <label>Environment<select style={fieldStyle} value={credential.environment} onChange={(e) => {
              const environment = e.target.value;
              setCredential({ ...credential, environment,
                ...(credential.channelCode === 'mtn_momo' ? { baseCurrency: environment === 'SANDBOX' ? 'EUR' : 'UGX', currencyCode: environment === 'SANDBOX' ? 'EUR' : 'UGX', baseUrl: environment === 'SANDBOX' ? 'https://sandbox.momodeveloper.mtn.com' : '', targetEnvironment: environment === 'SANDBOX' ? 'sandbox' : 'mtnuganda' } : {}),
                ...(credential.channelCode === 'airtel_open_api' ? { baseUrl: environment === 'SANDBOX' ? 'https://openapiuat.airtel.africa' : 'https://openapi.airtel.africa' } : {}),
              });
            }}><option>PRODUCTION</option><option>SANDBOX</option></select></label>
            <label>Country<input required style={fieldStyle} value={credential.countryCode} onChange={(e) => setCredential({ ...credential, countryCode: e.target.value.toUpperCase() })} /></label>
            <label>Currency<input required style={fieldStyle} value={credential.currencyCode} onChange={(e) => setCredential({ ...credential, currencyCode: e.target.value.toUpperCase() })} /></label>
            {credential.channelCode === 'mtn_momo' ? <>
              <label>MTN API base URL<input required type="url" style={fieldStyle} value={credential.baseUrl} onChange={(e) => setCredential({ ...credential, baseUrl: e.target.value })} /></label>
              <label>X-Target-Environment<input required style={fieldStyle} value={credential.targetEnvironment} onChange={(e) => setCredential({ ...credential, targetEnvironment: e.target.value })} /></label>
              <label>MTN base currency<input required style={fieldStyle} value={credential.baseCurrency} onChange={(e) => setCredential({ ...credential, baseCurrency: e.target.value.toUpperCase() })} /></label>
              <label>Registered callback host<input required placeholder="payments.example.com" style={fieldStyle} value={credential.callbackHost} onChange={(e) => setCredential({ ...credential, callbackHost: e.target.value })} /></label>
              <label>CPay callback URL<input required type="url" style={fieldStyle} value={credential.callbackUrl} onChange={(e) => setCredential({ ...credential, callbackUrl: e.target.value })} /></label>
              <label>Collection API user<input required type="password" autoComplete="new-password" style={fieldStyle} value={credential.collectionApiUser} onChange={(e) => setCredential({ ...credential, collectionApiUser: e.target.value })} /></label>
              <label>Collection API key<input required type="password" autoComplete="new-password" style={fieldStyle} value={credential.collectionApiKey} onChange={(e) => setCredential({ ...credential, collectionApiKey: e.target.value })} /></label>
              <label>Collection primary subscription key<input required type="password" autoComplete="new-password" style={fieldStyle} value={credential.collectionSubscriptionKey} onChange={(e) => setCredential({ ...credential, collectionSubscriptionKey: e.target.value })} /></label>
              <label>Collection secondary key<input type="password" autoComplete="new-password" style={fieldStyle} value={credential.collectionSecondarySubscriptionKey} onChange={(e) => setCredential({ ...credential, collectionSecondarySubscriptionKey: e.target.value })} /></label>
              <label>Disbursement API user<input required type="password" autoComplete="new-password" style={fieldStyle} value={credential.disbursementApiUser} onChange={(e) => setCredential({ ...credential, disbursementApiUser: e.target.value })} /></label>
              <label>Disbursement API key<input required type="password" autoComplete="new-password" style={fieldStyle} value={credential.disbursementApiKey} onChange={(e) => setCredential({ ...credential, disbursementApiKey: e.target.value })} /></label>
              <label>Disbursement primary subscription key<input required type="password" autoComplete="new-password" style={fieldStyle} value={credential.disbursementSubscriptionKey} onChange={(e) => setCredential({ ...credential, disbursementSubscriptionKey: e.target.value })} /></label>
              <label>Disbursement secondary key<input type="password" autoComplete="new-password" style={fieldStyle} value={credential.disbursementSecondarySubscriptionKey} onChange={(e) => setCredential({ ...credential, disbursementSecondarySubscriptionKey: e.target.value })} /></label>
            </> : credential.channelCode === 'airtel_open_api' ? <>
              <label>Airtel API base URL<input required type="url" style={fieldStyle} value={credential.baseUrl} onChange={(e) => setCredential({ ...credential, baseUrl: e.target.value })} /></label>
              <label>OAuth client ID<input required type="password" autoComplete="new-password" style={fieldStyle} value={credential.airtelClientId} onChange={(e) => setCredential({ ...credential, airtelClientId: e.target.value })} /></label>
              <label>OAuth client secret<input required type="password" autoComplete="new-password" style={fieldStyle} value={credential.airtelClientSecret} onChange={(e) => setCredential({ ...credential, airtelClientSecret: e.target.value })} /></label>
              <label>Airtel country<input required style={fieldStyle} value={credential.airtelCountry} onChange={(e) => setCredential({ ...credential, airtelCountry: e.target.value.toUpperCase(), countryCode: e.target.value.toUpperCase() })} /></label>
              <label>Airtel currency<input required style={fieldStyle} value={credential.airtelCurrency} onChange={(e) => setCredential({ ...credential, airtelCurrency: e.target.value.toUpperCase(), currencyCode: e.target.value.toUpperCase() })} /></label>
              <label>Disbursement API PIN<input required type="password" autoComplete="new-password" style={fieldStyle} value={credential.airtelApiPin} onChange={(e) => setCredential({ ...credential, airtelApiPin: e.target.value })} /></label>
              <label>Airtel RSA public key<textarea required style={{ ...fieldStyle, minHeight: 90 }} value={credential.airtelPublicKey} onChange={(e) => setCredential({ ...credential, airtelPublicKey: e.target.value })} /></label>
              <label>OAuth token path<input required style={fieldStyle} value={credential.tokenPath} onChange={(e) => setCredential({ ...credential, tokenPath: e.target.value })} /></label>
              <label>Collection path<input required style={fieldStyle} value={credential.collectionPath} onChange={(e) => setCredential({ ...credential, collectionPath: e.target.value })} /></label>
              <label>Disbursement path<input required style={fieldStyle} value={credential.payoutPath} onChange={(e) => setCredential({ ...credential, payoutPath: e.target.value })} /></label>
              <label>Balance path<input required style={fieldStyle} value={credential.balancePath} onChange={(e) => setCredential({ ...credential, balancePath: e.target.value })} /></label>
            </> : <>
              <label>Collect URL<input required={credential.environment === 'PRODUCTION'} style={fieldStyle} value={credential.collectUrl} onChange={(e) => setCredential({ ...credential, collectUrl: e.target.value })} /></label>
              <label>Payout URL<input required={credential.environment === 'PRODUCTION'} style={fieldStyle} value={credential.payoutUrl} onChange={(e) => setCredential({ ...credential, payoutUrl: e.target.value })} /></label>
              <label>Auth header name<input style={fieldStyle} value={credential.authHeaderName} onChange={(e) => setCredential({ ...credential, authHeaderName: e.target.value })} /></label>
              <label>Auth header value<input type="password" style={fieldStyle} value={credential.authHeaderValue} onChange={(e) => setCredential({ ...credential, authHeaderValue: e.target.value })} /></label>
              <label>Token alias<input style={fieldStyle} value={credential.tokenAlias} onChange={(e) => setCredential({ ...credential, tokenAlias: e.target.value })} /></label>
            </>}
            <Button variant="primary" type="submit" disabled={saveCredential.isPending}>Save encrypted credential</Button>
          </form>
        </Card>
        <Card flush><Table<PlatformCredential> columns={credentialColumns} rows={credentials.data ?? []} rowKey={(row) => row.id} emptyText="No CPay platform credentials configured." /></Card>
      </Section>

      {pendingAdjustments.length ? <p style={{ color: 'var(--ios-secondary)' }}>{pendingAdjustments.length} treasury adjustment(s) await an independent checker.</p> : null}
    </div>
  );
}
