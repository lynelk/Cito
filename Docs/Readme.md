# Cito Documentation Index

This folder is the detailed documentation set for **Cito**. The product/repository was previously named CPay, so some API filenames, compatibility routes and environment-variable prefixes retain the old name deliberately.

## Start here

| Document | Purpose |
|---|---|
| `../Readme.md` | Product overview, current architecture and production posture |
| `Engineering-Standards-Index.md` | Normative platform engineering, API, SLO, incident and service-maturity standards |
| `Financial-correctness-and-data-integrity.md` | Normative money, ledger, tax, FX, reconciliation and settlement invariants |
| `Architecture/Overview.md` | System/package architecture and main flows |
| `Api/cpay-v2-openapi.yaml` | Machine-readable v2 payments/compatibility API contract |
| `Api/cito-platform-v2-openapi.yaml` | Machine-readable Cito platform/merchant API contract |
| `Api/README.md` | API contract ownership and documentation boundaries |
| `Api-v2-signing.md` | Signed-request contract |
| `Api-v2-examples.md` | v2 integration examples |
| `sandbox-guide.md` | Sandbox/environment behavior |
| `Merchant-self-service.md` | Merchant journeys and controls |

## Financial and operational documentation

| Area | Main documents |
|---|---|
| Engineering governance | `Engineering-Standards-Index.md`, `Architecture/Cito-Platform-Engineering-Standard.md`, `Api/Cito-API-Lifecycle-and-Contract-Standard.md`, `Operations/Cito-SLO-Incident-and-Service-Maturity-Standard.md` |
| Money and ledger | `Financial-correctness-and-data-integrity.md`, `Money-ledger-and-orchestration-roadmap.md`, `Adr/0004-billing-ledger-integration.md` |
| Billing/BaaS | `Financial-correctness-and-data-integrity.md`, ADRs `0003`–`0005`, billing implementation/specification documents in this repository |
| Reconciliation and settlement | `Financial-correctness-and-data-integrity.md`, `Process-flow-controls.md`, `Runbooks/Reconciliation-finance-daily-close.md` |
| Production readiness | `Production-code-controls.md`, `Readiness/Market-readiness-gates.md`, `Readiness/Market-readiness-tracker.md` |
| Reliability/HA | `Reliability-scale-runbook.md`, `Runbooks/Backup-restore-dr.md`, root `Deployment.md` |
| Security | `Security-authentication-roadmap.md`, `Runbooks/Security-and-access-control.md`, `Runbooks/Callback-security-and-requeue.md` |
| Provider certification | `Provider-integration-roadmap.md`, `Runbooks/Provider-certification-checklist.md`, `Runbooks/Provider-sandbox-and-statement-validation.md` |
| Observability | `Observability.md`, `Runbooks/Operations-alerts.md`, `Runbooks/Production-incident-response.md` |

## Current financial source-of-truth rules

Documentation that predates the September 2026 financial-correctness pass must be interpreted consistently with `Financial-correctness-and-data-integrity.md`:

- authoritative money calculations use four-decimal `BigDecimal` and HALF_UP rounding;
- display precision is separate from calculation precision;
- ledger entries are append-only and balance per currency;
- settlement commercial attributes are immutable after batch creation;
- automatic reconciliation requires reference, amount, currency, eligible final status and a unique candidate;
- legacy `FeeSchedule` supports flat and percentage charging only; unsupported tier schedules fail closed;
- the separate billing rating engine may implement genuine tier pricing and must not be confused with the legacy fee-schedule adapter;
- tax and FX evidence is effective-dated/snapshotted;
- credit-note revenue/tax allocations conserve the original invoice and record exact rounding residual resolution;
- a balanced ledger alone does not prove finance close completion.

## Deployment status notes

Repository configuration and production runtime evidence are different things. The current Cito Railway application targets two backend and two frontend replicas in Amsterdam and a private MySQL 9.4 database. Native Railway MySQL HA is **not complete** until the live database has been converted to the planned three data nodes plus two HAProxy instances and a controlled leader failover has been verified.

The repository Flyway migration head is **V110**. Production must be checked after deployment to confirm the live schema has actually applied it.

## Architecture decisions

ADRs live under `Adr/`. Add/update an ADR when a change materially alters money movement, accounting, billing tenancy, callback/webhook semantics, provider boundaries, security posture, retention or externally visible contracts.

## Documentation maintenance

When code changes behavior:

1. update the root source-of-truth document(s);
2. update the affected detailed document/runbook;
3. update OpenAPI/examples if the public API changed;
4. update readiness controls if a gap becomes implemented or a new limitation is discovered;
5. update schema/migration references when the Flyway head changes;
6. distinguish implemented code from production-verified or externally certified capability.

Do not preserve a stale statement merely because it has survived several releases. Documentation is not a museum, despite occasional evidence to the contrary.
