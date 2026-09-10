# Cito Deployment Guide

This guide describes the production deployment and verification requirements for Cito. The repository still contains some `CPAY_*` environment variables and legacy deployment helpers for backward compatibility; the product and repository are now **Cito**.

## Production runtime

| Item | Current requirement |
|---|---|
| Backend | Spring Boot 4.1 on Java 21 |
| Backend artifact | `InitializrSpringbootProjectFresh/target/cito-fresh-0.0.1-SNAPSHOT.jar` |
| Frontend | React 18 / Vite 8 |
| Database | MySQL-compatible; current Railway production service uses MySQL 9.4 |
| Migrations | Flyway, repository head V110 |
| Sessions | Spring Session JDBC |
| Distributed jobs | ShedLock on the shared database |
| Health | backend `/status/health`; frontend `/readyz` in Railway |

## Current Railway topology

Repository Railway descriptors request:

- two backend replicas in Amsterdam;
- two frontend replicas in Amsterdam;
- a private MySQL service;
- no public MySQL endpoint;
- hourly logical backups.

The application must not be declared database-HA merely because application replicas exist. **The native Railway MySQL HA conversion to three data nodes plus two HAProxy instances is still an outstanding production task until the live environment is converted and failover-tested.** After that conversion, all database consumers, including backup jobs, must be verified against the HA endpoint rather than a hard-coded standalone MySQL hostname.

Do not delete production volumes or backups to simulate failover.

## Build and verification

Backend:

```bash
cd InitializrSpringbootProjectFresh
mvn clean package
mvn test
mvn verify
```

Frontend:

```bash
cd clientside
npm install
npm run typecheck
npm test
npm run build
```

Docker-tagged backend integration tests are opt-in:

```bash
mvn test -Ddocker.tests.excludedGroups=
```

A financial release must also pass the repository's billing, accounting, reconciliation and governance CI gates. Green CI is necessary, but deployment is not accepted until runtime evidence is checked.

## Database migration gate

Before production rollout:

1. back up the live database and verify the backup is readable;
2. test new migrations on a representative staging/restored copy where practical;
3. verify that no previously applied Flyway migration was edited;
4. deploy the backend and inspect startup logs for Flyway validation and migration success;
5. verify the runtime schema version. For this release the expected repository head is **V110**;
6. stop rollout if Flyway reports checksum mismatches, partial migration state or schema validation errors.

V110 adds immutable tax/revenue allocation evidence for billing credit notes and is part of the financial-correctness fix. It must be present before relying on the updated credit-note workflow.

## Financial deployment invariants

After deployment verify all of the following:

- authoritative monetary calculations remain four-decimal `BigDecimal` with HALF_UP rounding;
- ledger postings balance debits and credits independently for every currency;
- duplicate transaction/idempotency references do not create duplicate financial postings;
- a settlement batch can be replayed only with identical provider, channel, currency and amount;
- a conflicting settlement replay is rejected and cannot rewrite the operational amount independently of the ledger;
- automatic reconciliation does not match on merchant reference alone and leaves ambiguous candidates unmatched;
- unsupported `TIER` fee schedules fail rather than charge a disguised flat fee;
- fee schedules reject non-positive amounts and percentages above 100%;
- invoice tax, credit-note tax and FX evidence retain four-decimal precision;
- cumulative credit-note tax can never exceed the original invoice tax and a complete credit reversal resolves any remaining rounding residual exactly.

See `Docs/Financial-correctness-and-data-integrity.md`.

## Multiple backend instances

Schedulers and workers that may run on more than one backend replica must coordinate through shared database locking/claiming. Both backend instances must point at the same writable database endpoint. Verify ShedLock ownership in logs or database evidence when testing scheduled reconciliation, payout and cleanup work.

Spring Session must use JDBC in clustered production so a user session is not tied to one backend process.

## Required production configuration

Secrets and credentials must live outside source control. Representative variables include:

```text
DB_URL
DB_USERNAME
DB_PASSWORD
CUSTOM_GATEWAYSTATE=PRODUCTION
CUSTOM_SSL_SKIP_VERIFY=false
CORS_ALLOWED_ORIGINS
APP_BASE_URL
ACTUATOR_USERNAME
ACTUATOR_PASSWORD
ADMIN_API_USERNAME
ADMIN_API_PASSWORD
CALLBACK_SIGNING_SECRET
MERCHANT_CHANNEL_ENCRYPTION_KEY
CPAY_KEY_ENCRYPTION_KEY
CPAY_SECURITY_NONCE_STORE=jdbc
```

Keep the historical `CPAY_*` names where they are part of the deployed compatibility contract. A future rename requires an explicit dual-read migration period.

The admin API workbench requires runtime OpenAPI generation. Its routes require an administrator portal session; public Swagger UI remains disabled:

```text
SPRINGDOC_API_DOCS_ENABLED=true
SPRINGDOC_SWAGGER_UI_ENABLED=false
```

Provider production endpoints and credentials must be configured for the intended merchant/channel environment. Code presence is not provider certification.

## Health and smoke checks

After rollout verify the public application routes and private runtime evidence. At minimum:

- backend `/status/health` responds successfully;
- frontend `/readyz` responds successfully;
- both expected backend and frontend replicas are healthy;
- Hikari connects successfully;
- Flyway reports the expected schema version;
- no persistent database communication errors appear;
- Spring Session JDBC is functioning across backend replicas;
- ShedLock prevents duplicate scheduler execution;
- payment initiation/status paths remain idempotent;
- callbacks/webhooks do not double-deliver after retry;
- reconciliation imports, automatic matching and manual review work;
- trial balance reports balanced for each active currency;
- settlement open/replay/close behavior is correct;
- invoice create/finalize/pay/credit/void paths post the expected ledger entries;
- a fresh scheduled database backup completes successfully.

## Controlled MySQL HA failover gate

Once native Railway MySQL HA is enabled, perform a controlled leader switchover using the platform's supported leader action. Verify:

1. the HAProxy private endpoint remains stable;
2. application connections briefly retry rather than requiring configuration changes;
3. both backend replicas reconnect to the new primary;
4. workers and schedulers resume without duplicate processing;
5. payment, webhook and reconciliation logs show no duplicated financial outcomes;
6. backups still connect through the intended HA endpoint;
7. MySQL remains private.

Only after this test should the database layer be described as HA-ready.

## Finance-close caution

A balanced ledger is only one finance-close condition. Do not mark a business day financially closed unless required statements/imports, reconciliation exceptions, maker-checker approvals and close evidence are present. `ledger_balanced=true` by itself is not equivalent to a completed finance close. Humans have tried to make that shortcut before; accountants remain unimpressed.

## Rollback

Application rollback must not roll back already-applied financial history or destructive schema changes. Prefer forward fixes and append-only correcting entries. Before reverting an application release, confirm that the older code understands the current Flyway schema and financial records.

Never delete a production database volume or the verified backup destination as a rollback technique.

## API reference and endpoint access pricing

The merchant Developers workspace contains the searchable API reference and integration guide. The admin API workbench adds current endpoint rates and an administrator-session-only full-system schema. See [the integration guide](Docs/Api/Cito-Gateway-Integration-Guide.md) and [release notes](Docs/Api/API-REFERENCE-RELEASE.md).

This change requires Flyway V123. Rates start at zero, retain four-decimal precision and use existing billing usage, price-book and invoice records. Verify the exact release in sandbox before promotion. Regenerate the private merchant reference with `python scripts/api_docs/build_portal_reference.py` whenever an owning contract or the guide changes. CI checks this generated output. Backend API-docs generation is enabled by default for the admin workbench; its HTTP routes require an administrator portal session and remain unavailable to public and merchant callers. If the runtime explicitly sets `SPRINGDOC_API_DOCS_ENABLED=false`, the admin system reference remains unavailable until that override is changed.

Communications notification policies, provider configuration, evidence and release checks: [Notification orchestration](Docs/Communications/Notification-Orchestration.md).
