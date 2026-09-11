import ApiReference from '../features/ApiReference';
import React from 'react';
import Messager from './StableMessager';
import strings from './locale';
import { withRouter } from '../shared/router/compat';
import MainMenuMerchant from "./MainMenuMerchant";
import Progress from "./Progress";
import Logo from "../media/images/gwlogo.png";
import {
  Shell, Sidebar, Brand, TopBar, IconButton, UserChip, Page,
  EnvironmentSwitcher, ThemeToggle, Icons,
} from '../ui';
import ExperienceWorkspace from '../features/ExperienceWorkspace';
import MerchantServicePortfolio from '../features/MerchantServicePortfolio';

import MerchantModuleDashboard from './modules/merchant/MerchantModuleDashboard';
import MerchantModuleAdmins from './modules/merchant/MerchantModuleAdmins';
import MerchantModuleSettings from './modules/merchant/MerchantModuleSettings';
import MerchantModuleAuditTrail from './modules/merchant/MerchantModuleAuditTrail';
import MerchantModulePayments from './modules/merchant/MerchantModulePayments';
import MerchantModuleTransactions from './modules/merchant/MerchantModuleTransactions';
import MerchantModuleMerchantAccount from './modules/merchant/MerchantModuleMerchantsAccount';
import MerchantModuleSms from './modules/merchant/MerchantModuleSms';
import MerchantModulePaymentChannels from './modules/merchant/MerchantModulePaymentChannels';
import MerchantModuleWebhooks from './modules/merchant/MerchantModuleWebhooks';
import MerchantModuleVending from './modules/merchant/MerchantModuleVending';
import MerchantModuleSandbox from './modules/merchant/MerchantModuleSandbox';
import MerchantModuleCitoServices from './modules/merchant/MerchantModuleCitoServices';

import { apiFetch } from '../shared/api/httpClient';
import { apiUrl } from '../shared/config';
import { readStoredUser } from '../shared/useAuth';

const menuTitles = {
  home: { title: 'Dashboard', subtitle: 'Activation status, balances, live activity and next actions.' },
  'balances-settlements': { title: 'Balances & Settlements', subtitle: 'Available funds, statements, reconciliation and settlement evidence.' },
  customers: { title: 'Customers', subtitle: 'Customers created by real payment and billing journeys.' },
  developers: { title: 'Developers', subtitle: 'Sandbox, applications, credentials, webhooks, logs and go-live.' },
  services: { title: 'Services & Products', subtitle: 'Payments, communications, identity and scoring, vending, billing and integrations.' },
  reports: { title: 'Reports', subtitle: 'Transactions, statements, exports and operational reports.' },
  business: { title: 'Business', subtitle: 'Team, roles, billing and commercial context.' },
  help: { title: 'Help & Support', subtitle: 'Support cases with transaction and account context.' },
  notifications: { title: 'Notifications', subtitle: 'Account, payment and operational updates.' },
  'transaction-detail': { title: 'Transaction Detail', subtitle: 'Finality, provider, reconciliation and settlement evidence.' },
  dashboard: { title: 'Dashboard', subtitle: strings.menu_dashboard_subtitle_merchant },
  sandbox: { title: 'Sandbox & Go-Live', subtitle: 'Test safely, certify the integration and graduate to production.' },
  'cito-services': { title: 'Cito Services', subtitle: 'Entitlements, orchestration, marketplace, intelligence and platform tools.' },
  channels: { title: strings.menu_channels, subtitle: strings.menu_channels_subtitle },
  statement: { title: strings.menu_statement, subtitle: strings.menu_statement_subtitle },
  webhooks: { title: strings.menu_webhooks, subtitle: strings.menu_webhooks_subtitle },
  payments: { title: 'Payments', subtitle: strings.menu_payments_subtitle },
  vending: { title: 'Vending & Utilities', subtitle: 'Devices, pricing, rentals, QR journeys and manufacturer integration.' },
  sms: { title: 'Communications', subtitle: 'SMS and configured communication channels, routing, delivery and usage.' },
  transactions: { title: strings.menu_transactions, subtitle: strings.menu_transactions_subtitle_merchant },
  admins: { title: strings.menu_admins, subtitle: strings.menu_admins_subtitle_merchant },
  audittrail: { title: strings.menu_audittrail, subtitle: strings.menu_audittrail_subtitle_merchant },
  settings: { title: strings.settings, subtitle: strings.menu_settings_subtitle_merchant },
};

const merchantRoutes = {
  home: '/fo/dashboard',
  payments: '/fo/payments',
  'balances-settlements': '/fo/balances-settlements',
  customers: '/fo/customers',
  developers: '/fo/developers',
  services: '/fo/services',
  reports: '/fo/reports',
  business: '/fo/business',
  help: '/fo/help',
  settings: '/fo/settings',
  notifications: '/fo/notifications',
};

function merchantMenuFromPath(pathname) {
  if (/\/(?:fo|bo\/partner)\/transactions\/[^/]+/.test(pathname)) return 'transaction-detail';
  const segment = pathname.replace(/^\/(?:fo|bo\/partner)\/?/, '').split('/')[0];
  const aliases = { dashboard: 'home', statement: 'balances-settlements', sandbox: 'developers', 'cito-services': 'services', transactions: 'reports' };
  return aliases[segment] || (menuTitles[segment] ? segment : 'home');
}

class LayoutMerchantWithOutRouter extends React.Component {
  constructor(props) {
    super(props);
    this.chartRef = React.createRef();

    this.state = {
      loader: false,
      isLogged: false,
      progressValue: 0,
      currentMenuKey: merchantMenuFromPath(props.location?.pathname || ''),
      refreshTick: 0,
      user: readStoredUser('merchant'),
      entitlements: undefined,
    };
    this.menuChanged = this.menuChanged.bind(this);
    this.refreshCurrentPage = this.refreshCurrentPage.bind(this);
    this.sessionExpired = this.sessionExpired.bind(this);
    this.logoutUser = this.logoutUser.bind(this);
    this.startOrStopLoader = this.startOrStopLoader.bind(this);
  }

  async componentDidMount() {
    this.chartRef = React.createRef();
    const isLoggedIn = await this.isLoggedIn();
    const { history } = this.props;
    if (!isLoggedIn) {
      this.setState({ isLogged: false });
      this.messager.alert({
        title: strings.session_expired_title,
        icon: "info",
        msg: strings.session_expired_message,
        result: () => history.push("/")
      });
    } else {
      this.setState({ isLogged: true });
      this.loadEntitlements();
    }
  }

  componentDidUpdate(previousProps) {
    if (previousProps.location?.pathname !== this.props.location?.pathname) {
      const item = merchantMenuFromPath(this.props.location.pathname);
      if (item !== this.state.currentMenuKey) {
        this.setState({ currentMenuKey: item });
      }
    }
  }

  async loadEntitlements() {
    const merchantId = Number(this.state.user?.merchant_id || this.state.user?.merchantId);
    if (!Number.isFinite(merchantId) || merchantId <= 0) {
      this.setState({ entitlements: [] });
      return;
    }
    try {
      const response = await apiFetch(`/api/v2/merchants/${merchantId}/overview`);
      if (!response.ok) return;
      const body = await response.json();
      const rows = Array.isArray(body.entitlements) ? body.entitlements : [];
      const disabledStatuses = new Set(['REVOKED', 'EXPIRED', 'DISABLED']);
      const entitlements = rows
        .filter((row) => !disabledStatuses.has(String(row.status || '').toUpperCase()))
        .map((row) => row.service_code || row.serviceCode)
        .filter(Boolean)
        .map(String);
      this.setState({ entitlements });
    } catch {
      // Navigation remains usable when entitlement metadata is temporarily unavailable.
    }
  }

  renderModule(item, refreshSignal = this.state?.refreshTick || 0) {
    const moduleProps = {
      sessionExpired: this.sessionExpired,
      logOut: this.logoutUser,
      loader: this.startOrStopLoader,
      refreshSignal,
    };

    switch (item) {
      case 'home': return <><ExperienceWorkspace portal="merchant" section="lifecycle" /><MerchantModuleDashboard {...moduleProps} /></>;
      case 'balances-settlements': return <MerchantModuleMerchantAccount {...moduleProps} />;
      case 'customers': return <ExperienceWorkspace portal="merchant" section="customers" />;
      case 'developers': return <><ApiReference /><MerchantModuleSandbox {...moduleProps} /></>;
      case 'services': return <><MerchantServicePortfolio entitlements={this.state?.entitlements} /><section className="cito-compliance-panel"><div className="cito-section-heading"><div><h3>Advanced service controls</h3><p>Detailed marketplace, recurring, routing, analytics, virtual-account, embedded and connector tools remain available below.</p></div></div><MerchantModuleCitoServices {...moduleProps} /></section></>;
      case 'reports': return <MerchantModuleTransactions {...moduleProps} />;
      case 'business': return <ExperienceWorkspace portal="merchant" section="business" />;
      case 'help': return <ExperienceWorkspace portal="merchant" section="support" />;
      case 'notifications': return <ExperienceWorkspace portal="merchant" section="notifications" />;
      case 'transaction-detail': return <ExperienceWorkspace portal="merchant" section="transaction-detail" />;
      case 'sandbox': return <MerchantModuleSandbox {...moduleProps} />;
      case 'cito-services': return <><MerchantServicePortfolio entitlements={this.state?.entitlements} /><MerchantModuleCitoServices {...moduleProps} /></>;
      case 'channels': return <MerchantModulePaymentChannels {...moduleProps} />;
      case 'statement': return <MerchantModuleMerchantAccount {...moduleProps} />;
      case 'admins': return <MerchantModuleAdmins {...moduleProps} />;
      case 'payments': return <MerchantModulePayments {...moduleProps} />;
      case 'vending': return <MerchantModuleVending {...moduleProps} />;
      case 'webhooks': return <MerchantModuleWebhooks {...moduleProps} />;
      case 'sms': return <MerchantModuleSms {...moduleProps} />;
      case 'transactions': return <MerchantModuleTransactions {...moduleProps} />;
      case 'audittrail': return <MerchantModuleAuditTrail {...moduleProps} />;
      case 'settings': return <MerchantModuleSettings {...moduleProps} />;
      case 'dashboard':
      default: return <MerchantModuleDashboard {...moduleProps} />;
    }
  }

  sessionExpired() {
    const { history } = this.props;
    this.messager.alert({
      title: strings.session_expired_title,
      icon: "info",
      msg: strings.session_expired_message,
      result: () => history.push("/")
    });
  }

  async isLoggedIn() {
    try {
      await this.setState({ loader: true, progressValue: 0 });
      const response = await apiFetch(apiUrl("/auth/isMerchantUserLoggedIn"), {
        method: 'POST',
        mode: 'cors',
        cache: 'no-cache',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        redirect: 'follow',
        referrer: 'no-referrer',
        body: JSON.stringify({})
      });

      await this.setState({ loader: false, progressValue: 0 });
      const res = await response.json();
      return res.code === "000" && res.message === "true";
    } catch {
      this.setState({ loader: false, progressValue: 0 });
      return false;
    }
  }

  menuChanged(item) {
    this.goToScreen(item);
  }

  goToScreen(item) {
    if (item === 'exit') {
      this.logoutUser();
      return;
    }

    const route = merchantRoutes[item];
    if (route) this.props.history.push(route);
    this.setState({ currentMenuKey: item });
  }

  refreshCurrentPage() {
    this.setState((previousState) => ({ refreshTick: previousState.refreshTick + 1 }));
  }

  startOrStopLoader(action) {
    this.setState({ loader: action === "START", progressValue: 0 });
  }

  logoutUser() {
    const { history } = this.props;
    this.setState({ loader: true }, () => {
      apiFetch(apiUrl("/auth/logoutMerchantUser"), {
        method: 'POST',
        mode: 'cors',
        cache: 'no-cache',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        redirect: 'follow',
        referrer: 'no-referrer',
        body: JSON.stringify({})
      }).then(() => {
        localStorage.removeItem("merchantUser");
        history.push("/");
      }).catch(() => {
        localStorage.removeItem("merchantUser");
        history.push("/");
      });
    });
  }

  renderLoadingGate() {
    return (
      <div className="cpay-loading-gate">
        <canvas ref={this.chartRef} />
        <Messager ref={ref => this.messager = ref}></Messager>
        <Progress loaderState={this.state.loader} progressValue={this.state.progressValue} />
      </div>
    );
  }

  render() {
    if (!this.state.isLogged) {
      return this.renderLoadingGate();
    }

    const user = this.state.user || {};
    const current = menuTitles[this.state.currentMenuKey] || menuTitles.dashboard;

    return (
      <Shell
        navOpen={this.state.navOpen}
        sidebar={
          <Sidebar brand={<Brand logo={Logo} name="Cito" product="Merchant Workspace" />}>
            <MainMenuMerchant activeItem={this.state.currentMenuKey} onChangeMenu={this.menuChanged} entitlements={this.state.entitlements} />
          </Sidebar>
        }
        topbar={
          <TopBar
            left={
              <>
                <IconButton label="Navigation" onClick={() => this.setState(s => ({ navOpen: !s.navOpen }))}>
                  <Icons.MenuIcon size={20} />
                </IconButton>
                <button type="button" className="cito-global-search-trigger" onClick={() => this.goToScreen('reports')}>
                  <Icons.SearchIcon size={18} />
                  <span>Search transactions</span>
                  <kbd>/</kbd>
                </button>
              </>
            }
            right={
              <>
                <EnvironmentSwitcher portal="merchant" />
                <ThemeToggle />
                <IconButton label="Notifications" onClick={() => this.goToScreen('notifications')}>
                  <Icons.MailIcon size={19} />
                </IconButton>
                <IconButton label={strings.refresh} onClick={this.refreshCurrentPage}>
                  <Icons.RefreshIcon size={19} />
                </IconButton>
                <UserChip
                  name={user.name || user.username || 'Merchant User'}
                  meta={user.email || user.account_number || 'Signed in'}
                />
              </>
            }
          />
        }
      >
        <Page>
          <div className="cito-page-shell">
            <header className="cito-page-heading">
              <div>
                <span className="cito-page-heading__eyebrow">Cito workspace</span>
                <h1>{current.title}</h1>
                <p>{current.subtitle}</p>
              </div>
            </header>
            {this.renderModule(this.state.currentMenuKey, this.state.refreshTick)}
          </div>
        </Page>

        <Messager ref={ref => this.messager = ref}></Messager>
        <Progress loaderState={this.state.loader} progressValue={this.state.progressValue} />
      </Shell>
    );
  }
}

const LayoutMerchant = withRouter(LayoutMerchantWithOutRouter);
export default LayoutMerchant;
