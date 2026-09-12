import React from "react";
import { Link } from "react-router-dom";
import { apiFetch } from "../shared/api/httpClient";
import { trackProductEvent, usePageView } from "../shared/productAnalytics";
import { usePageMetadata } from "../shared/usePageMetadata";
import { StatusBadge } from "../ui";
import { PublicHeader, PublicFooter } from "./PublicSiteChrome";
import "../styles/cito-landing.css";

type PublicPage =
  | "payments"
  | "payouts"
  | "billing"
  | "operations"
  | "developer-platform"
  | "about"
  | "security";

const pages: Record<
  PublicPage,
  { title: string; eyebrow: string; description: string; outcomes: string[] }
> = {
  payments: {
    title: "Accept payments through one controlled gateway.",
    eyebrow: "Cito Payments",
    description:
      "Collections, hosted checkout, payment links, invoices, recurring payments, refunds, and disputes share one operational model.",
    outcomes: [
      "Accept payments through approved channels",
      "Track the outcome of each payment",
      "Reconcile provider and merchant references",
    ],
  },
  payouts: {
    title: "Move funds with approval and treasury controls.",
    eyebrow: "Cito Payments · Payouts",
    description:
      "Payout operations connect beneficiary validation, limits, maker-checker approval, provider disbursement float, and settlement evidence.",
    outcomes: [
      "Validate and approve beneficiaries",
      "Enforce limits and idempotency",
      "Monitor provider disbursement liquidity",
    ],
  },
  billing: {
    title: "Meter, rate, invoice, and assure revenue.",
    eyebrow: "Cito Billing",
    description:
      "Cito Billing provides service catalogues, price books, usage metering, rating, invoicing, credits, allocations, and financial integrity controls.",
    outcomes: [
      "Model versioned prices",
      "Rate complete usage evidence",
      "Close invoices with auditable allocations",
    ],
  },
  operations: {
    title: "Operate money with one shared source of truth.",
    eyebrow: "Cito Operations",
    description:
      "Operations, finance, risk, compliance, and support work from connected transaction, reconciliation, treasury, incident, and case context.",
    outcomes: [
      "Resolve exceptions safely",
      "Close reconciliation and settlements",
      "Coordinate provider incidents and support",
    ],
  },
  "developer-platform": {
    title: "Build safely from sandbox to production.",
    eyebrow: "Cito Developer Platform",
    description:
      "Applications, scoped credentials, signed requests, webhooks, logs, test scenarios, certification, and go-live evidence are brought into one journey.",
    outcomes: [
      "Start with guided sandbox scenarios",
      "Use documented versioned APIs",
      "Complete readiness checks before going live",
    ],
  },
  about: {
    title: "Infrastructure for connected digital services.",
    eyebrow: "About Cito",
    description:
      "Cito provides the gateway, control plane, and operational experience connecting businesses to payments, billing, communications, validation, vending, and partner services.",
    outcomes: [
      "One account for your approved services",
      "Connected services for your business",
      "Documented interfaces for your integrations",
    ],
  },
  security: {
    title: "Controls designed for financial operations.",
    eyebrow: "Security & Trust",
    description:
      "Cito combines scoped access, environment separation, encrypted credentials, signed requests, maker-checker operations, audit evidence, and incident readiness.",
    outcomes: [
      "Persistent environment clarity",
      "High-risk action confirmation",
      "Auditable operational decisions",
    ],
  },
};

export function PublicProductPage({
  page,
}: {
  page: PublicPage;
}): React.ReactElement {
  const content = pages[page];
  usePageMetadata(content.eyebrow, content.description, `/${page}`);
  usePageView(page);
  return (
    <div className="cito-landing">
      <PublicHeader />
      <main id="main-content" tabIndex={-1}>
        <section className="cito-hero cito-section">
          <div className="cito-hero-copy">
            <p className="cito-eyebrow">{content.eyebrow}</p>
            <h1>{content.title}</h1>
            <p className="cito-lead">{content.description}</p>
            <div className="cito-hero-actions">
              <Link
                className="cito-button cito-button-primary cito-button-large"
                to="/signup"
                onClick={() =>
                  trackProductEvent("CTA_SELECTED", {
                    cta: "get_started",
                    product: page,
                  })
                }
              >
                Get started
              </Link>
              <Link
                className="cito-button cito-button-secondary cito-button-large"
                to="/contact"
                onClick={() =>
                  trackProductEvent("CTA_SELECTED", {
                    cta: "talk_to_sales",
                    product: page,
                  })
                }
              >
                Talk to sales
              </Link>
            </div>
          </div>
          <div className="cito-product-card">
            <h2 className="cito-product-kicker">What you can achieve</h2>
            <ol className="cito-steps">
              {content.outcomes.map((outcome, index) => (
                <li key={outcome}>
                  <span>{String(index + 1).padStart(2, "0")}</span>
                  <div>
                    <h3>{outcome}</h3>
                  </div>
                </li>
              ))}
            </ol>
          </div>
        </section>
        <section className="cito-final-cta">
          <div>
            <p className="cito-eyebrow">Controlled activation</p>
            <h2>Start in sandbox. Go live with evidence.</h2>
            <p>
              Every enabled service remains subject to merchant verification,
              commercial approval, provider certification, settlement
              configuration, and production readiness.
            </p>
          </div>
          <Link className="cito-button cito-button-light" to="/signup">
            Create account
          </Link>
        </section>
      </main>
      <PublicFooter />
    </div>
  );
}

interface PublicStatusResponse {
  status: string;
  activeIncidents: Array<Record<string, unknown>>;
  generatedAt: string;
}

export function PublicStatusPage(): React.ReactElement {
  const [data, setData] = React.useState<PublicStatusResponse | null>(null);
  const [error, setError] = React.useState("");
  usePageMetadata(
    "Service Status",
    "Current Cito platform and provider incident status.",
    "/status",
  );
  usePageView("status");
  React.useEffect(() => {
    void apiFetch("/api/public/status")
      .then(async (response) => {
        if (!response.ok)
          throw new Error("Status data is temporarily unavailable.");
        setData(await response.json());
      })
      .catch((reason: unknown) =>
        setError(
          reason instanceof Error ? reason.message : "Status unavailable",
        ),
      );
  }, []);
  return (
    <div className="cito-landing">
      <PublicHeader />
      <main id="main-content" tabIndex={-1}>
        <section className="cito-section">
          <div className="cito-section-heading">
            <p className="cito-eyebrow">Service status</p>
            <h1>Cito availability</h1>
            {data ? (
              <p>
                <StatusBadge status={data.status} /> Updated {data.generatedAt}
              </p>
            ) : error ? (
              <p role="alert">{error}</p>
            ) : (
              <p role="status">Loading current status…</p>
            )}
          </div>
          <div className="cito-feature-grid">
            {data?.activeIncidents.map((incident) => (
              <article
                className="cito-feature-card"
                key={String(incident.incident_reference)}
              >
                <StatusBadge status={String(incident.severity)} />
                <h2>{String(incident.public_title)}</h2>
                <p>{String(incident.public_message)}</p>
                <small>{String(incident.started_at)}</small>
              </article>
            ))}
            {data && !data.activeIncidents.length ? (
              <article className="cito-feature-card">
                <h2>No active incidents</h2>
                <p>No production provider incident is currently published.</p>
              </article>
            ) : null}
          </div>
        </section>
      </main>
      <PublicFooter />
    </div>
  );
}

export function PublicContactPage(): React.ReactElement {
  const [form, setForm] = React.useState({
    contactName: "",
    workEmail: "",
    companyName: "",
    countryCode: "",
    serviceInterest: "CPAY",
    message: "",
    consent: false,
  });
  const [state, setState] = React.useState<{
    loading?: boolean;
    error?: string;
    reference?: string;
  }>({});
  usePageMetadata(
    "Talk to Sales",
    "Contact Cito about payments, billing, operations, and developer platform services.",
    "/contact",
  );
  usePageView("contact");
  const update = (field: string, value: string | boolean): void =>
    setForm((current) => ({ ...current, [field]: value }));
  async function submit(event: React.FormEvent): Promise<void> {
    event.preventDefault();
    setState({ loading: true });
    try {
      const response = await apiFetch("/api/public/sales-enquiries", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ ...form, sourcePath: window.location.pathname }),
      });
      const body = await response.json();
      if (!response.ok)
        throw new Error(body.message || "The enquiry could not be submitted.");
      setState({ reference: body.enquiryReference });
      trackProductEvent("CTA_SELECTED", {
        cta: "sales_enquiry",
        product: form.serviceInterest,
      });
    } catch (reason) {
      setState({
        error:
          reason instanceof Error
            ? reason.message
            : "The enquiry could not be submitted.",
      });
    }
  }
  return (
    <div className="cito-landing">
      <PublicHeader />
      <main id="main-content" tabIndex={-1}>
        <section className="cito-section cito-contact">
          <div className="cito-section-heading">
            <p className="cito-eyebrow">Talk to Cito</p>
            <h1>Let’s talk about your business.</h1>
            <p>
              Tell us about your business and the services you need. Please
              leave out passwords, credentials and sensitive customer
              information.
            </p>
          </div>
          {state.reference ? (
            <article className="cito-feature-card" role="status">
              <h2>Enquiry received</h2>
              <p>
                Reference: <strong>{state.reference}</strong>
              </p>
            </article>
          ) : (
            <form
              className="cito-product-card cito-workspace-stack"
              onSubmit={(event) => void submit(event)}
            >
              {state.error ? <p role="alert">{state.error}</p> : null}
              <label>
                Contact name
                <input
                  value={form.contactName}
                  required
                  maxLength={190}
                  onChange={(event) =>
                    update("contactName", event.target.value)
                  }
                />
              </label>
              <label>
                Work email
                <input
                  type="email"
                  value={form.workEmail}
                  required
                  maxLength={190}
                  onChange={(event) => update("workEmail", event.target.value)}
                />
              </label>
              <label>
                Company
                <input
                  value={form.companyName}
                  required
                  maxLength={240}
                  onChange={(event) =>
                    update("companyName", event.target.value)
                  }
                />
              </label>
              <label>
                Country code (for example, UG)
                <input
                  value={form.countryCode}
                  maxLength={3}
                  onChange={(event) =>
                    update("countryCode", event.target.value.toUpperCase())
                  }
                />
              </label>
              <label>
                Service
                <select
                  value={form.serviceInterest}
                  onChange={(event) =>
                    update("serviceInterest", event.target.value)
                  }
                >
                  <option value="CPAY">Payments</option>
                  <option value="PAYOUTS">Payouts</option>
                  <option value="BILLING">Billing</option>
                  <option value="OPERATIONS">Operations</option>
                  <option value="DEVELOPER_PLATFORM">Developer Platform</option>
                </select>
              </label>
              <label>
                Message
                <textarea
                  value={form.message}
                  maxLength={2000}
                  onChange={(event) => update("message", event.target.value)}
                />
              </label>
              <label>
                <input
                  type="checkbox"
                  checked={form.consent}
                  required
                  onChange={(event) => update("consent", event.target.checked)}
                />{" "}
                I consent to Cito using these details to respond.
              </label>
              <button
                className="cito-button cito-button-primary"
                disabled={state.loading}
              >
                {state.loading ? "Submitting…" : "Submit enquiry"}
              </button>
            </form>
          )}
        </section>
      </main>
      <PublicFooter />
    </div>
  );
}
