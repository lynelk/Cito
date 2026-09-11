# Canonical recovery, readiness and developer activation remediation

Tracking issue #195. Brand baseline 1.2. This record describes implementation and acceptance requirements, not provider certification or production acceptance.

## Reconciliation decision

The reviewed first candidate e8a124c1 passed its composition tests but is NOT safe to merge: the old PR #185 Airtel adapter would reject production calls that already entered the current canonical MobileMoneyExecutionService. Main already routes native, compatibility and merchant-batch MTN/Airtel payments through one persistent journal with encrypted credential snapshots and one atomic financial finalizer. Creating a parallel journal/accounting path would regress that architecture. PR #185 also permits a stored generic HTTP 4xx initiation rejection to bypass authenticated status verification, which is not acceptable settlement authority.

This replacement preserves current native adapters, compatibility bridge, original provider references, encrypted snapshots, BigDecimal fees, maker-checker and ledger/outbox finalization. It ports the useful provider-scoped admin/merchant presentation and complete synthetic billing fixtures from the reconciled proposal, but none of the parallel submission registry, status store, second scheduler or HTTP-4xx finality shortcut.

## Implemented controls

Additive V129 extends the existing execution journal with 90-second fenced claims, bounded retries, diagnostic codes and callback rate-limiting timestamps. The current claim and expiry are checked under the same row lock and database transaction as canonical financial finalization. Failed ledger, billing, treasury or outbox work rolls back together with claim completion. No network call occurs under that financial lock.

Provider status work has a 30-second caller deadline, at most two daemon workers and no queued unbounded tasks. A stalled/non-interruptible provider can exhaust only those two workers; subsequent work defers rather than inventing a result or exhausting threads. Finalization never runs in those worker tasks. Scheduler batches are bounded to ten rows with a 90-second start budget under the existing ten-minute distributed schedule lock. Sandbox runtimes reject production recovery; production runtimes may process separately scoped sandbox and production entries. Neither changes provider activation.

Unauthenticated Airtel callbacks schedule only canonical lookups. They never reopen the historical mutable-credential path. Historical submissions lacking canonical attribution need controlled reconciliation and original-account evidence.

The shared onboarding assessment separates Not configured, Configured, Sandbox verified, Certification pending, Production enabled and Degraded. Required-step emptiness, waived testing, missing completion timestamps and unsupported LIVE claims fail closed. Entitlement access is labelled separately from readiness. The same assessment is visible to merchants and authorized administrators. The developer quickstart is a local documentation filter, not a paid/API/provider operation.

## SMTP diagnostic evidence

Production sibling diagnostic worker 5f5b474d-b5b7-4c4f-aeac-664f42c4f1dc, deployment e11b28a5-0026-447b-9858-8dbe5ec1283e, observed 2026-09-11T14:57:20Z: mail.coresynergi.es DNS PASS; port465 TCP/TLS/SMTP PASS in90ms; port587 TCP/TLS/SMTP PASS in136ms. Configured implicitTLS465 with STARTTLSfalse. Authentication attempts0, message sends0, database writes0. This proves the sibling egress/handshake path only. Application authentication, original intermittent TIMEOUT and inbox delivery remain unverified.

Temporary worker references were blanked and its command made inert. Cleanup deployment b950107b-baf9-4349-9864-7885f5cdab6c reported SUCCESS and printed the inert-worker message at15:01:31Z. Production backend/frontend/database services were not redeployed by this diagnostic. A completed native production backup ID still has not been returned by the Railway agent; timeout is not backup evidence.

## Acceptance and rollout

Run full Maven verify, real MySQL clean/populated migration acceptance, stale/foreign claim and financial rollback scenarios, all Docker-tagged ledger/billing tests, native Airtel adapter regression, bounded verification tests, frontend typecheck/lint/tests/build, documentation generation/check, browser/accessibility and all existing release gates. Re-run authenticated staging UAT on the final merged revision; prior de301136 acceptance cannot certify this revision. Verify claim columns and schema V129, release markers, RBAC/tenant negatives and all changed web/portal surfaces.

No applied migration may be edited. Take and verify a fresh completed production backup before the required populated V125-to-latest upgrade. Promote through the existing exact-SHA release process only after acceptance. Keep environment secrets, databases and provider modes isolated. Rollback must retain new nullable journal columns and must not re-enable unsafe callback handling; prefer an independently reviewed forward fix. No RTO/RPO, licensing, conformity, provider certification or live-rail claim is introduced.

## Recommendation boundaries

This change implements recovery reliability, evidence-derived onboarding, safer developer activation and provider-scoped operational presentation. Communications routing/delivery/cost reporting, Billing/BaaS shadow design partners, additional Verify/WhatsApp integrations, modularization and commercial cohort experiments remain assessed in the umbrella tracker. Interview commitments, signed provider approval, merchant conversions and actual delivery cannot be manufactured by a code change. Do not mark these achieved merely because their plans or existing scaffolding are present.
