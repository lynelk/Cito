# Cito Installation Guide

This guide covers local development, test and production-preparation setup for **Cito**. The project was previously named CPay, so some directories, database names and `CPAY_*` variables remain for compatibility.

## Requirements

| Tool | Version / guidance |
|---|---|
| Java | 21 |
| Maven | Current stable |
| Node.js | 20.19+ |
| npm | Bundled with Node.js |
| MySQL | 8+ compatible; production currently uses MySQL 9.4 |
| Git | Current stable |
| Docker | Optional for local MySQL/backend and Docker-tagged integration tests |

## Repository layout

```text
InitializrSpringbootProjectFresh/   Active Spring Boot backend
clientside/                         React/Vite frontend
Integrations/Citoconnect/           Integration/reference assets
Docs/                               Architecture, finance, security and runbooks
Sdk/                                SDK/signing/OpenAPI material
```

Use `InitializrSpringbootProjectFresh/`; the older non-Fresh backend scaffold is not the active application.

## Database

Flyway migrations under:

```text
InitializrSpringbootProjectFresh/src/main/resources/db/migration
```

are the canonical schema path. The repository migration head is **V110**. Do not initialize a new environment from an old documentation claim such as V60 and assume the other fifty migrations will develop a sense of initiative.

For local development the root `compose.yaml` can provide MySQL and the backend:

```bash
docker compose up -d mysql
docker compose up --build backend
```

A host-run backend can use the compose database with the values defined by the local compose/environment configuration. Keep real passwords outside source control.

## Environment configuration

Start from `.env.example` where applicable. Important variables include:

```text
DB_URL
DB_USERNAME
DB_PASSWORD
HTTP_PORT
APP_BASE_URL
CORS_ALLOWED_ORIGINS
CUSTOM_GATEWAYSTATE
CUSTOM_SSL_SKIP_VERIFY
ACTUATOR_USERNAME
ACTUATOR_PASSWORD
ADMIN_API_USERNAME
ADMIN_API_PASSWORD
CALLBACK_SIGNING_SECRET
MERCHANT_CHANNEL_ENCRYPTION_KEY
CPAY_KEY_ENCRYPTION_KEY
CPAY_SECURITY_NONCE_STORE
```

For clustered production use shared JDBC-backed state where required, particularly Spring Session, nonce/replay protection and ShedLock.

Never commit `.env`, provider production credentials, merchant secrets, signing material, private keys or production database credentials.

## Backend setup

```bash
cd InitializrSpringbootProjectFresh
mvn clean package
mvn test
mvn verify
java -jar target/cito-fresh-0.0.1-SNAPSHOT.jar
```

Default test runs exclude tests tagged `docker`. Run those explicitly on a Docker-capable machine when the change requires them:

```bash
mvn test -Ddocker.tests.excludedGroups=
```

`mvn verify` includes repository verification such as formatting checks for changed files.

## Frontend setup

```bash
cd clientside
npm install
npm run dev
```

Before committing frontend changes run:

```bash
npm run typecheck
npm test
npm run build
```

## Money and financial-development rules

All new authoritative financial logic must use the canonical money policy:

- `BigDecimal`;
- scale 4;
- `RoundingMode.HALF_UP`;
- presentation formatting only after calculations are complete.

Use `net.citotech.cito.money.MoneyAmount` rather than adding another local amount helper. Legacy `Double` methods may remain only at compatibility boundaries and must be converted to `BigDecimal` immediately before arithmetic.

Other required invariants:

- ledger DR = CR for each currency;
- ledger corrections use reversals/new postings, not mutation;
- settlement commercial attributes are immutable after creation;
- automatic reconciliation requires reference + amount + currency + finality + a unique candidate;
- fee schedules support flat and percentage methods only until genuine tier bands are implemented;
- tax and FX rules are effective-dated and their evidence is retained;
- credit-note allocations must preserve exact cumulative revenue/tax outcomes.

See `Docs/Financial-correctness-and-data-integrity.md`.

## Local configuration vs production

Local development may use sandbox providers, memory-backed nonce storage and local URLs. Production must use approved provider endpoints/credentials, HTTPS origins, JDBC-backed shared controls, strong separate admin/actuator credentials and production-grade encryption/signing keys.

`CUSTOM_SSL_SKIP_VERIFY` must remain `false` in production.

Swagger/OpenAPI UI should remain disabled in production unless explicitly approved:

```text
SPRINGDOC_API_DOCS_ENABLED=false
SPRINGDOC_SWAGGER_UI_ENABLED=false
```

## Migration safety

When adding a schema change:

1. choose the next unused Flyway version;
2. never rewrite an applied migration;
3. prefer additive/non-destructive changes;
4. include constraints that enforce financial invariants where feasible;
5. test the migration and application together;
6. update root and relevant `Docs/` documentation;
7. verify the actual runtime Flyway version after deployment.

V110 creates `billing_credit_note_allocations`, storing gross/revenue/tax components independently at four decimals and enforcing `gross = revenue + tax` at the database layer.

## Common startup failures

Check, in order:

- database reachability and credentials;
- Flyway validation/migration errors;
- missing required environment variables;
- port conflicts;
- invalid CORS configuration;
- invalid encryption/signing values;
- provider configuration referenced at startup;
- incompatible Java/Node versions.

## Production preparation

Installation success does not mean production readiness. Before live traffic, follow `Deployment.md`, the readiness gates under `Docs/Readiness/`, and the reconciliation/finance runbooks. The current Railway database must not be described as native HA until the planned three-data-node + two-HAProxy conversion and controlled failover test are actually completed.

## API reference and endpoint access pricing

The merchant Developers workspace contains the searchable API reference and integration guide. The admin API workbench adds current endpoint rates and an administrator-session-only full-system schema. See [the integration guide](Docs/Api/Cito-Gateway-Integration-Guide.md) and [release notes](Docs/Api/API-REFERENCE-RELEASE.md).

This change requires Flyway V123. Rates start at zero, retain four-decimal precision and use existing billing usage, price-book and invoice records. Verify the exact release in sandbox before promotion. Regenerate the private merchant reference with `python scripts/api_docs/build_portal_reference.py` whenever an owning contract or the guide changes. CI checks this generated output. Backend API-docs generation is enabled by default for the admin workbench; its HTTP routes require an administrator portal session and remain unavailable to public and merchant callers. If the runtime explicitly sets `SPRINGDOC_API_DOCS_ENABLED=false`, the admin system reference remains unavailable until that override is changed.
