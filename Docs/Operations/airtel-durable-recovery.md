# Airtel durable recovery

Brand version: 1.2
Brand impact: Backend transaction lifecycle and merchant-facing status messages. No visual changes. Pending outcomes do not claim completion or invite another payment.

## Implemented lifecycle

Airtel initiation through the legacy production gateway and the adapter-native sandbox path first commits an immutable correlation. It records the original provider UUID, merchant reference, merchant, operation, amount, currency, environment, credential source and provider-account identity. It does not persist tokens, provider secrets or the customer's phone number in the recovery table. A duplicate reference with a different request fingerprint is rejected; a matching replay returns the original reference without sending another provider request.

The worker checks due correlations every 30 seconds. Per-item database leases and fencing protect two or more replicas. A process that disappears after preparation leaves a recoverable PREPARED row. Expired leases can be reclaimed. Backoff is capped at 30 minutes; repeatedly unresolved items enter REVIEW but remain eligible for authenticated checks. Neither age, HTTP 404 nor a callback-provided status releases a payout hold.

The status client uses strict HTTPS, fixed Airtel environment origins, same-origin endpoint validation, no redirects, bounded responses, product-specific status paths and one token refresh after HTTP 401. It requires the original transaction reference. Any returned amount/currency must match the immutable request. Contradictory or unknown results remain unresolved. A definitive provider submission rejection can be retained separately from retry diagnostics, so a later database failure cannot erase that evidence.

For legacy transactions, verified final status, canonical ledger finalisation, legacy statements and durable callback scheduling join one database transaction with row locks. An error rolls back the complete finalisation. A late initiation response cannot overwrite an already-terminal Airtel transaction. Shared sandbox reservations are bound to the original UUID before submission and resolved through the existing treasury service. Production adapter-direct calls without canonical orchestration are rejected.

## Credential provenance and existing pending transactions

Recovery reopens the exact original merchant-owned or platform-shared credential scope. Legacy global/merchant configuration is also pinned. Account identity changes or legacy accounting-mode changes pause recovery rather than silently use another account. Secret rotation on the same provider account is permitted. Disabled/unapproved modern credential records are not substituted or reactivated.

Only requests with a durable correlation are automatically recoverable. Historical pending rows without recorded environment/source provenance are not guessed or blindly resubmitted. Reconcile those separately using authenticated provider evidence and the existing controlled operational process.

## Callback registration

Register the stable application origin with the provider, not a development address. For the current production domain, the Airtel callback route is:

`https://cito.coresynergi.es/api/v2/provider-callbacks/airtel`

POST and PUT are supported. The provider's `data.transaction.id` (or `transaction.id`) identifies the existing UUID. The UUID-specific route `/api/v2/provider-callbacks/airtel/{providerReference}` is also supported. Payload amounts/statuses are not authoritative: a callback only advances a bounded status-check schedule. Unknown/malformed identifiers do not reveal whether a merchant transaction exists.

The MTN route remains `/api/v2/provider-callbacks/mtn/{providerReference}`. Provider-side registration, live TLS reachability and successful OAuth must be verified separately. A repository route is not evidence that an operator registered or delivered a callback.

## Status and controls

The existing authenticated merchant payment-status API can return native Airtel sandbox recovery status when no legacy transaction exists. Merchant scoping remains mandatory. An environment-ambiguous reference is rejected instead of returning another payment's state.

Worker controls: `cpay.airtel.recovery.enabled` (default true), `cpay.airtel.recovery.batch-size` (default 20, maximum 100), and `cpay.airtel.recovery.interval-ms` (default 30000). The correlation table is introduced by V123. Never amend an applied migration or mark an unresolved payment failed based only on age.

## Release evidence

Run targeted recovery tests, full Java 21 verification, the real-MySQL migration/lease tests and the existing ledger tests. Record actual run IDs and source SHAs on the PR. Tests are not live-provider certification. Promotion must preserve the current frontend/backend SHA-parity contract and unrelated staged Railway settings. Obtain authenticated no-money provider checks and isolated staging evidence before production activation. The repository source alone makes no deployment or registration claim.
