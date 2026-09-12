# Cito platform 10/10 evidence standard

Date: 12 September 2026. Brand baseline 1.2.

A score of 10/10 is an evidence state, not an aspiration. A software control may be complete while an externally owned outcome remains pending. Cito must never convert a target, configured field, provider account, test plan or empty dataset into achieved operating evidence.

## Code architecture

10/10 requires one canonical domain path per financial lifecycle, explicit module ownership, tenant isolation, frontend/backend contract parity, additive migrations, bounded integrations, no parallel recovery/accounting truth and no known high-severity architectural debt without an owned remediation record.

## Financial integrity

10/10 requires exact-decimal calculations, immutable attribution, idempotency, maker-checker where applicable, fenced recovery, DR=CR conservation, transactionally consistent ledger/statement/treasury/billing/outbox effects, stale-worker rejection, current-lock reads for funding decisions, populated migration tests and real MySQL concurrency/rollback evidence.

## Security

10/10 requires authenticated/authorized administrative and merchant boundaries, CSRF/session controls, tenant denial tests, secret encryption/redaction, TLS verification, endpoint allowlists, safe callback authentication or fail-closed containment, dependency/code scanning, audit trails and zero known unowned critical/high findings. Provider credentials and personally identifiable evidence must never enter logs or source.

## Release engineering

10/10 requires feature -> main -> isolated staging -> production, exact-SHA frontend/backend parity, immutable migration history, MySQL-version compatibility lanes, authenticated staging acceptance, backup evidence before schema production promotion, non-forced production refs, runtime health/revision/migration verification and durable evidence of every gate.

## Infrastructure resilience

10/10 requires always-on production application services, at least two application replicas, health probes, bounded resource use, verified backup/retention, database high availability/failover evidence, recovery procedure evidence and an exercised restore/failover scenario. A single database primary cannot be scored 10/10 regardless of application replica count.

Current runtime evidence: production frontend sleeping is disabled, its two Amsterdam replicas and `/readyz` health probe remain in service, and the always-on deployment succeeded without changing application SHA. Production MySQL remains a single primary, so infrastructure resilience is not yet 10/10 until HA/failover is implemented and exercised.

## Database compatibility

Production runs `mysql:9.7.2`. Flyway's 10 September 2026 MySQL documentation lists 9.4 as the newest verified MySQL version, so Cito must distinguish vendor verification from Cito qualification. The canonical recovery workflow therefore tests 8.4, 9.4 and the exact production image 9.7.2. The 9.7.2 lane exercises populated migration, financial rollback/recovery and Docker-backed ledger/billing invariants. A green lane means `CITO_QUALIFIED`; it does not rewrite Redgate's published verified-version statement.

## Provider operational readiness

10/10 is provider-specific. Required: complete approved credential revision, correct environment/profile selectors, non-money authenticated connectivity evidence, operator-approved callback/status configuration where applicable, every required certification scenario approved, production enablement explicitly approved, reconciliation/statement evidence and an observed controlled live transaction only where separately authorized. Configuration alone is not readiness.

A secret-free production inspection on 12 September 2026 recorded zero rows in `merchant_channel_credentials`, `platform_channel_credentials` and `provider_certification_evidence`. MTN lacked the environment selector and all collection/disbursement credential fields. Airtel lacked password, collection/disbursement account identifiers and PIN. The inspection made zero provider requests and zero database writes. A separately guarded one-key repair then set only `gw_mtn_api_env=mtnuganda` after proving every MTN execution credential field was absent; it made zero provider requests and moved no money. The one-shot mutation helper was removed from the release branch afterward. MTN and Airtel therefore remain externally blocked on real credential/certification evidence rather than software ambiguity.

## Product completeness

10/10 requires coherent merchant/admin/public/API behavior for every offered service family, evidence-derived readiness states, accessible responsive journeys, complete docs/pricing/permissions, observable support/recovery states, truthful unavailable states and no marketed capability whose operational path is absent.

## Commercial validation

10/10 requires measured customer evidence, not feature availability. The production-maturity scorecard reads durable Founding 20, commercial-package, embedded-partner, onboarding, production-usage and developer-API records. Targets remain targets. A 10/10 score requires the board/owner-defined cohort and adoption thresholds to be achieved from real records and sustained over the defined review window. Until those outcomes exist, the instrumentation can be 10/10 while commercial validation remains below 10/10.

## Current non-software dependencies

MTN and Airtel production credentials/certification are external evidence dependencies. The platform may be technically ready to receive them without the providers themselves being green. Governance owner reviews, database HA/failover evidence and real merchant adoption likewise require accountable operating evidence and cannot be generated by CI.
