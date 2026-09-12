import { cloneElement, FormEvent, ReactElement, ReactNode, useId, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { Badge, Button, Card, Section, Tabs } from '../ui';
import { useAuth } from '../shared/useAuth';
import { ApiError } from '../shared/api/httpClient';
import {
  PlatformCredential, ProviderLiveTest, ProviderTestMerchant,
  usePlatformCredentials, useSavePlatformCredential, useVerifyPlatformCredential, useApprovePlatformCredential,
  useProviderTestMerchants, useSharedProviderEntitlements, useCreateSharedEntitlement,
  useApproveSharedEntitlement, useRejectSharedEntitlement, useProviderLiveTests,
  useCreateProviderLiveTest, useApproveProviderLiveTest, useTreasuryAccounts, useRefreshProviderBalance,
} from '../shared/api/providerTreasury';
import { mtnEnvironment, mtnProfile } from './providerConnectionProfile';
import './MtnMomoWorkspace.css';

type Scope = ReturnType<typeof mtnProfile> & { environment: string; channelCode: string };
type Scoped = { channelCode: string; environment: string; countryCode: string; currencyCode: string };
const matches = (row: Scoped, scope: Scope) => row.channelCode === scope.channelCode && row.environment === scope.environment
  && row.countryCode === scope.countryCode && row.currencyCode === scope.currencyCode;
const message = (error: unknown) => error instanceof Error ? error.message : 'The request could not be completed. Try again.';
const label = (value: string) => value.replaceAll('_', ' ').toLowerCase();
const formatMoney = (value: unknown, currency: string) => `${currency} ${Number(value ?? 0).toLocaleString(undefined, { maximumFractionDigits: 4 })}`;
const privateFields = ['collectionApiUser', 'collectionApiKey', 'collectionSubscriptionKey', 'collectionSecondarySubscriptionKey',
  'disbursementApiUser', 'disbursementApiKey', 'disbursementSubscriptionKey', 'disbursementSecondarySubscriptionKey'];

function State({ value }: { value: string }) {
  return <Badge tone={['ACTIVE', 'VERIFIED', 'CONNECTIVITY_VERIFIED', 'SUCCEEDED'].includes(value) ? 'success'
    : ['FAILED', 'CONNECTIVITY_FAILED', 'REJECTED'].includes(value) ? 'danger' : 'warning'}>{label(value)}</Badge>;
}
function Field({ label: title, children, help }: { label: string; children: ReactElement; help?: string }) {
  const id = useId();
  return <div className="mtn-field"><label htmlFor={id}>{title}</label>{cloneElement(children, { id, 'aria-describedby': help ? `${id}-help` : undefined })}{help && <small id={`${id}-help`}>{help}</small>}</div>;
}
function Notice({ children, error = false }: { children?: ReactNode; error?: boolean }) {
  return children ? <div className={`mtn-notice${error ? ' mtn-notice--error' : ''}`} role={error ? 'alert' : 'status'}>{children}</div> : null;
}
function MerchantSelect({ merchants, value, onChange }: { merchants: ProviderTestMerchant[]; value: string; onChange: (value: string) => void }) {
  return <Field label="Merchant"><select required value={value} onChange={e => onChange(e.target.value)}>
    <option value="">Select an active merchant</option>
    {merchants.filter(row => row.status === 'ACTIVE').map(row => <option key={row.id} value={row.id}>{row.name} · {row.merchantNumber}</option>)}
  </select></Field>;
}
function useIndependentActor() {
  const { user } = useAuth('admin');
  return (actor?: string) => Boolean(actor && [user.username, user.email].some(value => value?.toLowerCase() === actor.toLowerCase()));
}

export default function MtnMomoWorkspace() {
  const [params, setParams] = useSearchParams();
  const environment = mtnEnvironment(params.get('environment'));
  const scope = { ...mtnProfile(environment), environment, channelCode: 'mtn_momo' };
  return <div className="mtn-workspace">
    <Card className="mtn-heading">
      <div><span className="mtn-eyebrow">MTN Uganda</span><h2>Collections and payouts, connected.</h2>
        <p>Configure both products, approve merchant access, then follow each payment to its final result.</p></div>
      <Field label="MTN environment"><select value={environment} onChange={e => {
        const next = new URLSearchParams(params); next.set('environment', e.target.value); setParams(next);
      }}><option value="SANDBOX">Sandbox · EUR</option><option value="PRODUCTION">Production · UGX</option></select></Field>
    </Card>
    <ScopedWorkspace key={environment} scope={scope} />
  </div>;
}

function ScopedWorkspace({ scope }: { scope: Scope }) {
  const [tab, setTab] = useState('connection');
  const [testsOpened, setTestsOpened] = useState(false);
  const credentials = usePlatformCredentials();
  const credential = credentials.data?.find(row => matches(row, scope));
  return <>
    <div className="mtn-scope"><strong>{scope.environment === 'PRODUCTION' ? 'Production · real money' : 'Sandbox · simulated money'}</strong>
      <span>{scope.countryCode} / {scope.currencyCode} · {scope.targetEnvironment}</span>
      <State value={credential?.status ?? 'NOT_CONFIGURED'} /></div>
    <Tabs active={tab} onChange={next => { setTab(next); if (next === 'tests') setTestsOpened(true); }} items={[
      { key: 'connection', label: '1. Connection' }, { key: 'access', label: '2. Merchant access' },
      { key: 'tests', label: '3. Payment tests' }, { key: 'balances', label: '4. Balances' },
    ]} />
    {credentials.isPending ? <Notice>Loading MTN configuration…</Notice> : credentials.error
      ? <Notice error>{message(credentials.error)} <Button variant="ghost" onClick={() => credentials.refetch()}>Retry</Button></Notice>
      : <div role="tabpanel" aria-label={tab}>
        {tab === 'connection' && <Connection scope={scope} credential={credential} />}
        {tab === 'access' && <MerchantAccess scope={scope} />}
        {testsOpened && <div hidden={tab !== 'tests'}><PaymentTests scope={scope} credential={credential} /></div>}
        {tab === 'balances' && <Balances scope={scope} />}
      </div>}
  </>;
}

function Connection({ scope, credential }: { scope: Scope; credential?: PlatformCredential }) {
  const verify = useVerifyPlatformCredential();
  const approve = useApprovePlatformCredential();
  const own = useIndependentActor();
  const [editing, setEditing] = useState(!credential);
  const [snapshot, setSnapshot] = useState(credential);
  const [notice, setNotice] = useState('');
  const verified = credential?.lastTestStatus === 'CONNECTIVITY_VERIFIED';
  const checks = verify.data?.id === credential?.id && verify.data?.revision === credential?.revision ? verify.data?.verificationChecks : undefined;
  return <div className="mtn-stack">
    <Section title="Connection status">
      <p>{credential ? `Revision ${credential.revision} · saved by ${credential.updatedBy || 'an operator'}` : 'No credentials are saved for this environment.'}</p>
      <div className="mtn-actions"><State value={credential?.lastTestStatus || 'NOT_VERIFIED'} />
        {credential?.lastTestedAt && <span>Last checked: {new Date(credential.lastTestedAt).toLocaleString()}</span>}</div>
      <p>Verification checks collection and disbursement authentication separately. Payment tests confirm acceptance and finality.</p>
      {checks && <div className="mtn-grid">{checks.map(check => <div className="mtn-product-result" key={check.operation}>
        <strong>{check.operation === 'COLLECT' ? 'Collections' : 'Payouts'}</strong> <State value={check.status} /><p>{check.message}</p>
      </div>)}</div>}
      <Notice error>{verify.error ? message(verify.error) : approve.error ? message(approve.error) : ''}</Notice>
      <Notice>{notice}</Notice>
      {credential && !editing && <div className="mtn-actions">
        <Button loading={verify.isPending} disabled={approve.isPending} onClick={() => { setNotice(''); verify.mutate(credential.id); }}>Verify both products</Button>
        <Button variant="secondary" disabled={!verified || credential.status !== 'CONFIGURED' || own(credential.updatedBy) || verify.isPending} loading={approve.isPending}
          onClick={() => approve.mutate(credential.id, { onSuccess: () => setNotice('Connection approved. Review merchant access before submitting a payment.') })}>Approve connection</Button>
        <Button variant="ghost" disabled={verify.isPending || approve.isPending} onClick={() => { setSnapshot(credential); setEditing(true); verify.reset(); setNotice(''); }}>Edit credentials</Button>
      </div>}
      {credential?.status === 'CONFIGURED' && <p className="mtn-help">After both checks pass, a different authorised operator must approve this revision.</p>}
    </Section>
    {editing && <CredentialEditor scope={scope} existing={snapshot} onSaved={() => { setEditing(false); setNotice('Credentials saved securely. Verify both products, then ask a different operator to approve.'); }} onCancel={credential ? () => setEditing(false) : undefined} />}
    <Section title="Before your first payment">
      <ol className="mtn-checklist"><li>Obtain a separate API user, API key and subscription key for each MTN product.</li>
        <li>Register the callback host with MTN and save the matching HTTPS callback address below.</li>
        <li>Verify and independently approve the connection, then approve each merchant’s collection and payout access.</li>
        <li>Use Payment tests to check the result. A pending payment remains pending until provider status confirms the outcome.</li></ol>
      <a href="https://momoapi.mtn.com/api-documentation/api-description" target="_blank" rel="noreferrer">MTN credential setup guide ↗</a>
    </Section>
  </div>;
}

function CredentialEditor({ scope, existing, onSaved, onCancel }: { scope: Scope; existing?: PlatformCredential; onSaved: () => void; onCancel?: () => void }) {
  const save = useSavePlatformCredential();
  // Capture the revision when editing begins. A background refresh cannot overwrite a concurrent edit.
  const [revision] = useState(existing?.revision ?? 0);
  const [fields, setFields] = useState<Record<string, string>>(() => ({
    ...Object.fromEntries(privateFields.map(key => [key, ''])),
    callbackHost: existing?.credentials.callbackHost || window.location.hostname,
    callbackUrl: existing?.credentials.callbackUrl || `${window.location.origin}/api/v2/provider-callbacks/mtn`,
  }));
  const update = (key: string, value: string) => setFields(current => ({ ...current, [key]: value }));
  const submit = (event: FormEvent) => {
    event.preventDefault();
    const values = Object.fromEntries(Object.entries(fields).filter(([key, value]) => !privateFields.includes(key) || value.trim() !== ''));
    save.mutate({ channelCode: scope.channelCode, environment: scope.environment, countryCode: scope.countryCode, currencyCode: scope.currencyCode, revision,
      credentials: { ...values, baseUrl: scope.baseUrl, targetEnvironment: scope.targetEnvironment, baseCurrency: scope.baseCurrency } }, {
      onSuccess: () => { setFields({}); onSaved(); },
    });
  };
  return <Section title={existing ? 'Update credentials' : 'Set up your connection'}>
    <p>{existing ? 'Leave a secret blank to keep its stored value. Saving creates a new revision that needs verification and approval.' : 'Enter the credentials issued by MTN for this environment. Secrets are encrypted and never displayed after saving.'}</p>
    <dl className="mtn-profile"><div><dt>API address</dt><dd>{scope.baseUrl}</dd></div><div><dt>Target environment</dt><dd>{scope.targetEnvironment}</dd></div><div><dt>Currency</dt><dd>{scope.currencyCode}</dd></div></dl>
    <form onSubmit={submit} autoComplete="off"><fieldset disabled={save.isPending} className="mtn-form-reset">
      <div className="mtn-grid">{[['collection', 'Collection credentials', 'Incoming payments · Request to Pay'], ['disbursement', 'Payout credentials', 'Outgoing payments · Transfer']].map(([prefix, title, help]) =>
        <fieldset className="mtn-product" key={prefix}><legend>{title}</legend><p>{help}</p>
          {[['ApiUser', 'API user'], ['ApiKey', 'API key'], ['SubscriptionKey', 'Subscription key'], ['SecondarySubscriptionKey', 'Secondary subscription key (optional)']].map(([suffix, title]) => {
            const key = prefix + suffix;
            return <Field key={key} label={`${prefix === 'collection' ? 'Collection' : 'Payout'} ${title}`} help={existing?.credentials[key] ? 'Stored securely · leave blank to keep' : undefined}>
              <input type="password" autoComplete="new-password" spellCheck={false} required={!existing?.credentials[key] && suffix !== 'SecondarySubscriptionKey'} value={fields[key] || ''} onChange={e => update(key, e.target.value)} />
            </Field>;
          })}</fieldset>)}</div>
      <h3>Callbacks</h3><p>Register this host for both products in MTN’s portal. Cito adds the payment reference to the callback address automatically.</p>
      <div className="mtn-grid"><Field label="Registered callback host" help="Host only, without https:// or a path."><input required value={fields.callbackHost || ''} onChange={e => update('callbackHost', e.target.value)} /></Field>
        <Field label="Callback address" help="HTTPS address ending in /api/v2/provider-callbacks/mtn. Do not add a payment reference."><input type="url" required value={fields.callbackUrl || ''} onChange={e => update('callbackUrl', e.target.value)} /></Field></div>
      <Notice error>{save.error && message(save.error)}</Notice>
      <div className="mtn-actions"><Button type="submit" loading={save.isPending}>Save credentials</Button>{onCancel && <Button type="button" variant="ghost" onClick={onCancel}>Cancel</Button>}</div>
    </fieldset></form>
  </Section>;
}

function MerchantAccess({ scope }: { scope: Scope }) {
  const merchants = useProviderTestMerchants();
  const entitlements = useSharedProviderEntitlements();
  const create = useCreateSharedEntitlement();
  const approve = useApproveSharedEntitlement();
  const reject = useRejectSharedEntitlement();
  const own = useIndependentActor();
  const [form, setForm] = useState({ merchantId: '', operation: 'COLLECT', perTransactionLimit: '', dailyLimit: '', notes: '' });
  const [notice, setNotice] = useState('');
  const rows = (entitlements.data ?? []).filter(row => matches(row, scope));
  const error = merchants.error || entitlements.error || create.error || approve.error || reject.error;
  return <div className="mtn-stack"><Section title="Grant merchant access">
    <p>Approve collections and payouts separately. These permissions allow the merchant to use Cito’s shared MTN connection in {label(scope.environment)}.</p>
    <form onSubmit={e => { e.preventDefault(); create.mutate({ ...form, channelCode: scope.channelCode, environment: scope.environment, countryCode: scope.countryCode, currencyCode: scope.currencyCode,
      merchantId: Number(form.merchantId), perTransactionLimit: form.perTransactionLimit || null, dailyLimit: form.dailyLimit || null }, { onSuccess: () => setNotice('Access request submitted. A different operator must approve it.') }); }}>
      <div className="mtn-grid"><MerchantSelect merchants={merchants.data ?? []} value={form.merchantId} onChange={merchantId => setForm({ ...form, merchantId })} />
        <Field label="Operation"><select value={form.operation} onChange={e => setForm({ ...form, operation: e.target.value })}><option value="COLLECT">Collections</option><option value="PAYOUT">Payouts</option></select></Field>
        <Field label={`Per-payment limit (${scope.currencyCode})`} help="Optional; blank uses the platform policy."><input type="number" min="0.0001" step="0.0001" value={form.perTransactionLimit} onChange={e => setForm({ ...form, perTransactionLimit: e.target.value })} /></Field>
        <Field label={`Daily limit (${scope.currencyCode})`} help="Optional; blank uses the platform policy."><input type="number" min="0.0001" step="0.0001" value={form.dailyLimit} onChange={e => setForm({ ...form, dailyLimit: e.target.value })} /></Field>
      </div><Field label="Review note"><input value={form.notes} onChange={e => setForm({ ...form, notes: e.target.value })} /></Field>
      <Button type="submit" loading={create.isPending} disabled={merchants.isPending || Boolean(merchants.error)}>Request access</Button>
    </form><Notice error>{error && message(error)}</Notice><Notice>{notice}</Notice>
  </Section><Section title="Merchant approvals">
    {entitlements.isPending ? <p>Loading merchant access…</p> : !rows.length ? <p>No merchant access is configured for this environment.</p> : rows.map(row => <div className="mtn-list-row" key={row.id}>
      <div><strong>{row.merchantName || `Merchant ${row.merchantId}`} · {row.operation === 'COLLECT' ? 'Collections' : 'Payouts'}</strong><p>Requested by {row.requestedBy || 'an operator'} · <State value={row.status} /></p></div>
      {row.status === 'PENDING' && <div className="mtn-actions"><Button disabled={own(row.requestedBy) || reject.isPending} loading={approve.isPending} onClick={() => approve.mutate(row.id)}>Approve access</Button>
        <Button variant="ghost" disabled={approve.isPending || own(row.requestedBy)} loading={reject.isPending} onClick={() => reject.mutate(row.id)}>Reject</Button>{own(row.requestedBy) && <small>A different operator must approve.</small>}</div>}
    </div>)}
  </Section></div>;
}

function PaymentTests({ scope, credential }: { scope: Scope; credential?: PlatformCredential }) {
  const merchants = useProviderTestMerchants();
  const entitlements = useSharedProviderEntitlements();
  const tests = useProviderLiveTests();
  const create = useCreateProviderLiveTest();
  const [form, setForm] = useState({ merchantId: '', operation: 'COLLECT', amount: '', party: '', mfaCode: '', confirmProduction: false, idempotencyKey: crypto.randomUUID() as string });
  const [submitted, setSubmitted] = useState(false);
  const [result, setResult] = useState<ProviderLiveTest>();
  const production = scope.environment === 'PRODUCTION';
  const activeAccess = (entitlements.data ?? []).some(row => matches(row, scope) && row.status === 'ACTIVE' && String(row.merchantId) === form.merchantId && row.operation === form.operation);
  const ready = credential?.status === 'ACTIVE' && credential.lastTestStatus === 'CONNECTIVITY_VERIFIED' && activeAccess;
  const history = (tests.data ?? []).filter(row => matches(row, scope));
  const latest = result ? history.find(row => row.id === result.id) ?? result : undefined;
  const loadError = merchants.error || entitlements.error || tests.error;
  const submit = (event: FormEvent) => {
    event.preventDefault(); setSubmitted(true);
    create.mutate({ ...form, channelCode: scope.channelCode, environment: scope.environment, countryCode: scope.countryCode, currencyCode: scope.currencyCode, merchantId: Number(form.merchantId) }, {
      onSuccess: value => { setResult(value); setForm(current => ({ ...current, mfaCode: '' })); },
    });
  };
  return <div className="mtn-stack"><Section title={production ? 'Production payment test' : 'Sandbox payment test'}>
    <p>{production ? 'This submits a real payment using the selected merchant’s approved access and existing limits. Confirm the amount and phone number before submitting.' : 'Use MTN sandbox credentials and test phone numbers. Requests use EUR and follow the same collection and payout processing as production.'}</p>
    {!production && <p className="mtn-help">MTN examples: 46733123450 returns failed; 46733123453 stays pending. See <a href="https://momoapi.mtn.com/api-documentation/testing" target="_blank" rel="noreferrer">MTN’s sandbox scenarios ↗</a> for phone and amount combinations.</p>}
    <Notice error>{loadError && message(loadError)}</Notice>
    <form onSubmit={submit}><fieldset className="mtn-form-reset" disabled={create.isPending || submitted}>
      <div className="mtn-grid"><MerchantSelect merchants={merchants.data ?? []} value={form.merchantId} onChange={merchantId => setForm({ ...form, merchantId })} />
        <Field label="Payment operation"><select value={form.operation} onChange={e => setForm({ ...form, operation: e.target.value })}><option value="COLLECT">Collect from a wallet</option><option value="PAYOUT">Pay out to a wallet</option></select></Field>
        <Field label={`Amount (${scope.currencyCode})`}><input type="number" min="0.0001" step="0.0001" required value={form.amount} onChange={e => setForm({ ...form, amount: e.target.value })} /></Field>
        <Field label={form.operation === 'COLLECT' ? 'Payer phone number' : 'Recipient phone number'} help="Include country code, digits only."><input type="tel" inputMode="numeric" pattern="[0-9]{9,15}" required value={form.party} onChange={e => setForm({ ...form, party: e.target.value })} /></Field>
      </div>
      {production && <><Field label="Your MFA code"><input autoComplete="one-time-code" inputMode="numeric" required type="password" value={form.mfaCode} onChange={e => setForm({ ...form, mfaCode: e.target.value })} /></Field>
        <label className="mtn-confirm"><input type="checkbox" required checked={form.confirmProduction} onChange={e => setForm({ ...form, confirmProduction: e.target.checked })} />I confirm this is a real {form.operation === 'COLLECT' ? 'collection' : 'payout'} of {form.amount || '—'} {scope.currencyCode} for {form.party || 'the entered phone number'}.</label></>}
    </fieldset>
      {!ready && <Notice>First verify and approve the connection, then approve this merchant’s {form.operation === 'COLLECT' ? 'collection' : 'payout'} access in {label(scope.environment)}.</Notice>}
      <p className="mtn-help">Payouts require a different operator’s approval before execution. Available float, merchant funds and configured limits are checked by the payment service.</p>
      <p className="mtn-reference">Request key: <code>{form.idempotencyKey}</code></p>
      {(!submitted || !result) && <Button type="submit" loading={create.isPending} disabled={!ready || Boolean(loadError)}>{submitted ? 'Retry the same request safely' : form.operation === 'COLLECT' ? 'Submit collection test' : 'Request payout test'}</Button>}
    </form>
    <Notice error>{create.error && <>{message(create.error)} The request key and payment details have been retained. Check history before retrying this same request.</>}</Notice>
    {create.error instanceof ApiError && [400, 422].includes(create.error.status) && !result && <Button variant="ghost" onClick={() => {
      setSubmitted(false); create.reset(); setForm({ ...form, mfaCode: '', confirmProduction: false });
    }}>Correct rejected request</Button>}
    {latest && <Notice><strong>{label(latest.status)}</strong> · {latest.testReference}<br />{latest.resultMessage || (latest.status === 'PENDING_APPROVAL' ? 'Waiting for a different operator to approve this payout.' : 'Follow the result in test history below.')}</Notice>}
    {latest && ['SUCCEEDED', 'FAILED', 'REJECTED'].includes(latest.status) && <Button variant="ghost" onClick={() => { setResult(undefined); setSubmitted(false); create.reset(); setForm({ ...form, amount: '', party: '', mfaCode: '', confirmProduction: false, idempotencyKey: crypto.randomUUID() }); }}>Start a new test</Button>}
    {submitted && !result && production && <Field label="Fresh MFA code for the same request"><input type="password" autoComplete="one-time-code" value={form.mfaCode} onChange={e => setForm({ ...form, mfaCode: e.target.value })} /></Field>}
  </Section><Section title="Test history">
    {tests.isPending ? <p>Loading test history…</p> : !history.length ? <p>No payment tests in this environment yet.</p> : history.map(row => <TestResult key={row.id} row={row} />)}
  </Section></div>;
}

function TestResult({ row }: { row: ProviderLiveTest }) {
  const approve = useApproveProviderLiveTest();
  const own = useIndependentActor();
  const [mfa, setMfa] = useState('');
  const [confirmed, setConfirmed] = useState(false);
  const production = row.environment === 'PRODUCTION';
  return <article className="mtn-test-result">
    <div className="mtn-actions"><strong>{row.operation === 'COLLECT' ? 'Collection' : 'Payout'} · {formatMoney(row.amount, row.currencyCode)}</strong><State value={row.status} /></div>
    <p>{row.merchantName} · {row.partyMask}</p><p className="mtn-reference">{row.testReference}<br />Provider reference: {row.providerReference || 'Not assigned yet'}</p>
    <p>{row.resultMessage}</p>{row.treasuryStatus && <p>Treasury: {label(row.treasuryStatus)}</p>}
    {row.status === 'PENDING_APPROVAL' && (own(row.requestedBy) ? <p>A different operator must approve your payout request.</p> : <form onSubmit={e => { e.preventDefault(); approve.mutate({ id: row.id, body: { mfaCode: mfa, confirmProduction: production && confirmed } }, { onSuccess: () => { setMfa(''); setConfirmed(false); } }); }}>
      {production && <><Field label={`Approval MFA code for ${row.testReference}`}><input type="password" required autoComplete="one-time-code" value={mfa} onChange={e => setMfa(e.target.value)} /></Field>
        <label className="mtn-confirm"><input type="checkbox" required checked={confirmed} onChange={e => setConfirmed(e.target.checked)} />I approve this real payout of {formatMoney(row.amount, row.currencyCode)} to {row.partyMask}.</label></>}
      <Button type="submit" loading={approve.isPending}>Approve payout test</Button><Notice error>{approve.error && message(approve.error)}</Notice>
    </form>)}
    <details><summary>Payment timeline and request key</summary><p className="mtn-reference">{row.idempotencyKey}</p><ol className="mtn-checklist">{(row.events ?? []).map(event => <li key={event.sequenceNumber}><strong>{label(event.status)}</strong> · {new Date(event.createdAt).toLocaleString()}<p>{event.message}</p></li>)}</ol></details>
  </article>;
}

function Balances({ scope }: { scope: Scope }) {
  const accounts = useTreasuryAccounts();
  const refresh = useRefreshProviderBalance();
  const rows = (accounts.data ?? []).filter(row => matches(row, scope));
  return <Section title="MTN float and provider balances">
    <p>Cito’s available float and MTN’s reported wallet balance are shown separately. A missing provider balance is unavailable, never an assumed zero.</p>
    <Notice error>{(accounts.error || refresh.error) && message(accounts.error || refresh.error)}</Notice>
    {accounts.isPending ? <p>Loading balances…</p> : !rows.length ? <p>No MTN treasury accounts in this environment. Save a connection to create the scoped accounts.</p> : <div className="mtn-grid">{rows.map(row => <Card key={row.id}>
      <h3>{label(row.accountRole)}</h3><dl className="mtn-balances"><div><dt>Cito available</dt><dd>{formatMoney(row.availableBalance, row.currencyCode)}</dd></div>
        <div><dt>Reserved</dt><dd>{formatMoney(row.reservedBalance, row.currencyCode)}</dd></div>
        <div><dt>MTN reported</dt><dd>{row.providerBalanceAvailable && row.providerReportedBalance != null ? formatMoney(row.providerReportedBalance, row.currencyCode) : 'Unavailable'}</dd></div></dl>
      <p>{row.providerBalanceMessage}</p><p className="mtn-help">Last provider update: {row.providerBalanceUpdatedAt ? new Date(row.providerBalanceUpdatedAt).toLocaleString() : 'Never'}</p>
      {row.accountRole !== 'MASTER' && <Button variant="secondary" loading={refresh.isPending} onClick={() => refresh.mutate(row.id)}>Refresh provider balance</Button>}
    </Card>)}</div>}
    <p><Link to={`/bo/admin/provider-treasury?channel=mtn_momo&environment=${scope.environment}`}>Open treasury funding and reconciliation →</Link></p>
  </Section>;
}
