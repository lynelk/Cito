# Cito

Cito is a multi-tenant payments, billing and financial-operations platform. It combines payment orchestration, merchant APIs, hosted payment journeys, callbacks/webhooks, reconciliation, settlement, immutable double-entry accounting, usage billing/BaaS, invoicing, communication metering, treasury controls, compliance workflows and operational reporting in one platform.

The repository was previously named **CPay**. Some compatibility identifiers and environment-variable prefixes still use `CPAY_`; they remain intentionally stable where changing them would break existing installations or merchant integrations.

## Current architecture

| Area | Implementation |
|---|---|
| Backend | Java 21, Spring Boot 4.1 |
| Frontend | React 18, Vite 8 |
| Database | MySQL 9.4 in the current Railway production environment; migrations use Flyway |
| APIs | Legacy `/api/v1/**` plus signed/versioned `/api/v2/**` |
| Sessions | Spring Session JDBC |
| Distributed jobs | ShedLock against the shared database |
| Accounting | Immutable double-entry ledger, balanced independently per currency |
| Billing | Metering, rating, tax, FX, charging, invoicing, BaaS and traceability |
| Reconciliation | Provider statement import, deterministic matching, exceptions and governed settlement |

## Financial correctness rules

Cito treats money as accounting data, not as a convenient floating-point suggestion.

1. **Authoritative monetary calculations use `BigDecimal`, four decimal places, `HALF_UP`.** Two-decimal formatting is presentation only and must never reduce calculation precision before fees, tax, FX, ledger posting, settlement or reconciliation are complete.
2. **Every ledger transaction must balance DR = CR for each currency.** Corrections are new reversing entries; posted ledger entries are never edited in place.
3. **Commercial idempotency is strict.** Reusing a reference with different commercial attributes fails closed.
4. **Settlement batch attributes are immutable once opened.** An identical replay is allowed; a replay with another provider, channel, currency or amount is rejected.
5. **Automatic reconciliation is multi-factor.** A merchant reference alone cannot create a match. The candidate must also match amount, currency, eligible final status and be unique. Ambiguous rows stay unmatched for review.
6. **Fees are effective-dated and validated.** Flat and percentage charging are supported. `TIER` deliberately fails closed until a real tier-band model exists; it is never silently treated as a flat fee.
7. **Tax and FX evidence is retained.** Effective-dated rules/rates are snapshotted against the commercial artifact that used them.
8. **Partial credit notes cannot accumulate tax drift.** Revenue/tax allocations are stored at four decimals and the final full reversal absorbs any prior rounding residual without exceeding the original invoice tax.

See [`Docs/Financial-correctness-and-data-integrity.md`](Docs/Financial-correctness-and-data-integrity.md) for the detailed invariants and acceptance tests.

## Repository layout

```text
InitializrSpringbootProjectFresh/   Active Spring Boot backend
clientside/                         React/Vite admin and merchant portal
Integrations/Citoconnect/           CitoConnect/reference integration assets
Docs/                               Architecture, API, finance, security, readiness and runbooks
Sdk/                                Signing helpers and SDK/OpenAPI material
deployment/                         Deployment support assets
Setup/                              Legacy/transitional installation helpers
```

`InitializrSpringbootProjectFresh/src/main/resources/db/migration/` is the canonical schema history. The repository migration head is **V110**.

## Core capabilities

Cito provides payment collection and payout orchestration; payment links and hosted checkout; request-to-pay/invoicing; merchant self-service; signed merchant APIs; provider adapters; asynchronous callback and webhook handling; idempotency and replay protection; reconciliation and settlement; double-entry accounting; finance-close controls; treasury and provider balances; billing/metering/rating; BaaS charging; effective-dated pricing, tax and FX; communication metering; vending capabilities; compliance/KYB controls; audit trails; exports and operational analytics.

Provider integrations represented in the codebase include MTN MoMo, Airtel Money, Airtel OpenAPI, Safaricom M-Pesa and Yo! Payments. Provider certification is separate from code existence: no integration should be described as live-certified unless its production credentials, callbacks, settlement evidence and provider acceptance have actually been verified.

## Local development

Requirements:

- Java 21
- Maven
- Node.js 20.19+ and npm
- MySQL 8+ compatible database, or Docker for the provided local stack
- Git

Backend:

```bash
cd InitializrSpringbootProjectFresh
mvn clean package
mvn test
mvn verify
java -jar target/cito-fresh-0.0.1-SNAPSHOT.jar
```

Frontend:

```bash
cd clientside
npm install
npm run typecheck
npm test
npm run build
```

Local Docker database/backend:

```bash
docker compose up -d mysql
docker compose up --build backend
```

See [`Installation.md`](Installation.md) for environment variables and setup details.

## Database migrations

Flyway migrations are append-only. Never edit an already-applied production migration to change financial behavior; add a new migration and document the cutover.

Current financial-integrity migrations include ledger controls, billing tenancy/catalog/metering/rating, invoice and credit-note domains, BaaS charging and tax, provider treasury, pricing/rating evidence, tenant idempotency, entitlement bridging, marketplace/refund alignment and **V110 credit-note tax allocation evidence**.

Production schema state must be verified from runtime logs after deployment rather than inferred merely because a migration exists in Git. A repository with a new migration and a database that has not applied it are, inconveniently, two different realities.

## Testing and release gates

Backend changes should pass:

```bash
cd InitializrSpringbootProjectFresh
mvn test
mvn verify
```

Docker-tagged integration tests can be enabled with:

```bash
mvn test -Ddocker.tests.excludedGroups=
```

Frontend changes should pass:

```bash
cd clientside
npm run typecheck
npm test
npm run build
```

Financial changes must include regression tests for the invariant being changed. A green build is necessary but does not replace reconciliation, ledger and settlement evidence.

The repository also contains GitHub Actions gates for billing convergence, financial/entitlement/isolation behavior, ISO governance/financial messaging and other production controls. See [`CI_CD_SETUP.md`](CI_CD_SETUP.md).

## Production deployment

The current hosted production topology uses Railway. Repository deployment descriptors request two backend and two frontend replicas in Amsterdam. MySQL is private and currently uses MySQL 9.4.

**Important current limitation:** the native Railway three-data-node MySQL HA topology with two HAProxy instances has not yet been completed. Do not describe the database as HA until the live service has actually been converted and failover-tested. The database must remain private, backups must remain recoverable, and application database references must be reverified after any HA conversion.

See [`Deployment.md`](Deployment.md) for deployment gates and production verification.

## Security and operational principles

- never commit production secrets, merchant keys or provider credentials;
- require signed/idempotent requests where the API contract specifies them;
- use shared JDBC nonce/session/lock storage in clustered production;
- preserve maker-checker separation for high-risk finance actions;
- keep callbacks/webhooks auditable and replay-safe;
- prefer append-only corrections over mutation of financial history;
- retain provider, tax, FX, ledger and reconciliation evidence required to reconstruct an outcome;
- fail closed when a commercial calculation or provider state is unsupported or ambiguous.

## Documentation

Start with:

- [`Installation.md`](Installation.md)
- [`Deployment.md`](Deployment.md)
- [`Contributing.md`](Contributing.md)
- [`CI_CD_SETUP.md`](CI_CD_SETUP.md)
- [`Docs/Financial-correctness-and-data-integrity.md`](Docs/Financial-correctness-and-data-integrity.md)
- [`Docs/Money-ledger-and-orchestration-roadmap.md`](Docs/Money-ledger-and-orchestration-roadmap.md)
- [`Docs/Readiness/Market-readiness-gates.md`](Docs/Readiness/Market-readiness-gates.md)
- [`Docs/Runbooks/Reconciliation-finance-daily-close.md`](Docs/Runbooks/Reconciliation-finance-daily-close.md)

## Compatibility note

The product name is **Cito**. `CPay` remains in some Java class names, database identifiers, URLs/settings examples and `CPAY_*` environment variables for backward compatibility. Rename those only through an explicit compatibility migration, not cosmetic search-and-replace. Payment platforms have enough exciting failure modes without manufacturing new ones in configuration names.

## API reference and endpoint access pricing

The merchant Developers workspace contains the searchable API reference and integration guide. The admin API workbench adds current endpoint rates and an administrator-session-only full-system schema. See [the integration guide](Docs/Api/Cito-Gateway-Integration-Guide.md) and [release notes](Docs/Api/API-REFERENCE-RELEASE.md).

This change requires Flyway V128. Rates start at zero, retain four-decimal precision and use existing billing usage, price-book and invoice records. Verify the exact release in sandbox before promotion. Regenerate the private merchant reference with `python scripts/api_docs/build_portal_reference.py` whenever an owning contract or the guide changes. CI checks this generated output. Backend API-docs generation is enabled by default for the admin workbench; its HTTP routes require an administrator portal session and remain unavailable to public and merchant callers. If the runtime explicitly sets `SPRINGDOC_API_DOCS_ENABLED=false`, the admin system reference remains unavailable until that override is changed.

Communications notification policies, provider configuration, evidence and release checks: [Notification orchestration](Docs/Communications/Notification-Orchestration.md).

SMTP transport, mailbox configuration and the optional connection-only production diagnostic: [Administrator email recovery](Docs/Api/admin-password-recovery.md#smtp-configuration). Password-reset and Communications emails share TLS settings and bounded timeouts; application health alone does not verify delivery.

Alert group phone recipients: V125 extends existing memberships to accept either an active administrator ID or an international phone number through the notification API and Communications UI. Phone recipients receive alerts without an administrator account. Repeated saves update membership; deactivate recipients after testing. Apply Flyway through V125 before deploying the corresponding UI. The clean-database release scenario verifies phone normalization, duplicate saves, disabled recipients and critical-alert delivery through the fake provider.

SMS provider activation is an explicit admin action in Communications → Provider catalog, also available at `POST /api/v2/admin/communication/routing/providers/{providerCode}/activation` with `{ "enabled": true }`. Activation is audited and uses the existing provider registry; SMSMobilo requires its configured API key. Routing-rule activation alone does not enable a provider. No migration or automatic provider activation occurs on deployment. Verify the saved provider state separately from live message delivery.


MTN connection configuration and release acceptance: see `Docs/Operations/mtn-promotion-20260911.md`. General Settings now links to the governed provider credential workspace; populated settings are not authentication evidence. No provider activation or automatic credential migration occurs.


## External developer delivery

The Cito consumer SDK 2.0 and versioned external API handover are documented in `Docs/Api/consumer/START-HERE.md`. Generate with `python3 scripts/consumer_api/build_package.py`; verify with `--check`. `Consumer API Handover` CI checks all three language signers, client safety, the source-derived BaaS request schemas, the consumer-only Postman/OpenAPI projection and package checksums. This is a library/documentation release: no database migration, provider activation, credential change or financial transaction is performed. Preserve exact-revision staging/production acceptance before claiming live endpoint readiness.
