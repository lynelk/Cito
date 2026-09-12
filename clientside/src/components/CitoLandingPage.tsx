import React from "react";
import { Link, useNavigate } from "react-router-dom";
import PublicApiOverview from "./PublicApiOverview";
import { PublicHeader, PublicFooter } from "./PublicSiteChrome";
import { usePageMetadata } from "../shared/usePageMetadata";
import "../styles/cito-landing.css";

const services = [
  {
    id: "payments",
    title: "Payments",
    short: "Collect. Pay out. Reconcile.",
    copy: "Accept customer payments, pay suppliers and track every transaction through Cito Payments / CPay.",
    tags: "Collections · Payouts · Payment links",
    to: "/payments",
  },
  {
    id: "communications",
    title: "Communications",
    short: "Keep the conversation going.",
    copy: "Connect with customers using SMS, WhatsApp Business and USSD through approved providers. Track the available delivery evidence.",
    tags: "Messaging · Notifications · USSD",
  },
  {
    id: "identity",
    title: "Identity, Credit & Scoring",
    short: "Make better-informed decisions.",
    copy: "Access NIN, KYC/KYB, CRB reports and credit scoring through approved providers, with consent and service controls.",
    tags: "Verification · Credit intelligence",
  },
  {
    id: "vending",
    title: "Vending",
    short: "Deliver everyday digital services.",
    copy: "Bring airtime, data, utilities, devices and other value-added services into your customer experience.",
    tags: "Airtime · Data · Utilities",
  },
  {
    id: "billing",
    title: "Billing & Finance",
    short: "Turn usage into revenue.",
    copy: "Connect metering, rating, invoicing and Billing-as-a-Service. Keep pricing, tax and currency evidence with the bill.",
    tags: "Usage billing · Invoices · Reconciliation",
    to: "/billing",
  },
  {
    id: "integrations",
    title: "Integrations & Automation",
    short: "Make your systems work together.",
    copy: "Use documented APIs, webhooks and provider adapters to connect Cito services to your existing workflows.",
    tags: "APIs · Webhooks · Sandbox",
    to: "/developer-platform",
  },
];

function Arrow(): React.ReactElement {
  return <span aria-hidden="true">↗</span>;
}

function CitoLandingPage(): React.ReactElement {
  const navigate = useNavigate();
  usePageMetadata(
    "Business, connected",
    "Payments, communications, identity, vending and billing. Connect the services your business needs through one Cito account.",
    "/",
  );
  React.useEffect(() => {
    if (new URL(window.location.href).searchParams.get("uiportal") === "portal")
      navigate("/login?realm=platform", { replace: true });
  }, [navigate]);
  return (
    <div className="cito-landing">
      <PublicHeader />
      <main id="main-content" tabIndex={-1}>
        <section className="cito-hero cito-section">
          <div className="cito-hero-copy">
            <p className="cito-eyebrow">Business, connected.</p>
            <h1>
              Your business.
              <br />
              <em>Better connected.</em>
            </h1>
            <p className="cito-lead">
              Payments, communications, identity and billing. Bring the services
              your business runs on together in one Cito account.
            </p>
            <div className="cito-hero-actions">
              <Link
                className="cito-button cito-button-primary cito-button-large"
                to="/signup"
              >
                Create Cito account <Arrow />
              </Link>
              <Link
                className="cito-button cito-button-secondary cito-button-large"
                to="/contact"
              >
                Talk to sales
              </Link>
            </div>
            <p className="cito-hero-note">
              Start in sandbox. Go live with approved services.
            </p>
            <a className="cito-text-link" href="#services">
              Explore the platform <span aria-hidden="true">↓</span>
            </a>
          </div>
          <aside
            className="cito-service-directory"
            aria-label="Explore Cito services"
          >
            <div className="cito-directory-heading">
              <span>THE CITO PLATFORM</span>
              <span>6 SERVICE FAMILIES</span>
            </div>
            <h2>
              One connection.
              <br />
              More possibilities.
            </h2>
            <div className="cito-directory-list">
              {services.map((service, i) => (
                <a key={service.id} href={`#service-${service.id}`}>
                  <span className="cito-directory-number">0{i + 1}</span>
                  <span>{service.title}</span>
                  <Arrow />
                </a>
              ))}
            </div>
            <p>Choose the services that fit your business.</p>
          </aside>
        </section>
        <div className="cito-principles" aria-label="Platform strengths">
          <div>
            <span>01</span> One business account
          </div>
          <div>
            <span>02</span> Connected services
          </div>
          <div>
            <span>03</span> Clear access controls
          </div>
          <div>
            <span>04</span> Sandbox before go-live
          </div>
        </div>
        <section className="cito-section" id="services">
          <div className="cito-section-heading cito-heading-split">
            <div>
              <p className="cito-eyebrow">
                One platform. Six service families.
              </p>
              <h2>
                Less fragmentation.
                <br />
                More forward motion.
              </h2>
            </div>
            <p>
              Start with what you need today. Connect more services as your
              business grows, without starting over with every provider.
            </p>
          </div>
          <div className="cito-feature-grid">
            {services.map((service, i) => (
              <article
                className="cito-feature-card"
                id={`service-${service.id}`}
                key={service.id}
              >
                <div className="cito-service-label">
                  <span>0{i + 1}</span>
                  <span>{service.title}</span>
                </div>
                <h3>{service.short}</h3>
                <p>{service.copy}</p>
                <div className="cito-service-bottom">
                  <small>{service.tags}</small>
                  {service.to && (
                    <Link
                      to={service.to}
                      aria-label={`Explore ${service.title}`}
                    >
                      <Arrow />
                    </Link>
                  )}
                </div>
              </article>
            ))}
          </div>
          <p className="cito-availability-note">
            Service availability depends on your country, account approval and
            provider readiness.{" "}
            <Link to="/contact">
              Check what is available for your business <Arrow />
            </Link>
          </p>
        </section>
        <section className="cito-payment-band">
          <div className="cito-section cito-payment-layout">
            <div className="cito-section-heading">
              <p className="cito-eyebrow">Cito Payments / CPay</p>
              <h2>
                From payment
                <br />
                to a clearer picture.
              </h2>
              <p>
                Connect collections, payouts and reconciliation in one payments
                workspace. Follow the reference, understand the outcome and keep
                your team aligned.
              </p>
              <Link className="cito-text-link" to="/payments">
                Explore Cito Payments <Arrow />
              </Link>
            </div>
            <div className="cito-payment-steps">
              <article>
                <span>01</span>
                <div>
                  <h3>Collect with less friction</h3>
                  <p>
                    Offer payment links and hosted checkout across your approved
                    payment channels.
                  </p>
                </div>
              </article>
              <article>
                <span>02</span>
                <div>
                  <h3>Pay out with control</h3>
                  <p>
                    Pay customers and suppliers with beneficiary checks, limits
                    and approval workflows.
                  </p>
                </div>
              </article>
              <article>
                <span>03</span>
                <div>
                  <h3>Close the loop</h3>
                  <p>
                    Track payment outcomes, match references and resolve
                    reconciliation exceptions.
                  </p>
                </div>
              </article>
              <p className="cito-provider-note">
                Provider families include MTN MoMo, Airtel Money, Yo! Payments,
                Safaricom M-Pesa and FlexiPay. Production availability is
                explicit per provider, country and account.
              </p>
            </div>
          </div>
        </section>
        <section className="cito-section cito-how">
          <div className="cito-section-heading">
            <p className="cito-eyebrow">A clear path to getting started</p>
            <h2>Start small. Connect with confidence.</h2>
          </div>
          <ol className="cito-steps">
            <li>
              <span>01</span>
              <div>
                <h3>Create your account</h3>
                <p>
                  Register your business and complete the required identity and
                  business checks.
                </p>
              </div>
            </li>
            <li>
              <span>02</span>
              <div>
                <h3>Choose and test</h3>
                <p>
                  Configure your approved services and verify the full journey
                  in sandbox.
                </p>
              </div>
            </li>
            <li>
              <span>03</span>
              <div>
                <h3>Get ready to go live</h3>
                <p>
                  Complete service approval, provider readiness and settlement
                  setup before activation.
                </p>
              </div>
            </li>
          </ol>
        </section>
        <section className="cito-developer-band" id="developers">
          <div className="cito-section">
            <div className="cito-developer-layout">
              <div className="cito-section-heading">
                <p className="cito-eyebrow">Built for the people building</p>
                <h2>
                  Connect once.
                  <br />
                  Build what comes next.
                </h2>
                <p>
                  Documented APIs, signing helpers and webhooks give your team a
                  practical starting point. Test in sandbox before moving to
                  approved production services.
                </p>
                <div className="cito-hero-actions">
                  <a
                    className="cito-button cito-button-light"
                    href="/fo/developers"
                  >
                    Cito Payments API documentation <Arrow />
                  </a>
                  <Link className="cito-text-link" to="/developer-platform">
                    Developer platform <Arrow />
                  </Link>
                </div>
                <p className="cito-developer-note">
                  Sign in to access the merchant API reference.
                </p>
              </div>
              <div className="cito-integration-guide">
                <p className="cito-eyebrow">Your integration journey</p>
                <ol>
                  <li>
                    <span>01</span>
                    <div>
                      <strong>Connect your application</strong>
                      <p>Scoped credentials and signed requests</p>
                    </div>
                  </li>
                  <li>
                    <span>02</span>
                    <div>
                      <strong>Test the complete flow</strong>
                      <p>Sandbox requests and callback verification</p>
                    </div>
                  </li>
                  <li>
                    <span>03</span>
                    <div>
                      <strong>Keep your systems in sync</strong>
                      <p>Webhooks, status enquiries and delivery evidence</p>
                    </div>
                  </li>
                </ol>
              </div>
            </div>
            <details className="cito-api-disclosure">
              <summary>
                Explore API topics<span aria-hidden="true">+</span>
              </summary>
              <PublicApiOverview />
            </details>
          </div>
        </section>
        <section className="cito-section cito-security">
          <div className="cito-section-heading">
            <p className="cito-eyebrow">Trust is in the details</p>
            <h2>
              Control where
              <br />
              it counts.
            </h2>
            <p>
              Keep access, credentials and financial operations accountable as
              more people and services connect.
            </p>
            <Link className="cito-text-link" to="/security">
              Explore security & trust <Arrow />
            </Link>
          </div>
          <div className="cito-security-grid">
            <article>
              <h3>The right access</h3>
              <p>
                Roles and service permissions help people work within their
                responsibilities.
              </p>
            </article>
            <article>
              <h3>Protected connections</h3>
              <p>
                Signed requests, encrypted credentials and verified webhooks
                protect integrations.
              </p>
            </article>
            <article>
              <h3>Traceable actions</h3>
              <p>
                Audit trails and approval controls support review of important
                operational decisions.
              </p>
            </article>
            <article>
              <h3>Clear environments</h3>
              <p>
                Sandbox testing and production activation remain separate,
                explicit steps.
              </p>
            </article>
          </div>
        </section>
        <section className="cito-section cito-contact" id="contact">
          <div className="cito-section-heading">
            <p className="cito-eyebrow">Let's connect</p>
            <h2>The right next step starts here.</h2>
          </div>
          <div className="cito-contact-grid">
            <Link to="/contact">
              <span>For your business</span>
              <strong>
                Discuss Cito services for your business <Arrow />
              </strong>
              <small>Talk to our sales team</small>
            </Link>
            <a href="mailto:support@citotech.net">
              <span>For existing customers</span>
              <strong>
                Get help with an existing Cito account <Arrow />
              </strong>
              <small>support@citotech.net</small>
            </a>
            <Link to="/developer-platform">
              <span>For your next integration</span>
              <strong>
                Find your developer starting point <Arrow />
              </strong>
              <small>Explore APIs and sandbox</small>
            </Link>
          </div>
        </section>
        <section className="cito-final-cta">
          <div>
            <p className="cito-eyebrow">Make the connection</p>
            <h2>Move your business forward.</h2>
            <p>One account. The services you need. Room to grow.</p>
          </div>
          <Link
            className="cito-button cito-button-light cito-button-large"
            to="/signup"
          >
            Get started with Cito <Arrow />
          </Link>
        </section>
      </main>
      <PublicFooter />
    </div>
  );
}
export default CitoLandingPage;
