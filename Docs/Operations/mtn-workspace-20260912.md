# MTN workspace correction — 12 September 2026

## Problem and scope

The Settings button opened the standalone treasury console, lost the selected environment and
buried MTN credentials among unrelated forms. MTN sandbox tests initially used UGX. Verification
returned a generic failure and the test UI could describe a failed result as submitted successfully.

The correction adds `/bo/admin/mtn-momo` inside the normal admin shell with Connection, Merchant
access, Payment tests and Balances. Legacy MTN links preserve their query/hash. MTN Sandbox uses
UG/EUR and Uganda Production uses UG/UGX. The backend rejects unsupported test environments and
MTN scope mismatches before creating or executing a test.

The existing verify endpoint now returns safe per-product authentication checks and a persisted
overall result/timestamp. A completed HTTP 200 probe can report CONNECTIVITY_FAILED. Consumers must
inspect that field and `verificationChecks`. Verification does not activate the connection; both
products and the current revision must pass before a different operator can approve. No database
migration, provider activation, secret change, funding adjustment or money movement accompanies
this release.

## Acceptance evidence

- Frontend regressions cover environment switching, secret preservation, stale edit revisions,
  separate product errors, failed collections, ambiguous retries and maker-checker UI behavior.
- Backend regressions cover product-specific OAuth headers/endpoints, partial failures, sanitized
  diagnostics, revision fencing and rejection of invalid portal test scopes.
- The browser matrix exercises the real admin shell and legacy redirect with isolated API fixtures,
  credential inputs and collection/payout forms from 320px through desktop sizes on supported
  engines. Fixture results are not authenticated staging or actual MTN acceptance evidence.
- Existing MTN execution/status/recovery and database invariants remain required release gates.
- Runbook and owning OpenAPI documents are updated; generated portal references must remain current.

Record exact candidate/merged SHAs, CI outcomes, deployment IDs and runtime probes in the release PR.
The portal owner remains responsible for actual MTN credential entry and independently approved
acceptance cases. Do not include keys, bearer tokens or full real wallet numbers in release evidence.

## Financial and security review

Canonical v2 collections/payouts, scoped shared-provider entitlements, product tokens, decimal
amounts, reservations, callback/status recovery and audited finality remain the money path. No
ledger or reservation implementation changes are included. Payout tests still require a different
operator; production requires explicit confirmation and MFA. A failed or ambiguous provider response
does not become success. Client request keys survive ambiguous retries and tab changes in the
workspace; persisted test history remains authoritative across page reloads.

Human admin sessions, permissions, CSRF controls, encrypted storage, masked reads and backend
maker-checker checks remain required. Secret fields start blank on edit and never submit masks.
Saving uses the revision captured when editing began. Probe diagnostics exclude raw response
bodies and transport exception details. There is no change to provider URL allowlists or egress.

## Release and containment

Advance `feature/* -> main -> sandbox -> production` only after applicable CI passes. Verify both
backend and frontend resolve to the accepted release SHA. Check readiness, the public home and
merchant/admin entry points, MTN route delivery and unauthenticated denial of credential/test APIs.
Actual authenticated portal and provider acceptance must be labelled separately from these checks.
Use the owner's explicit production deployment instruction; do not invent staging sign-off or MTN
certification. No privileged test accounts or direct balance updates are permitted.

If the UI fails, retain the existing `/bo/admin/provider-treasury` operations route and roll both
services back to the previous accepted release through the normal reviewed branch process. No
schema rollback is needed. In-flight payments continue through the unchanged canonical recovery
path; do not resubmit them to compensate for a display problem.

## Brand and synchronized surfaces

Brand version: 1.2. Changed touchpoints: admin Settings, navigation, MTN workspace, treasury
presentation, verification messages and operator/API documentation. Existing Cito tokens and UI
primitives are reused. Merchant-owned credential editing and merchant payments retain their current
APIs and behavior; public pages require no new capability claim. The shared integration guide makes
the operator journey available without exposing platform secrets in merchant or public surfaces.

No ISO messaging profile, schema migration, dependency version, retention, backup/RTO/RPO, supplier
contract, climate or sustainability claim changes are introduced. Live MTN acceptance remains an
external dependency owned by the platform/provider operations team.
