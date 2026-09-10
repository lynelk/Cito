# Airtel and MTN payment readiness

Brand baseline: Cito Brand Standard v1.2 (8 September 2026). Affected touchpoints are merchant Payment channels, CPay provider credentials/reviews, transaction states and operational documentation. Canonical tokens and existing UI components are retained. Keyboard, reduced-motion and responsive browser checks are included in the browser CI matrix; a blocked local preview is not visual acceptance evidence.

This change closes the code and workflow findings from the 10 September 2026 review. It covers `mtn_momo` and `airtel_open_api`, with either merchant-owned credentials or CPay shared credentials. Provider activation and production acceptance remain operational release gates; authentication alone is not payment certification. No live credentials are changed by the migration.

## Execution and accounting

All v2 and compatibility collections/payouts for these channels enter `MobileMoneyExecutionService`. The service locks the merchant, claims the business reference, applies risk/production/payout controls, validates the selected credential scope, records a canonical transaction and encrypted credential snapshot, and reserves payout principal plus fees before committing. It then calls the provider outside the database transaction. MTN's provider UUID is persisted separately from the business reference, canonical transaction UUID and eventual financial reference.

A business reference is unique per merchant across operations, channels and environments. Reuse with different commercial attributes is rejected. Identical replays return the stored outcome without another provider submission. Use distinct business references for sandbox and production. Historical provider-run evidence also blocks blind resubmission even when the old path did not create a canonical transaction.

HTTP acceptance, timeout, connection failure, 408, 409, 429 and server errors do not prove settlement. Ambiguous requests remain pending/undetermined with their reservations held. Callbacks wake the status worker; authenticated status evidence must match the stored provider reference and commercial identity. MTN verifies external ID, amount, currency and party. Airtel requires the echoed transaction ID and validates amount/currency when returned. `TA` remains undetermined.

The finalizer commits terminal status, balanced ledger entries, merchant statement projection, shared float/exposure movement, billing outbox and webhook enqueue in one transaction. Duplicate and competing terminal events cannot post twice. The existing notification orchestration receives production payment events through the webhook service. Sandbox rows are recorded with `execution_environment=SANDBOX`; production reporting, financial consumers and SMS orchestration exclude them.

Merchant batches commit their complete slice reservations before provider execution. Managed payments reuse those holds, persist beneficiary links, and leave posting/capture to verified settlement. Stopping a batch releases only unsubmitted holds; in-flight managed payments remain reserved. Refund payouts use the same durable executor, remain `PROCESSING` until confirmed, and synchronize their outcome, attempt evidence and notification event from the canonical payment. Rejected or cancelled payout approvals release unsubmitted batch holds and conclude waiting refund/live-test records. These are compensating payouts, not a claim of provider-native reversal support.

## Credentials and approval

1. Configure the correct environment and product credentials in the merchant Payment channels screen or CPay provider console. MTN sandbox uses EUR and its sandbox origin; Uganda production uses UGX and `mtnuganda`. Airtel Uganda uses UGX and its environment-specific official origin.
2. Saving creates a new revision and invalidates prior verification and approval. Editing preserves stored secrets when secret fields are blank or masked. Public configuration fields remain readable. API callers can explicitly remove optional fields with `clearFields`; a stale `revision` is rejected.
3. Verify the connection. This performs OAuth authentication only. It never sends money. Failure invalidates previous authentication evidence for the tested revision; a local structure check cannot claim connectivity.
4. Merchant owners/admins submit the verified revision. An independent CPay reviewer approves or rejects it with a reason in Merchant credential reviews. Platform credentials use their existing independent approval workflow after verification. The authenticated actor is used for audit, not a submitted actor alias.
5. Shared usage additionally needs an active entitlement for channel, environment, country, currency and operation, available daily quota, and sufficient provider float for payouts. Merchant ledger funds and payout approval controls apply as well.

Unknown merchant roles fail closed. Secret masks disclose no prefixes/suffixes. HTTP traces exclude authorization, credential bodies, payloads and exception messages. Provider base origins and relative endpoint paths are validated before credentials are sent; redirects are disabled. The old token/raw-payment test routes return HTTP 410 and direct operators to the governed console. Unavailable balances are reported as unavailable, not zero.

## Migration and cutover

Apply `V125__mobile_money_execution_integrity.sql` after the current migration head, V124. It adds durable execution evidence, credential revisions/test evidence, environment-scoped transaction views and merchant-scoped payout approval references. Historical transaction rows default to production because the old canonical path was the production path. Historical local-only credential tests are not upgraded into connectivity evidence. Existing Airtel/MTN configurations require current authentication evidence before new execution.

Before release, inventory unresolved historical rows in `merchant_transactions_log`, `provider_endpoint_runs`, `mtn_momo_correlations` and `provider_treasury_reservations`, grouped by merchant, environment, operation and provider reference. Historical native runs without a canonical transaction or immutable credential scope cannot safely be recreated from a merchant callback or inferred amount. Preserve them as reconciliation exceptions; obtain authenticated provider/statement evidence and use the existing approved reconciliation/adjustment workflow. Do not create a new payment to discover the old outcome. Credential rotation must retain a way to verify outstanding provider references with the original provider account. Restrict and clean up old diagnostic logs under the existing retention process after credential rotation; this change prevents new secret-bearing traces but does not rewrite historical audit evidence.

Release through feature → main → sandbox → production. Keep provider access disabled until environment configuration, independent approvals, migration verification and the acceptance matrix below are complete. Reverting application code after V125 does not safely revert in-flight payment ownership; drain/reconcile outstanding executions before a rollback. Do not drop execution evidence or monetary entries as a rollback strategy.

## Acceptance evidence

Automated gates cover provider HTTP responses/token refresh, approved endpoint policy, credential edits and approval gates, concurrent idempotency claims, durable submission evidence, commercial-identity checks, decimal ledger conservation and pending reservations. The clean MySQL migration gate additionally exercises real ledger, statement and treasury writes, concurrent finalization, rollback after a forced outbox failure, payout success/failure, batch hold reuse/cancellation, pending refunds and sandbox exclusion. Frontend tests cover masked editing and reviewer decisions. Browser evidence is collected by the responsive workflow.

For **each provider × merchant-owned/shared × collection/payout × intended environment**, retain evidence for: accepted request, confirmed success, confirmed failure, timeout after acceptance, missing/duplicate/reordered callback, restart before finalization, insufficient merchant/shared float, conflicting/replayed reference, stale credential revision and independent approval. Match provider statement, canonical transaction, merchant balance, platform float/exposure, fees and callback/webhook evidence. Real provider acceptance and low-value production checks require the corrected credentials and the normal release approvals; they are not replaced by fake-provider tests.

## Review finding coverage

| Review finding | Implemented control |
| --- | --- |
| F01 credential-bearing diagnostics | Redacted traces, removed token/key logs and merchant raw traces |
| F02 native financial-control bypass | Canonical durable executor and atomic accounting/outboxes |
| F03 untrusted Airtel callbacks | Correlated wake-up followed by authenticated verification |
| F04 idempotency races/fail-open | Database claims, merchant lock, conflict checks, fail-closed storage |
| F05 ambiguous MTN failure | Pending/undetermined outcomes retain reservations |
| F06 credential/routing divergence | Compatibility and native managed routes share resolution and readiness |
| F07 MTN correlation/finalization split | Persisted provider UUID; verified canonical finalizer; legacy worker exclusions |
| F08 Airtel missed-callback recovery | Distributed scheduled authenticated polling and durable snapshots |
| F09 missing merchant activation workflow | Revision-aware independent approval, rejection and disable actions |
| F10 destructive masked editing | Safe merge, revision check and explicit optional-field clearing |
| F11 readiness/capability overclaims | Nonfinancial connection probe; honest capability/balance reporting |
| F12 unrestricted provider URLs | Exact reviewed HTTPS origins, safe relative paths, no redirects |

Additional corrections include decimal percentage/flat fee calculation, collection/payout fee conservation, Airtel reconciliation channel identity, sandbox financial isolation, batch hold reuse, asynchronous refund status and production notification isolation, zero-balance Airtel sandbox treasury scopes, and routing feedback based on verified production outcomes.


## Local validation record — 10 September 2026

- JDK 21 / Maven `clean verify`: successful; 1,087 tests passed, zero failures/errors. The external MySQL gate is the one skipped test in that command and was run separately below. Spotless verification and executable JAR packaging passed.
- Disposable MySQL 8.4.11: all 116 migrations applied through V125; the migration/payment/notification scenario passed with synthetic providers. It verifies durable submission before outbound I/O, concurrent finalization, forced-outbox rollback, fee conservation, merchant and shared float reservations, batch reuse and cancellation, pending and cancelled refunds, production routing health, sandbox exclusion, zero pending treasury control balances after settlement, and a balanced ledger trial balance.
- Frontend: 41 test files / 259 tests passed; TypeScript checking and production build passed. ESLint passed with four existing warnings and no errors. API/CI YAML, browser-test JavaScript syntax, brand checks and Git whitespace checks passed.
- Responsive/keyboard/zoom browser cases are committed for CI, but were not executed locally because the authenticated browser could not reach this runtime's preview. Provider authentication, real provider acceptance, release CI and production deployment are not claimed by this local evidence.
- No live operator credential values were changed, and no real-money request or deployment was performed. Publishing the feature branch was blocked by automatic approval review pending explicit permission to push it and open the PR.

## PR follow-up validation

The branch is published as PR #190. The current main SMTP/password-recovery changes were merged without changing provider credentials. CI follow-up adds the previously undocumented portal/refund paths, administrator authorization for global portal lists, merchant-scoped callback counts and exclusion of platform alerts from merchant summaries. The public refund entry point now starts its Spring transaction directly; the MySQL refund scenario invokes that method through the real transaction interceptor. Updated CI results are recorded on the PR.

### Legacy refund parity and concurrency

The signed `/api/doMobileMoneyRefund` compatibility API now enters the same governed refund lifecycle as merchant and v2 requests. It claims the remaining unrefunded amount, preserves the legacy callback across approval, and reports the lifecycle status without treating acceptance as settlement. Production payin refunds explicitly use production execution even when the server defaults to sandbox. Refund references cannot be rebound to a different payment or amount.

Cumulative claims use a current locking read after locking the original payin. The disposable MySQL scenario opens two repeatable-read snapshots before concurrent refund requests; exactly one reaches approval when their sum exceeds the remaining balance. It also verifies callback persistence, production isolation and replay through the Spring transaction proxy.

Before cutover, reconcile historical legacy payout reversals that have no `refunds` association to their original payin. These old records do not reliably contain the original reference, so they must be resolved from operational evidence before accepting new refunds on affected collections. Do not infer financial associations or rewrite settled ledger entries.
