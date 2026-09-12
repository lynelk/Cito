# CPay API v2 Signing

CPay API v2 uses a versioned RSA signature contract.

## Required headers

- `X-CPay-Merchant-Number`
- `X-CPay-Signature-Version`: `v2`
- `X-CPay-Timestamp`: ISO-8601 instant, for example `2026-07-03T08:00:00Z`
- `X-CPay-Nonce`: unique value per merchant request
- `X-CPay-Signature`: base64 RSA SHA256 signature

## Optional idempotency header

- `X-CPay-Idempotency-Key`: unique merchant-generated key for collect and payout requests

When the same key is reused with the same request body, CPay returns the stored result. When the same key is reused with a different body, CPay rejects the request.

An acquired key is not released when validation, risk or credential resolution fails. Request cleanup stores a replayable `REQUEST_FAILED` result; this is a request-level failure, not evidence that a provider declined the payment. Claims abandoned by a process crash become replayable after 15 minutes through the shared recovery job. The key remains bound to its original body. Check the commercial reference's status before trying a corrected request with a new idempotency key; never resubmit an uncertain payment to discover its outcome. A completed payment response is never replaced by failure cleanup.

## Canonical string

```text
METHOD
PATH
CANONICAL_QUERY
TIMESTAMP
NONCE
BODY_SHA256_HEX
```

Example path:

```text
/api/v2/native/payments/collect
```

The compatibility route `/api/v2/payments/collect` uses the same signing contract.

For a request such as:

```text
GET /api/v2/balances?merchantNumber=123
```

The canonical query line is:

```text
merchantNumber=123
```

The released `CanonicalRequestSigner` applies **Java `String.trim()`** to the body before computing its UTF-8 SHA-256 digest: only leading/trailing characters U+0000 through U+0020 are removed. Whitespace inside JSON remains significant; nonbreaking spaces are not removed. Compact JSON without surrounding whitespace avoids this compatibility edge. This documents the current verifier; it does not change the wire protocol. For a bodyless GET, hash the empty string.

Query canonicalization sorts **decoded names and then repeated decoded values** using Java UTF-16 code-unit ordering, then UTF-8 form-encodes each pair. Spaces become `+`, literal plus becomes `%2B`, `*` remains `*`, and `~` becomes `%7E`. Join pairs with `&`; preserve duplicate keys and empty strings. The SDK omits null values. SDK 2.0 matches this rule in Node, Python, PHP and the Postman Web Crypto signer.

The shared golden corpus is `sdk/tests/signing-vectors.json`. It is checked against a Java 21 oracle, all three language signers and the actual backend verification helper. Never use locale-dependent sorting or RFC 3986 query escaping as a substitute.

## Replay protection

The default nonce store is JDBC-backed:

```text
cpay.security.nonce-store=jdbc
```

The in-memory store exists only for isolated local tests with `cpay.security.nonce-store=memory`.
Clustered production deployments must use a durable shared store so all application instances share
replay state. Portal sessions are database-backed through Spring Session JDBC and should be treated
separately from API nonce replay storage.

## Backward compatibility

`/api/v1` keeps the legacy concatenated-field signing contract. `/api/v2` uses this versioned query-aware contract.


## Consumer SDK 2.0 migration

The client constructor now requires an explicit environment. Native collection/payout wrappers require a caller-owned idempotency key; the signer no longer invents one. Every attempt gets a fresh nonce and timestamp while retaining the same operation key, reference and commercially identical body. No automatic retry or redirect follows an uncertain response. The updated consumer kit, clean Postman collection and migration guide are under `Docs/Api/consumer/`. These changes do not alter backend routes or weaken authorization.
