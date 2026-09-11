# Cito Gateway integration guide

Version 2.1 · 11 September 2026 · Cito brand baseline 1.2

This guide explains how to connect an external application, choose the correct authentication contract, understand results and API access charges, and use the developer reference. Cito is the platform; CPay identifiers remain part of the compatible payments protocol.

## 1. Find the right reference

| Location | Audience | Available information |
|---|---|---|
| Website developer section | Prospective customers | Capabilities, security overview and sign-in link |
| Merchant portal → Developers | Merchant developers | Searchable integration and merchant workspace contracts, guide, schemas, examples and current API access rates |
| Admin portal → API workbench → Commercial and merchant APIs | Administrators | The merchant reference plus endpoint rate controls |
| Admin portal → API workbench → Full system | Signed-in administrators only | Runtime-generated system API paths and schemas, including internal administration |

Search by method, path, feature, parameter or description. Select an operation to read its authentication, required fields, success/error responses and schema dictionary. Download OpenAPI JSON for Postman or an OpenAPI-compatible client generator. The guide is downloadable Markdown, which can be searched, versioned and printed.

The merchant contract deliberately distinguishes THIRD_PARTY operations from MERCHANT_WORKSPACE operations. A portal cookie is not an external service-account credential. Admin permissions are not obtained by selecting an admin API in a browser.

## 2. Before your first request

1. Obtain a Cito merchant account and the approved URL for the environment you will use. Do not copy an example hostname into a production application.
2. Confirm that your requested service and provider are enabled for that merchant and environment.
3. Register the public key required for signed payments, or create the appropriate developer project/service account and scoped credential for Billing BaaS.
4. Store private keys and API keys in your server's secret store. Never include them in frontend JavaScript, a mobile app bundle, Git, documentation or a support ticket.
5. Configure your webhook receiver using HTTPS and obtain the separate callback secret.
6. Review current access rates and service charges. Start with an approved test environment and synthetic data.

A documented provider is not necessarily enabled or externally certified. Production activation remains subject to Cito's existing readiness, entitlement and approval controls.

## 3. Choose authentication by operation

| Contract | What to send | What it authorizes |
|---|---|---|
| CPay v2 signed API | RSA-SHA256 signature, timestamp, nonce and version headers | The merchant resolved and verified by that request |
| Billing BaaS API | `X-Cito-Api-Key` and the documented environment/scope context | The credential's mapped billing tenant and service-account scopes |
| Merchant workspace | Signed-in merchant session; CSRF token on protected writes | That merchant's workspace operations |
| Full system/admin | Signed-in administrator session and existing authorization controls | Authorized administration; the full schema itself requires an admin portal session |
| Legacy `/api/v1` | Existing concatenated-field RSA signing format | Supported compatibility operations only |

Do not substitute a BaaS key for an RSA signature. Do not substitute a Cito-to-merchant callback HMAC signature for a merchant-to-Cito RSA signature. Use the operation's declared scheme.

## 4. Sign a CPay v2 request

Send these headers:

| Header | Value |
|---|---|
| `X-CPay-Merchant-Number` | Your merchant number; keep it consistent with the request's merchant identifier |
| `X-CPay-Signature-Version` | `v2` |
| `X-CPay-Timestamp` | Current ISO-8601 UTC timestamp, for example `2026-09-10T12:00:00Z` |
| `X-CPay-Nonce` | Fresh unique value for every HTTP attempt |
| `X-CPay-Signature` | Base64 RSA-SHA256 signature of the canonical string |
| `X-CPay-Idempotency-Key` | Stable business-operation key where the operation supports it |

Construct six lines, joined with a single newline and no trailing newline:

```text
METHOD
PATH
CANONICAL_QUERY
TIMESTAMP
NONCE
BODY_SHA256_HEX
```

Use the uppercase HTTP method and the exact API path, without a hostname. Sort query parameter names, then repeated values; encode names and values with the server's Java form-url-encoding rules (UTF-8, spaces as `+`). Join encoded pairs using `&`. For an empty query use an empty third line. Hash the exact UTF-8 body bytes; a bodyless GET hashes the empty string. Do not reformat the JSON after signing.

For example, a balance request uses `GET`, path `/api/v2/balances`, query `merchantNumber=YOUR_MERCHANT_NUMBER`, and an empty body. The repository's `Docs/Api-v2-signing.md` and `sdk/` helpers explain the compatibility protocol. Validate signatures in the approved test environment before automating retries.

Never paste a private key into the portal workbench. Generate the signature on your server and paste only the headers for that individual request if using the workbench.

## 5. Environment selection is operation-specific

Always use the approved deployment URL and credentials. The portal workbench executes against its connected deployment and is not a separate test environment.

| Operation family | Environment behavior |
|---|---|
| `/api/v2/native/payments/collect` and `/payout` | `X-CPay-Environment` takes precedence over `metadata.environment`; the implementation defaults to SANDBOX when neither is supplied |
| Compatibility `/api/v2/payments/collect` and `/payout` | Same explicit selector, but omission preserves the historical PRODUCTION behavior |
| `/api/v2/refunds` and batch-payout status/retry | Explicit `X-CPay-Environment` selects sandbox; omission retains PRODUCTION behavior |
| Billing BaaS | Authenticated project/environment mapping, scopes, tenant mapping and activation rules apply |
| Legacy `/api/v1` | No sandbox selection contract is assumed |
| Other reads, merchant workspace and services | Follow the specific operation's implementation; an arbitrary sandbox header does not establish isolation |

For payment sandbox calls use exactly `SANDBOX`. For production use exactly `PRODUCTION`. A sandbox-looking header on a production-only operation does not make that operation safe to test with live data.

## 6. Collect a payment

Start with `POST /api/v2/native/payments/collect` in an approved sandbox. An illustrative body is:

```json
{
  "merchantNumber": "YOUR_MERCHANT_NUMBER",
  "amount": "5000.0000",
  "currency": "UGX",
  "country": "UG",
  "channel": "YOUR_ENABLED_CHANNEL",
  "payer": {"type": "MSISDN", "value": "YOUR_APPROVED_TEST_ACCOUNT"},
  "reference": "order-test-0001",
  "description": "Sandbox order",
  "callbackUrl": "https://YOUR-APPLICATION.example/cito/events",
  "metadata": {"environment": "SANDBOX"}
}
```

The payer object uses `type` and `value`; select the party type supported by the enabled channel. Example values are placeholders, not provisioned credentials or provider test accounts.

Sign the exact JSON and send it with `Content-Type: application/json`, the required signature headers and your idempotency key. A `202` response acknowledges submission or a pending workflow. It does not prove settlement or successful collection. Preserve the Cito reference, your reference, the result status and correlation information.

Check the operation's final status endpoint and receive verified webhooks. Payouts use a payee and may enter an approval-pending state. Existing maker-checker and balance controls remain in force.

## 7. Retries and payment idempotency

Keep the same idempotency key and commercially identical payload when retrying an operation that supports idempotency. Generate a new nonce and timestamp and re-sign the retry. Do not reuse a nonce: replay protection may reject it across backend replicas.

A timeout means the result is uncertain. Look up the original reference before creating another payment. A new reference or idempotency key can create another operation. A changed amount, currency, merchant or payload is not an equivalent replay.

The API access meter counts authenticated HTTP admissions, not unique payments. Consequently, a new authenticated retry can incur another access fee even when payment idempotency prevents a second payment. Polling also produces access calls. Default access rates are zero; check the current published rate before choosing a polling schedule.

## 8. Receive and verify webhooks

For CPay `callback-v1`, use the raw request body and these header values to reconstruct six newline-separated lines:

```text
CALLBACK_TASK_ID
MERCHANT_ID
REFERENCE
TIMESTAMP
NONCE
RAW_REQUEST_BODY
```

The headers are `X-CPay-Callback-Task-Id`, `X-CPay-Merchant-Id`, `X-CPay-Reference`, `X-CPay-Timestamp`, and `X-CPay-Nonce`. Check `X-CPay-Signature-Version: callback-v1`. Calculate HMAC-SHA256 using the active callback secret, base64-encode it and compare with `X-CPay-Signature` in constant time. Callback timestamps use Unix epoch seconds, unlike the ISO-8601 timestamp in the v2 request-signing contract.

Reject stale timestamps and replayed delivery identifiers according to your configured replay policy. Confirm the merchant/reference matches your integration. Persist the verified event before acknowledging it and process it idempotently. Do not assume delivery order. Provider-to-Cito callbacks are a different contract and are not third-party customer API calls.

Consult the current `Docs/Webhook-events.md` for provider and callback behavior. Billing BaaS webhook operations use their separately documented contract.

## 9. Understand API access billing

The access unit is one authenticated HTTP admission to a commercial endpoint. Rates are keyed by HTTP method plus canonical route template: all IDs in `GET /api/v2/payments/{reference}` use one rate, while POST and GET on the same path can have different rates.

| Situation | API access treatment |
|---|---|
| New registered endpoint | Starts at `UGX 0.0000`; zero remains an explicit billable rate |
| Successfully authenticated call | One durable usage event before business execution |
| Business validation/provider failure after authentication | Access fee still applies; it is an admission fee, not a success fee |
| Authentication failure before admission | No admission charge |
| Fresh authenticated retry or poll | A separate admission; service/payment idempotency remains independent |
| Explicit native/compatibility payment sandbox execution, or BaaS SANDBOX context | Zero-rated usage evidence; no production invoice charge |
| Production API admission at zero | Usage and a zero-rated charge remain traceable |
| Public overview, documentation retrieval, provider callbacks, admin operations | Not customer access charges |

API access fees are separate from payment processing, provider, messaging, tax and other contractual charges. Authoritative amounts use four decimal places. The access charge references its immutable price-book version and enters the existing customer invoice staging process; it does not directly debit a payment wallet or bypass invoice approvals. Tax and ledger posting remain part of the established invoice lifecycle.

If admission evidence cannot be persisted, the operation fails before its business side effects. Do not interpret an admission fee as proof that a payment or provider action succeeded.

## 10. Change rates as an administrator

1. Sign in to the admin portal and open API workbench.
2. Select the commercial collection, search for an endpoint and select its HTTP method.
3. Inspect the current rate and set a non-negative decimal amount, including `0`, with no more than four decimal places. Select the three-letter currency.
4. Publish and confirm the rate. It applies to future admissions immediately.
5. If another admin published while you were editing, reload the reference. The version check rejects a stale update.

Publication and admission serialize on the endpoint's database row. History remains in the existing price-book tables, and publication records the authenticated admin identity in the audit trail. A customer cannot change rates by altering the merchant portal or calling the admin URL.

## 11. Troubleshoot safely

| Symptom | Check next |
|---|---|
| 401/signature rejection | Correct key and merchant; exact bytes, query encoding, UTC clock, version, fresh nonce |
| 403 or readiness rejection | Merchant/service entitlement, environment activation, user permissions and approvals |
| 400/422 | Required fields, enum values, account schema, amount and currency |
| 409 | Idempotency conflict, state transition or stale rate version |
| 429/quota rejection | Reduce concurrency and apply bounded backoff; retain the original operation reference |
| Timeout/5xx | Query the original reference; avoid an automatic second payment |
| Full system reference cannot load | Admin portal session and backend API-docs configuration |

Status codes and payloads vary across legacy and v2 contracts; use the selected operation's responses. Send support the time, environment, HTTP method, route template, reference and sanitized error code. Never send private keys, passwords, API keys, signatures reusable within their window, or unnecessary customer identity data.

## 12. Integration acceptance checklist

Before production, demonstrate signature verification, rejected invalid/replayed requests, tenant isolation, allowed scopes, equivalent/conflicting idempotent retries, pending-to-final status handling, duplicate webhook handling, zero/nonzero pricing, rejected stale admin rate edits, and sandbox exclusion from production invoices. Confirm the exact deployed release and required migration before claiming readiness.

This document describes the repository change. It is not evidence that a deployment, provider certification or production activation has occurred.

## 13. Service readiness and identity callback safety

Treat configuration, certification and activation as separate facts. Use these terms when planning an integration; this table defines their meaning, not the current state of your account or a claim that every screen implements this lifecycle.

| Readiness term | Required evidence |
|---|---|
| Not configured | Required service configuration is missing |
| Configured | Required fields exist; connectivity, certification and activation are not implied |
| Sandbox verified | The stated test scenario passed in the approved isolated environment |
| Certification pending | Required operator/provider or acceptance evidence is still outstanding |
| Production enabled | The specific merchant, provider and environment have explicit activation approval and configuration |
| Degraded | A configured or enabled service has an observed health or delivery problem |

For GnuGrid, synchronous verification remains the supported connector path, subject to existing consent, feature, entitlement and configuration controls. `GET /api/v2/identity/capabilities` reports `supportsSync: true` and `supportsAsync: false` for this connector. Synthetic sandbox matches do not constitute real identity checks or provider certification.

GnuGrid asynchronous callbacks are intentionally unsupported until the provider's actual authentication contract and durable pending-request correlation, replay protection and idempotent finalization are implemented and certified. The existing `POST /api/v2/identity/provider/gnugrid/callback` rejects callbacks with HTTP 401 and `INVALID_CALLBACK_SIGNATURE`; a present `X-Gnugrid-Signature` header or the outbound API key does not establish authenticity. Do not retry this endpoint in a loop or use it to mark a customer verified. Direct connector parsing also rejects untrusted callback bodies. This containment is not a claim that a signed asynchronous integration has been completed.

For Communications, distinguish request acceptance, provider acceptance and final delivery evidence. A successful submission response is not proof that an email reached an inbox or that an SMS reached a handset. Report uncertain delivery as uncertain and use approved test recipients when establishing end-to-end evidence.

For initial developer onboarding, prefer an approved non-money sandbox read or capability-discovery exercise. Verify its exact environment and authorization first; even reads may incur the published API access charge. Do not initiate a payment, payout or vending purchase merely to prove that documentation loads.
