# Webhook Event Registry

> Current consumer guidance: Delivery contracts are not interchangeable: task-queue `callback-v1` uses its documented six-line HMAC; the current `MerchantWebhookService` uses a payload-and-endpoint-secret digest without those freshness headers. The external receiver guide and reference verifier are in `Docs/Api/consumer/CALLBACK-RECEIVER.md` and `sdk/Python/cito_callbacks.py`. Configure the actual subscription scheme; do not auto-fallback between them or infer settlement from unsigned headers.

Webhook payloads should be versioned and event-driven instead of exposing raw status strings. Providers can still return their own codes internally, but merchant callbacks should use CPay event names.

## Envelope

```json
{
  "event_id": "evt_01J0Z000000000000000000000",
  "event_type": "payment.succeeded",
  "event_version": 1,
  "created_at": "2026-07-16T09:30:00Z",
  "merchant_id": "10003482",
  "data": {
    "transaction_id": "tx_01J0Z000000000000000000000",
    "merchant_reference": "order-123",
    "status": "SUCCESSFUL",
    "amount": {
      "currency": "UGX",
      "minor_units": 2480000
    }
  }
}
```

## Events

| Event | When Emitted |
|---|---|
| `payment.pending` | Collection is accepted for processing. |
| `payment.succeeded` | Collection has completed successfully. |
| `payment.failed` | Collection cannot complete. |
| `payout.pending` | Payout is accepted for processing. |
| `payout.succeeded` | Payout has completed successfully. |
| `payout.failed` | Payout cannot complete. |
| `refund.pending` | Refund is accepted. |
| `refund.succeeded` | Refund has completed successfully. |
| `refund.failed` | Refund cannot complete. |
| `invoice.issued` | One-off request-to-pay invoice is sent to the customer. |
| `callback.parked` | Delivery retries are exhausted or require operator action. |

## Delivery Contract

- Delivery must pass through the callback task queue.
- Payloads are signed with the merchant callback secret.
- Retries must preserve the same `event_id`.
- Provider callback handlers must deduplicate by provider reference plus terminal status.
- Event schemas are registered in code by event type/version; do not add a webhook event without a JSON schema and an example payload.

## MTN MoMo provider callbacks

MTN `RequestToPay` and `Transfer` requests are asynchronous. Cito sends a unique UUID in
`X-Reference-Id` and a transaction-specific HTTPS callback URL under:

```text
/api/v2/provider-callbacks/mtn/{providerReference}
```

MTN callback handling follows these rules:

- the callback URL must use HTTPS and its host must match the callback host registered for the MTN API user;
- the provider reference is correlated to the exact Cito merchant, merchant reference, operation, environment, country, currency and credential source;
- the callback payload is treated as a signal, not as sufficient authority to settle money;
- Cito performs an authenticated MTN status lookup for the same provider reference before moving the merchant transaction or CPay shared-provider treasury reservation to a final state;
- the verified MTN `externalId` must match the merchant reference stored in the correlation record;
- duplicate or already-final callbacks remain idempotent;
- unresolved MTN transactions are polled through the corresponding MTN status endpoint because MTN callbacks are single-attempt and may be missed;
- the generic transaction-timeout scheduler must not convert an MTN `PENDING` transaction to `FAILED` without a verified provider result.

The provider-facing callback contract is defined in `Docs/Api/provider-callbacks-openapi.yaml`.
Merchant callbacks continue to use the CPay webhook event contract described in this document.

## Callback signature verification

New callback deliveries expose a versioned, independently verifiable HMAC contract.

Required callback headers:

- `X-CPay-Signature-Version: callback-v1`
- `X-CPay-Signature`: base64 HMAC-SHA256 signature
- `X-CPay-Timestamp`: Unix epoch seconds
- `X-CPay-Nonce`: unique callback-delivery nonce
- `X-CPay-Callback-Task-Id`: callback task id
- `X-CPay-Merchant-Id`: CPay merchant id
- `X-CPay-Reference`: merchant/payment reference used when the task was queued

For `callback-v1`, reconstruct the exact canonical string below using the raw HTTP request body as received:

```text
CALLBACK_TASK_ID
MERCHANT_ID
REFERENCE
TIMESTAMP
NONCE
RAW_REQUEST_BODY
```

Calculate `HMAC-SHA256(canonical_string, active_merchant_callback_secret)`, base64-encode the result,
and compare it to `X-CPay-Signature` using a constant-time comparison.

Receivers should also:

- reject unsupported signature versions;
- reject timestamps outside their configured replay window;
- reject a reused nonce/event id within the replay window;
- verify that the merchant id/reference agree with the receiving integration context;
- parse the body only after signature verification where practical;
- preserve the raw request bytes until verification is complete.

The signing-context headers are metadata rather than secrets and are required so receivers can
independently reconstruct and verify the callback signature.
