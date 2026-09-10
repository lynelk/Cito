# Cito SLO, Incident and Service Maturity Standard

**Status:** Normative  
**Scope:** Production Cito services, provider integrations, scheduled workers and critical operational dependencies.

## 1. Service classes

Every production capability MUST be assigned a service class.

| Class | Typical Cito examples | Availability target | Primary objective |
| --- | --- | ---: | --- |
| **Class A: Financial critical** | payment execution, callbacks/status recovery, ledger, settlement, reconciliation | 99.95% monthly unless an approved provider dependency makes a lower target explicit | prevent incorrect, duplicate or lost money movement |
| **Class B: Transaction supporting** | billing/rating, identity checks required for transactions, communications required for OTP/critical notifications | 99.9% monthly | preserve transaction completion and customer access |
| **Class C: Operational/product** | analytics, reporting, admin tooling, non-critical campaigns, marketplace discovery | 99.5% monthly | preserve operator and product usefulness without compromising financial truth |

A dependency-specific SLO MAY be lower than the platform target, but the difference must be visible rather than silently absorbed into a platform claim.

## 2. SLI requirements

Critical services MUST measure the indicators relevant to their contract:

- request success rate;
- latency distribution (at least p50/p95/p99 where volume justifies it);
- provider acceptance and terminal success rate;
- callback/status-recovery delay;
- queue/outbox age and retry depth;
- reconciliation backlog and unresolved exceptions;
- ledger posting/settlement failures;
- scheduled-job completion freshness;
- billing completeness and rating lag;
- authentication/authorization failure anomalies;
- database availability and connection saturation.

Metrics MUST use bounded-cardinality dimensions. Merchant IDs, transaction IDs and raw provider references do not belong in metric labels.

## 3. Error budgets

The monthly error budget is the allowable failure implied by the SLO. Class A and Class B services MUST use the budget to govern release risk.

When a Class A service consumes 50% of its monthly error budget before the midpoint of the month, the owner MUST review change velocity and the dominant error source.

When 100% of the error budget is consumed, non-essential risky changes to that service SHOULD pause until reliability is restored or an explicit executive risk acceptance is recorded.

Provider-caused failures MUST remain distinguishable from Cito-caused failures, but both should be visible in customer-impact reporting.

## 4. Incident severity

### SEV-1: Critical

Use for actual or credible risk of incorrect/duplicate money movement, widespread inability to transact, security compromise, material data loss, ledger inconsistency, or unrecoverable provider correlation failure.

Targets:
- acknowledge within 10 minutes;
- incident commander assigned immediately;
- customer/regulatory communication owner identified as required;
- continuous active response until containment;
- post-incident review mandatory.

### SEV-2: High

Use for major degradation affecting a significant customer/provider segment, prolonged payment delays with recoverable state, material settlement/reconciliation backlog, or critical admin/operational workflows unavailable.

Targets:
- acknowledge within 20 minutes;
- owner and communication cadence established;
- post-incident review required for repeated or systemic incidents.

### SEV-3: Moderate

Use for localized degradation, non-critical feature outage, elevated errors with a working workaround, or operational delays that do not threaten financial correctness.

### SEV-4: Low

Use for minor defects, cosmetic operational issues and low-impact improvements.

## 5. Incident roles

For SEV-1/2 incidents, explicitly assign:

- **Incident Commander:** owns priorities and coordination.
- **Technical Lead:** owns diagnosis and remediation.
- **Operations/Provider Lead:** handles provider, Railway/infrastructure or external dependency coordination.
- **Finance/Reconciliation Lead:** required when money, settlement, billing or ledger evidence is involved.
- **Communications Lead:** owns customer, executive and regulatory updates as applicable.
- **Scribe:** preserves timeline, decisions and evidence.

One person may hold multiple roles for a small incident, but the responsibilities must remain explicit.

## 6. Incident response sequence

1. Detect and classify impact.
2. Stop unsafe expansion of impact; use feature flags, routing controls, production limits or provider isolation where available.
3. Preserve evidence before destructive repair.
4. Confirm tenant/provider/transaction scope.
5. Establish whether financial state is authoritative, pending verification or inconsistent.
6. Mitigate customer impact.
7. Reconcile affected records before declaring financial recovery.
8. Verify recovery from production evidence, not only health checks.
9. Close with timeline, root cause, corrective actions and owners.

Do not "fix" an incident by deleting financial/reconciliation evidence that is inconvenient to explain.

## 7. Post-incident review

A post-incident review for SEV-1 and material SEV-2 incidents MUST document:

- customer and financial impact;
- exact start/detection/containment/recovery times;
- triggering condition and contributing factors;
- why existing controls did or did not prevent escalation;
- detection gaps;
- recovery/reconciliation evidence;
- permanent corrective actions with owners and due dates;
- whether tests, runbooks, alerts, SLOs or architecture standards must change.

The review should be blameless about individuals and unsparing about system design.

## 8. Service maturity model

Every significant service/capability is assessed from **M0** to **M4**. Maturity is evidence-based; a service does not advance because a roadmap slide says it has.

### M0 - Experimental

- code/prototype exists;
- no production commitment;
- incomplete operational ownership;
- sandbox or developer use only.

### M1 - Controlled

- clear owner and domain boundary;
- basic automated tests;
- documented configuration and security boundary;
- sandbox behavior defined;
- known limitations recorded.

### M2 - Production ready

All M1 evidence plus:
- production authorization/readiness path;
- health/readiness evidence;
- retry/idempotency behavior tested where relevant;
- tenant isolation verified;
- API contract documented;
- runbook and rollback/containment path;
- monitoring and alerting for critical failure modes;
- backup/recovery dependency understood.

### M3 - Operationally proven

All M2 evidence plus:
- sustained production usage;
- SLI/SLO history available;
- incident/recovery experience or tested game-day evidence;
- reconciliation/financial controls proven where relevant;
- provider certification evidence where relevant;
- capacity and cost behavior understood;
- no unresolved critical operational debt.

### M4 - Scaled and resilient

All M3 evidence plus:
- demonstrated behavior under expected peak/load and failure modes;
- tested DR/restore path where applicable;
- multi-replica/scheduler safety demonstrated;
- automated operational controls and mature alerting;
- cost and reliability trends actively managed;
- documented change/deprecation path;
- repeatable onboarding/support model.

## 9. Maturity evidence registry

Cito's existing production-maturity validation-run APIs and readiness controls SHOULD be used to record validation evidence instead of maintaining an unrelated spreadsheet-only maturity claim.

Each maturity assessment SHOULD reference:

- service/capability code;
- target maturity level;
- test/CI evidence;
- production deployment evidence;
- SLO/monitoring evidence;
- runbook/ADR/API documents;
- provider/certification evidence if applicable;
- unresolved blockers.

## 10. Release readiness

A production release for Class A/B services MUST satisfy:

- exact-head CI green;
- required OpenAPI and architecture/operations documentation current;
- migrations reviewed and backward-safe for rollout;
- no unresolved critical security/financial invariant failure;
- rollback or containment mechanism understood;
- provider/credential/environment prerequisites verified;
- post-deploy health and workflow verification defined before promotion.

A health endpoint alone is insufficient post-deploy evidence for a financial workflow.

## 11. Backup, restore and DR

Critical database-backed services MUST have:

- scheduled recoverable backups;
- retention appropriate to business/regulatory needs;
- integrity verification;
- periodic restore tests to an isolated target;
- documented RPO/RTO targets;
- evidence of the most recent successful restore exercise.

A backup that has never been restored is an aspiration with a timestamp.

## 12. Provider and dependency maturity

Every critical external dependency MUST have:

- named owner;
- provider status/health view where technically possible;
- credential renewal/expiry process;
- sandbox/certification evidence;
- timeout/retry/recovery contract;
- escalation route;
- known maintenance/limit constraints;
- fallback/routing policy where a legitimate alternative exists.

## 13. Review cadence

- Class A service SLO/error budgets: weekly operational review.
- Open SEV corrective actions: weekly until closed.
- Service maturity: at least quarterly and before major market/provider expansion.
- Restore/DR evidence: according to approved RPO/RTO risk class, with at least periodic isolated restore testing.
- Runbooks: review after any material incident or architectural change.

## 14. Definition of reliable

A service is reliable only when its correctness, availability, recovery and operational ownership are supported by evidence over time. Code presence, a green deployment badge or a provider logo in the UI is not enough.
