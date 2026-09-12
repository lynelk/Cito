# Receiving Cito callbacks safely

## Identify the actual delivery contract

**Task queue `callback-v1`:** expect `X-CPay-Signature-Version: callback-v1`, a base64 HMAC-SHA256 signature, timestamp in Unix seconds, nonce, callback task ID, merchant ID and reference. The canonical bytes are six newline-separated fields: task ID, merchant ID, reference, timestamp, nonce and the unmodified raw request body. The secret is the separately provisioned callback secret, not the merchant RSA key. Check the timestamp window, expected merchant and reference. Use constant-time comparison. This contract is documented by `Docs/Webhook-events.md` and implemented in the callback task subsystem.

**Current merchant-event/BaaS delivery service:** `MerchantWebhookService` presently emits `X-CPay-Event`, `X-CPay-Reference`, and a hexadecimal `X-CPay-Signature` calculated as SHA-256 of the exact payload string, then a literal `.`, then the endpoint secret. It is not the task queue HMAC contract and does not carry its signed timestamp/nonce/version headers. The payload is augmented with `eventId`, `eventVersion` and `createdAt`; provider-specific payload fields remain event dependent. This is an existing compatibility contract, not a claim that all Cito deliveries use one cryptographic envelope. Do not silently try both schemes for one registered receiver: configure the expected scheme for the endpoint and reject mismatches.

For merchant-event delivery, do not trust event/reference headers independently of the verified payload: they are not separately bound into that digest. Correlate the eventId, business reference and receiving tenant using the verified body and your expected subscription. The absence of signed freshness headers makes durable event deduplication especially important. Use authoritative signed status queries for uncertain financial outcomes. A stronger versioned event-delivery protocol would need an explicit compatibility/certification rollout; it is not silently invented by this developer kit.

## Durable inbox algorithm

1. Enforce HTTPS, body-size limits and the configured signature scheme. Preserve raw bytes until verification; do not pretty-print/re-serialize before verification. Reject malformed or duplicate security headers and a different merchant scope.
2. Verify the signature in constant time. For callback-v1 also enforce the timestamp window; reject expired/future deliveries according to the agreed tolerance. Parse the payload only after verification.
3. In one database transaction, insert an inbox record keyed by the receiving merchant and stable event/task identity. Store an encrypted/minimized payload and a digest according to your retention policy. A unique constraint makes duplicate deliveries harmless across replicas.
4. Commit the inbox before acknowledging HTTP 2xx. A duplicate already durably recorded event can be acknowledged without applying business effects again. Do not permanently consume an event in a process-local cache before its durable record succeeds.
5. Process asynchronously with your own idempotent business transaction. Preserve pending/final state ordering and the original monetary/reference attributes. Event delivery and settlement are separate outcomes.
6. On local failure, leave the inbox work retryable. On invalid authentication, reject rather than acknowledging a forged event. Do not log secrets, full sensitive payloads or signatures reusable within their validity window.

The SDK signing helpers sign merchant-to-Cito requests. Use the separately named callback reference verifiers below, not the request signers. Neither implements your durable inbox or receiver infrastructure.

## Executable reference verifier

`sdk/Python/cito_callbacks.py` provides separately named `verify_task_callback` and `verify_merchant_event` functions; choose the configured scheme before accepting a request. They enforce body limits, duplicate security-header rejection, constant-time authentication, required tenant scope and strict JSON parsing. Task callbacks also enforce a configurable timestamp window. The verified result supplies the stable event/task identity for your **durable** unique inbox key; it does not mark that identity consumed in memory.

Pass the original header pairs when your web framework exposes them, so duplicate security headers can be rejected rather than silently collapsed. The merchant-event helper supports payloads with `merchantNumber`, `eventType`, `eventId` and `reference`. Do not call it a universal parser for domain-specific payloads without those fields; give those event types their own explicit scope/schema adapter. Both helpers preserve pending status and return no settlement decision. Their offline tests include tampering, duplicate headers/JSON keys, cross-tenant input, stale/future times and forbidden signature-scheme fallback.
