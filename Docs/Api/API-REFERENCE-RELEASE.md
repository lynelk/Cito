# API reference and endpoint access billing release

Brand baseline: Cito 1.2. Affected touchpoints: public hero developer section, merchant Developers, admin API workbench, downloadable OpenAPI and integration guide. Uses canonical Cito/monochrome tokens and existing sign-in destinations. Provider and deployment readiness claims remain evidence-based.

## What changes

- Searchable merchant integration guide and component-pruned OpenAPI projection; downloads, request schemas, parameters, responses, examples and manual request execution.
- Admin-only full-system runtime OpenAPI, accessible only with an administrator portal session; no admin schema is embedded in the public JavaScript bundle.
- Searchable public capability topics and a merchant reference link.
- Explicit endpoint access rates, including zero, keyed by HTTP method and route template. Rate publication requires admin authorization, CSRF and a matching current version; every update is audited and historical price-book versions remain.
- Shared authenticated admission metering at the RSA v2, BaaS, legacy signature and documented merchant-session boundaries. Usage persists before business execution. Payment/BaaS sandbox usage cannot enter production invoice staging.
- No direct wallet debit; production access charges feed the existing invoice lifecycle. Service charges remain independent. Each new authenticated retry is a new access admission, including when business idempotency returns an existing result.

## Compatibility and rollout

Flyway V123 is additive. New rates default to UGX 0.0000. This is a zero-rate rollout; no nonzero customer rates are activated by this code change.

`GET /api/v2/channels` and `GET /api/v2/webhooks/events` now enforce the merchant signature already declared in the payment OpenAPI contract and require `merchantNumber` in the query. Anonymous callers must migrate to signed requests. They are discoverability APIs, not provider callbacks. No payment execution route is renamed.

`/v3/api-docs`, its YAML variant and subresources require an administrator portal session in addition to admin authorization. Swagger UI remains disabled; the admin workbench renders the protected runtime document. Existing `/api/ui` proxy normalization carries browser requests to the backend. Do not publish the full system document through public static hosting.

The workbench executes against the connected deployment. It must not be advertised as an isolated sandbox. Existing authentication, tenant checks, CSRF, signatures and business approvals apply to each executed endpoint. Users explicitly confirm requests; the code does not auto-run live provider calls.

The merchant projection is based on current source contracts. A runtime system operation with insufficient comments may have a generated schema but limited explanatory prose; improve its owning contract/controller documentation as part of domain maintenance. The release's coverage gate protects the generated merchant projection from staleness and admin leakage.

## Required release verification

Run Maven verify, backend billing/security tests, API contract validation, frontend typecheck/tests/build, and brand checks. In a MySQL-backed isolated environment apply V123, run two simultaneous rate changes and prove stale-version rejection, submit concurrent admissions, check four-decimal amounts and version attribution, and confirm sandbox usage is absent from invoice staging. Verify signed invalid requests cannot create admission evidence. Verify merchant and anonymous sessions cannot obtain the full system schema or publish rates. Inspect the UI at 320, 390, 768 and 1440 pixels, keyboard-only, 200% text and both approved themes.

Confirm runtime Flyway version, zero initial rates, deployed commit, invoice staging and full-schema access controls before promoting `main -> sandbox -> production`. Rollback application code only if new clients are compatible; preserve billing evidence and V123 tables. Never delete usage or price history to roll back.

## Local verification evidence (10 September 2026)

- Java 21 compilation, Maven Spotless and full Maven verification passed. The environment requires loading Mockito as a Java agent; no production configuration was changed for this.
- Merchant OpenAPI 3.1 validation: 195 operations across 164 paths. Three generation/audience tests passed.
- Frontend typecheck, focused API/landing-page tests (11) and production build passed. Markdown tables and code blocks render through a safe Markdown renderer.
- Brand mirror checks passed. Browser visual verification remains pending because the browser blocked the local preview URL.
- No live provider call, monetary transaction, database migration, staging deployment or production promotion was performed. MySQL concurrency and invoice-flow runtime checks remain required before release.

## Staging provisioning and release blocker (10 September 2026)

The user authorized staging and deployment. Isolated Railway project **Cito Staging** (`c69c90a9-ab6f-48ff-8e04-1e10a10f92db`) was created with its own MySQL 8.4, backend and frontend service instances. Railway labels this separate project's default environment `production`; it is **not** the canonical Cito production project. Canonical IDs remain unchanged in `ops/environments/cito-environments.json`. Staging has fresh secrets, no copied data or provider credentials, SANDBOX gateway mode, secure cookies and private database networking. The database is disposable, without a persistent volume; do not store durable acceptance evidence or real customer data there.

Provisioning is incomplete: backend startup/migrations remain to be verified; the connector created the frontend repository source without its requested branch binding and cannot trigger its first deployment. Frontend domain creation also failed. Configure the existing frontend service's branch in Railway rather than creating replacement services. Do not mark staging ACTIVE until both services run the accepted revision, domains/proxy and authenticated access are verified, and billing concurrency/invoice-flow evidence is recorded. After feature acceptance, bind both services to main for automatic staging updates.

GitHub's `CI` workflow is `disabled_manually`. Re-enable the existing workflow to restore its clean-MySQL migration gate and successful-main-CI trigger for Promote Sandbox. The GitHub connector has no workflow enable/dispatch action, and the Railway assistant reports its usage limit reached. No release control was bypassed and no canonical production service was changed.

Staging build correction: Railpack's default Maven install command failed because its source archive lacks Git metadata required by Spotless ratcheting. The staging packaging command uses `-Dspotless.skip=true`; formatting and tests remain mandatory in Git checkout/CI. The environment contract records the staging packaging commands; direct Railway service settings apply them. Railway rejected new Config-as-Code bindings as deprecated, so no new railway.json binding is used. Existing production configuration is unchanged. The initial root-level archive build also looked for the wrong target directory; each service now has its own application root.
