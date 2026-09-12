# API reference and endpoint access billing release

> Current consumer guidance: This file retains historical release/recovery evidence, including superseded migration numbering and environment snapshots. It is not the current external installation checklist. Use `Docs/Api/consumer/START-HERE.md`, the versioned handover manifest and the actual environment release marker for a consumer delivery.

Brand baseline: Cito 1.2. Affected touchpoints: public hero developer section, merchant Developers, admin API workbench, downloadable OpenAPI and integration guide. Uses canonical Cito/monochrome tokens and existing sign-in destinations. Provider and deployment readiness claims remain evidence-based.

## What changes

- Searchable merchant integration guide and component-pruned OpenAPI projection; downloads, request schemas, parameters, responses, examples and manual request execution.
- Admin-only full-system runtime OpenAPI, accessible only with an administrator portal session; no admin schema is embedded in the public JavaScript bundle.
- Searchable public capability topics and a merchant reference link.
- Explicit endpoint access rates, including zero, keyed by HTTP method and route template. Rate publication requires admin authorization, CSRF and a matching current version; every update is audited and historical price-book versions remain.
- Shared authenticated admission metering at the RSA v2, BaaS, legacy signature and documented merchant-session boundaries. Usage persists before business execution. Payment/BaaS sandbox usage cannot enter production invoice staging.
- No direct wallet debit; production access charges feed the existing invoice lifecycle. Service charges remain independent. Each new authenticated retry is a new access admission, including when business idempotency returns an existing result.

## Compatibility and rollout

Flyway V128 is additive. New rates default to UGX 0.0000. This is a zero-rate rollout; no nonzero customer rates are activated by this code change.

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
- At initial local verification, no runtime migration or deployment had been performed. Subsequent staging evidence and remaining release gates are recorded below. No live provider call or monetary transaction was performed.

## Staging and release reconciliation (10 September 2026)

The user authorized deployment, staging, and resolution of all blockers. Canonical production has not been modified.

An isolated Railway project **Cito Staging** (`c69c90a9-ab6f-48ff-8e04-1e10a10f92db`) contains a fresh MySQL database, backend and frontend. Its default environment is labelled `production` by Railway; this is **not** the canonical Cito production project. IDs and configuration are recorded in `ops/environments/cito-environments.json`. No production data, provider credentials or balances were copied. The database has no persistent volume and is disposable; retain acceptance evidence outside it.

Resolved deployment defects:

- Corrected backend/frontend build roots.
- Corrected Git-dependent Spotless execution in source-archive packaging. Container packaging skips Spotless; full Git-backed Maven verify remains mandatory.
- Applied the MySQL trigger capability required by the existing migration preflight.
- Verified schema V123 and 115 validated migrations in backend runtime logs.
- Added a dedicated staging profile retaining secure cookies, JDBC nonces, protected OpenAPI, SANDBOX gateway state and disabled EFRIS delivery. ProductionSafetyConfig remains unchanged.
- Verified backend deployment `e96f299a-2a28-4270-acb0-e5b93189434e` and frontend deployment `cbe4ac9e-4382-488d-bc9a-75e3a5349a07` reached SUCCESS. These are different source revisions and are not release acceptance. The frontend defaults to main; the backend temporarily tracks the candidate branch.
- Merged current main's Communications work (including V124) into this candidate, preserving both documentation workflows/contracts, and regenerated the portal reference. Combined Maven verify passed: 1,084 tests, zero failures/errors, one skipped. Frontend typecheck/build and OpenAPI validation passed.

Outstanding gates:

1. GitHub's `CI` workflow is `disabled_manually`; re-enable the existing workflow to restore the clean-MySQL check and successful-main-CI trigger for Promote Sandbox. The connector has no enable/dispatch capability.
2. Validate V124 and the combined candidate in staging, align both services to the exact accepted revision, and finish authenticated portal/access/billing concurrency/invoice-flow tests. Direct HTTP checks from the current session were stopped by the network approval layer.
3. Complete staging frontend domain configuration. The generated domain request failed; the connector cannot rename the overlong generated service name or change its source branch. Railway's assistant reported its usage limit reached. Do not create duplicate replacement services.
4. Record release acceptance, then use the established main/sandbox/production gates and verify both canonical production service SHAs. Do not infer deployment success from static documents, a transient healthcheck, or Railway status alone.

New Config-as-Code bindings were rejected by Railway as deprecated. Staging currently uses direct service settings recorded in the environment contract. No new railway.json binding or unreviewed IaC apply was introduced; existing production configuration is unchanged.

## Reconciliation after database recovery — 11 September 2026

The current candidate incorporates main 0ab5bd5cbc919949f132c7e0d6890bcfc38733d3. It preserves the newer governed refund lifecycle, cumulative refund limits, independent approvals, idempotency and settlement tracking. API admission metering is added only after signature verification. Manually constructed unit-test fixtures receive the required billing collaborator; production guards and original refund assertions are unchanged.

The API-rate migration is now V128, because this PR's earlier V123 never entered main and the recovered active staging database has advanced to V127. Main's existing migration files remain byte-for-byte unchanged. The obsolete experimental V123 database must not be pointed at this release without a separately reviewed migration plan; no Flyway repair, history deletion, database reset or destructive operation is authorized by this reconciliation.

On 11 September the read-only health probe reported database UP, gateway SANDBOX and exact release 0ab5bd5c. Protected API routes rejected anonymous access. This is not authenticated API acceptance, an external provider certification or a production deployment. Exact candidate CI, MySQL migration/upgrade checks, both staging service SHAs, portal tests and rollback readiness remain release gates.

Brand version: 1.2. Affected surfaces remain the merchant Developers page, administrator API workbench, public website and revision-derived API/HTML exports.

## Restored security gates — 11 September 2026

CI had been manually disabled and has been re-enabled. Its dependency scan identified affected Spring Framework 7.0.8, Spring Security 7.1.0, Netty 4.2.15 and Tomcat 11.0.22. The candidate pins vendor-published fixes: Spring Framework 7.0.9, Security 7.1.1, Netty 4.2.16.Final and Tomcat 11.0.25. Runtime OpenAPI uses the API-only Springdoc starter; unused Swagger UI assets are no longer packaged. All tests and fresh vulnerability scans must pass after dependency resolution.

The MySQL regression retains populated V126 MTN references, validates their V127 collation correction and the V128 endpoint rate schema, including zero default, four-decimal precision and the nonnegative constraint. All existing treasury, ledger, mobile-money and notification scenarios remain. No applied migration or stored balance is edited.

GitHub default CodeQL setup already uploads repository analyses. The advanced security-extended scan retains complete SARIF as an exact-SHA artifact and fails explicitly on high/critical findings instead of making a second, rejected upload. Default setup stays enabled. No scan is skipped, vulnerability is suppressed, CVSS threshold is lowered, or security failure is marked successful.

The merchant portal, admin workbench and public website remain coupled to the same release; no visual change is required for these dependency/configuration fixes. Brand baseline remains 1.2.
