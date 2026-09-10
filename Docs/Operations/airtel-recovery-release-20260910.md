# Airtel recovery and MTN readiness: 10 September 2026

Brand version: 1.2

## Release decision

The implementation in PR #184 is a recovery and financial-safety change, not permission to activate either provider. Production promotion is blocked until the exact accepted source passes isolated staging and provider readiness requirements. Current test conclusions and exact source SHA are recorded in the PR and its downloadable CI artifacts; this document does not turn a running or failed check into a pass.

## Implemented recovery

Airtel OpenAPI recovery polls existing merchant transaction records and CPay Shared Payments treasury reservations. It checks the original submitted reference, not the network receipt. Both collection and payout operations are supported. Only an authenticated HTTP 200 status response with an explicit final outcome can finalize a transaction. Transport failures, throttling, malformed responses, uncertain outcomes, duplicate references and missing callbacks do not justify a new payment or release a hold.

V123 adds durable scan cursors and immutable, non-secret credential attribution. Attribution is recorded before sending a request and includes the credential source, application identity, endpoint, country and currency. Rotation of the secret for the same application is allowed. Switching applications or credential owners cannot resolve an earlier payment. Scan position survives process restarts; bounded batches rotate so one unresolved item does not starve the rest.

Merchant finalization locks and rechecks the original record, invokes the canonical transaction update, confirms the persisted result, applies the canonical ledger outcome and records the resolver in one database transaction. Shared-provider finalization locks and rechecks its reservation and uses the existing treasury service. Duplicate terminal processing has no second financial effect. Conflicting shared references fail closed rather than guessing a merchant.

The admin route `/bo/airtel-money` appears in the Operations menu and scopes the existing treasury console to `airtel_open_api`. Its account, credential, entitlement, adjustment and test controls cannot silently operate on MTN or the older `airtel_money` channel.

### Coverage and historical transactions

Production v2 payments currently use the persisted compatibility/orchestration transaction path. This worker covers those Airtel records and shared-provider reservations. The non-shared adapter-native v2 sandbox path is not a substitute for production persistence or recovery certification.

Historical payments without recorded credential attribution are intentionally not backfilled from current settings. They remain unresolved pending authenticated, independently controlled reconciliation against their original provider account. A callback body alone is not trusted as settlement evidence. This change does not introduce or certify a new Airtel callback endpoint; polling supplies callback-loss recovery.

### Additional financial issue found by expanded tests

Real MySQL concurrency tests exposed stale REPEATABLE READ balances during reservation. The repair acquires the merchant/currency serialization lock before the reservation lookup and uses current locking reads at both posted-balance and active-reservation query levels. Existing concurrent-reservation tests remain, and a deterministic test covers an enclosing transaction that already created an older snapshot. No ledger history or production balance was edited.

## Actual production-runtime preflight evidence

All checks ran in a one-shot `cito-provider-readiness` Railway service, using references to the existing backend's secrets. Values stayed in the Railway runtime. Database reads used read-only transactions; no payment, transfer, refund, provider activation, approval, selected-source change or balance write was submitted.

| Check | Observed result |
| --- | --- |
| Modern Airtel OpenAPI / MTN merchant and platform credential stores | Zero matching records |
| Selected legacy credential source | Merchant-specific mode enabled |
| Legacy merchant provider configurations | Zero matching configurations |
| Saved, non-selected global Airtel configuration | Client ID present; `gw_airtelmoney_api_password` / client secret absent |
| Saved MTN production base URL | `momodeveloper.mtn.com`, a developer portal rather than the API host |
| Saved MTN sandbox base URL | `developers.mtn.com` with a non-root path, rather than the sandbox API host |
| Saved MTN target environment | Neither `mtnuganda` nor `sandbox` |
| Canonical production collection OAuth | HTTP 401; no token obtained |
| Canonical production disbursement OAuth | HTTP 401; no token obtained |
| Canonical sandbox collection OAuth | HTTP 401; no token obtained |
| Canonical sandbox disbursement OAuth | HTTP 401; no token obtained |
| Public `cito.coresynergi.es` TLS certificate reachability | Verified |
| Operator-side callback registration | Not verified |

Canonical token-only checks temporarily used `proxy.momoapi.mtn.com` for the saved production key set and `sandbox.momodeveloper.mtn.com` for the saved sandbox key set inside the diagnostic process. They did not modify stored endpoints or select these global credentials for live traffic. Four HTTP 401 responses establish that those saved credential/subscription combinations were not accepted by the tested APIs; they do not identify which individual field or operator-side entitlement is wrong.

Evidence deployment IDs:

- `f7ffdd70-0435-4951-8127-0dc8ad0cb710`: modern/legacy configuration inspection, 12:30 UTC.
- `a77ce682-7e12-460a-88f6-44cfbd6b2b65`: saved production inspection and public TLS check, 12:34 UTC.
- `114df507-4cc4-401c-88c5-43cf00b5b95d`: endpoint identity inspection, 12:42 UTC.
- `74ebbd64-ebf9-4e49-921c-6bd20ec25e7c`: canonical token-only checks, 12:50 UTC.

An absent explicit callback setting is not proof that the application has no derived callback route. TLS reachability is not proof of an operator's registration or callback delivery.

## Provider corrections required through secure administration

Install the approved Airtel client secret with its matching application and environment. Reconcile the approved credential-source model: the selected merchant source is empty, while saved globals are not selected. Do not silently select global credentials merely because fields are populated.

For MTN, verify each product's API user, API key and subscription key as one matching operator-approved set; correct the production/sandbox API bases and target environment through the normal configuration controls. Re-run token-only checks before activation. Confirm the registered HTTPS callback host with the operator and test original-reference correlation, duplicate signals and callback-loss recovery. Never paste these credentials into chat, source code, issues, test artifacts or logs.

## Isolated staging provisioned, not ready

A separate private Railway project was created because the available same-project environment creation action timed out. It contains no copied production services, credentials, balances, customer data or volumes.

- Project: `Cito Staging`, `20b66160-463b-4e8e-8909-eaf5291cde40`.
- Environment: `075f2585-7285-46dd-b09b-df3fd40de63e`. Railway's default label is `production` inside this separate staging project; it is not the live Cito environment.
- MySQL service: `578dc09b-2917-4c87-bff5-268cca09a3be`, separate test database/user and fresh stage-only credentials.
- Backend service: `987b9f43-cd4b-4a79-b96c-22f9113957ab`, source PR #184, backend root, health path `/status/health`.

MySQL started successfully. It has no attached persistent volume and must be treated as disposable. Backend secret/configuration application was blocked by the tool safety check, including the corrected TLS-required attempt. The backend is not verified healthy. No staging frontend, authenticated UAT or exact-release staging acceptance has been completed. Do not describe the project as ready or substitute the isolated CI databases for deployed staging acceptance.

Complete staging secret injection through the secure Railway administration surface. Require an encrypted database connection, new staging-only application encryption/authentication/signing secrets, sandbox-only provider settings, synthetic data and no production resource references. Set up the frontend and exercise both surfaces on the exact candidate SHA.

## Promotion and rollback requirements

After current-head code review and CI pass, merge through the normal GitHub PR API. Do not bypass repository checks or manufacture approvals. Before production promotion, pin one accepted source SHA, validate that exact code in staging, then follow the repository's sandbox/production promotion rules and verify both live services' deployed identities and health. Record the prior successful deployment IDs and evaluate migration compatibility before rollback. V123 is additive; do not delete its attribution records to roll back application code.

No new production application deployment, provider approval, credential source switch, live transaction or balance repair is authorized by a successful CI result alone. The current user authorized a controlled tested release; the staging and provider failures remain real release blockers.
