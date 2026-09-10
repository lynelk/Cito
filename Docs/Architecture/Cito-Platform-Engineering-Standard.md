# Cito Platform Engineering Standard

**Status:** Normative  
**Applies to:** Cito backend, frontend, scheduled workers, integrations, embedded/BaaS surfaces and operational tooling  
**Normative terms:** **MUST**, **MUST NOT**, **SHOULD** and **MAY** have their ordinary RFC-style meanings.

## 1. Architectural intent

Cito is one platform with multiple product domains. New features MUST extend the existing domain ownership model rather than creating parallel lifecycle, ledger, billing, entitlement, provider, communication or analytics systems.

The platform MUST preserve these boundaries:

| Domain | Authoritative ownership |
| --- | --- |
| Payments and provider execution | `api/v2`, `gateway`, payment orchestration and provider adapters |
| Financial truth | `ledger`, settlement and reconciliation services |
| Billing and monetisation | `billing/*` |
| Merchant activation | `experience/MerchantActivationLifecycleService` and activation tables |
| Product access | `platform/CitoEntitlementService` and entitlement tables |
| Communications | `communication/*` |
| Vending | `vending/*` |
| Identity/KYC | `identity/*` and `compliance/*` |
| Sandbox/go-live | `sandbox/*` and activation/go-live controls |
| Analytics | existing reporting/analytics stores and projections from durable source data |
| Embedded/BaaS | `embedded/*` and `billing/baas/*` |

A pull request that introduces a second authoritative store for one of these concerns MUST include an approved architecture decision record explaining why the current owner cannot be extended.

## 2. Financial invariants

Financial correctness takes precedence over convenience.

1. Money MUST use decimal/fixed-scale representations. Floating-point money is prohibited.
2. Posted ledger entries MUST be immutable. Corrections MUST be represented by reversal or compensating entries.
3. Every financial posting MUST balance according to the double-entry ledger contract.
4. Provider responses MUST NOT directly mutate ledger balances outside the ledger/settlement ownership boundary.
5. Payment execution MUST be idempotent across retries, callbacks and status polling.
6. Provider callbacks MUST be correlated to one exact internal transaction before terminal state or financial posting is accepted.
7. A timeout MUST NOT manufacture a provider terminal state when the provider contract requires status verification.
8. Reconciliation MUST preserve source evidence and must not silently rewrite provider or ledger history.
9. Currency-bearing totals MUST remain separated by currency unless an approved effective-dated FX conversion is applied.
10. Customer price and provider cost MUST remain separate economic dimensions.

## 3. Provider adapters and external systems

Provider-specific transport, authentication, request/response translation and error mapping MUST remain inside provider adapters or dedicated provider clients.

Business services MUST depend on stable Cito contracts, not provider-specific payload shapes.

Every production provider integration MUST define:

- authentication and token lifecycle;
- request idempotency/correlation identifiers;
- callback verification rules;
- status-query/recovery rules;
- retry policy and retryable error classes;
- terminal-state mapping;
- timeout behavior;
- credential schema and secret-storage boundary;
- sandbox versus production differences;
- certification evidence and operational owner.

A provider is not considered production-ready merely because an adapter class exists.

## 4. Tenant isolation

Merchant and billing-tenant scope MUST be explicit at every read/write boundary.

- Merchant-facing endpoints MUST derive merchant scope from authenticated context and MUST NOT trust an arbitrary merchant identifier supplied by the client.
- Administrator cross-merchant access MUST require administrator authorization and auditable purpose.
- Billing operations MUST use `billing_tenant_id` as the billing-domain scope where that domain defines it.
- Background jobs MUST retain merchant/tenant scope in queued or outbox records.
- Cache keys, idempotency keys and external correlation records MUST include sufficient tenant context to prevent cross-merchant collision.
- Tests MUST cover cross-tenant denial for sensitive surfaces.

## 5. State ownership and consistency

Every state transition MUST have one owning service.

- Lifecycle state changes belong to lifecycle services.
- Entitlement state changes belong to entitlement services.
- Ledger postings belong to ledger services.
- Rated charges belong to billing/rating services.
- Provider execution state belongs to payment/provider execution services.

Read models and dashboards MAY project these states but MUST be reconstructable from the owning data and MUST NOT be used as an alternative mutation path.

Where an operation crosses bounded contexts, use one of:

1. a transactional write plus outbox;
2. an idempotent orchestrator with durable state;
3. a compensating saga where atomicity cannot span external systems.

In-memory coordination is insufficient for behavior that must survive replicas or restarts.

## 6. Scheduled and asynchronous work

All scheduled jobs that can run on multiple replicas MUST use the configured shared lock mechanism or an equivalent database-enforced ownership primitive.

Workers MUST be idempotent. A restart, duplicate delivery or concurrent attempt MUST NOT create duplicate financial postings, customer messages, usage charges or external side effects.

Retry queues MUST distinguish transient failure from permanent validation/business failure. Infinite retries are prohibited.

## 7. Security architecture

Secrets MUST come from approved runtime secret/configuration stores and MUST NOT be committed to source control, logs, analytics payloads or error responses.

The platform MUST enforce:

- TLS verification for outbound provider connections unless a formally approved test-only exception exists;
- least-privilege authorization;
- MFA/step-up controls for privileged operations where defined;
- replay protection for signed requests/callbacks;
- PII masking in logs and operational responses;
- explicit callback URL validation;
- auditable administrator actions;
- secure password/key handling and rotation paths.

## 8. Data and schema changes

Database changes MUST use forward-only Flyway migrations. A migration that deletes or irreversibly transforms production data requires an explicit backup/restore and rollback plan.

Schema additions SHOULD be backward-compatible for at least one deployment cycle when application instances can overlap during rollout.

Indexes MUST be added for expected high-volume access paths. New high-cardinality event tables MUST define retention/archival behavior.

## 9. Observability

Every production-critical flow MUST expose enough evidence to answer:

- what request was received;
- which merchant/tenant was in scope;
- which internal correlation/idempotency reference was used;
- which provider or subsystem was selected;
- what terminal outcome occurred;
- how long it took;
- whether financial/reconciliation follow-up is pending.

Logs MUST avoid secrets and unnecessary PII. Metrics MUST use bounded-cardinality labels.

## 10. Change and release standard

Production changes MUST pass the repository's exact-head release gates. A previously green ancestor is not evidence for a changed head.

A production promotion MUST identify the exact commit being promoted. The deployment record and post-deploy verification MUST be traceable back to that commit.

Changes to payment execution, ledger, billing, credentials, authentication, tenant isolation or provider callbacks require focused regression tests in addition to generic build success.

## 11. Architecture review triggers

An architecture review and ADR are required when a change:

- creates a new authoritative store or bounded context;
- changes ledger ownership or posting semantics;
- introduces a new asynchronous consistency model;
- changes tenant isolation boundaries;
- introduces a new provider class with money movement;
- changes encryption/key ownership;
- creates a new cross-border settlement model;
- introduces a breaking API version;
- changes retention of regulated/auditable records.

## 12. Definition of done

A platform change is not complete until code, tests, schema, API documentation, operational documentation and release evidence agree on the same behavior. "Implemented" means runnable and governed, not merely present in a branch.
