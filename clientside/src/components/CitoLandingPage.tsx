import PublicApiOverview from './PublicApiOverview';
import React from 'react';
import { Link, useNavigate } from 'react-router-dom';
import '../styles/cito-landing.css';

const DOCS_URL = '/fo/developers';
const SIGN_IN_PATH = '/login';
const SIGN_UP_PATH = '/signup';

const serviceFamilies = [
  ['Payments', 'Collections, payouts, refunds, payment links, reconciliation and settlement through Cito Payments / CPay.'],
  ['Communications', 'SMS, WhatsApp Business and USSD through provider-aware routing, delivery evidence and usage billing.'],
  ['Identity, Credit & Scoring', 'NIN, KYC/KYB, CRB reports, bank verification and normalized scoring through approved providers.'],
  ['Vending & Value-Added Services', 'Airtime, data, utilities, devices and other services delivered through a unified vending layer.'],
  ['Billing & Monetisation', 'Metering, rating, invoicing and Billing-as-a-Service with effective-dated pricing, tax and FX evidence.'],
  ['Integrations & Automation', 'APIs, webhooks, provider adapters, routing, certification and automation for connected business workflows.'],
] as const;

const paymentCapabilities = [
  ['Collect payments', 'Receive customer payments across approved and configured payment channels.'],
  ['Make payouts', 'Send funds to customers, suppliers and beneficiaries through governed payout workflows.'],
  ['Payment links & invoices', 'Create hosted payment experiences without building checkout from scratch.'],
  ['Reconciliation', 'Track payment status, references, balances and transaction outcomes centrally.'],
  ['Webhooks & automation', 'Connect transaction events to business workflows with verifiable callbacks.'],
  ['Developer APIs', 'Build with Cito Payments API v2, OpenAPI, SDK helpers, Postman and sandbox tooling.'],
] as const;

const audiences = [
  ['Businesses & merchants', 'Use Cito as one operational front door for payments and other approved digital services.'],
  ['Platforms & developers', 'Integrate Cito Payments and other Cito services through documented interfaces and webhooks.'],
  ['Institutions', 'Centralize access to payments, identity, reporting, controls, reconciliation and service providers.'],
  ['Technology partners', 'Connect payment, communication, identity, credit and value-added services through the Cito integration layer.'],
] as const;

const securityControls = [
  ['Signed API requests', 'Cryptographic request authentication for merchant integrations.'],
  ['Controlled environments', 'Separate sandbox and production workflows with explicit activation controls.'],
  ['Scoped access', 'Merchant and platform access remain governed by authentication, session, role and authorization controls.'],
  ['Protected credentials', 'Sensitive provider configuration is encrypted and displayed only in masked form.'],
  ['Auditable operations', 'Important configuration and operational actions are recorded for review.'],
  ['Webhook security', 'Signing, delivery history, rotation and replay controls for asynchronous events.'],
] as const;

function CitoLandingPage(): React.ReactElement {
  const navigate = useNavigate();

  React.useEffect(() => {
    const uiportal = new URL(window.location.href).searchParams.get('uiportal');
    if (uiportal === 'portal') navigate('/login?realm=platform', { replace: true });
  }, [navigate]);

  return (
    <div className="cito-landing">
      <header className="cito-header">
        <a className="cito-brand" href="#top" aria-label="Cito home">
          <span className="cito-brand-mark" aria-hidden="true">C</span>
          <span><strong>Cito</strong><small>Business Services</small></span>
        </a>
        <nav className="cito-nav" aria-label="Primary navigation">
          <a href="#services">Services</a><Link to="/payments">Payments</Link><Link to="/billing">Billing</Link><Link to="/developer-platform">Developers</Link><Link to="/status">Status</Link>
        </nav>
        <div className="cito-header-actions">
          <Link className="cito-button cito-button-quiet" to={SIGN_IN_PATH}>Sign in</Link>
          <Link className="cito-button cito-button-primary" to={SIGN_UP_PATH}>Get started</Link>
        </div>
        <details className="cito-mobile-menu">
          <summary aria-label="Open navigation">Menu</summary>
          <div className="cito-mobile-panel">
            <Link to={SIGN_UP_PATH}>Get started</Link><Link to={SIGN_IN_PATH}>Sign in</Link>
            <a href="#services">Services</a><Link to="/payments">Payments</Link><Link to="/developer-platform">Developers</Link><Link to="/about">About</Link><Link to="/contact">Contact</Link>
          </div>
        </details>
      </header>

      <main id="top">
        <section className="cito-hero cito-section">
          <div className="cito-hero-copy">
            <p className="cito-eyebrow">Payments, communications, identity, intelligence and business services</p>
            <h1>One platform for the services your business runs on.</h1>
            <p className="cito-lead">Accept payments, make payouts, communicate with customers, verify identities, access credit intelligence, vend digital services, bill for usage and connect your systems through one controlled Cito account.</p>
            <div className="cito-hero-actions">
              <Link className="cito-button cito-button-primary cito-button-large" to={SIGN_UP_PATH}>Create Cito account</Link>
              <Link className="cito-button cito-button-secondary cito-button-large" to="/contact">Talk to sales</Link>
            </div>
            <Link className="cito-text-link" to="/developer-platform">Developer? Explore Cito APIs and sandbox <span aria-hidden="true">→</span></Link>
          </div>
          <aside className="cito-product-card" aria-label="Cito service portfolio summary">
            <div className="cito-product-card-head"><div><span className="cito-product-kicker">Cito</span><h2>Service Platform</h2></div><span className="cito-status"><span aria-hidden="true" /> Entitlement controlled</span></div>
            <p className="cito-product-intro">Activate only the services your organization is approved to use.</p>
            <div className="cito-product-grid"><span>Payments</span><span>Communications</span><span>Identity & Scoring</span><span>Vending</span><span>Billing & BaaS</span><span>APIs & Automation</span></div>
            <div className="cito-provider-row"><strong>Provider-neutral by design</strong><p>Availability depends on configured, approved and certified providers for your market and account.</p></div>
          </aside>
        </section>

        <section className="cito-trust-strip" aria-label="Platform strengths"><span>One Cito account</span><span>Sandbox before production</span><span>Provider-aware routing</span><span>Merchant self-service</span></section>

        <section className="cito-section" id="services">
          <div className="cito-section-heading cito-section-heading-center"><p className="cito-eyebrow">Cito Services</p><h2>Business infrastructure without the usual integration sprawl.</h2><p>Cito groups provider-backed capabilities into understandable service families. Your account, environment and entitlements determine what is available.</p></div>
          <div className="cito-feature-grid">{serviceFamilies.map(([title, copy], index) => <article className="cito-feature-card" key={title}><span>{String(index + 1).padStart(2, '0')}</span><h3>{title}</h3><p>{copy}</p></article>)}</div>
        </section>

        <section className="cito-section cito-about" id="about">
          <div className="cito-section-heading"><p className="cito-eyebrow">About Cito</p><h2>A controlled gateway to the digital services a business actually needs.</h2><p>Cito Technologies connects organizations to payments, communications, identity, credit intelligence, billing, vending and operational services through one governed platform. Providers remain replaceable; customer journeys remain consistent.</p></div>
          <div className="cito-pillars">
            <article><span>01</span><h3>Access</h3><p>One account, role model and service-entitlement layer across Cito.</p></article>
            <article><span>02</span><h3>Orchestration</h3><p>Choose and route approved providers without rebuilding the customer experience every time.</p></article>
            <article><span>03</span><h3>Control</h3><p>Operate with auditability, environment separation, reconciliation and usage evidence.</p></article>
          </div>
        </section>

        <section className="cito-section cito-cpay" id="payments">
          <div className="cito-section-heading cito-section-heading-center"><p className="cito-eyebrow">Cito Payments / CPay</p><h2>Collect, pay out and reconcile from one payments workspace.</h2><p>Cito Payments provides controlled collections, payouts, transaction management and reconciliation. Provider availability is shown by actual configuration and certification state, never merely by the existence of adapter code.</p></div>
          <div className="cito-feature-grid">{paymentCapabilities.map(([title, copy], index) => <article className="cito-feature-card" key={title}><span>{String(index + 1).padStart(2, '0')}</span><h3>{title}</h3><p>{copy}</p></article>)}</div>
          <div className="cito-provider-row" style={{ marginTop: 24 }}><strong>Payment-provider families supported by the Cito integration strategy</strong><p>MTN MoMo · Airtel Money · Yo! Payments · Safaricom M-Pesa · FlexiPay · additional approved providers. Production availability is explicit per provider, country and account.</p></div>
        </section>

        <section className="cito-section cito-how">
          <div className="cito-section-heading cito-section-heading-center"><p className="cito-eyebrow">How access works</p><h2>Start with Cito. Activate the services you need.</h2></div>
          <ol className="cito-steps">
            <li><span>01</span><div><h3>Create or request access</h3><p>Businesses self-register; privileged staff and partners use controlled access requests.</p></div></li>
            <li><span>02</span><div><h3>Verify the business and account</h3><p>Complete the identity, KYB, email and authorization requirements appropriate to your services.</p></div></li>
            <li><span>03</span><div><h3>Select, configure and test services</h3><p>Use sandbox and provider-specific setup for payments, communications, identity, scoring, vending and integrations.</p></div></li>
            <li><span>04</span><div><h3>Activate approved production capabilities</h3><p>Production access remains explicit, auditable and entitlement controlled.</p></div></li>
          </ol>
        </section>

        <section className="cito-section cito-developers" id="developers">
          <div className="cito-developer-copy"><p className="cito-eyebrow">For developers</p><h2>Integrate once. Add services as the business grows.</h2><p>Build with documented APIs, first-party signing helpers, webhooks and a controlled sandbox environment. The long-term integration model covers payments and other provider-backed Cito services behind consistent access and governance.</p><div className="cito-developer-actions"><a className="cito-button cito-button-light" href={DOCS_URL}>Cito Payments API documentation</a><Link className="cito-button cito-button-outline-light" to="/developer-platform">Developer platform</Link></div><PublicApiOverview /><p className="cito-developer-resources">Searchable OpenAPI · Integration guide · Webhooks · Admin-set API access rates, including zero</p></div>
          <div className="cito-quickstart" aria-label="Cito developer quickstart"><span className="cito-quickstart-label">Start safely</span><ol><li><span>01</span> Create your Cito account</li><li><span>02</span> Choose an entitled service</li><li><span>03</span> Configure sandbox credentials</li><li><span>04</span> Make a test request</li><li><span>05</span> Verify callbacks or result evidence</li><li><span>06</span> Complete production readiness</li></ol></div>
        </section>

        <section className="cito-section cito-audiences"><div className="cito-section-heading cito-section-heading-center"><p className="cito-eyebrow">Who Cito is for</p><h2>One service platform for businesses, institutions and builders.</h2></div><div className="cito-audience-grid">{audiences.map(([title, copy]) => <article key={title}><h3>{title}</h3><p>{copy}</p></article>)}</div></section>

        <section className="cito-section cito-security"><div className="cito-section-heading"><p className="cito-eyebrow">Trust & control</p><h2>Built for controlled business access and financial operations.</h2><p>Security controls cover request authentication, environments, credential protection, access review, audit trails and callback delivery.</p></div><div className="cito-security-grid">{securityControls.map(([title, copy]) => <article key={title}><h3>{title}</h3><p>{copy}</p></article>)}</div></section>

        <section className="cito-section cito-contact" id="contact"><div className="cito-section-heading"><p className="cito-eyebrow">Contact</p><h2>Talk to Cito.</h2><p>Choose the route that matches what you need and reach the appropriate team directly.</p></div><div className="cito-contact-grid"><Link to="/contact"><span>Sales</span><strong>Discuss Cito services for your business</strong><small>Submit a sales enquiry</small></Link><a href="mailto:support@citotech.net"><span>Support</span><strong>Get help with an existing Cito account</strong><small>support@citotech.net</small></a><a href={DOCS_URL}><span>Developers</span><strong>Open Cito Payments integration documentation</strong><small>API v2 documentation</small></a></div></section>

        <section className="cito-final-cta"><div><p className="cito-eyebrow">Start with Cito</p><h2>Ready to simplify your service stack?</h2><p>Create your account, activate only the capabilities you need and move from sandbox to production under controlled Cito workflows.</p></div><div className="cito-final-actions"><Link className="cito-button cito-button-light" to={SIGN_UP_PATH}>Get started</Link><Link className="cito-button cito-button-outline-light" to="/contact">Talk to sales</Link></div></section>
      </main>

      <footer className="cito-footer">
        <div className="cito-footer-brand"><strong>Cito</strong><span>Payments, communications, identity, intelligence, vending, billing and integrations through one governed platform.</span></div>
        <div className="cito-footer-links"><div><strong>Services</strong><Link to="/payments">Payments</Link><a href="#services">Service portfolio</a><Link to="/billing">Billing</Link></div><div><strong>Developers</strong><a href={DOCS_URL}>Payments documentation</a><Link to="/developer-platform">Developer platform</Link></div><div><strong>Company</strong><Link to="/about">About Cito</Link><Link to="/contact">Contact</Link></div><div><strong>Access</strong><Link to={SIGN_UP_PATH}>Create account</Link><Link to={SIGN_IN_PATH}>Sign in</Link></div></div>
        <div className="cito-footer-bottom"><span>© {new Date().getFullYear()} Core-Synergies</span><span>Cito Business Services</span></div>
      </footer>
    </div>
  );
}

export default CitoLandingPage;
