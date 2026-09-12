# Migrating to consumer SDK 2.0.0

This is a deliberate breaking client-library release. Existing backend API routes and their authentication are unchanged.

| Previous helper behaviour | SDK 2.0 behaviour | Required integration change |
|---|---|---|
| No constructor environment | Explicit SANDBOX/PRODUCTION required | Supply the approved environment |
| Collect/payout used production-default compatibility routes | Native `/api/v2/native/payments/*` with explicit environment header | Review response schemas and native request shape |
| New implicit idempotency key per call | Caller-owned stable key required | Persist key with the original payload before first attempt |
| Query escaping/order differed between languages/server | Java-compatible names/repeated values, UTF-16 ordering, UTF-8 form encoding | Use the new helper rather than old locale/RFC3986 code |
| Documentation said exact raw body hash universally | Existing verifier trims U+0000..U+0020 at body boundaries | Send compact JSON; use supplied signer and vectors |
| No HTTP deadline / automatic redirect defaults | Bounded deadlines; redirects and automatic retries disabled | Handle unknown outcomes and original-reference lookups |
| JSON assumed for every success response | CSV text and empty responses supported | Handle response type for selected operation |
| Payload could replace configured merchant | Conflicting merchant or environment rejected locally | Correct the caller's tenant scope; do not override it |
| Postman mixed admin and consumer calls | Generated server-to-server v2 collection only | Use separate admin tools for operations work |
| Webhook catalogue example unsigned/no merchant query | Signed GET with merchantNumber | Register merchant public key and use fresh signed headers |

Node: `client.collect(payload, { idempotencyKey: storedKey })`.
Python: `client.collect(payload, idempotency_key=stored_key)`.
PHP: `$client->collect($payload, $storedKey)`.

Do not change an in-flight payment's original provider/reference/environment to migrate a library. Complete or reconcile existing work using its recorded identity. Upgrade one integration in an isolated environment, test identical/conflicting retries and callback handling, then roll out through the normal acceptance process.

The raw v2 signing helper omits the idempotency header when no key was supplied. It never creates one implicitly. The wrappers require keys for native payment writes and payment-link creation. This library-side requirement does not alter which backend operations implement idempotency.
