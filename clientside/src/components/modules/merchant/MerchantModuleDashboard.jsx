import React from 'react';
import Messager from '../../StableMessager';
import { withRouter } from '../../../shared/router/compat';
import { CardsIcon, CheckIcon, CloseIcon } from "../../ShellIcons";
import LinearChart from './LinearChart';
import { dashboardErrorDetails, formatAmount, formatCount, numericValues } from '../ModuleDashboard';
import {
    Button,
    WorkspaceMetricGrid,
    WorkspaceMetric,
    WorkspaceGrid,
    WorkspacePanel,
    WorkspaceQuickActions,
    WorkspaceStatusList,
    WorkspaceDisclosure,
} from '../../../ui';

import { apiFetch } from '../../../shared/api/httpClient';
import { apiUrl } from '../../../shared/config';

const merchantDefaultSnapshotCards = [];

const merchantSnapshotCards = [
    { id: 'transactionTypes', title: 'Transaction Types', label: 'Mix', kind: 'chart', chartKey: 'chartDataTxTypes' },
    { id: 'gatewaySplit', title: 'Gateway Split', label: 'MTN / Airtel / Yo! Payments', kind: 'chart', chartKey: 'chartDataTxPerGateway' },
    { id: 'apiReadiness', title: 'API Readiness', label: 'Integration', kind: 'api' },
    { id: 'smsNotifications', title: 'SMS Notifications', label: 'Messaging', kind: 'sms' },
    { id: 'floatWatch', title: 'Float Watch', label: 'Liquidity', kind: 'float' },
    { id: 'payoutControls', title: 'Payout Controls', label: 'Risk', kind: 'risk' },
];

const STORAGE_KEY = 'cpay-merchant-dashboard-snapshots-v2';
const MAX_SNAPSHOT_CARDS = 4;

const sanitizeSnapshotCards = (cards) => {
    const allowed = new Set(merchantSnapshotCards.map(card => card.id));
    const unique = [];
    (Array.isArray(cards) ? cards : merchantDefaultSnapshotCards).forEach(cardId => {
        if (allowed.has(cardId) && !unique.includes(cardId) && unique.length < MAX_SNAPSHOT_CARDS) {
            unique.push(cardId);
        }
    });
    return unique;
};

function channelTone(status) {
    if (status === 'ACTIVE' || status === 'SANDBOX_TESTED') return 'success';
    if (status === 'SUBMITTED_FOR_APPROVAL') return 'info';
    if (status === 'DEGRADED') return 'warning';
    if (status === 'FAILED' || status === 'DISABLED' || status === 'SUSPENDED') return 'danger';
    return 'neutral';
}

class MerchantModuleDashboardC extends React.Component {
    constructor(props) {
        super(props);
        this.state = {
            chartData: null,
            chartDataTxTypes: null,
            chartDataTxVolumes: null,
            chartDataTxPerGateway: null,
            portalSummary: null,
            visibleSnapshotCards: this.loadSnapshotCards(),
            showSnapshotPicker: false,
            fetchErrors: []
        };
    }

    componentDidMount() {
        this.refreshDashboardData();
    }

    componentDidUpdate(prevProps) {
        if (prevProps.refreshSignal !== this.props.refreshSignal) {
            this.refreshDashboardData();
        }
    }

    refreshDashboardData() {
        this.getData("chartData", "getDashboardDetailsPayinsVsPayoutsMerchant");
        this.getData("chartDataTxTypes", "getDashboardDetailsTransactionTypesMerchant");
        this.getData("chartDataTxVolumes", "getDashboardDetailsTxVolumesMerchant");
        this.getData("chartDataTxPerGateway", "getDashboardDetailsTxPerGatewayMerchant");
        this.getPortalSummary();
    }

    async getPortalSummary() {
        try {
            const response = await apiFetch(apiUrl("/api/v2/portal/dashboard/summary"), {
                method: 'GET',
                mode: 'cors',
                credentials: 'include',
                headers: { 'Content-Type': 'application/json' },
            });
            const summary = await response.json();
            if (response.ok) {
                this.setState({ portalSummary: summary });
            }
        } catch (error) {
            this.addFetchError(error.message);
        }
    }

    loadSnapshotCards() {
        try {
            const saved = localStorage.getItem(STORAGE_KEY);
            return sanitizeSnapshotCards(saved ? JSON.parse(saved) : merchantDefaultSnapshotCards);
        } catch {
            return merchantDefaultSnapshotCards;
        }
    }

    saveSnapshotCards(cards) {
        try {
            localStorage.setItem(STORAGE_KEY, JSON.stringify(cards));
        } catch {
            // Dashboard snapshots are optional; the dashboard still works without persistence.
        }
    }

    removeSnapshotCard(cardId) {
        this.setState(prevState => {
            const nextCards = sanitizeSnapshotCards(prevState.visibleSnapshotCards.filter(activeId => activeId !== cardId));
            this.saveSnapshotCards(nextCards);
            return { visibleSnapshotCards: nextCards };
        });
    }

    toggleSnapshotCard(cardId) {
        this.setState(prevState => {
            const isActive = prevState.visibleSnapshotCards.includes(cardId);
            if (!isActive && prevState.visibleSnapshotCards.length >= MAX_SNAPSHOT_CARDS) {
                return { showSnapshotPicker: true };
            }

            const nextCards = isActive
                ? sanitizeSnapshotCards(prevState.visibleSnapshotCards.filter(activeId => activeId !== cardId))
                : sanitizeSnapshotCards([...prevState.visibleSnapshotCards, cardId]);
            this.saveSnapshotCards(nextCards);
            return { visibleSnapshotCards: nextCards };
        });
    }

    getData(chartType, api) {
        this.props.loader("START");
        apiFetch(apiUrl("/transactions/" + api), {
            method: 'POST',
            mode: 'cors',
            cache: 'no-cache',
            credentials: 'include',
            headers: { 'Content-Type': 'application/json' },
            redirect: 'follow',
            referrer: 'no-referrer',
            body: JSON.stringify({ pageSize: this.state.pageSize, searchingValue: this.state.searchingValue, sort: 'asc' })
        }).then((response) => response.text())
            .then((response_) => {
                this.props.loader("STOP");
                let res;
                try {
                    res = JSON.parse(response_);
                    if (res.code === "000") {
                        this.setChartData(chartType, res.chartData);
                    } else {
                        if (res.code === "107") {
                            this.sessionExpired();
                            return;
                        }
                        if (res.code === "110") {
                            this.accessNotAllowed(res.message);
                            return;
                        }
                        const errorDetails = dashboardErrorDetails(res);
                        this.addFetchError(errorDetails.message);
                        this.messager.alert({ title: errorDetails.title, icon: "error", msg: errorDetails.message });
                    }
                } catch (Error) {
                    this.addFetchError(Error.message);
                    this.messager.alert({ title: "Error", icon: "error", msg: Error.message });
                }
            }).catch((error) => {
                this.props.loader("STOP");
                this.addFetchError(error.message);
                this.messager.alert({ title: "Error", icon: "error", msg: error.message });
            });
    }

    setChartData(chartType, chartData) {
        switch (chartType) {
            case "chartData": this.setState({ chartData }); break;
            case "chartDataTxTypes": this.setState({ chartDataTxTypes: chartData }); break;
            case "chartDataTxVolumes": this.setState({ chartDataTxVolumes: chartData }); break;
            case "chartDataTxPerGateway": this.setState({ chartDataTxPerGateway: chartData }); break;
            default: break;
        }
    }

    addFetchError(message) {
        if (!message) return;
        this.setState(prevState => ({
            fetchErrors: [message, ...prevState.fetchErrors.filter(existing => existing !== message)].slice(0, 3)
        }));
    }

    accessNotAllowed(msg) {
        this.messager.alert({ title: "Access Denied", icon: "error", msg });
    }

    sessionExpired() {
        const { history } = this.props;
        this.messager.alert({
            title: "Session Expired!",
            icon: "info",
            msg: "Your session has expired.",
            result: () => history.push("/portal")
        });
    }

    renderChart(data, emptyText) {
        return (
            <div className="cpay-dashboard-chart-shell">
                <LinearChart data={data} title={emptyText} />
                {!data ? <div className="cpay-dashboard-empty">{emptyText}</div> : null}
            </div>
        );
    }

    renderSnapshotCard(cardId) {
        const card = merchantSnapshotCards.find(candidate => candidate.id === cardId);
        if (!card) return null;

        const chartData = card.chartKey ? this.state[card.chartKey] : null;
        const chartCount = numericValues(chartData).length;
        let body;
        let metric = card.label;

        if (card.kind === 'chart') {
            body = this.renderChart(chartData, `${card.title} will appear when data loads.`);
            metric = `${chartCount} data points`;
        } else if (card.kind === 'api') {
            metric = 'Ready';
            body = <p className="cpay-dashboard-card-copy">Use the Pay In, Pay Out, Balance, Status, and SMS endpoints from your system.</p>;
        } else if (card.kind === 'sms') {
            metric = 'Available';
            body = <p className="cpay-dashboard-card-copy">SMS availability follows your communication entitlement and funded balance.</p>;
        } else if (card.kind === 'float') {
            metric = 'Monitor';
            body = <p className="cpay-dashboard-card-copy">Review available liquidity before payout batches and reversals.</p>;
        } else {
            metric = 'Controlled';
            body = <p className="cpay-dashboard-card-copy">Review failed callbacks, duplicate references, pending payouts and configured controls.</p>;
        }

        return (
            <article className="cpay-dashboard-card cpay-dashboard-snapshot-card" key={card.id}>
                <header className="cpay-dashboard-card-header">
                    <div><span>{card.label}</span><h3>{card.title}</h3></div>
                    <button type="button" title="Remove card" aria-label={`Remove ${card.title}`} onClick={() => this.removeSnapshotCard(card.id)}>
                        <CloseIcon />
                    </button>
                </header>
                <div className="cpay-dashboard-metric">{metric}</div>
                <div className="cpay-dashboard-card-body">{body}</div>
            </article>
        );
    }

    renderSnapshotPicker() {
        if (!this.state.showSnapshotPicker) return null;

        const active = new Set(this.state.visibleSnapshotCards);
        const canAdd = this.state.visibleSnapshotCards.length < MAX_SNAPSHOT_CARDS;
        const activeCount = this.state.visibleSnapshotCards.length;

        return (
            <div className="cpay-dashboard-picker" role="menu" aria-label="Customize dashboard cards">
                <div className="cpay-dashboard-picker-heading">
                    <strong>Dashboard cards</strong>
                    <span>{activeCount}/{MAX_SNAPSHOT_CARDS} shown</span>
                </div>
                {merchantSnapshotCards.map(card => {
                    const isActive = active.has(card.id);
                    const disabled = !isActive && !canAdd;
                    return (
                        <button
                            key={card.id}
                            type="button"
                            role="menuitemcheckbox"
                            aria-pressed={isActive}
                            disabled={disabled}
                            className={`cpay-dashboard-picker-option${isActive ? ' cpay-dashboard-picker-option-active' : ''}`}
                            onClick={() => this.toggleSnapshotCard(card.id)}>
                            <span className="cpay-dashboard-picker-state">{isActive ? <CheckIcon /> : <CardsIcon />}</span>
                            <span className="cpay-dashboard-picker-copy"><strong>{card.title}</strong><span>{card.label}</span></span>
                            <em>{isActive ? 'Shown' : disabled ? 'Limit reached' : 'Add'}</em>
                        </button>
                    );
                })}
            </div>
        );
    }

    render() {
        const summary = this.state.portalSummary || {};
        const channels = Array.isArray(summary.activeChannels) ? summary.activeChannels : [];
        const metrics = [
            { label: 'Collections', value: formatAmount(Number(summary.payIns) || 0), meta: 'Incoming payments', tone: 'info' },
            { label: 'Disbursements', value: formatAmount(Number(summary.payOuts) || 0), meta: 'Outgoing payments', tone: 'info' },
            { label: 'Transactions', value: formatCount(Number(summary.transactions) || 0), meta: 'Recorded transactions', tone: 'neutral' },
            { label: 'Active channels', value: formatCount(channels.filter(channel => ['ACTIVE', 'SANDBOX_TESTED'].includes(channel.status)).length), meta: 'Ready payment channels', tone: channels.length ? 'success' : 'neutral' },
        ];

        const channelItems = channels.slice(0, 5).map(channel => ({
            label: channel.display_name || channel.channel_code || 'Payment channel',
            value: channel.status || 'Not configured',
            tone: channelTone(channel.status),
        }));
        if (!channelItems.length) {
            channelItems.push({ label: 'Payment channels', value: 'None configured', tone: 'neutral' });
        }
        if (this.state.fetchErrors.length) {
            channelItems.unshift({ label: 'Data sources', value: `${this.state.fetchErrors.length} need review`, tone: 'danger' });
        }

        const quickActions = [
            { label: 'Payments', description: 'Collect, pay out and review transactions', onClick: () => this.props.history.push('/fo/payments') },
            { label: 'Balances & settlements', description: 'Review balances and settlement evidence', onClick: () => this.props.history.push('/fo/balances-settlements') },
            { label: 'Developers', description: 'Sandbox, credentials, webhooks and go-live', onClick: () => this.props.history.push('/fo/developers') },
            { label: 'Services & products', description: 'Review available Cito services', onClick: () => this.props.history.push('/fo/services') },
        ];

        return (
            <div className="cito-service-workspace cpay-dashboard cpay-merchant-dashboard">
                <WorkspaceMetricGrid>
                    {metrics.map(metric => <WorkspaceMetric key={metric.label} {...metric} />)}
                </WorkspaceMetricGrid>

                <WorkspaceGrid>
                    <WorkspacePanel eyebrow="Trend" title="Money movement">
                        <div className="cito-chart-shell">
                            {this.renderChart(this.state.chartData, 'Pay In and Pay Out trends will appear when live data loads.')}
                        </div>
                    </WorkspacePanel>

                    <WorkspacePanel eyebrow="Status" title="Account readiness">
                        <WorkspaceStatusList items={channelItems} />
                        <p className="cito-inline-note">
                            Environment: {summary.environment || 'Not reported'}. Production limits and channel availability follow your live account configuration.
                        </p>
                        <WorkspaceQuickActions actions={quickActions} />
                    </WorkspacePanel>
                </WorkspaceGrid>

                <WorkspaceDisclosure summary="Optional dashboard insights">
                    <div className="cpay-dashboard-toolbar">
                        <div className="cpay-dashboard-toolbar-copy">
                            <h2>Additional insights</h2>
                            <p>Add only the secondary cards you actually need. The default dashboard stays intentionally focused.</p>
                        </div>
                        <div className="cpay-dashboard-actions">
                            <Button
                                variant="ghost"
                                className="cpay-card-manager-button"
                                aria-expanded={this.state.showSnapshotPicker}
                                aria-label="Customize dashboard cards"
                                onClick={() => this.setState(prevState => ({ showSnapshotPicker: !prevState.showSnapshotPicker }))}>
                                <CardsIcon />
                                <span>Customize cards</span>
                                <em>{this.state.visibleSnapshotCards.length}/{MAX_SNAPSHOT_CARDS}</em>
                            </Button>
                            {this.renderSnapshotPicker()}
                        </div>
                    </div>
                    {this.state.visibleSnapshotCards.length > 0
                        ? <section className="cpay-dashboard-snapshot-grid">{this.state.visibleSnapshotCards.map(cardId => this.renderSnapshotCard(cardId))}</section>
                        : <div className="cito-empty-compact">No optional insight cards are shown.</div>}
                </WorkspaceDisclosure>

                <Messager ref={ref => this.messager = ref}></Messager>
            </div>
        );
    }
}

const MerchantModuleDashboard = withRouter(MerchantModuleDashboardC);

export default MerchantModuleDashboard;
