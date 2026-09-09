import React from 'react';
import Messager from './StableMessager';
import strings from './locale';
import { withRouter } from '../shared/router/compat';
import MainMenu from "./MainMenu";
import Progress from "./Progress";
import Logo from "../media/images/gwlogo.png";
import {
  Shell, Sidebar, Brand, TopBar, IconButton, UserChip, Page,
  EnvironmentSwitcher, ThemeToggle, Icons,
} from '../ui';
import ExperienceWorkspace from '../features/ExperienceWorkspace';

import ModuleInsights from './modules/ModuleInsights';
import ModuleCitoPlatform from './modules/ModuleCitoPlatform';
import ModuleVending from './modules/ModuleVending';
import ModuleAdmins from './modules/ModuleAdmins';
import ModuleSettings from './modules/ModuleSettings';
import ModuleAuditTrail from './modules/ModuleAuditTrail';
import ModuleMerchants from './modules/ModuleMerchants';
import ModuleTransactions from './modules/ModuleTransactions';
import ModuleReconciliation from './modules/ModuleReconciliation';
import ModuleFinanceClose from './modules/ModuleFinanceClose';
import ModulePayoutApprovals from './modules/ModulePayoutApprovals';
import ModulePayoutControls from './modules/ModulePayoutControls';
import ModuleSettlementClose from './modules/ModuleSettlementClose';
import ModuleWebhookOps from './modules/ModuleWebhookOps';
import ModuleCommunicationRouting from './modules/ModuleCommunicationRouting';
import ModuleCompliance from './modules/ModuleCompliance';
import ModuleKybReview from './modules/ModuleKybReview';
import ModuleCertification from './modules/ModuleCertification';
import ModuleSandboxGoLive from './modules/ModuleSandboxGoLive';
import ModuleTreasury from './modules/ModuleTreasury';
import ProductionMaturityDashboard from '../features/productionMaturity/ProductionMaturityDashboard';

import { apiFetch } from '../shared/api/httpClient';
import { apiUrl } from '../shared/config';
import { readStoredUser } from '../shared/useAuth';

const menuTitles = {
  insights: { title: 'Dashboard', subtitle: 'Live priorities, money movement, service health and recent activity.' },
  home: { title: 'Dashboard', subtitle: 'Live priorities, money movement, service health and recent activity.' },
  'merchants-accounts': { title: 'Merchants / Businesses', subtitle: 'Activation, account controls, lifecycle and merchant context.' },
  'money-operations': { title: 'Payments', subtitle: 'Collections, payouts, transaction review, reconciliation and settlement controls.' },
  'risk-compliance': { title: 'KYC & Identity', subtitle: 'KYB, identity, CRB/scoring, screening, review and compliance controls.' },
  'providers-integrations': { title: 'Integrations / API', subtitle: 'Provider credentials, certification, health, incidents and routing.' },
  platform: { title: 'Billing & BaaS', subtitle: 'Service entitlements, metering, billing, monetisation and product access.' },
  administration: { title: 'Administration', subtitle: 'Users, roles, audit and support operations.' },
  engineering: { title: 'Engineering / Internal', subtitle: 'Production maturity, observability and internal control planes.' },
  search: { title: 'Global Search', subtitle: 'Scoped search across merchants, transactions and support cases.' },
  support: { title: 'Support', subtitle: 'Cases, SLA queues and merchant context.' },
  notifications: { title: 'Notifications', subtitle: 'Operational and account updates.' },
  'transaction-detail': { title: 'Transaction Detail', subtitle: 'Finality, provider, reconciliation and settlement evidence.' },
  'provider-incidents': { title: 'Provider Incidents', subtitle: 'Incident handling and safe status communication.' },
  dashboard: { title: 'Dashboard', subtitle: 'Live priorities, money movement, service health and recent activity.' },
  citoplatform: { title: 'Billing & BaaS', subtitle: 'Service catalogue, merchant entitlements and access governance.' },
  vending: { title: 'Vending & Utilities', subtitle: 'Vending estate, rentals, callbacks, fulfilment and operational events.' },
  merchants: { title: strings.menu_merchants, subtitle: strings.menu_merchants_subtitle },
  transactions: { title: 'Payments', subtitle: strings.menu_transactions_subtitle_admin },
  reconciliation: { title: 'Reconciliation', subtitle: strings.menu_reconciliation_subtitle },
  financeclose: { title: 'Finance Close', subtitle: 'Maker-checker daily close for reconciliation.' },
  payoutapprovals: { title: 'Payout Approvals', subtitle: 'Maker-checker approval queue for limit-parked payouts.' },
  payoutcontrols: { title: 'Payout Controls', subtitle: 'Configure payout risk limits enforced on the v2 path.' },
  settlementclose: { title: 'Settlement Close', subtitle: 'Maker-checker settlement batch close.' },
  webhookops: { title: 'Webhook Ops', subtitle: 'Merchant callback verification and test events.' },
  communicationrouting: { title: 'Communications', subtitle: 'Communication providers, routing rules and delivery configuration.' },
  compliance: { title: 'Compliance', subtitle: 'AML/KYC cases, screening events and compliance profiles.' },
  kybreview: { title: 'KYB Review', subtitle: 'Approve or reject beneficial owners and KYC documents.' },
  certification: { title: 'Certification', subtitle: 'Provider sandbox and statement evidence and approvals.' },
  sandboxgolive: { title: 'Sandbox Go-Live', subtitle: 'Review readiness, promote safe configuration and stage production rollout.' },
  treasury: { title: 'Treasury / Float', subtitle: 'Channel and currency positions, balances and liquidity monitoring.' },
  productionmaturity: { title: 'Production Maturity', subtitle: 'Finance, compliance, cross-border and automation readiness.' },
  admins: { title: strings.menu_admins, subtitle: strings.menu_admins_subtitle_admin },
  audittrail: { title: strings.menu_audittrail, subtitle: strings.menu_audittrail_subtitle_admin },
  settings: { title: strings.settings, subtitle: strings.menu_settings_subtitle_admin },
};

const adminRoutes = {
  insights: '/bo/admin/insights',
  home: '/bo/admin/insights',
  dashboard: '/bo/admin/insights',
  'merchants-accounts': '/bo/admin/merchants-accounts',
  'money-operations': '/bo/admin/money-operations',
  communicationrouting: '/bo/admin/communicationrouting',
  vending: '/bo/admin/vending',
  reconciliation: '/bo/admin/reconciliation',
  treasury: '/bo/admin/treasury',
  'risk-compliance': '/bo/admin/risk-compliance',
  'providers-integrations': '/bo/admin/providers-integrations',
  platform: '/bo/admin/platform',
  administration: '/bo/admin/administration',
  engineering: '/bo/admin/engineering',
  search: '/bo/admin/search',
  support: '/bo/admin/support',
  notifications: '/bo/admin/notifications',
  settings: '/bo/admin/settings',
};

function adminMenuFromPath(pathname) {
  if (/\/bo\/admin\/transactions\/[^/]+/.test(pathname)) return 'transaction-detail';
  if (pathname.includes('/providers-integrations/incidents')) return 'provider-incidents';
  const segment = pathname.replace(/^\/bo\/admin\/?/, '').split('/')[0];
  const aliases = {
    '': 'insights',
    home: 'insights',
    dashboard: 'insights',
    merchants: 'merchants-accounts',
    transactions: 'money-operations',
    compliance: 'risk-compliance',
    certification: 'providers-integrations',
    citoplatform: 'platform',
    admins: 'administration',
    productionmaturity: 'engineering',
  };
  return aliases[segment] || (menuTitles[segment] ? segment : 'insights');
}

class LayoutWithOutRouter extends React.Component {
  constructor(props) {
    super(props);
    this.chartRef = React.createRef();

    const currentMenuKey = adminMenuFromPath(props.location?.pathname || '');
    this.state = {
      loader: false,
      isLogged: false,
      progressValue: 0,
      currentMenuKey,
      refreshTick: 0,
      user: readStoredUser('admin'),
      currentMenuItem: this.renderModule(currentMenuKey, 0),
    };
    this.menuChanged = this.menuChanged.bind(this);
    this.refreshCurrentPage = this.refreshCurrentPage.bind(this);
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
        result: () => history.push("/portal")
      });
    } else {
      this.setState({ isLogged: true });
      const pathname = this.props.location?.pathname || '';
      if (pathname === '/bo/admin' || pathname === '/bo/admin/' || pathname === '/bo/admin/home' || pathname === '/bo/admin/dashboard') {
        history.replace('/bo/admin/insights');
      }
    }
  }

  componentDidUpdate(previousProps) {
    if (previousProps.location?.pathname !== this.props.location?.pathname) {
      const item = adminMenuFromPath(this.props.location.pathname);
      if (item !== this.state.currentMenuKey) {
        this.setState({ currentMenuKey: item, currentMenuItem: this.renderModule(item, this.state.refreshTick) });
      }
    }
  }

  renderModule(item, refreshSignal = this.state?.refreshTick || 0) {
    const moduleProps = {
      sessionExpired: this.sessionExpired?.bind(this),
      logOut: this.logoutUser?.bind(this),
      loader: this.startOrStopLoader?.bind(this),
      refreshSignal,
    };

    switch (item) {
      case 'insights':
      case 'home':
      case 'dashboard': return <ModuleInsights {...moduleProps} />;
      case 'merchants-accounts': return <ModuleMerchants {...moduleProps} />;
      case 'money-operations': return <ModuleTransactions {...moduleProps} />;
      case 'risk-compliance': return <ModuleCompliance {...moduleProps} />;
      case 'providers-integrations': return <ModuleCertification {...moduleProps} />;
      case 'platform': return <ModuleCitoPlatform {...moduleProps} />;
      case 'administration': return <ModuleAdmins {...moduleProps} />;
      case 'engineering': return <ProductionMaturityDashboard {...moduleProps} />;
      case 'search': return <ExperienceWorkspace portal="admin" section="search" />;
      case 'support': return <ExperienceWorkspace portal="admin" section="support" />;
      case 'notifications': return <ExperienceWorkspace portal="admin" section="notifications" />;
      case 'transaction-detail': return <ExperienceWorkspace portal="admin" section="transaction-detail" />;
      case 'provider-incidents': return <ExperienceWorkspace portal="admin" section="provider-incidents" />;
      case 'citoplatform': return <ModuleCitoPlatform {...moduleProps} />;
      case 'vending': return <ModuleVending {...moduleProps} />;
      case 'admins': return <ModuleAdmins {...moduleProps} />;
      case 'merchants': return <ModuleMerchants {...moduleProps} />;
      case 'transactions': return <ModuleTransactions {...moduleProps} />;
      case 'reconciliation': return <ModuleReconciliation {...moduleProps} />;
      case 'financeclose': return <ModuleFinanceClose {...moduleProps} />;
      case 'payoutapprovals': return <ModulePayoutApprovals {...moduleProps} />;
      case 'payoutcontrols': return <ModulePayoutControls {...moduleProps} />;
      case 'settlementclose': return <ModuleSettlementClose {...moduleProps} />;
      case 'webhookops': return <ModuleWebhookOps {...moduleProps} />;
      case 'communicationrouting': return <ModuleCommunicationRouting {...moduleProps} />;
      case 'compliance': return <ModuleCompliance {...moduleProps} />;
      case 'kybreview': return <ModuleKybReview {...moduleProps} />;
      case 'certification': return <ModuleCertification {...moduleProps} />;
      case 'sandboxgolive': return <ModuleSandboxGoLive {...moduleProps} />;
      case 'treasury': return <ModuleTreasury {...moduleProps} />;
      case 'productionmaturity': return <ProductionMaturityDashboard {...moduleProps} />;
      case 'audittrail': return <ModuleAuditTrail {...moduleProps} />;
      case 'settings': return <ModuleSettings {...moduleProps} />;
      default: return <ModuleInsights {...moduleProps} />;
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
      const response = await apiFetch(apiUrl("/auth/isLoggedIn"), {
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

    const route = adminRoutes[item];
    if (route) this.props.history.push(route);
    this.setState({
      currentMenuKey: item,
      currentMenuItem: this.renderModule(item, this.state.refreshTick),
    });
  }

  refreshCurrentPage() {
    this.setState(prevState => {
      const refreshTick = prevState.refreshTick + 1;
      return {
        refreshTick,
        currentMenuItem: this.renderModule(prevState.currentMenuKey, refreshTick),
      };
    });
  }

  logoutUser() {
    this.messager.confirm({
      title: strings.confirm_logout_title,
      msg: strings.confirm_logout_message,
      result: r => {
        if (r) {
          this.logoutSendRequest();
        }
      }
    });
  }

  logoutSendRequest() {
    const { history } = this.props;
    this.setState({ loader: true }, () => {
      apiFetch(apiUrl("/auth/logout"), {
        method: 'POST',
        mode: 'cors',
        cache: 'no-cache',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        redirect: 'follow',
        referrer: 'no-referrer',
        body: JSON.stringify({})
      }).then(response => response.text())
        .then(responseText => {
          let res;
          try {
            res = JSON.parse(responseText);
            this.setState({ loader: false, progressValue: 0 }, () => {
              if (res.code === "000") {
                history.push("/portal");
              } else {
                this.messager.alert({ title: "Error " + res.code, icon: "error", msg: res.message });
              }
            });
          } catch (Error) {
            this.setState({ loader: false, progressValue: 0 });
            this.messager.alert({ title: "Error", icon: "error", msg: Error.message });
          }
        }).catch(error => {
          this.setState({ loader: false, progressValue: 0 });
          this.messager.alert({ title: "Error", icon: "error", msg: error.message });
        });
    });
  }

  startOrStopLoader(operation) {
    this.setState({ loader: operation === "START" });
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
    const current = menuTitles[this.state.currentMenuKey] || menuTitles.insights;

    return (
      <Shell
        navOpen={this.state.navOpen}
        sidebar={
          <Sidebar brand={<Brand logo={Logo} name="Cito" product="Operations Console" />}>
            <MainMenu activeItem={this.state.currentMenuKey} onChangeMenu={this.menuChanged} />
          </Sidebar>
        }
        topbar={
          <TopBar
            left={
              <>
                <IconButton label="Navigation" onClick={() => this.setState(s => ({ navOpen: !s.navOpen }))}>
                  <Icons.MenuIcon size={20} />
                </IconButton>
                <button type="button" className="cito-global-search-trigger" onClick={() => this.goToScreen('search')}>
                  <Icons.SearchIcon size={18} />
                  <span>Search Cito</span>
                  <kbd>/</kbd>
                </button>
              </>
            }
            right={
              <>
                <EnvironmentSwitcher portal="admin" />
                <ThemeToggle />
                <IconButton label="Notifications" onClick={() => this.goToScreen('notifications')}>
                  <Icons.MailIcon size={19} />
                </IconButton>
                <IconButton label={strings.refresh} onClick={this.refreshCurrentPage}>
                  <Icons.RefreshIcon size={19} />
                </IconButton>
                <UserChip name={user.name || 'User'} meta={user.email || 'Signed in'} />
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
            {this.state.currentMenuItem}
          </div>
        </Page>

        <Messager ref={ref => this.messager = ref}></Messager>
        <Progress loaderState={this.state.loader} progressValue={this.state.progressValue} />
      </Shell>
    );
  }
}

const LayoutWithR = withRouter(LayoutWithOutRouter);
export default LayoutWithR;
