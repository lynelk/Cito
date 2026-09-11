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

The body hash is the SHA-256 hex digest of the exact request body string sent over the wire. For GET requests with no body, hash an empty string.

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
