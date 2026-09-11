# Airtel recovery consolidation

Tracking issue #195. Brand baseline 1.2. Candidate, not provider certification.

Current-main base `de3011367b67eac2cad65e508e72966a3f70b5ea`; durable journal/lease implementation from PR #185 `292bccb6ec69b71199eb30eaa590fa9fa86cff2a`; reservation current-read correction, fixture improvements and provider-scoped admin operations from PR #184 `ae17f944f25caa41d1090194fd1681c3cc5ee53d`. Current main's BigDecimal fee helpers, canonical endpoint policy, extracted deterministic reservation scenario and MTN safe profiles are preserved through explicit conflict decisions. Only one recovery scheduler is retained. Historical main migrations are byte-for-byte preserved. The unmerged V123 proposal is allocated additive V129; no Flyway repair or history edit is authorized.

Acceptance requires full backend/formatting, Docker-tagged MySQL invariants, frontend/UI, API/docs, security and existing exact-head release checks. A generated candidate is not approval to merge. Authenticated UAT must be rerun on the new revision. Verify original merchant/reference/environment/account/amount/currency attribution; never resubmit, settle or release holds from a timeout or callback assertion. Historical payments without immutable attribution require controlled reconciliation.

No secrets are included. No database, provider or production operation is performed by composition. Production requires a verified completed backup, populated migration acceptance, exact-SHA staging acceptance and the existing release process.
