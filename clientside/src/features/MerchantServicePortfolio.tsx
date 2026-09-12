import React from 'react';
import { useNavigate } from 'react-router-dom';
import { Badge, Button } from '../ui';

interface Props {
  entitlements?: string[];
}

type ServiceFamily = {
  code: string;
  mark: string;
  title: string;
  description: string;
  capabilities: string[];
  entitlementHints: string[];
  route?: string;
  action?: string;
};

const families: ServiceFamily[] = [
  {
    code: 'payments',
    mark: 'P',
    title: 'Payments',
    description: 'Collect, pay out, refund and reconcile through Cito Payments / CPay using the channels approved for your business.',
    capabilities: ['CPay', 'MTN MoMo', 'Airtel Money', 'Yo! Payments', 'FlexiPay', 'M-Pesa'],
    entitlementHints: ['CPAY', 'PAYMENT', 'PAYOUT', 'COLLECTION'],
    route: '/fo/payments',
    action: 'Open payments',
  },
  {
    code: 'communications',
    mark: 'C',
    title: 'Communications',
    description: 'Send and automate customer communications through configured SMS, WhatsApp Business and USSD providers with delivery evidence and routing controls.',
    capabilities: ['SMS', 'WhatsApp Business', 'USSD', 'Notifications', 'Provider failover'],
    entitlementHints: ['SMS', 'WHATSAPP', 'USSD', 'COMMUNICATION', 'MESSAGE'],
    route: '/fo/sms',
    action: 'Open communications',
  },
  {
    code: 'identity-credit',
    mark: 'I',
    title: 'Identity, Credit & Scoring',
    description: 'Use approved verification and credit-data providers for identity, KYC/KYB, CRB reports and decisioning without hard-wiring your business to one provider.',
    capabilities: ['NIN verification', 'KYC / KYB', 'CRB reports', '0–1000 scoring', 'Bank verification', 'TIN / registry'],
    entitlementHints: ['IDENTITY', 'KYC', 'KYB', 'CRB', 'SCORING', 'SCORE', 'CREDIT', 'NIN'],
    route: '/fo/services',
    action: 'Open identity controls',
  },
  {
    code: 'vending',
    mark: 'V',
    title: 'Vending & Value-Added Services',
    description: 'Offer airtime, data, utilities and device-backed services through one Cito-managed vending layer.',
    capabilities: ['Airtime', 'Data', 'Utilities', 'Devices', 'QR journeys'],
    entitlementHints: ['VENDING', 'AIRTIME', 'UTILITY', 'DATA_BUNDLE', 'DEVICE'],
    route: '/fo/vending',
    action: 'Open vending',
  },
  {
    code: 'billing',
    mark: 'B',
    title: 'Billing & Monetisation',
    description: 'Meter usage, apply pricing, issue invoices and use Cito as a Billing-as-a-Service layer for your own products and customers.',
    capabilities: ['Metering', 'Rating', 'BaaS', 'Invoices', 'Recurring', 'Tax & FX evidence'],
    entitlementHints: ['BILLING', 'BAAS', 'INVOICE', 'METERING', 'RATING', 'RECURRING'],
    route: '/fo/services',
    action: 'Open billing controls',
  },
  {
    code: 'integrations',
    mark: 'A',
    title: 'Integrations & Automation',
    description: 'Connect your systems through APIs, webhooks, developer projects and certified provider integrations.',
    capabilities: ['APIs', 'Webhooks', 'Developer projects', 'Connectors', 'Automation'],
    entitlementHints: ['API', 'WEBHOOK', 'INTEGRATION', 'CONNECTOR'],
    route: '/fo/developers',
    action: 'Open developer workspace',
  },
];

const advancedCapabilities = [
  'Marketplace splits',
  'Refunds & disputes',
  'Recurring payments',
  'Virtual accounts',
  'Embedded Cito',
  'Routing intelligence',
  'Operational analytics',
];

function entitlementState(family: ServiceFamily, entitlements?: string[]): { label: string; tone: 'success' | 'neutral' | 'warning' } {
  if (!Array.isArray(entitlements)) return { label: 'Checking access', tone: 'neutral' };
  const enabled = entitlements.map((item) => String(item).toUpperCase());
  if (family.entitlementHints.some((hint) => enabled.some((item) => item.includes(hint)))) {
    return { label: 'Access granted · readiness separate', tone: 'neutral' };
  }
  return { label: 'Available by entitlement', tone: 'warning' };
}

export default function MerchantServicePortfolio({ entitlements }: Props): React.ReactElement {
  const navigate = useNavigate();
  return (
    <div className="cito-service-hub" style={{ marginBottom: 18 }}>
      <header className="cito-workspace-hero">
        <div>
          <p className="cito-workspace-hero__eyebrow">Your Cito service portfolio</p>
          <h2>Use the services your business needs</h2>
          <p>Payments are only one part of Cito. Explore communications, identity and credit intelligence, vending, billing and integrations by service family. Entitlements grant workspace access; configuration, certification and production activation remain separate checks.</p>
        </div>
        <div className="cito-workspace-hero__actions">
          <Button variant="ghost" onClick={() => navigate('/fo/help')}>Request service access</Button>
          <Button variant="primary" onClick={() => navigate('/fo/developers')}>Open developer reference</Button>
        </div>
      </header>

      <div className="cito-service-grid">
        {families.map((family) => {
          const state = entitlementState(family, entitlements);
          return (
            <article className="cito-service-card" key={family.code}>
              <div className="cito-service-card__top">
                <span className="cito-service-card__mark" aria-hidden="true">{family.mark}</span>
                <Badge tone={state.tone}>{state.label}</Badge>
              </div>
              <h3>{family.title}</h3>
              <p>{family.description}</p>
              <div className="cito-service-card__capabilities">
                {family.capabilities.map((capability) => <span key={capability}>{capability}</span>)}
              </div>
              {family.route ? <div className="cito-service-card__actions"><button className="cito-service-card__link" type="button" onClick={() => navigate(family.route!)}>{family.action} →</button></div> : null}
            </article>
          );
        })}
      </div>

      <section className="cito-compliance-panel">
        <div className="cito-section-heading">
          <div><h3>Advanced platform capabilities</h3><p>These remain available inside the detailed service workspace without cluttering your everyday navigation.</p></div>
        </div>
        <div className="cito-service-card__capabilities">
          {advancedCapabilities.map((capability) => <span key={capability}>{capability}</span>)}
        </div>
      </section>
    </div>
  );
}
