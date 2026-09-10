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
- Merchant OpenAPI 3.1 validation: 188 operations across 157 paths. Three generation/audience tests passed.
- Frontend typecheck, focused API/landing-page tests (11) and production build passed. Markdown tables and code blocks render through a safe Markdown renderer.
- Brand mirror checks passed. Browser visual verification remains pending because the browser blocked the local preview URL.
- No live provider call, monetary transaction, database migration, staging deployment or production promotion was performed. MySQL concurrency and invoice-flow runtime checks remain required before release.
