## Summary

Describe the change, the user/operational problem it solves, and the measurable acceptance criteria.

## Frontend/backend parity

Every PR must declare the actual application-surface impact. One-sided behavior-bearing changes require an approved exception reference; otherwise update both surfaces in the same PR.

Frontend/backend parity: [BOTH | BACKEND_ONLY | FRONTEND_ONLY | NONE]

Parity rationale: [explain why the declared surfaces are correct and how feature/function/process flow remains coherent]

Parity evidence: [tests, API contract, screenshots/browser evidence, or explicit non-applicability]

Parity exception: [None, or approved issue/ADR/decision reference for a justified one-sided behavior change]

## Verification

- [ ] Backend build/tests pass for affected code.
- [ ] Frontend typecheck/tests/build pass for affected UI.
- [ ] Frontend and backend implement the same user/operator capability, state, permissions and process flow where the change is parity-sensitive.
- [ ] Flyway versions are unique and migrations are additive/reversible or have a tested recovery plan where applicable.
- [ ] Security, tenant isolation, money movement and audit implications were reviewed.
- [ ] Post-deployment verification and rollback/containment steps are documented.

## Integrated management-system impact

For each item, check it or explain why it is not applicable. Do not silently leave a material impact unassessed.

### Quality / ISO 9001

- [ ] Customer, merchant, developer or internal requirements and acceptance criteria are clear.
- [ ] Defect/regression risk and required test evidence are identified.
- [ ] Documentation, support and training impacts are addressed.
- [ ] Relevant quality/SLO objective or measurement is updated when behavior changes materially.

### Information security / ISO/IEC 27001 and ISO/IEC 27032

- [ ] Authentication, authorization, tenant isolation and privileged-access impacts are reviewed.
- [ ] Data classification, privacy, retention, logging and cryptographic/key impacts are reviewed.
- [ ] Threat/abuse cases, dependency/vulnerability exposure and security monitoring are covered.
- [ ] New or materially changed residual risk is recorded in the risk/control system with an owner.

### Service management / ISO/IEC 20000-1

- [ ] Service owner, SLO/availability/capacity/monitoring/support implications are reviewed.
- [ ] Incident/problem/change/release/configuration implications are documented.
- [ ] Supplier/provider SLA, escalation or operational-procedure changes are addressed.

### Business continuity / ISO 22301

- [ ] RTO/RPO, critical dependency and recovery implications are reviewed.
- [ ] Backup/restore/DR or continuity procedures are updated when recovery behavior changes.
- [ ] A continuity/rollback exercise is included when the change can materially affect Tier 0/1 recovery.

### Financial integrity and interoperability

- [ ] Ledger, settlement, reconciliation, amount/currency precision, idempotency and reversal/refund impacts are reviewed.
- [ ] ISO 20022 profile/schema/usage-guideline changes are documented and tested where applicable.
- [ ] ISO 8583 MTI/data-dictionary/security/network-profile changes are documented and certified where applicable.
- [ ] BIC/ISO 9362 inputs are structurally validated and authoritative registry/counterparty verification remains external where required.
- [ ] Sensitive ISO 8583/card/payment-authentication data cannot enter normal logs or repository evidence.

### Climate / ISO 32212 and management-system context

- [ ] Material climate/sustainability or supplier-transition impact has been assessed, or is explicitly not applicable.
- [ ] No sustainability/net-zero/conformity claim is introduced without approved evidence and applicability review.

## Sandbox parity

Every production capability should have a sandbox test path unless an explicit restriction is documented.

- [ ] New non-admin `/api/v2` endpoints appear in the runtime sandbox capability catalog.
- [ ] New provider-facing or money-moving behavior uses a SANDBOX adapter/simulator and cannot reach production providers or balances when `X-CPay-Environment: SANDBOX` is selected.
- [ ] New feature flags are visible through the sandbox catalog and have deterministic test data/scenarios where useful.
- [ ] New webhook/callback behavior has a sandbox test/replay path.
- [ ] New KYC/KYB/identity behavior has synthetic personas or documented sandbox-provider fixtures.
- [ ] Sandbox reset/snapshot behavior covers any new sandbox-owned state.
- [ ] Certification/readiness checks were extended when the feature is a go-live dependency.

## Documentation and controlled records

- [ ] OpenAPI annotations/schemas/examples reflect new or changed public APIs.
- [ ] Developer guides/runbooks are updated for new integration behavior.
- [ ] Swagger/OpenAPI remains usable on the sandbox deployment.
- [ ] Breaking changes include migration/versioning guidance.
- [ ] IMS/control/risk/service/supplier records are updated when the change affects them.
- [ ] Evidence references do not contain production secrets, raw identity documents or restricted payment-authentication data.
- [ ] Any Railway/runtime configuration change is reflected in repository-controlled environment/runbook records before the work is declared complete.

## Deployment

- [ ] The change is safe to advance to `main` after CI.
- [ ] When staging exists, `main` auto-deploys to staging and backend/frontend staging deployments resolve to the exact same `main` SHA.
- [ ] Staging verification evidence is recorded before manual promotion to production.
- [ ] Production backend and frontend resolve to the exact same accepted `production` SHA.
- [ ] Production configuration changes are peer-reviewed and secret values remain outside source control.
- [ ] High-risk or emergency changes identify the required retrospective problem/CAPA review.

## Brand and customer experience

Read `Docs/Brand/LATEST.json` and its referenced standard. Replace every field below; do not leave the brand assessment implicit.

Brand version: [current version from the pointer]

Brand impact: [changed touchpoints, or not applicable with a specific reason]

- [ ] Current tokens, approved artwork, naming and voice are used where relevant.
- [ ] Capabilities, prices, limits, permissions and financial states remain truthful.
- [ ] Responsive/state, keyboard/focus, 200% text and relevant assistive-technology evidence is attached, or non-applicability is explained.
- [ ] Claims and exceptions have real owners, evidence and review/expiry dates.
- [ ] Documentation, messaging and release scope identify exactly which surfaces changed.

Evidence / approvals / exceptions: [references or explicit non-applicability]
