# Cito CI/CD and Release Gates

Cito uses GitHub Actions to validate code, financial invariants, API contracts, security/governance controls and deployable artifacts. Do not rely on an older list of three Docker/Kubernetes workflows as the source of truth; inspect `.github/workflows/` because the repository now contains additional domain-specific release gates.

## Required pull-request posture

Every production change should be merged through a pull request. The exact checks triggered depend on paths and workflow configuration, but a release must not bypass a failing relevant check merely because another unrelated workflow is green.

Financial/payment changes require particular scrutiny because compile success is not arithmetic correctness. Tests should express the invariant being changed.

## Backend verification

From `InitializrSpringbootProjectFresh/`:

```bash
mvn test
mvn verify
```

Docker-tagged integration tests are excluded from the normal run. On a Docker-capable environment, run them when the change touches database/provider/end-to-end behavior:

```bash
mvn test -Ddocker.tests.excludedGroups=
```

`mvn verify` includes changed-file formatting checks. If Spotless reports formatting issues, apply the repository formatter rather than hand-tuning whitespace until the computer becomes bored.

## Frontend verification

From `clientside/`:

```bash
npm install
npm run typecheck
npm test
npm run build
```

## Financial release gates

Relevant workflows include billing convergence and financial/entitlement/isolation checks. For any change to money, fees, billing, ledger, reconciliation, settlement, tax or FX, CI should prove the applicable rules in `Docs/Financial-correctness-and-data-integrity.md`.

The baseline financial invariants include:

- canonical four-decimal `BigDecimal` arithmetic;
- DR = CR per currency;
- idempotent financial references;
- no fake tier-pricing fallback;
- valid fee bounds;
- safe multi-factor reconciliation;
- immutable settlement commercial attributes;
- effective-dated tax/FX evidence;
- cumulative credit-note tax conservation;
- no invoice over-allocation;
- tenant isolation and entitlement enforcement.

## Migration changes

Flyway migrations are production code. CI/review must verify that:

- the migration number is unused;
- applied migrations are not rewritten;
- destructive changes have an explicit cutover/rollback plan;
- financial constraints are represented in SQL where appropriate;
- the application can start against the new schema;
- docs state the new migration head.

The repository head for this financial-correctness release is **V110**.

## Deployment model

The current hosted production environment is Railway. Repository Railway descriptors are part of the deployment source of truth. Older Docker/Kubernetes assets may remain useful for portability/testing, but their presence does not mean Kubernetes is the active production platform.

Current repository intent:

- backend: two Amsterdam replicas;
- frontend: two Amsterdam replicas;
- database: private MySQL 9.4;
- backups: hourly logical backup job;
- MySQL native HA conversion: **not yet complete until live conversion/failover evidence exists**.

See `Deployment.md` for the production gate.

## Secrets

Never store deployment credentials, provider credentials, database passwords, merchant private keys or signing/encryption keys in workflow YAML or repository files. Use the deployment platform/GitHub secret mechanism appropriate to the workflow.

Do not copy placeholder secret names from old deployment documents and assume they are configured. Inspect the workflow actually being run.

## Pull-request checklist

Before merge:

1. affected backend/frontend builds pass;
2. relevant domain tests pass;
3. migration checks pass;
4. API/OpenAPI contract checks pass if applicable;
5. security/governance checks pass if applicable;
6. documentation reflects behavior after the change;
7. no failing relevant check is ignored;
8. the PR diff contains no secrets, generated junk or accidental unrelated files.

## Post-merge verification

The MySQL migration/payment lifecycle gate also runs the deterministic stale-snapshot ledger reservation scenario for single and batch payouts. See [Staging consolidation](Docs/Operations/staging-consolidation-20260910.md) for its financial invariant and the preserved older branch work.

A successful merge does not prove production has deployed it. Where deployment is automatic, verify the actual deployment status and runtime logs. For database changes, confirm the runtime Flyway version rather than assuming migration application from Git history.

Financial post-deploy checks should include health, database connectivity, scheduler locking, ledger balance, reconciliation behavior, settlement replay safety and a fresh backup result.

## Rollback principle

Application rollback must respect forward-only financial history and schema evolution. Never roll back by deleting the production database/volume or by mutating already-posted ledger entries. Prefer forward corrections and explicit reversals.


Communications notification policies, provider configuration, evidence and release checks: [Notification orchestration](Docs/Communications/Notification-Orchestration.md).

SMTP transport, mailbox configuration and the optional connection-only production diagnostic: [Administrator email recovery](Docs/Api/admin-password-recovery.md#smtp-configuration). Password-reset and Communications emails share TLS settings and bounded timeouts; application health alone does not verify delivery.

Alert group phone recipients: V125 extends existing memberships to accept either an active administrator ID or an international phone number through the notification API and Communications UI. Phone recipients receive alerts without an administrator account. Repeated saves update membership; deactivate recipients after testing. Apply Flyway through V125 before deploying the corresponding UI. The clean-database release scenario verifies phone normalization, duplicate saves, disabled recipients and critical-alert delivery through the fake provider.

SMS provider activation is an explicit admin action in Communications → Provider catalog, also available at `POST /api/v2/admin/communication/routing/providers/{providerCode}/activation` with `{ "enabled": true }`. Activation is audited and uses the existing provider registry; SMSMobilo requires its configured API key. Routing-rule activation alone does not enable a provider. No migration or automatic provider activation occurs on deployment. Verify the saved provider state separately from live message delivery.
