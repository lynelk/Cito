import React from 'react';
import { mtnWorkspacePath } from '../../features/providerConnectionProfile';
import { Link } from 'react-router-dom';
import Messager from '../StableMessager';
import { withRouter } from '../../shared/router/compat';
import { isSensitiveSetting, maskedSettingValue } from './settingsGridHelpers';
import { Badge, Button, Icons, PasswordField, SearchField, Select, TextArea, TextField } from '../../ui';

import { apiFetch } from '../../shared/api/httpClient';
import { apiUrl } from '../../shared/config';

const SETTINGS_SECTIONS = [
    { id: 'general', title: 'General', groupNames: ['Application'], icon: Icons.SettingsIcon },
    { id: 'login', title: 'Login Portal', groupNames: ['Login Portal'], icon: Icons.UsersIcon },
    { id: 'accounts', title: 'Accounts', groupNames: ['Accounts'], icon: Icons.CardsIcon },
    { id: 'email', title: 'Email', groupNames: ['Email'], icon: Icons.MailIcon },
    { id: 'mtn', title: 'MTN MoMo', groupNames: ['MTN'], icon: Icons.CardsIcon, provider: true },
    { id: 'airtel', title: 'Airtel Money', groupNames: ['Airtel'], icon: Icons.PaymentsIcon, provider: true },
    { id: 'mpesa', title: 'M-Pesa', groupNames: ['Safaricom'], icon: Icons.StoreIcon, provider: true },
    { id: 'notifications', title: 'Notifications', groupNames: ['SMS'], icon: Icons.SmsIcon },
    { id: 'security', title: 'Security & Keys', groupNames: ['Security', 'Keys'], icon: Icons.ShieldIcon },
];

const APPLICATION_ENVIRONMENT_OPTIONS = [
    { value: 'production', label: 'Production' },
    { value: 'sandbox', label: 'Sandbox' },
];

const ENVIRONMENT_OPTIONS = [
    { value: 'mtnuganda', label: 'Production - MTN Uganda' },
    { value: 'production', label: 'Production' },
    { value: 'sandbox', label: 'Sandbox' },
    { value: 'development', label: 'Development' },
];

const CURRENCY_OPTIONS = ['EUR','UGX', 'KES', 'USD', 'TZS'].map(value => ({ value, label: value }));

const CHARGING_METHOD_OPTIONS = [
    { value: 'flat', label: 'Flat' },
    { value: 'percentage', label: 'Percentage' },
];

function settingName(row) {
    return String(row?.name || row?.setting_key || row?.label || '');
}

function settingLabel(row) {
    return row?.label || settingName(row).replace(/[._-]+/g, ' ');
}

function settingValue(row) {
    return row?.setting_value == null ? '' : String(row.setting_value);
}

function isManagedMtnConnectionSetting(row) {
    return settingGroup(row) === 'MTN' && !/(cost_of|customer_charge)/i.test(settingName(row));
}

function settingGroup(row) {
    return row?.setting_group || 'General';
}

function sanitizeId(value) {
    return String(value || 'setting').replace(/[^a-z0-9_-]+/gi, '-').toLowerCase();
}

function cloneSettings(data) {
    return (Array.isArray(data) ? data : []).map(row => ({ ...row }));
}

function rowMatches(row, search) {
    const q = search.trim().toLowerCase();
    if (!q) {
        return true;
    }
    return [settingName(row), settingLabel(row), row.description, settingValue(row), settingGroup(row)]
        .filter(Boolean)
        .some(value => String(value).toLowerCase().includes(q));
}

function isBlank(row) {
    return settingValue(row).trim() === '';
}

function isRequiredLike(row) {
    const name = settingName(row).toLowerCase();
    return /(url|host|port|username|password|user_key|subscription_key|api_key|account|currency|email|from)/.test(name);
}

function isBooleanSetting(row) {
    const name = settingName(row).toLowerCase();
    const value = settingValue(row).toLowerCase();
    return /(^|[._-])(enable|enabled|auth|use|validate|auto)[._-]/.test(name)
        || ['true', 'false'].includes(value);
}

function isCurrencySetting(row) {
    return /(currency)/i.test(settingName(row));
}

function isEnvironmentSetting(row) {
    return /(environment|env|state)$/i.test(settingName(row));
}

function isChargingMethodSetting(row) {
    return /(cost_of|charge)[a-z0-9_]*_method$/i.test(settingName(row));
}

function isLongSetting(row) {
    return /(url|template|tmp_|parameters|public_key|private_key|consumer_secret|subscription_key|api_key|user_key|image_url|callback)/i
        .test(settingName(row));
}

function isTemplateSetting(row) {
    return /(email_tmp|template|parameters|public_key|private_key)/i.test(settingName(row));
}

function fieldSizeClass(row) {
    const name = settingName(row).toLowerCase();
    if (isLongSetting(row)) return 'cpay-settings-field--full';
    if (/(port|timeout|retry|interval|min|max|cost|charge|threshold)/.test(name)) return 'cpay-settings-field--sm';
    if (isCurrencySetting(row) || isBooleanSetting(row) || isEnvironmentSetting(row) || isChargingMethodSetting(row)) return 'cpay-settings-field--sm';
    if (/account|username|email/.test(name)) return 'cpay-settings-field--md';
    return '';
}

function cardMetaForSetting(row, sectionId) {
    const name = settingName(row).toLowerCase();
    if (sectionId === 'general') {
        return name.includes('internal_app_access')
            ? { id: 'access', title: 'Application access', description: 'Control internal access, hostnames and trusted network boundaries.' }
            : { id: 'defaults', title: 'Platform defaults', description: 'Base URLs, environment and operational defaults for the platform.' };
    }
    if (sectionId === 'login') {
        return name.startsWith('admin_login')
            ? { id: 'admin-login', title: 'Admin Login Portal', description: 'Media panel, floating cards and benefit copy for administrator access.' }
            : { id: 'customer-login', title: 'Customer Login Portal', description: 'Media panel, floating cards and benefit copy for customer access.' };
    }
    if (sectionId === 'accounts') {
        return { id: 'accounts', title: 'System accounts', description: 'Operational accounts used for float, revenue, suspense and alerts.' };
    }
    if (sectionId === 'email') {
        if (name.includes('email_tmp')) return { id: 'templates', title: 'Email templates', description: 'Subjects, copy and notification templates sent by CPay.' };
        return { id: 'smtp', title: 'SMTP configuration', description: 'Connection, sender and authentication settings for outbound email.' };
    }
    if (['mtn', 'airtel', 'mpesa'].includes(sectionId)) {
        if (/(url|env|currency|version|shortcode)/.test(name)) {
            return { id: 'connection', title: 'Connection details', description: 'Provider endpoints, environment and currency configuration.' };
        }
        if (/(collections|inbound|collection)/.test(name)) {
            return { id: 'collections', title: 'Collection pricing', description: 'Costs and customer charges for incoming payments.' };
        }
        if (/(disbursement|disbursements|outbound)/.test(name)) {
            return { id: 'disbursements', title: 'Payout pricing', description: 'Costs and customer charges for outgoing payments.' };
        }
        return { id: 'operations', title: 'Operational configuration', description: 'Charges, thresholds and provider-specific operational controls.' };
    }
    if (sectionId === 'notifications') {
        return name.includes('api')
            ? { id: 'sms-gateway', title: 'SMS gateway', description: 'Gateway endpoint, request format and authentication details.' }
            : { id: 'sms-pricing', title: 'Message pricing', description: 'Default provider and charge settings for SMS activity.' };
    }
    return { id: 'section-settings', title: 'Configuration', description: 'Section settings and controls.' };
}

class ModuleSettingsC extends React.Component {
    constructor(props) {
        super(props);
        this.state = {
            data: [],
            baseline: [],
            activeSection: 'general',
            search: '',
        };
    }

    componentDidMount() {
        this.getData();
    }

    getData() {
        this.props.loader("START");
        apiFetch(apiUrl("/settings/getSettings"), {
            method: 'POST', mode: 'cors', cache: 'no-cache', credentials: 'include',
            headers: { 'Content-Type': 'application/json' }, redirect: 'follow', referrer: 'no-referrer',
            body: JSON.stringify({ settings: "all" })
        }).then((response) => response.text()).then((response_) => {
            this.props.loader("STOP");
            let res;
            try {
                res = JSON.parse(response_);
                if (res.code === "000") {
                    const data = Array.isArray(res.data) ? res.data : [];
                    this.setState({ data, baseline: cloneSettings(data) });
                } else {
                    if (res.code === "107") { this.props.sessionExpired(); return; }
                    this.messager.alert({ title: "Error " + res.code, icon: "error", msg: res.message });
                }
            } catch (Error) {
                this.messager.alert({ title: "Error", icon: "error", msg: Error.message });
            }
        }).catch((error) => {
            this.props.loader("STOP");
            this.messager.alert({ title: "Error", icon: "error", msg: error.message });
        });
    }

    saveSettings() {
        this.props.loader("START");
        apiFetch(apiUrl("/settings/updateSettings"), {
            method: 'POST', mode: 'cors', cache: 'no-cache', credentials: 'include',
            headers: { 'Content-Type': 'application/json' }, redirect: 'follow', referrer: 'no-referrer',
            body: JSON.stringify(this.dirtyRows().filter(row => !isManagedMtnConnectionSetting(row)))
        }).then((response) => response.text()).then((response_) => {
            this.props.loader("STOP");
            let res;
            try {
                res = JSON.parse(response_);
                if (res.code === "000") {
                    this.setState({ baseline: cloneSettings(this.state.data) });
                    this.messager.alert({ title: "Success!", icon: "info", msg: res.message });
                } else {
                    if (res.code === "107") { this.props.sessionExpired(); return; }
                    this.messager.alert({ title: "Error " + res.code, icon: "error", msg: res.message });
                }
            } catch (Error) {
                this.messager.alert({ title: "Error", icon: "error", msg: Error.message });
            }
        }).catch((error) => {
            this.props.loader("STOP");
            this.messager.alert({ title: "Error", icon: "error", msg: error.message });
        });
    }

    updateValue(row, value) {
        this.setState((prev) => ({
            data: prev.data.map((r) => (r === row ? { ...r, setting_value: value } : r)),
        }));
    }

    findSetting(name) {
        return this.state.data.find(row => settingName(row) === name);
    }

    sectionRows(section) {
        return this.state.data
            .filter(row => section.groupNames.includes(settingGroup(row)))
            .filter(row => !isManagedMtnConnectionSetting(row))
            .filter(row => rowMatches(row, this.state.search));
    }

    dirtyRows() {
        const baseline = new Map(this.state.baseline.map(row => [settingName(row), settingValue(row)]));
        return this.state.data.filter(row => baseline.get(settingName(row)) !== settingValue(row));
    }

    missingRows() {
        return this.state.data.filter(row => !isManagedMtnConnectionSetting(row) && isRequiredLike(row) && isBlank(row));
    }

    discardChanges() {
        this.setState({ data: cloneSettings(this.state.baseline) });
    }

    reviewChanges() {
        const changes = this.dirtyRows();
        if (changes.length === 0) {
            this.messager.alert({ title: "No changes", icon: "info", msg: "There are no unsaved settings changes." });
            return;
        }
        const summary = changes.slice(0, 8).map(row => `• ${settingLabel(row)}`).join('\n');
        const more = changes.length > 8 ? `\n• ${changes.length - 8} more changes` : '';
        this.messager.alert({ title: "Review changes", icon: "info", msg: `${summary}${more}` });
    }

    renderEditor(row, index) {
        const id = `setting-${sanitizeId(settingName(row) || index)}`;
        const value = settingValue(row);
        const label = settingLabel(row);

        if (isSensitiveSetting(row)) {
            return (
                <PasswordField
                    id={id}
                    label={label}
                    value={value}
                    onValueChange={(v) => this.updateValue(row, v)}
                    placeholder={maskedSettingValue}
                    autoComplete="new-password"
                />
            );
        }
        if (isBooleanSetting(row)) {
            const raw = value.toLowerCase();
            const numericBoolean = ['1', '0'].includes(raw);
            const truthy = ['true', 'enabled', '1', 'yes'].includes(raw);
            const normalized = numericBoolean ? (truthy ? '1' : '0') : (truthy ? 'true' : 'false');
            const options = numericBoolean
                ? [{ value: '1', label: 'Enabled' }, { value: '0', label: 'Disabled' }]
                : [{ value: 'true', label: 'Enabled' }, { value: 'false', label: 'Disabled' }];
            return (
                <Select
                    id={id}
                    label={label}
                    value={normalized}
                    options={options}
                    onValueChange={(v) => this.updateValue(row, v)}
                />
            );
        }
        if (isChargingMethodSetting(row)) {
            return (
                <Select
                    id={id}
                    label={label}
                    value={value || 'flat'}
                    options={CHARGING_METHOD_OPTIONS}
                    onValueChange={(v) => this.updateValue(row, v)}
                />
            );
        }
        if (isEnvironmentSetting(row)) {
            return (
                <Select
                    id={id}
                    label={label}
                    value={value || 'production'}
                    options={ENVIRONMENT_OPTIONS}
                    onValueChange={(v) => this.updateValue(row, v)}
                />
            );
        }
        if (isCurrencySetting(row)) {
            return (
                <Select
                    id={id}
                    label={label}
                    value={value || 'UGX'}
                    options={CURRENCY_OPTIONS}
                    onValueChange={(v) => this.updateValue(row, v)}
                />
            );
        }
        if (isTemplateSetting(row)) {
            return (
                <TextArea
                    id={id}
                    label={label}
                    rows={3}
                    value={value}
                    onValueChange={(v) => this.updateValue(row, v)}
                />
            );
        }
        return (
            <TextField
                id={id}
                label={label}
                value={value}
                type={/(port|timeout|retry|interval|min|max|cost|charge|threshold)/i.test(settingName(row)) ? 'number' : 'text'}
                onValueChange={(v) => this.updateValue(row, v)}
            />
        );
    }

    renderSettingField(row, index) {
        const missing = isRequiredLike(row) && isBlank(row);
        return (
            <div
                className={`cpay-settings-field ${fieldSizeClass(row)} ${missing ? 'cpay-settings-field--missing' : ''}`.trim()}
                key={settingName(row) || `${settingLabel(row)}-${index}`}
            >
                {this.renderEditor(row, index)}
                {row.description ? <p>{row.description}</p> : null}
                {isSensitiveSetting(row) ? <span className="cpay-settings-secret-note">Masked by default. Leave unchanged to keep the current secret.</span> : null}
                {missing ? <span className="cpay-settings-validation">Required configuration is missing.</span> : null}
            </div>
        );
    }

    renderSettingsCard(meta, rows) {
        return (
            <section className="cpay-settings-card" key={meta.id}>
                <header className="cpay-settings-card-header">
                    <div>
                        <span>{rows.length} settings</span>
                        <h3>{meta.title}</h3>
                        <p>{meta.description}</p>
                    </div>
                </header>
                <div className="cpay-settings-form-grid">
                    {rows.map((row, index) => this.renderSettingField(row, index))}
                </div>
            </section>
        );
    }

    renderProviderOverview(section, rows) {
        if (!section.provider) {
            return null;
        }
        const missingCount = rows.filter(row => isRequiredLike(row) && isBlank(row)).length;
        const env = rows.find(row => /env/i.test(settingName(row))) || this.findSetting('application_settings_state');
        const currency = rows.find(row => /currency/i.test(settingName(row)));
        const statusTone = missingCount > 0 ? 'warning' : 'neutral';
        return (
            <section className="cpay-settings-provider-hero">
                <div className="cpay-settings-provider-logo">{section.title.slice(0, 2)}</div>
                <div>
                    <h3>{section.title}</h3>
                    <p>{settingValue(env) || 'Production'} · Uganda · {settingValue(currency) || 'UGX'}</p>
                    <Badge tone={statusTone}>{missingCount > 0 ? `${missingCount} missing` : 'Not verified here'}</Badge>
                </div>
                <p>Stored settings do not prove provider authentication or payment readiness.</p>
                {['mtn', 'airtel'].includes(section.id) ? (
                    <Link className="ios-btn ios-btn--ghost" to={section.id === 'mtn' ? mtnWorkspacePath(settingValue(this.findSetting('application_settings_state')) || 'production') : '/bo/provider-treasury?channel=airtel_open_api#platform-provider-credentials'}>
                        Open governed provider connection
                    </Link>
                ) : <p>Provider verification evidence is not available in this settings view.</p>}
            </section>
        );
    }

    renderSectionContent(section) {
        const rows = this.sectionRows(section);
        const grouped = rows.reduce((acc, row) => {
            const meta = cardMetaForSetting(row, section.id);
            if (!acc.has(meta.id)) {
                acc.set(meta.id, { meta, rows: [] });
            }
            acc.get(meta.id).rows.push(row);
            return acc;
        }, new Map());

        return (
            <main className="cpay-settings-content">
                <header className="cpay-settings-section-title">
                    <div>
                        <span>{section.groupNames.join(' / ')}</span>
                        <h2>{section.title}</h2>
                    </div>
                    <Badge tone={rows.length > 0 ? 'info' : 'neutral'}>{rows.length} settings</Badge>
                </header>
                {section.id === 'mtn' ? (
                    <section className="cpay-settings-card" aria-label="MTN connection configuration">
                        <h3>Connect MTN MoMo</h3>
                        <p>Configure MTN API users, API keys, subscription keys and callbacks in the governed provider workspace. Sandbox uses EUR; Uganda production uses UGX and mtnuganda. Saving general settings does not activate the connection.</p>
                        <Link className="ios-btn ios-btn--primary" to={mtnWorkspacePath(settingValue(this.findSetting('application_settings_state')) || 'production')}>Configure and verify MTN MoMo</Link>
                        <p>Legacy connection values are retained for compatibility, not copied or activated automatically. The pricing controls below remain separate from authentication.</p>
                    </section>
                ) : this.renderProviderOverview(section, rows)}
                {rows.length === 0 ? (
                    <section className="cpay-settings-card cpay-settings-empty">
                        <h3>No settings found</h3>
                        <p>Clear the search or choose another settings section.</p>
                    </section>
                ) : Array.from(grouped.values()).map(group => this.renderSettingsCard(group.meta, group.rows))}
            </main>
        );
    }

    renderStatusCards() {
        const providerSections = SETTINGS_SECTIONS.filter(section => section.provider);
        const configured = providerSections.filter(section => {
            const rows = this.state.data.filter(row => section.groupNames.includes(settingGroup(row)));
            return rows.length > 0 && rows.some(row => !isBlank(row));
        }).length;
        const missingCount = this.missingRows().length;
        const dirtyCount = this.dirtyRows().length;
        const emailRows = this.state.data.filter(row => settingGroup(row) === 'Email');
        const emailConnected = emailRows.some(row => /smtp.host/i.test(settingName(row)) && !isBlank(row));
        const cards = [
            { id: 'mtn', icon: Icons.CardsIcon, label: 'Integrations', value: `${configured} with stored settings`, meta: `${missingCount} items need attention` },
            { id: 'email', icon: Icons.MailIcon, label: 'Email Service', value: emailConnected ? 'Configured, not verified' : 'Not configured', meta: emailConnected ? 'SMTP settings available' : 'Add SMTP details' },
            { id: 'general', icon: Icons.ShieldIcon, label: 'Missing Configuration', value: `${missingCount} Items`, meta: 'Require your attention' },
            { id: 'general', icon: Icons.HistoryIcon, label: 'Recent Changes', value: `${dirtyCount} Pending`, meta: dirtyCount > 0 ? 'Unsaved in this session' : 'No unsaved changes' },
        ];

        return (
            <section className="cpay-settings-status-grid" aria-label="Settings status summary">
                {cards.map((card, index) => {
                    const Icon = card.icon;
                    return (
                        <button type="button" key={`${card.label}-${index}`} onClick={() => this.setState({ activeSection: card.id })}>
                            <span><Icon size={20} /></span>
                            <strong>{card.label}</strong>
                            <em>{card.value}</em>
                            <small>{card.meta}</small>
                        </button>
                    );
                })}
            </section>
        );
    }

    render() {
        const activeSection = SETTINGS_SECTIONS.find(section => section.id === this.state.activeSection) || SETTINGS_SECTIONS[0];
        const environmentSetting = this.findSetting('application_settings_state');
        const dirtyRows = this.dirtyRows();
        const dirtyCount = dirtyRows.length;

        return (
            <div className="cpay-settings-workspace">
                <header className="cpay-settings-header">
                    <div>
                        <h2>Settings</h2>
                        <p>Configure platform integrations and operational defaults.</p>
                    </div>
                    <div className="cpay-settings-header-actions">
                        <SearchField
                            value={this.state.search}
                            onValueChange={(search) => this.setState({ search })}
                            placeholder="Search settings..."
                            ariaLabel="Search settings"
                        />
                        <Select
                            id="settings-environment"
                            value={settingValue(environmentSetting) || 'production'}
                            options={APPLICATION_ENVIRONMENT_OPTIONS}
                            onValueChange={(value) => environmentSetting ? this.updateValue(environmentSetting, value) : null}
                        />
                        <Button variant="ghost" className="ios-btn--sm" onClick={() => this.reviewChanges()}>
                            <Icons.HistoryIcon size={15} />Review pending changes
                        </Button>
                        <Button variant="ghost" className="ios-btn--sm" onClick={() => this.getData()}>
                            <Icons.RefreshIcon size={15} />Refresh
                        </Button>
                    </div>
                </header>

                {this.renderStatusCards()}

                <section className="cpay-settings-layout">
                    <aside className="cpay-settings-nav" aria-label="Settings sections">
                        {SETTINGS_SECTIONS.map(section => {
                            const Icon = section.icon;
                            const rows = this.state.data.filter(row => section.groupNames.includes(settingGroup(row)));
                            return (
                                <button
                                    type="button"
                                    key={section.id}
                                    className={section.id === activeSection.id ? 'cpay-settings-nav-active' : ''}
                                    onClick={() => this.setState({ activeSection: section.id })}
                                >
                                    <Icon size={17} />
                                    <span>{section.title}</span>
                                    <em>{rows.length}</em>
                                </button>
                            );
                        })}
                    </aside>
                    {this.renderSectionContent(activeSection)}
                </section>

                <footer className="cpay-settings-savebar">
                    <span>{dirtyCount > 0 ? `You have ${dirtyCount} unsaved changes` : 'No unsaved changes'}</span>
                    <div>
                        <Button variant="ghost" className="ios-btn--sm" disabled={dirtyCount === 0} onClick={() => this.discardChanges()}>
                            Discard changes
                        </Button>
                        <Button variant="ghost" className="ios-btn--sm" disabled={dirtyCount === 0} onClick={() => this.reviewChanges()}>
                            Review changes
                        </Button>
                        <Button variant="primary" className="ios-btn--sm" disabled={dirtyCount === 0} onClick={() => this.saveSettings()}>
                            <Icons.SettingsIcon size={15} />Save changes
                        </Button>
                    </div>
                </footer>
                <Messager ref={ref => this.messager = ref}></Messager>
            </div>
        );
    }
}

const ModuleSettings = withRouter(ModuleSettingsC);

export default ModuleSettings;
