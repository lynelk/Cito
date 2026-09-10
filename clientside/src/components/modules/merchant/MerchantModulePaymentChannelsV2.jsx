import React from 'react';
import { Badge, Button, Card, TextField, Toolbar } from '../../../ui';

import { apiFetch } from '../../../shared/api/httpClient';
import { apiUrl } from '../../../shared/config';

const FIELD_LABELS = {
  collectUrl: 'Collection URL',
  payoutUrl: 'Payout URL',
  authHeaderName: 'Auth header name',
  authHeaderValue: 'Auth header value',
  shortCode: 'Short code',
  consumerKey: 'Consumer key',
  consumerSecret: 'Consumer secret',
  passKey: 'Pass key',
  clientId: 'Client ID',
  clientSecret: 'Client secret',
  apiPin: 'Disbursement API PIN',
  publicKey: 'Airtel RSA public key',
  country: 'Airtel country code',
  currency: 'Airtel currency code',
  tokenPath: 'OAuth token path',
  collectionPath: 'Collection path',
  payoutPath: 'Disbursement path',
  balancePath: 'Balance path',
  apiUser: 'API user',
  apiKey: 'API key',
  collectionAccount: 'Collection account',
  baseUrl: 'Provider API base URL',
  targetEnvironment: 'X-Target-Environment',
  baseCurrency: 'MTN transaction currency',
  callbackHost: 'Registered callback host',
  callbackUrl: 'CPay callback URL',
  collectionApiUser: 'Collection API user',
  collectionApiKey: 'Collection API key',
  collectionSubscriptionKey: 'Collection primary subscription key',
  collectionSecondarySubscriptionKey: 'Collection secondary subscription key (optional)',
  disbursementApiUser: 'Disbursement API user',
  disbursementApiKey: 'Disbursement API key',
  disbursementSubscriptionKey: 'Disbursement primary subscription key',
  disbursementSecondarySubscriptionKey: 'Disbursement secondary subscription key (optional)',
};

const SECRET_FIELDS = new Set([
  'authHeaderValue', 'consumerKey', 'consumerSecret', 'passKey', 'clientSecret', 'apiKey', 'apiPin',
  'collectionApiUser', 'collectionApiKey', 'collectionSubscriptionKey', 'collectionSecondarySubscriptionKey',
  'disbursementApiUser', 'disbursementApiKey', 'disbursementSubscriptionKey', 'disbursementSecondarySubscriptionKey',
]);

function editableCredentials(credentials = {}) {
  return Object.fromEntries(Object.entries(credentials).map(([key, value]) => [key, String(value).includes('****') ? '' : value]));
}

const ENVIRONMENTS = [
  { id: 'SANDBOX', label: 'Sandbox', copy: 'Use provider-issued sandbox credentials and test accounts.' },
  { id: 'PRODUCTION', label: 'Production', copy: 'Use live credentials after approval. Daily limit applies.' },
];

function statusTone(status) {
  if (status === 'APPROVED' || status === 'READY' || status === 'ACTIVE' || status === 'SANDBOX_TESTED') return 'success';
  if (status === 'PENDING' || status === 'SUBMITTED' || status === 'SUBMITTED_FOR_APPROVAL' || status === 'CONFIGURED') return 'warning';
  if (status === 'REJECTED' || status === 'FAILED') return 'danger';
  return 'neutral';
}

function environmentRecord(channel, environment) {
  const records = channel?.environments || {};
  return {
    ...channel,
    ...(records[environment] || {}),
    environments: records,
    sandboxCredentials: channel?.sandboxCredentials,
    sandboxGuide: channel?.sandboxGuide,
  };
}

class MerchantModulePaymentChannelsV2 extends React.Component {
  constructor(props) {
    super(props);
    this.state = {
      channels: [],
      selected: null,
      values: {},
      environment: 'SANDBOX',
      environmentStatus: null,
      sandboxGuide: null,
      message: '',
      loading: false,
    };
  }

  componentDidMount() {
    this.loadPage();
  }

  componentDidUpdate(prevProps) {
    if (prevProps.refreshSignal !== this.props.refreshSignal) {
      this.loadPage(true);
    }
  }

  async loadPage(silent = false) {
    if (!silent) this.setState({ loading: true });
    const environment = await this.loadEnvironment();
    await this.loadChannels(environment || this.state.environment);
  }

  async loadEnvironment() {
    try {
      const response = await apiFetch(apiUrl('/api/v2/merchant-self-service/environment'), {
        method: 'GET', mode: 'cors', credentials: 'include', headers: { 'Content-Type': 'application/json' },
      });
      const data = await response.json();
      if (!response.ok) {
        this.setState({ message: data.message || 'Unable to load environment status.', loading: false });
        return this.state.environment;
      }
      const environment = data.environment || 'SANDBOX';
      this.setState({
        environment,
        environmentStatus: data,
        sandboxGuide: data.sandbox || null,
      });
      return environment;
    } catch (error) {
      this.setState({ message: error.message, loading: false });
      return this.state.environment;
    }
  }

  async loadChannels(environment = this.state.environment) {
    this.setState({ loading: true });
    try {
      const response = await apiFetch(apiUrl('/api/v2/merchant-self-service/channels'), {
        method: 'GET', mode: 'cors', credentials: 'include', headers: { 'Content-Type': 'application/json' },
      });
      const data = await response.json();
      if (!response.ok) {
        this.setState({ message: data.message || 'Unable to load channels.', loading: false });
        return;
      }
      const channels = Array.isArray(data) ? data : [];
      const selectedCode = this.state.selected?.channelCode || channels[0]?.channelCode;
      const baseSelected = channels.find(channel => channel.channelCode === selectedCode) || null;
      const selected = baseSelected ? environmentRecord(baseSelected, environment) : null;
      this.setState({
        channels,
        selected,
        values: editableCredentials(selected?.credentials),
        loading: false,
      });
    } catch (error) {
      this.setState({ message: error.message, loading: false });
    }
  }

  async setEnvironment(environment) {
    if (environment === this.state.environment) return;
    this.setState({ loading: true, message: '' });
    try {
      const response = await apiFetch(apiUrl('/api/v2/merchant-self-service/environment'), {
        method: 'POST', mode: 'cors', credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ environment }),
      });
      const data = await response.json();
      if (!response.ok) {
        this.setState({ message: data.message || 'Unable to switch environment.', loading: false });
        return;
      }
      this.setState({
        environment: data.environment || environment,
        environmentStatus: data,
        sandboxGuide: data.sandbox || this.state.sandboxGuide,
      }, () => this.loadChannels(data.environment || environment));
    } catch (error) {
      this.setState({ message: error.message, loading: false });
    }
  }

  fieldsFor(channelCode) {
    const base = ['collectUrl', 'payoutUrl', 'authHeaderName', 'authHeaderValue'];
    if (channelCode === 'mtn_momo') return [
      'baseUrl', 'targetEnvironment', 'baseCurrency', 'callbackHost', 'callbackUrl',
      'collectionApiUser', 'collectionApiKey', 'collectionSubscriptionKey', 'collectionSecondarySubscriptionKey',
      'disbursementApiUser', 'disbursementApiKey', 'disbursementSubscriptionKey', 'disbursementSecondarySubscriptionKey',
    ];
    if (channelCode === 'safaricom_mpesa') return base.concat(['shortCode', 'consumerKey', 'consumerSecret', 'passKey']);
    if (channelCode === 'airtel_open_api') return [
      'baseUrl', 'clientId', 'clientSecret', 'country', 'currency', 'apiPin', 'publicKey',
      'tokenPath', 'collectionPath', 'payoutPath', 'balancePath',
    ];
    return base.concat(['apiUser', 'apiKey', 'collectionAccount']);
  }

  select(channel) {
    const selected = environmentRecord(channel, this.state.environment);
    this.setState({ selected, values: editableCredentials(selected.credentials), message: '' });
  }

  selectChannelCode(channelCode) {
    const channel = this.state.channels.find(item => item.channelCode === channelCode);
    if (channel) this.select(channel);
  }

  update(field, value) {
    this.setState((prev) => ({ values: { ...prev.values, [field]: value } }));
  }

  applySandboxCredentials() {
    if (!this.state.selected) return;
    const isMtn = this.state.selected.channelCode === 'mtn_momo';
    this.setState({
      values: { ...(this.state.selected.sandboxCredentials || {}) },
      message: isMtn
        ? 'Official MTN sandbox defaults loaded. Add your provisioned Collection and Disbursement credentials before saving.'
        : this.state.selected.channelCode === 'airtel_open_api'
          ? 'Official Airtel UAT endpoints loaded. Add the client ID, client secret, API PIN, and Airtel RSA public key issued for your application.'
          : 'Sandbox credentials loaded for local testing.',
    });
  }

  async save() {
    if (!this.state.selected) return;
    await this.call('/api/v2/merchant-self-service/channels/save', {
      channelCode: this.state.selected.channelCode,
      environment: this.state.environment,
      credentials: this.state.values,
      revision: this.state.selected.revision ?? 0,
    }, `${this.state.environment} channel details saved.`);
  }

  async test() {
    if (!this.state.selected) return;
    await this.call('/api/v2/merchant-self-service/channels/test', {
      connectivity: ['mtn_momo', 'airtel_open_api'].includes(this.state.selected.channelCode),
      channelCode: this.state.selected.channelCode,
      environment: this.state.environment,
    }, `${this.state.environment} connection check completed.`);
  }

  async submitForApproval() {
    if (!this.state.selected) return;
    await this.call('/api/v2/merchant-self-service/channels/submit', {
      channelCode: this.state.selected.channelCode,
      environment: this.state.environment,
    }, `${this.state.environment} channel submitted for approval.`);
  }

  async call(path, body, successMessage) {
    this.setState({ loading: true, message: '' });
    try {
      const response = await apiFetch(apiUrl(path), {
        method: 'POST', mode: 'cors', credentials: 'include',
        headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
      });
      const data = await response.json();
      if (!response.ok) { this.setState({ message: data.message || 'Action failed.', loading: false }); return; }
      this.setState({ message: successMessage, loading: false });
      await this.loadChannels(this.state.environment);
    } catch (error) {
      this.setState({ message: error.message, loading: false });
    }
  }

  renderEnvironmentSwitch() {
    const limit = this.state.environmentStatus?.productionLimit || {};
    return (
      <div className="ios-channel-env-panel">
        <div className="ios-channel-env-switch" role="tablist" aria-label="Gateway environment">
          {ENVIRONMENTS.map(option => (
            <button
              key={option.id}
              type="button"
              role="tab"
              aria-selected={this.state.environment === option.id}
              onClick={() => this.setEnvironment(option.id)}
            >
              <strong>{option.label}</strong>
              <span>{option.copy}</span>
            </button>
          ))}
        </div>
        <div className="ios-channel-env-limit">
          <strong>Production limit</strong>
          <span>
            {limit.enabled === false
              ? 'Disabled'
              : `${limit.remainingToday ?? limit.limit ?? 10} of ${limit.limit ?? 10} live transactions remaining today`}
          </span>
        </div>
      </div>
    );
  }

  renderSandboxGuide() {
    const guide = this.state.sandboxGuide;
    if (!guide || this.state.environment !== 'SANDBOX') return null;
    const testAccounts = Array.isArray(guide.testAccounts) ? guide.testAccounts.slice(0, 6) : [];
    const envVars = guide.environmentVariables || {};
    return (
      <Card className="ios-channel-guide">
        <div>
          <span className="ios-channel-eyebrow">Developer sandbox</span>
          <h3 className="ios-section-title">First integration checklist</h3>
          <p className="ios-channel-subtitle">Use these values with the API guide to send your first signed collection or payout.</p>
        </div>
        <div className="ios-channel-guide-grid">
          <div>
            <strong>Base URL</strong>
            <code>{guide.sandboxBaseUrl}</code>
          </div>
          <div>
            <strong>Merchant number</strong>
            <code>{guide.merchantNumber}</code>
          </div>
          <div>
            <strong>Idempotency</strong>
            <code>{guide.idempotencyWindowHours || 24} hours</code>
          </div>
        </div>
        <div className="ios-channel-envvars">
          {Object.entries(envVars).slice(0, 6).map(([key, value]) => (
            <span key={key}><strong>{key}</strong><code>{String(value)}</code></span>
          ))}
        </div>
        <div className="ios-channel-test-list">
          {testAccounts.map(account => (
            <span key={account.account}>
              <code>{account.account}</code>
              <strong>{account.description}</strong>
              <em>{account.expected}</em>
            </span>
          ))}
        </div>
      </Card>
    );
  }

  renderSelected() {
    const selected = this.state.selected;
    if (!selected) {
      return (
        <Card className="ios-channel-empty">
          <h3 className="ios-section-title">Select a provider</h3>
          <p>Choose a channel to configure sandbox credentials, test readiness, and submit for production approval.</p>
        </Card>
      );
    }

    if (selected.channelCode === 'cpay_shared') {
      const rails = Array.isArray(selected.rails) ? selected.rails : [];
      return (
        <Card className="ios-channel-panel">
          <Toolbar>
            <div>
              <h3 className="ios-section-title" style={{ margin: 0 }}>CPay Shared Payments</h3>
              <p className="ios-channel-subtitle">CPay-managed MTN and Airtel access while your own provider credentials are not approved.</p>
            </div>
            <Toolbar.Spacer />
            <Badge tone={statusTone(selected.status)}>{selected.status || 'NOT_AVAILABLE'}</Badge>
          </Toolbar>
          <div className="ios-channel-sandbox-callout">
            <div>
              <strong>No provider credentials required from you</strong>
              <span>Collections use the first entitled, ready underlying rail. Payouts require separate approval and sufficient prefunded CPay disbursement float.</span>
            </div>
          </div>
          <div className="ios-channel-test-list">
            {rails.length ? rails.map((rail, index) => (
              <span key={`${rail.channelCode}-${rail.operation}-${index}`}>
                <strong>{rail.channelCode === 'mtn_momo' ? 'MTN MoMo' : 'Airtel Open API'} · {rail.operation}</strong>
                <code>{rail.currencyCode} {rail.perTransactionLimit ?? 'No'} per transaction</code>
                <em>{rail.status} · {rail.credentialStatus} · {rail.remainingToday ?? '—'} remaining today</em>
              </span>
            )) : <span><strong>Not available in {this.state.environment}</strong><em>Switch to Production or ask an administrator to provision this environment.</em></span>}
          </div>
          <Toolbar>
            <Button variant="ghost" className="ios-btn--sm" onClick={() => this.selectChannelCode('mtn_momo')}>Add own MTN credentials</Button>
            <Button variant="ghost" className="ios-btn--sm" onClick={() => this.selectChannelCode('airtel_open_api')}>Add own Airtel credentials</Button>
          </Toolbar>
          <p className="ios-channel-subtitle">Once your approved provider-owned credentials are active, they take precedence. CPay Shared Payments will not silently take over that transaction path.</p>
        </Card>
      );
    }

    return (
      <Card className="ios-channel-panel">
        <Toolbar>
          <div>
            <h3 className="ios-section-title" style={{ margin: 0 }}>{selected.displayName}</h3>
            <p className="ios-channel-subtitle">{selected.countryCode} {selected.channelCode === 'mtn_momo' && this.state.environment === 'SANDBOX' ? 'EUR' : selected.currencyCode} - {this.state.environment}</p>
          </div>
          <Toolbar.Spacer />
          <Badge tone={statusTone(selected.status)}>{selected.status || 'NOT_CONFIGURED'}</Badge>
        </Toolbar>
        {this.state.environment === 'SANDBOX' ? (
          <div className="ios-channel-sandbox-callout">
            <div>
              <strong>Guided sandbox mode</strong>
              <span>{selected.channelCode === 'mtn_momo'
                ? 'Use EUR, X-Target-Environment sandbox, and separate Collection and Disbursement product credentials provisioned by MTN.'
                : selected.channelCode === 'airtel_open_api'
                  ? 'Use the Airtel UAT base URL and the OAuth client credentials, API PIN, and RSA public key issued in the Airtel developer portal.'
                : 'Blank endpoints use CPay\'s deterministic simulator; add URLs only when testing your own callback receiver.'}</span>
            </div>
            <Button variant="ghost" className="ios-btn--sm" onClick={() => this.applySandboxCredentials()}>Load sandbox template</Button>
          </div>
        ) : null}
        <p>Blank secret fields preserve stored values. Saving changes requires a new connection check and approval.</p>
        {selected.lastTestMessage ? <p role="status">{selected.lastTestMessage}</p> : null}
        {selected.decisionReason ? <p>Review decision: {selected.decisionReason}</p> : null}
        <div className="ios-channel-form">
          {this.fieldsFor(selected.channelCode).map((field) => field === 'publicKey' ? (
            <label key={field} htmlFor="airtel-public-key">Airtel RSA public key
              <textarea style={{ width: '100%', maxWidth: '100%', boxSizing: 'border-box' }} id="airtel-public-key" rows={6} value={this.state.values[field] || ''} onChange={(event) => this.update(field, event.target.value)} />
            </label>
          ) : (
            <TextField
              key={field}
              id={`channel-${this.state.environment}-${field}`}
              label={`${FIELD_LABELS[field] || field}${String(selected.credentials?.[field] || '').includes('****') ? ' (stored; leave blank to keep)' : ''}`}
              value={this.state.values[field] || ''}
              onValueChange={(value) => this.update(field, value)}
              type={SECRET_FIELDS.has(field) ? 'password' : 'text'}
              autoComplete={SECRET_FIELDS.has(field) ? 'new-password' : 'off'}
            />
          ))}
        </div>
        <Toolbar>
          <Button variant="primary" className="ios-btn--sm" loading={this.state.loading} onClick={() => this.save()}>Save</Button>
          <Button variant="ghost" className="ios-btn--sm" loading={this.state.loading} onClick={() => this.test()}>Verify connection</Button>
          <Button variant="secondary" className="ios-btn--sm" loading={this.state.loading} onClick={() => this.submitForApproval()}>
            Submit for review
          </Button>
        </Toolbar>
      </Card>
    );
  }

  render() {
    return (
      <Card className="ios-channel-page">
        <Toolbar>
          <div>
            <h2 className="ios-section-title" style={{ margin: 0 }}>Payment Channels</h2>
            <p className="ios-channel-subtitle">Connect MTN, Airtel, M-Pesa, Yo! Payments, and other active settlement channels.</p>
          </div>
          <Toolbar.Spacer />
          <Button variant="ghost" className="ios-btn--sm" loading={this.state.loading} onClick={() => this.loadPage()}>Refresh cards</Button>
        </Toolbar>
        {this.renderEnvironmentSwitch()}
        {this.state.message ? <div className="ios-channel-message">{this.state.message}</div> : null}
        <div className="ios-channel-layout">
          <div className="ios-channel-list" aria-label="Payment channels">
            {this.state.channels.map((channel) => {
              const environmentChannel = environmentRecord(channel, this.state.environment);
              return (
                <button
                  key={channel.channelCode}
                  type="button"
                  className={`ios-channel-option ${this.state.selected?.channelCode === channel.channelCode ? 'ios-channel-option--active' : ''}`.trim()}
                  onClick={() => this.select(channel)}
                >
                  <strong>{channel.displayName}</strong>
                  <span>{channel.channelCode === 'cpay_shared'
                    ? `MTN + Airtel · ${this.state.environment}`
                    : `${channel.countryCode} ${channel.channelCode === 'mtn_momo' && this.state.environment === 'SANDBOX' ? 'EUR' : channel.currencyCode} - ${this.state.environment}`}</span>
                  <Badge tone={statusTone(environmentChannel.status)}>{environmentChannel.status || 'NOT_CONFIGURED'}</Badge>
                </button>
              );
            })}
          </div>
          <div className="ios-channel-detail">
            {this.renderSelected()}
            {this.renderSandboxGuide()}
          </div>
        </div>
      </Card>
    );
  }
}

export default MerchantModulePaymentChannelsV2;
