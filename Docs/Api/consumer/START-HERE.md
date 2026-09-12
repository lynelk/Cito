# Cito External Developer Kit — 2.0.0

## Connect Once. Operate Everything.

This package is the external server-to-server handover for Cito Payments, Communications, Identity and Billing/BaaS. It contains a filtered OpenAPI contract, matching Postman collection, Node/Python/PHP payment-signing SDKs, conformance tests, a searchable offline reference and migration guidance. Service discovery is not permission to use an unapproved provider.

## Open these files first

`index.html` is the portable searchable reference and works without a documentation server. `external-openapi.json` is the server-to-server v2 projection. `ENDPOINTS.md` lists each operation's method, path, authentication and environment policy. `Cito-External-API.postman_collection.json` contains the same operations. `cito-environment.postman_environment.json` has only non-secret placeholders and requests disabled. `sdk/Readme.md` explains the three server SDKs. `MIGRATION-2.0.md` records breaking client-library changes, not backend endpoint changes. `MANIFEST.json` pins the package's source revision and individual SHA-256 checksums.

There are no administrator APIs, portal cookies or legacy body-signature operations in the external projection. The full merchant workspace still contains additional session-authenticated capabilities; those are not silently advertised as service-account APIs. The package's source revision is not a claim that this revision is currently deployed. Confirm the environment's `/releasez` and approved onboarding record before integration acceptance.

## Onboarding inputs

Your Cito onboarding record supplies the approved HTTPS base origin, merchant number or billing tenant/project mapping, environment, permitted services/providers/currencies, scopes, published charges, operating limits, callback arrangements and support/escalation contact. The kit contains **no credentials or provisioned test accounts**. These are normal account prerequisites, not values to guess from examples.

Payment/identity/communications signed operations use the merchant's registered RSA public key and the server-side private key. Billing/BaaS operations use a separately provisioned scoped service-account key. Never reuse an MTN/Airtel portal password, an operator subscription key, a merchant portal cookie or an administrator credential as a Cito integration credential.

| Surface | Authentication | Environment selection |
|---|---|---|
| CPay v2 signed consumer API | RSA-SHA256, timestamp, fresh nonce, merchant number | See the operation policy; native payment commands explicitly select `X-CPay-Environment` |
| Billing/BaaS v2.1 | `X-Cito-Api-Key` with the operation's scope | Required `X-Cito-Environment` |
| Merchant portal | Merchant session and CSRF on protected writes | Not part of this server-to-server kit |
| Administrator control plane | Administrator authorization | Not part of this server-to-server kit |

A configured adapter is not operator certification. Production usage requires the existing service, credential, entitlement and independent go-live controls; no collection import, SDK constructor or successful build activates them.

## First successful request

1. Confirm the approved origin and environment, registered key and access price. Keep keys server-side; never paste them into support tickets or shared environment files.
2. Choose a non-money operation such as the signed channel catalogue or webhook event catalogue. Both need `merchantNumber` and a valid merchant signature. Reads may be charged; non-money does not mean free.
3. Use the appropriate SDK's `channels()` or `webhookEvents()` method. Check HTTP status and the documented response, retaining only the non-secret request/correlation ID for support.
4. Exercise rejected invalid signatures, expired timestamps, repeated nonces and cross-tenant requests in the approved isolated environment. A healthy unauthenticated endpoint is not authenticated integration acceptance.
5. Only after these checks, test explicitly approved native payment scenarios with the assigned synthetic accounts. No live provider transaction is needed to verify this package's code.

## Postman desktop

Import the collection and environment; select the environment. Set `baseUrl`, `environment` and `merchantNumber` from your onboarding record. Keep TLS verification enabled and redirects disabled. Enable local Vault access for this trusted collection, then add `cito-rsa-private-key` as an unencrypted PKCS#8 RSA private key of at least 2048 bits, or `cito-baas-api-key` for BaaS. Do not put either value in exported collection/environment variables.

Once the environment and access fees are approved, set `allowRequests` to `true`. Send one read operation. The collection computes fresh timestamps/nonces and RSA signatures using built-in Web Crypto. It neither downloads a crypto library nor makes a separate signing request.

For each deliberate write, fill the required body/path/query variables, persist your business reference/idempotency key, and set `confirmOperation` to that exact request's operationId. The script consumes that confirmation for one attempt. This prevents an accidental run of every write, but it is not a replacement for server permissions or operator approval. BaaS charging/usage keys belong in the documented JSON body; CPay payment keys use the `X-CPay-Idempotency-Key` header.

The supplied collection targets current **Postman desktop with local Vault and Web Crypto**. Local Vault scripts are not the unattended Newman/CLI credential mechanism. Use the supplied server-side SDKs for CI rather than exporting private keys to emulate Vault. Script conformance tests execute the actual generated script with standard Web Crypto and a mocked Postman API; they do not claim an automated test of the desktop application itself.

## Payment lifecycle and retries

Preserve your business reference, the returned Cito reference, environment and operation key. A `202` response is acceptance/pending, not collection, payout or settlement. A timeout is an unknown result, not an instruction to create another payment. Query the original operation before retrying; the SDK never retries automatically.

A commercially identical retry keeps its original operation key and body but signs a new nonce and timestamp. Changed amount, currency, merchant, provider, reference or other business identity is not an identical retry. Backend idempotency remains the authority; an idempotency header on an operation that does not implement it does not create a guarantee. Polling and fresh authenticated retries may incur additional access admissions independently of payment idempotency.

Use decimal strings for money. Internal calculation precision is four decimal places; two decimals are display only. Check operation-specific schemas and responses rather than treating every HTTP 200 body or every provider status code as a final business success.

## BaaS sequence

Use the scoped BaaS key and explicit environment. Request schemas describe the actual controller records rather than generic empty objects. Start with the customer list/catalogue; create customer/account/contract/subscription records only for an approved design partner. Contract approval requires the appropriate independent service account; do not reuse the submitter to bypass maker-checker. Effective dates, price-book versions, quotas and account mapping still apply.

Price quotes and authorization are distinct from committing a charge. Usage must preserve its original event time, source reference, quantity, dimensions and JSON-body idempotency key. No price, invoice, wallet, contract or provider transaction is created by importing the kit. Response maps that are explicitly open schemas should be treated as extensible; never generate a stronger field guarantee than the owning contract declares.

## Callback and webhook verification

Read `WEBHOOKS.md` and `CALLBACK-RECEIVER.md`. The `callback-v1` task-queue HMAC contract and the current merchant-event/BaaS delivery contract are different. Do not guess a signature scheme from the `X-CPay-Signature` header alone. Preserve raw bytes, correlate the authenticated merchant/reference, durably deduplicate the event and acknowledge only after recording it. Do not infer final settlement from an untrusted callback or from delivery acceptance.

## Errors and support

401: check credential/signature, canonical query, clock and fresh nonce. 403: inspect service entitlement, environment and authorized role/scope. 400/422: inspect the operation's actual fields and enum values. 409: investigate idempotency/state/version conflict. 429: use bounded backoff and the original operation key where supported. Timeout/5xx: query the original reference before any repeat submission. Redirects are rejected; confirm the base origin instead of following them with signed headers.

Share UTC time, environment, package/source revision, method, route template, sanitized code and request/reference IDs with your onboarding contact. Never share a private key, API key, OTP, reusable signature, full account credentials or unnecessary identity/customer data. Support response-time commitments come from the signed service agreement, not this package.

## Release and acceptance boundary

The CI report proves the specific software tests it lists: cross-language signing, SDK safety, source-derived schema consistency, consumer-only projection, Postman script conformance and package integrity. It is not evidence of authenticated live consumption, provider certification, message delivery, settlement, regulatory approval or commercial adoption.

An account's go-live acceptance still requires the exact deployed release, isolated successful/negative integration tests, idempotency/replay cases, callback receipt and applicable provider scenarios. Do not suppress these controls to label the platform universally live. The package is designed for handover without administrator documentation or known signing/example defects; normal account provisioning and production acceptance remain explicit.

## Verify this delivery

Unzip the archive into a new directory and run `python verify_manifest.py`. Compare the zip SHA-256 with the separately supplied `.zip.sha256` value before installation. The manifest records each delivered file and the immutable source revision; retain it with your integration release. A checksum detects corruption or mismatched files; obtain the zip and checksum from the authenticated Cito delivery/repository channel, not an untrusted forwarded download.

The included conformance suite runs with ephemeral keys and injected/local test transports. It does not contain a customer private key or an enabled provider account. The package's successful CI record is software evidence; your tenant/environment's production activation remains a separately approved operating state. A fresh integration requires the exact scope listed in `ENDPOINTS.md`, not an administrator account.
