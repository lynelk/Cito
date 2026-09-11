"""Apply the reviewed MTN settings correction to the pinned baseline, without secrets."""
from pathlib import Path
import subprocess

root = Path('.')
settings = root / 'clientside/src/components/modules/ModuleSettings.jsx'
provider = root / 'clientside/src/features/ProviderTreasuryConsole.tsx'
for path, expected in ((settings, 'ef58292f7adb725b81876eaaa8814ee0a8496852'), (provider, '55f7f16ef1b3fbdec7fa3d86db1a99ac1e9e49ae')):
    actual = subprocess.check_output(['git', 'hash-object', str(path)], text=True).strip()
    if actual != expected:
        raise SystemExit(f'Baseline changed: {path}; review before applying')

s = settings.read_text()
s = s.replace("import React from 'react';", "import React from 'react';\nimport { Link } from 'react-router-dom';")
s = s.replace('const ENVIRONMENT_OPTIONS = [', "const APPLICATION_ENVIRONMENT_OPTIONS = [\n    { value: 'production', label: 'Production' },\n    { value: 'sandbox', label: 'Sandbox' },\n];\n\nconst ENVIRONMENT_OPTIONS = [")
s = s.replace('body: JSON.stringify(this.state.data)', 'body: JSON.stringify(this.dirtyRows().filter(row => !isManagedMtnConnectionSetting(row)))')
s = s.replace('function settingGroup(row) {', "function isManagedMtnConnectionSetting(row) {\n    return settingGroup(row) === 'MTN' && !/(cost_of|customer_charge)/i.test(settingName(row));\n}\n\nfunction settingGroup(row) {")
s = s.replace('.filter(row => section.groupNames.includes(settingGroup(row)))', '.filter(row => section.groupNames.includes(settingGroup(row)))\n            .filter(row => !isManagedMtnConnectionSetting(row))', 1)
s = s.replace('return this.state.data.filter(row => isRequiredLike(row) && isBlank(row));', 'return this.state.data.filter(row => !isManagedMtnConnectionSetting(row) && isRequiredLike(row) && isBlank(row));')
a = s.index('    testConnection(section) {')
b = s.index('    renderEditor(row, index) {', a)
s = s[:a] + s[b:]
s = s.replace("const statusTone = missingCount > 0 ? 'warning' : 'success';", "const statusTone = missingCount > 0 ? 'warning' : 'neutral';")
s = s.replace("{missingCount > 0 ? `${missingCount} missing` : 'Connected'}", "{missingCount > 0 ? `${missingCount} missing` : 'Not verified here'}")
a = s.index('                <dl>', s.index('    renderProviderOverview'))
b = s.index('\n            </section>', a)
s = s[:a] + '''                <p>Stored settings do not prove provider authentication or payment readiness.</p>
                {['mtn', 'airtel'].includes(section.id) ? (
                    <Link className="ios-btn ios-btn--ghost" to={`/bo/provider-treasury?channel=${section.id === 'mtn' ? 'mtn_momo' : 'airtel_open_api'}#platform-provider-credentials`}>
                        Open governed provider connection
                    </Link>
                ) : <p>Provider verification evidence is not available in this settings view.</p>}''' + s[b:]
s = s.replace('                {this.renderProviderOverview(section, rows)}', '''                {section.id === 'mtn' ? (
                    <section className="cpay-settings-card" aria-label="MTN connection configuration">
                        <h3>MTN connection configuration has moved</h3>
                        <p>Configure MTN API users, API keys, subscription keys and callbacks in the governed provider workspace. Sandbox uses EUR; Uganda production uses UGX and mtnuganda. Saving general settings does not activate the connection.</p>
                        <Link className="ios-btn ios-btn--primary" to="/bo/provider-treasury?channel=mtn_momo#platform-provider-credentials">Configure and verify MTN MoMo</Link>
                        <p>Legacy connection values are retained for compatibility, not copied or activated automatically. The pricing controls below remain separate from authentication.</p>
                    </section>
                ) : this.renderProviderOverview(section, rows)}''')
s = s.replace('const connected = providerSections.filter', 'const configured = providerSections.filter')
s = s.replace('`${connected} Connected`', '`${configured} with stored settings`')
s = s.replace("emailConnected ? 'Connected' : 'Not configured'", "emailConnected ? 'Configured, not verified' : 'Not configured'")
s = s.replace('options={ENVIRONMENT_OPTIONS}\n                            onValueChange', 'options={APPLICATION_ENVIRONMENT_OPTIONS}\n                            onValueChange')
s = s.replace('<Icons.HistoryIcon size={15} />Audit history', '<Icons.HistoryIcon size={15} />Review pending changes')
settings.write_text(s)

s = provider.read_text()
s = s.replace("import { request }", "import { changeProviderScope, mtnProfile } from './providerConnectionProfile';\nimport { request }")
s = s.replace('const [credential, setCredential] = useState({', 'const [credential, setCredential] = useState(() => changeProviderScope({')
s = s.replace("channelCode: 'airtel_money', environment: 'PRODUCTION', countryCode: 'UG', currencyCode: 'UGX',\n    collectUrl:", "channelCode: 'mtn_momo', environment: 'SANDBOX',\n    collectUrl:")
s = s.replace("    baseUrl: '', targetEnvironment: '', baseCurrency: 'UGX', callbackHost: '', callbackUrl: '',", "    ...mtnProfile('SANDBOX'), callbackHost: '', callbackUrl: '',")
s = s.replace("    balancePath: '/standard/v2/users/balance',\n  });", "    balancePath: '/standard/v2/users/balance',\n  }, new URLSearchParams(window.location.search).get('channel') === 'airtel_open_api' ? 'airtel_open_api' : 'mtn_momo', 'SANDBOX'));")
a = s.index('              setCredential({ ...credential, channelCode,', s.index('<Section title="CPay Platform Provider Credentials">'))
b = s.index('\n            }}><option', a)
s = s[:a] + '              setCredential(changeProviderScope(credential, channelCode, credential.environment));' + s[b:]
a = s.index('              setCredential({ ...credential, environment,', a)
b = s.index('\n            }}><option', a)
s = s[:a] + '              setCredential(changeProviderScope(credential, credential.channelCode, environment));' + s[b:]
s = s.replace('<Section title="CPay Platform Provider Credentials">', '<div id="platform-provider-credentials" />\n      <Section title="CPay Platform Provider Credentials">')
s = s.replace('Credential editor and approver must be different operators.</p>', 'Credential editor and approver must be different operators. An API key is not a subscription key or portal password. Switching provider or environment clears unsaved credentials and callback values. This release requires both MTN products for connection verification; Collections-only activation is not yet supported.</p>')
s = s.replace('required type="url" style={fieldStyle} value={credential.baseUrl}', "required type=\"url\" readOnly={credential.channelCode === 'mtn_momo'} style={fieldStyle} value={credential.baseUrl}", 1)
s = s.replace('<label>X-Target-Environment<input required', '<label>X-Target-Environment<input readOnly required')
s = s.replace('<label>MTN base currency<input required', '<label>MTN base currency<input readOnly required')
s = s.replace('<label>Country<input required style={fieldStyle} value={credential.countryCode}', "<label>Country<input readOnly={credential.channelCode === 'mtn_momo'} required style={fieldStyle} value={credential.countryCode}", 1)
s = s.replace('<label>Currency<input required style={fieldStyle} value={credential.currencyCode}', "<label>Currency<input readOnly={credential.channelCode === 'mtn_momo'} required style={fieldStyle} value={credential.currencyCode}", 1)
provider.write_text(s)

note = '\n\nMTN connection configuration and release acceptance: see `Docs/Operations/mtn-promotion-20260911.md`. General Settings now links to the governed provider credential workspace; populated settings are not authentication evidence. No provider activation or automatic credential migration occurs.\n'
for name in ('Readme.md', 'Installation.md', 'Deployment.md', 'CI_CD_SETUP.md'):
    path = root / name
    path.write_text(path.read_text() + note)
guide = root / 'Docs/Api/Cito-Gateway-Integration-Guide.md'
guide.write_text(guide.read_text() + '''

## MTN configuration ownership and verification

Platform administrators configure CPay-owned MTN connections in `/bo/provider-treasury?channel=mtn_momo#platform-provider-credentials`. Settings -> MTN MoMo links there and retains separate pricing controls. Merchant-owned connections remain in merchant Payment channels and do not expose platform secrets. Stored settings are not connectivity evidence.

The governed platform form derives MTN's API origin, target and currency: Sandbox uses sandbox/EUR; Uganda production uses mtnuganda/UGX. Changing provider or environment clears unsaved credential and callback values. API users/API keys differ from product subscription keys and portal passwords. Use the server-side Verify connection action followed by independent approval; verification requires both MTN products in this release. Authentication alone is not payment or callback certification. No automatic migration or activation of legacy connection settings is performed.
''')
