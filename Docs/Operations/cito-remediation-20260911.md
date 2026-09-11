# Cito amber/red remediation and release-parity workstream

Date: 11 September 2026. Tracking issue: #195. Brand baseline: 1.2.

## Decision and status

The owner requested remediation, application of the executive recommendations and final main/staging/production parity. This record defines the executable work packages and evidence needed; it is not a completion certificate. Do not turn a security containment into a claim of provider certification. Do not turn a commercial target into reported usage.

The first repair branch contains hidden-file edge denial, fail-closed GnuGrid callbacks, synthetic regression coverage and synchronized developer/customer guidance. It does not implement durable authenticated asynchronous identity verification, consolidate Airtel recovery, fix SMTP transport, enable providers, or establish deployment acceptance.

## Verified baseline and source of truth

Use the current `ops/environments/cito-environments.json` before every operation. At the time of this record the active production project is `8d361df2-d17e-4d15-984e-435735f22f6c`, environment `bec50941-04c7-426d-8bc3-883cbdece892`. Do not reuse older Cito project references from memory. Active staging is project `c69c90a9-ab6f-48ff-8e04-1e10a10f92db`, environment `efde4ddb-0312-48bc-94b2-a096fd3678b0`; its Railway display name is not proof of live status.

Main was inspected at `b162dea5d74220b7d302b7441fc50a4db3d7e189`. Production frontend deployment `fedddab1-a2b0-476d-b061-4b7f338c76cf` and backend deployment `fcdbf73b-4eb6-43e8-b3fe-c23b76fc3dc0` both reported SUCCESS at `56811e29fb872d1e595391c169b0149f15164f38`. This proves deployment metadata parity within production, not parity with main or end-to-end acceptance.

The active staging environment still exposes four pending changes in patch `52637f6d-c7a9-41e7-aed9-ee0bafa0bb91`. Two read-only agent attempts to inspect the field-level diff timed out. Neither acceptance nor discard is justified from a change count alone. The production environment returned no staged patch. Production logs reconfirmed a masked email failure classified TIMEOUT at 2026-09-11T05:03:18Z on an authentication request.

PRs #184 and #185 are open and report mergeable=false. Both propose V123 recovery migrations despite main containing V128. Neither is safe to force-merge. Their financial invariants and tests must be reconciled into one current-main replacement before superseding them.

## Authorization boundaries

Allowed: repository repairs through PRs; existing CI; isolated synthetic tests; redacted, read-only infrastructure diagnosis; release promotion only after existing exact-revision gates and required acceptance.

Not authorized by this workstream: merchant money movement, live payment-provider activation, regulated credential changes, destructive production/data changes, copying production secrets/balances into staging, blindly accepting staged Railway changes, waiving financial tests, editing applied migration history or changing governance review dates without actual review. A green deployment does not expand this authority.

## First repair acceptance and containment

### Edge routing

The first regex location denies hidden file/directory segments before API compatibility regexes and SPA history fallback. `.well-known` has a deliberately limited real-file exception; nested hidden files are still denied. Legitimate API, assets, health, readiness, release marker and SPA routes remain unchanged.

Run `python3 scripts/ci/check-edge-hidden-paths.py`. It uses the nginx image declared by the frontend Dockerfile, a synthetic same-container backend, deliberately planted hidden fixtures and GET/HEAD probes. Missing Docker is a failure, never a skip. The new `Cito Security Containment` workflow runs this additional check without provider credentials. It does not replace any existing release gate.

### Identity callback trust

The current header-only adapter hook cannot prove body integrity or freshness. The callback controller previously parsed and acknowledged a body but did not persist a correlated result. The first repair makes `supportsAsync()` false, rejects every callback-header set and prevents direct parsing from bypassing that decision. Synchronous behavior and document/country support remain unchanged.

`GnuGridCallbackSafetyTest` covers absent/forged headers, direct-parser bypass, controller rejection without identity-service interaction, capability truth and positive/negative synthetic synchronous fixtures. A full backend suite must also detect any older tests or callers relying on the unsupported async claim.

To implement async support later, obtain the provider-approved body-bound authentication specification, separate callback key handling, timestamp/replay policy and fixtures. Implement durable pending-request/tenant/provider correlation, idempotent atomic completion, duplicate/out-of-order handling and negative tests. Do not reuse the outbound API key as an invented signing secret. Until then, keep the path closed and do not market async support.

### Documentation and surfaces

The canonical integration guide and packaged portal guide must be the same Git blob. Both merchant and admin workbenches already fetch and search the packaged guide. The public searchable API overview states readiness distinctions, unsupported GnuGrid async behavior and the difference between submission and delivery. No OpenAPI route/schema is added or hand-edited by this repair.

Run the existing documentation generator/checks and frontend build/tests. Verify the guide is searchable and downloadable in both authenticated portals on the deployed exact SHA. The public overview must not expose admin APIs or secrets.

## Ranked completion plan

| Rank | Accountable function | Deliverable | Evidence required to close |
|---|---|---|---|
| 1 | Engineering / Security | Merge the reviewed edge/identity containment after all gates | Exact-head CI, nginx regression, identity tests, frontend/docs parity |
| 2 | Operations | Reconcile all four staged Railway changes | Redacted before/after diff, source-controlled intended settings, no unexplained patch |
| 3 | Engineering / Payments operations | One consolidated latest-main Airtel recovery implementation | Unused additive migration version, populated MySQL upgrade, original-account/reference attribution, fenced recovery, authenticated terminal state, atomic ledger/outbox and merchant/admin visibility |
| 4 | Operations / Communications | Diagnose SMTP TIMEOUT | DNS/egress/TLS/auth stage evidence, bounded failures, approved-recipient submission and actual inbox evidence; no silent retries causing duplicates |
| 5 | QA / Release owner | Authenticated staging acceptance | Admin and merchant sessions, RBAC/tenant negatives, API search/download/rates, billing, Communications, identity and readiness on exact candidate SHA |
| 6 | Provider operations | MTN/Airtel non-money certification steps | Correct environment/account metadata, approved auth/status checks and operator callback registration; separately owned secret/activation blockers |
| 7 | Governance owner | Resolve #157 and other stale objective reviews | Actual evidence review and owner sign-off, not date-only edits |
| 8 | Release / Operations | Production promotion and final parity | Existing release process, exact-SHA runtime matrix, migration validation, public/protected endpoint smoke and rollback evidence |
| 9 | Product / Engineering | Readiness UX, traceability and modularization increments | Behavior tests, role/tenant coverage, current docs, accessible merchant/admin UI and feature-specific acceptance |
| 10 | Sales / Marketing | Design-partner experiments and approved positioning | Named qualified prospects, measured funnel, evidence-backed claims and weekly decision record |

## Product recommendations as implementation packages

**Unified merchant readiness.** Establish one authoritative backend readiness assessment per merchant, service, provider and environment. Separate configuration, sandbox verification, certification and production enablement, with degradation as health evidence. Show the blocker, responsible function, evidence timestamp and next permitted action. Do not derive readiness from an empty balance or mere credential presence. Tests must reject tenant leakage and false production-ready states.

**Developer acquisition.** Extend the existing workbench with an explicitly safe onboarding route, endpoint price, authorization requirements, callback behavior and certification limits. The connected workbench is not itself a sandbox. Measure first approved non-money API success and time-to-first-success without collecting secrets, request bodies or raw identity data. Preserve priced admissions and permissions; do not silently zero commercial rates.

**Communications operations.** Expose request, selected route, attempt, provider reference, receipt state, provider cost and merchant charge in one permissioned trace. Preserve separate states for queued, submitted, provider accepted, delivered, failed and unknown; introduce new states only through API/UI/tests together. Diagnose email transport before claiming reliable omnichannel delivery.

**Identity and trust.** Consent, evidence minimization, provider authentication, reusable verified profiles and risk-policy orchestration precede expansion into liveness or additional checks. Commercial claims must reflect supported document/country coverage. No synthetic verification may be presented as a real identity check.

**Billing/BaaS.** Use a shadow-billing design partner to validate event completeness, effective-dated rating, cost versus price, invoice-line-to-usage traceability, reconciliation and tenant isolation. No live invoicing, wallet debits or price changes are part of a shadow experiment.

**Frontend/domain architecture.** Implement new work in the target feature/domain seams. Migrate large legacy modules incrementally with contract and workflow tests; do not combine a sweeping refactor with a payment recovery release. Inventory Payments, Communications, Vending/Utilities, Billing/BaaS and Verify dependencies before expanding Integrations or Validate.

**WhatsApp and Embedded Cito.** First establish merchant demand, approved platform/provider terms, consent, message-template and channel-cost requirements. Prototype the conversation/customer/identity/order/payment/receipt lifecycle with synthetic data. No production channel activation or commercial messaging is authorized merely by a discovery plan. Qualify POS, ERP and vertical-software partners for controlled downstream merchant onboarding.

## Positioning and immediate communications pack

Working positioning: Cito is the merchant infrastructure platform for East African digital businesses, bringing payments, customer communication, digital services, verification, billing and operational visibility into one governed platform. The commercial entry point is payments; differentiation should come from operating the complete merchant lifecycle. This positioning is a direction, not a claim that every service is active for every merchant.

After the corresponding accepted release, promote searchable developer documentation, transparent endpoint pricing and the value of connecting reconciliation, billing and communication to payments. Suggested content theme: **One payment API is no longer enough: what merchants need after a transaction succeeds.** Explain pending-state handling, reconciliation and delivery evidence rather than making unsupported reliability claims. Use the approved brand toolkit and its current CTA.

Do not promote certified live MTN/Airtel rails, unrestricted payouts, guaranteed message delivery, async GnuGrid support, regulatory status, merchant adoption or performance figures without current evidence. This file is not authorization to publish an external campaign, contact prospects or spend advertising budget.

## Commercial experiments — proposed targets, not observed results

All baselines are unmeasured until telemetry or qualified-prospect records are available. Owners below are accountable functions, not fabricated individual assignments. Record cohort enrollment date, eligibility, exclusions, environment and outcome evidence. Review weekly; do not enlarge a cohort merely to hide weak conversion.

| Experiment | Owner | Cohort | Success target | Measurement boundary |
|---|---|---:|---|---|
| API workbench activation | Product / Developer success | 20 qualified teams | At least 10 complete an approved non-money sandbox call within 24 hours; at least 5 request integration/certification support within 7 days | Denominator is enrolled qualified teams; exclude staff and automated tests; use existing secure auth and sandbox isolation |
| Multi-service merchant pilot | Sales / Product | 10 businesses | At least 4 enter a structured pilot; at least 2 test two or more service families | Distinguish testing from production activation and revenue |
| Billing/BaaS shadow partner | Enterprise sales / Billing | 3 platforms | At least 1 provides approved representative synthetic/sanitized usage; 100% accepted invoice lines trace to source usage | Shadow calculation only; no posted invoice, payment or tariff change |
| Communications proof cohort | Communications / Operations | 5 businesses | At least 3 demonstrate approved-recipient delivery; every tested send has a traceable route/provider reference | Start only after a verified provider path and consent; submission alone is not delivery |
| WhatsApp discovery | Product / Partnerships | 10 merchants/platforms | At least 6 identify it as a top-three operating channel; at least 3 commit to a design session | Interview evidence, not invented channel usage or signed contract value |
| Embedded Cito distribution | Partnerships / Engineering | 5 POS/ERP/SaaS prospects | At least 2 technically qualify; at least 1 commits to a controlled downstream onboarding plan | Report written intent separately from an integration or activated merchant |

## Final release definition of done

At one recorded acceptance point, `main`, the `production` branch and all staging/production frontend/backend deployed revisions must resolve to the same accepted commit. Record the SHAs, deployment IDs, artifact/build identity, CI run IDs, authenticated UAT evidence, migration validation and health/readiness checks. A branch name alone is insufficient. Revalidate after any intervening commit.

Parity means application revision, schema compatibility, contract/docs and intended configuration policy. It does not mean identical secrets, databases, customer records, balances, provider-live flags, domains or scaling. Staging remains isolated and synthetic/sanitized.

Do not sign off while tests/UAT are pending, a staged patch is unexplained, SMTP/provider readiness is misrepresented or governance review is invented. For external blockers, record the exact dependency and owner; do not recolor an unresolved item green.

## Rollback and continuity

No migration is changed by the first containment repair. Keep the last accepted artifact and normal rollout/rollback procedures. A blanket rollback to pre-containment code would reopen unsafe callbacks; prefer a targeted forward fix or an independently reviewed edge block. Never silently restore permissive callback behavior. No RTO/RPO or sustainability/conformity claim is changed. Financial ledger, tax, settlement and ISO 20022/8583 behavior are outside this repair's code changes and remain subject to their existing gates.
