# Cito Commercial Packaging, Founding 20 and Embedded Partner Programme

**Status:** Operational standard  
**Scope:** Cito commercial packaging, initial merchant cohort execution and Embedded Cito partner growth.

## 1. Purpose

Cito's commercial layer must make the platform easier to buy without creating a second product, entitlement, lifecycle or billing system. Packages describe an offer. Existing Cito service entitlements still determine what a merchant can actually use, the merchant activation lifecycle still determines production readiness, Billing/BaaS remains the charging and contract engine, and Embedded Cito remains the operational partner platform.

The commercial programme therefore stores only commercial intent, ownership, targets and approved package terms. Operational truth is joined from the systems that already own it.

## 2. Commercial packages

Commercial packages are versioned bundles of existing Cito service codes. They may include:

- target segment and positioning;
- included service codes;
- onboarding/support level;
- approved commercial terms as structured configuration;
- status and approval evidence.

Package economics are intentionally not hard-coded into the application. Pricing, discounts, commitments, minimums and negotiated terms belong in approved commercial configuration and the existing pricing/billing engines.

### Package lifecycle

1. **DRAFT**: package is being authored or revised.
2. **SUBMITTED**: package is frozen for commercial approval.
3. **ACTIVE**: approved package may be assigned to merchants.
4. **SUSPENDED**: package remains historically visible but cannot be newly sold.
5. **RETIRED**: package has reached end of sale life.

An active package revised through the authoring endpoint returns to draft and increments its version. Approval uses a maker-checker pattern when the original creator is known.

### Merchant assignment

A package assignment records the commercial offer for a merchant and environment. It does **not** directly grant product access. Entitlements continue to be managed through `CitoEntitlementService` and existing production-readiness controls.

This separation prevents a sales configuration error from becoming a permission escalation.

## 3. Founding 20 framework

Founding 20 is a controlled initial cohort, not a vanity counter. Cito enforces slots **1 through 20** and prevents the active cohort from expanding beyond twenty merchants.

Each merchant records:

- cohort slot;
- programme status;
- commercial owner;
- customer-success owner;
- target go-live date;
- programme notes and exit evidence.

The cohort view reads canonical lifecycle state and production activation from `merchant_activation_lifecycles`; those facts are not manually duplicated in the programme record.

### Programme statuses

- **CANDIDATE**: qualified for evaluation.
- **INVITED**: commercial invitation accepted or in progress.
- **ONBOARDING**: actively completing the canonical merchant lifecycle.
- **LIVE**: production activated and operating.
- **RETAINED**: evidence shows sustained useful production activity.
- **AT_RISK**: activation, usage, support, product fit or economics require intervention.
- **EXITED**: no longer in the active programme; reason is retained.

### Operating rhythm

The Founding 20 review should combine P1 growth intelligence with the cohort view. At minimum review:

- lifecycle step and blocker;
- target versus actual activation date;
- first successful production activity;
- weekly/monthly activity;
- 7/30/90-day retention as merchants become eligible;
- service family attachment;
- payment/provider performance where applicable;
- billed revenue, provider cost and gross margin by currency;
- open operational or support blockers.

Do not mark a merchant retained merely because a salesperson remains optimistic. Retention is an observed behavior metric.

## 4. Embedded partner programme

The commercial Embedded Partner Programme is an overlay on the existing `embedded_partners` model. A merchant must already have an **active Embedded Cito partner record** before enrolment.

The programme tracks:

- programme status and optional commercial tier;
- commercial owner;
- target number of downstream merchants;
- target go-live date;
- current downstream-merchant count from `embedded_partner_merchants`;
- notes and review state.

It does not recreate branding, onboarding sessions, downstream relationships, service delegations or commissions. Those remain owned by `EmbeddedCitoService` and its existing tables.

### Partner statuses

- **CANDIDATE**
- **QUALIFIED**
- **ONBOARDING**
- **LIVE**
- **SCALING**
- **AT_RISK**
- **EXITED**

A partner should progress to **SCALING** only when downstream merchant activation and usage evidence supports it.

## 5. Commercial control rules

1. Only **ACTIVE** packages may be assigned.
2. Package assignment must not activate entitlements or production by itself.
3. Package commercial terms are versioned and auditable.
4. Founding 20 may never have more than twenty non-exited merchants.
5. Embedded programme enrolment requires an existing active Embedded Cito partner.
6. Commercial dashboards must read lifecycle, entitlement, activity and financial evidence from their authoritative sources.
7. Currency-bearing economics must remain separated by currency unless an approved FX conversion is explicitly applied.
8. Credentials, KYC documents, secrets and unnecessary PII are excluded from the commercial programme model.

## 6. API surface

Administrator endpoints live under `/api/v2/admin/commercial` and are documented in `Docs/Api/cito-admin-v2-openapi.yaml`.

The control plane supports:

- package list, authoring, submission and approval;
- merchant package proposal and activation;
- merchant commercial summary;
- Founding 20 enrolment, status and cohort listing;
- Embedded Partner Programme enrolment, status and programme listing.

All operations in the authoritative admin OpenAPI contract must retain stable, unique `operationId` values so generated clients and API-governance checks remain deterministic across releases.

## 7. Relationship to P1 and P2

P1 supplies authoritative activation, usage, retention, service-attachment and economics reporting. P2 supplies the coherent merchant onboarding/readiness projection. P4 consumes those capabilities conceptually and through their existing data owners; it must not reimplement either system.

Once P1 and P2 are merged, the commercial UI should surface their read models beside the programme records rather than creating new calculations in the browser.

## 8. Definition of done

P4 implementation is code-complete when:

- the Flyway schema is applied;
- admin APIs and authorization are active;
- package approval and assignment workflows pass tests;
- Founding 20 slot and maximum-cohort controls are enforced;
- embedded programme enrolment requires the canonical partner record;
- OpenAPI and CI gates cover the new controller;
- operational/commercial documentation is published.

P4 is commercially proven only after real merchants and partners produce activation, retention, revenue and attach-rate evidence. A table with twenty rows is not product-market fit, despite the enduring popularity of spreadsheets pretending otherwise.
