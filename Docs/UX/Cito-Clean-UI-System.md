# Cito Clean UI System

Brand version: **1.2**  
Implementation baseline: **9 September 2026**

## Purpose

This standard turns the approved Cito brand system into one reusable product interface for the administrator and merchant portals. The goal is to make Cito's breadth feel simple and controlled instead of trying to display every capability on every screen.

The implementation does not change payment arithmetic, ledger behavior, reconciliation rules, provider activation, tenant isolation, security controls, entitlements, maker-checker flows or API contracts.

## Core hierarchy

Authenticated screens use one shared shell:

1. compact Cito sidebar;
2. global top bar with search/navigation, environment, appearance, notifications/refresh and account context;
3. one page heading with a concise purpose statement;
4. at most four summary metrics on an overview;
5. one primary working area plus one supporting status/action panel;
6. recent or primary records;
7. progressive disclosure for advanced or secondary operational detail.

A dashboard is an overview, not the entire module squeezed into one viewport.

## Canonical visual rules

- Cito brand tokens remain sourced from `Docs/Brand/LATEST.json` and `clientside/src/styles/cito-brand.css`.
- Product-system layout rules live in `clientside/src/styles/cito-product-system.css` and load after the brand layer.
- Headings use Space Grotesk with Inter/system fallbacks; body/UI uses Inter/system fallbacks.
- Normal cards use a 12 px radius, a 1 px divider and only a restrained 1 px shadow.
- Primary controls target at least 44 px height.
- Page spacing follows the existing 4 px basis, with 16/20/24 px as the main product rhythm.
- Semantic success, warning and danger colours are used only for actual state and always accompany text.
- Tables use quiet headers, horizontal dividers and mobile card fallbacks from the shared `Table` component.
- Advanced sections use drawers, sheets or `WorkspaceDisclosure` rather than stacking every table and form into the default view.

## Information architecture

### Administrator portal

**Overview**
- Dashboard

**Services**
- Payments
- Communications
- Vending & Utilities
- KYC & Identity
- Billing & BaaS

**Business**
- Merchants / Businesses
- Treasury / Float

**Operations**
- Reconciliation

**Platform**
- Integrations / API
- Administration
- Engineering / Internal

**Account**
- Settings

Deeper payout, settlement, KYB, certification, finance-close and provider-control screens remain reachable through the relevant workspaces and routes. They should not be promoted into permanent global navigation merely because the implementation has a module for them.

### Merchant portal

The merchant surface uses the same shell and visual system, while entitlement-aware navigation keeps unavailable services out of view.

## Shared workspace primitives

`clientside/src/ui/Workspace.tsx` provides:

- `WorkspaceMetricGrid`
- `WorkspaceMetric`
- `WorkspaceGrid`
- `WorkspacePanel`
- `WorkspaceQuickActions`
- `WorkspaceStatusList`
- `WorkspaceDisclosure`

New overview and operational screens should compose these primitives before creating new page-specific card systems.

## Reference screens implemented in this rollout

### Dashboard

The administrator Dashboard is deliberately reduced to:

- four live metrics;
- one money-movement chart;
- one needs-attention/status panel;
- five recent activity records;
- a short service navigation list.

The previous service-card wall and duplicate performance panels are not part of the default Dashboard view.

### Payments

The Payments workspace uses:

- four live summary metrics from the existing portal summary source;
- one transaction working table with search/filter controls;
- one quick-action panel for reconciliation, treasury, payout approvals and provider health;
- existing transaction detail and resolve sheets unchanged.

No payment totals are fabricated when summary data is unavailable.

### Communications

The current backend-backed communications surface is provider routing. The redesigned screen therefore shows configuration truth rather than pretending that message-volume APIs exist:

- provider/rule metrics;
- routing-rule table;
- one routing configuration panel;
- effective-route preview;
- provider catalog behind progressive disclosure.

Future SMS/WhatsApp/email campaign dashboards should reuse this shell when their authoritative APIs are available.

### Vending & Utilities

The current vending admin API is estate/rental/device oriented. The redesigned screen therefore shows:

- four operational metrics;
- recent rentals as the primary table;
- one operational-health/filter panel;
- callbacks, device commands and events behind progressive disclosure.

It does not invent airtime, utility or commission totals that are not returned by the current API.

## Global convergence

The product-system CSS applies to the shared shell, navigation, cards, controls, tables and responsive behavior used across both portals. This means screens that have not yet been individually re-composed still inherit the same colour, typography, spacing, border, control and table rules.

Module-specific density should continue to be reduced as those workflows are touched. Do not rewrite financial or security behavior solely to satisfy a visual redesign.

## Responsive acceptance

Changed surfaces must be checked at minimum at:

- 320 px
- 390 px
- 768 px
- 1024 px
- 1440 px

At narrower widths:

- sidebar behavior uses the existing responsive shell;
- four metrics become two columns, then one column;
- two-column workspaces become one column;
- tables use the existing shared mobile card layout;
- global search collapses to an icon control;
- account metadata can collapse while preserving the signed-in user affordance.

Also verify keyboard focus, 200% text, reduced motion, empty states, errors and loading states.

## Development rules

Before adding a new UI pattern, check whether the shared UI primitives already cover it. A new module must not invent another card radius, colour palette, heading scale, filter bar or status style.

Production-looking sample data is prohibited. Missing data must render as an explicit no-activity, unavailable, not-configured, restricted or error state.

## Verification

Frontend:

```bash
cd clientside
npm install
npm run typecheck
npm test
npm run build
```

Brand mirror:

```bash
python3 Docs/Brand/check_brand.py
```

A successful build is not evidence of deployment. Runtime verification and provider certification remain separate.
