# Cito Growth Intelligence Standard

## Purpose

Cito growth reporting must be derived from durable operational facts. Dashboards, exports and executive summaries may aggregate those facts, but they must not create a second definition of activation, usage, entitlement or revenue.

## Authoritative sources

| Business fact | Authoritative source | Rule |
| --- | --- | --- |
| Merchant created | `merchants.created_on` | A signed merchant exists only when the merchant record exists. |
| Activation stage | `merchant_activation_lifecycles` and `merchant_activation_steps` | Lifecycle status and step completion are authoritative. |
| Production activation | `merchant_activation_lifecycles.activated_at` | Do not infer production activation from UI visits or sandbox activity. |
| Production activity | `merchant_production_usage` | Active-merchant metrics require durable production usage. |
| Payment performance | `merchant_transactions_log` | Status, amount and currency come from the transaction record. |
| Product attachment | `cito_service_entitlements` | Count only effective `ACTIVE` production entitlements. |
| Billing revenue and provider cost | `billing_rated_charges` | Use effective rated charges, separated by charge type and currency. |
| Provider performance | `merchant_provider_analytics` | Use routed count, success/failure and measured latency. |
| Funnel observations | `product_analytics_events` | These are immutable projections of durable milestones, not an alternative source of lifecycle state. |

## Canonical metric definitions

- **Signed merchants:** merchants created inside the selected reporting window.
- **Live merchants:** merchants whose canonical lifecycle status is `LIVE`.
- **Weekly active merchants (WAU):** distinct merchants with at least one `merchant_production_usage` record in the last 7 days.
- **Monthly active merchants (MAU):** distinct merchants with at least one `merchant_production_usage` record in the last 30 days.
- **Dormant merchants:** merchants whose latest durable production usage is between 31 and 90 days old.
- **Day N retention:** among merchants activated at least N days ago, the share with durable production usage on or after `activated_at + N days`.
- **Service attach rate:** the share of MAU with active production entitlements in at least two commercial product families.
- **Payment success rate:** successful transactions divided by all transactions for the same window and currency.
- **Billed revenue:** rated charges with `charge_type=CUSTOMER_CHARGE`, grouped by currency.
- **Provider cost:** rated charges with `charge_type=PROVIDER_COST`, grouped by currency.
- **Gross margin:** billed revenue less provider cost for the same currency. Never combine currencies before conversion under an approved FX policy.
- **Activation time:** elapsed time from lifecycle creation to `activated_at`; the scorecard reports the median only where both timestamps exist.

## Commercial product families

Five product families are used for service-attachment reporting:

1. **Payments:** `CPAY`, `MARKETPLACE_PAYMENTS`, `RECURRING_PAYMENTS`, `VIRTUAL_ACCOUNTS`, `INTELLIGENT_ROUTING`, `REFUND_OPERATIONS`.
2. **Communications:** `COMMUNICATIONS`.
3. **Vending & Utilities:** `VENDING`.
4. **Billing & BaaS:** `BILLING`, `EMBEDDED_CITO`.
5. **KYC & Identity:** `IDENTITY_VALIDATION`.

Support capabilities such as Merchant Analytics and Developer Control Plane are intentionally excluded from commercial attach-rate calculations.

## Funnel milestone projection

`GrowthMilestoneProjectionService` projects durable milestones into `product_analytics_events` using deterministic event references and `INSERT IGNORE`. The projection is idempotent and contains no raw email addresses, phone numbers, credentials, tokens or payment-card data.

Projected milestones are:

- `MERCHANT_ACCOUNT_CREATED`
- `MERCHANT_USER_ACTIVATED`
- `SANDBOX_CREDENTIAL_READY`
- `FIRST_SANDBOX_SUCCESS`
- `PROVIDER_CONFIGURED`
- `GO_LIVE_APPROVED`
- `PRODUCTION_ACTIVATED`
- `FIRST_PRODUCTION_SUCCESS`

The projection is maintained by a ShedLock-protected scheduler and can also be reconciled explicitly by an administrator.

## API surface

- `GET /api/v2/admin/growth/definitions`
- `GET /api/v2/admin/growth/scorecard?windowDays=30`
- `GET /api/v2/admin/growth/merchants/{merchantId}?windowDays=30`
- `POST /api/v2/admin/growth/milestones/reconcile`

All endpoints require the administrator role. Merchant drill-downs return operational identifiers and aggregate/status data only. They must not expose credentials, secrets or unnecessary PII.

## Reporting controls

1. A metric must identify its source table and business definition.
2. Currency-bearing values must remain separated by currency unless an approved effective-dated FX conversion is applied.
3. Empty evidence is reported as zero, null or an empty collection according to the metric contract; it must never be replaced with fabricated sample values.
4. Historical funnel events are immutable observations. Correcting lifecycle state does not authorize destructive editing of historical observations.
5. Any future change to a metric definition must update the metric catalog, this standard, tests and the API contract in the same pull request.
6. Growth dashboards must display the reporting window and generation timestamp.

## Executive review cadence

The 30-day scorecard is the default executive view. Weekly reviews should focus on activation blockers, WAU/MAU, retention, service attachment, provider performance and economics. Monthly reviews should compare trends and investigate material changes in activation time, dormancy, retention and gross margin before changing commercial or rollout policy.
