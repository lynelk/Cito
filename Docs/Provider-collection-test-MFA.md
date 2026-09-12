# Temporary MTN collection-test MFA exception

Brand baseline: Cito 1.2. Touchpoints: the admin MTN MoMo production collection-test form and its same-request retry. Existing Cito field, notice and semantic styling is reused; no logo, palette or layout changes are introduced.

## Scope and defaults

MFA remains required by default. An explicitly configured, time-limited exception can suspend only the step-up MFA code for the named authenticated admin's `mtn_momo` / `PRODUCTION` / `UG` / `UGX` / `COLLECT` live test. The actor comes from the authenticated principal, never the request body. No exception applies to normal merchant payments, login, other providers, other operators, payout creation or payout approval.

The exception does not activate credentials, approve a merchant, change limits, skip real-money confirmation, alter idempotency, remove audit evidence or execute a test automatically. Existing authentication, role/permission checks, CSRF protection, financial processing and maker-checker controls remain unchanged. A new test using the exception records `MFA_TEMPORARILY_SUSPENDED` in its event history with the actor and expiry.

## Configuration and restoration

Both backend Spring properties must be set; an empty actor, absent/malformed expiry or elapsed expiry requires MFA:

```properties
cpay.provider-live-test.mtn-collection-mfa-suspended-actor=approved-operator@example.com
cpay.provider-live-test.mtn-collection-mfa-suspended-until=2026-09-13T17:00:00Z
```

The timestamp above is an example, not a default. Select a short, explicitly approved UTC expiry when activating. Configure deployment values outside Git; no operator account is hard-coded. With Railway, these properties can be supplied through `SPRING_APPLICATION_JSON`, preserving any existing JSON properties. Clear either property to restore MFA early and redeploy the backend. Expiry is checked on every request and restores enforcement automatically without a deployment.

Deploy the backend and frontend from the same accepted release. Verify the read-only policy while authenticated as the intended admin, confirm the collection input is replaced with an expiry notice, then switch to payout and confirm the MFA input remains required. Do not submit a real-money test as part of deployment verification.

## Admin API contract

`GET /api/v2/admin/provider-treasury/live-tests/mfa-policy` is admin-only and requires `LIVE_COLLECTION_TEST`. It accepts no body and returns `Cache-Control: no-store` with:

```json
{"mtnCollectionMfaRequired":true,"suspendedUntil":""}
```

Only the configured authenticated actor receives `mtnCollectionMfaRequired: false` and the active ISO-8601 UTC `suspendedUntil`. The response does not expose the configured actor. This is presentation information, not an authorization token. The live-test POST independently rechecks the same policy, scope and expiry. Its existing request contract is unchanged; only the eligible collection test can omit `mfaCode`. Payout approval continues to require it regardless of any spoofed collection fields in the approval body.

The form and retry component fail closed on missing, expired, malformed or unavailable policy. Production confirmation remains required. Merchant portal and public website capabilities are unchanged: the exception is an internal operational control, not a new public integration capability or claim of provider certification. No database migration is needed.

## Validation

Backend regression coverage checks default enforcement, actor isolation, expiry and malformed configuration, production confirmation, scope restrictions, approval-body spoofing and maker-checker. Frontend coverage checks field removal, stale-code clearing, payout enforcement and fail-closed presentation. Existing MTN journey tests continue to cover approvals, sandbox behavior and idempotent retries.
