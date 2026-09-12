# Cito Developer Guide

> Current consumer guidance: For the current external server-to-server handover, start with `Docs/Api/consumer/START-HERE.md`. It owns the SDK 2.0 migration, filtered external collection, explicit environment and signing conformance guidance. This broader guide also describes internal/merchant-workspace operations; they are not all externally callable with one service-account key.

This is the onboarding path for developers integrating with Cito. **Cito is the platform; CPay is the payments capability within Cito.** The API documentation is split by product and security boundary so developers can determine the correct contract and authentication model before implementation.

> Documentation freshness: API documentation is managed as code. API-facing pull requests are validated against the committed OpenAPI contracts and changed Spring controller paths are hard-gated to appear in at least one authoritative contract.

## 1. Choose the correct API contract

| Contract | Source | Use it for | Primary authentication |
| --- | --- | --- | --- |
| CPay Payments API | `Docs/Api/cpay-v2-openapi.yaml` | collections, payouts, refunds, balances, statements, checkout, invoices, FX/cross-border and payment-adjacent operations | CPay v2 canonical request signature, or endpoint-specific admin/public auth |
| Cito Platform API | `Docs/Api/cito-platform-v2-openapi.yaml` | merchant platform services, entitlements, developer control plane, routing, marketplace, recurring payments, virtual accounts, analytics, embedded Cito and integrations | authenticated Cito merchant session plus service/environment entitlements |

The unified catalog is `Docs/Api/README.md`.

## 2. Environments

Use the environment URLs supplied by the active deployment or merchant onboarding material. Do not hard-code example documentation hostnames into production clients.

Where supported, the environment selector is:

```http
X-CPay-Environment: SANDBOX
```

Production access can additionally depend on merchant entitlements, channel readiness, risk controls, compliance controls, transaction limits and approval policy.

## 3. CPay v2 server-to-server signing

Signed CPay payment requests are verified using the merchant RSA public key registered with Cito/CPay. The client retains the private key.

Current verifier headers:

- `X-CPay-Signature-Version` - currently `v2`
- `X-CPay-Timestamp` - ISO-8601 instant
- `X-CPay-Nonce` - unique per merchant request
- `X-CPay-Signature` - base64 RSA-SHA256 signature over the canonical request

The canonical request is:

```text
METHOD
PATH
CANONICAL_QUERY
TIMESTAMP
NONCE
BODY_SHA256_HEX
```

The merchant identity is supplied by the operation's body or query context. Do not assume a merchant-number header is part of verification unless that specific endpoint contract defines it. See `Docs/Api-v2-signing.md`.

## 4. Cito merchant-session APIs

The Cito Platform API is designed for the authenticated merchant workspace. A successful Cito sign-in establishes the merchant session; controllers then resolve the signed-in user and merchant context. Individual services can also require an active merchant entitlement for the requested environment.

Current merchant platform groups include:

- `/api/v2/merchant-self-service/cito` - service catalog and entitlements
- `/api/v2/merchant-self-service/developer` - projects, service accounts, credentials, test events, request logs and readiness
- `/api/v2/merchant-self-service/routing` - intelligent routing policies, rules, simulation and decisions
- `/api/v2/merchant-self-service/marketplace` - subaccounts, split rules, executions and refund allocations
- `/api/v2/merchant-self-service/recurring` - plans, mandates, subscriptions and charges
- `/api/v2/merchant-self-service/refunds` - merchant-workspace refund lifecycle and financial timeline
- `/api/v2/merchant-self-service/virtual-accounts` - virtual-account issuance, closure and transfer visibility
- `/api/v2/merchant-self-service/analytics` - merchant intelligence and recommendations
- `/api/v2/merchant-self-service/embedded` - partner, branding, onboarding, delegation and commission controls
- `/api/v2/merchant-self-service/integrations` - connector catalog, installations, mappings, subscriptions and jobs

Do not reuse CPay RSA-signing assumptions for these session-scoped endpoints unless an operation explicitly says so.

## 5. Idempotency and safe retries

Financial writes should use an idempotency key:

```http
X-CPay-Idempotency-Key: <opaque-client-supplied-key>
```

- Same key + same request body: return the original result without re-execution.
- Same key + different body: treat as an idempotency conflict.
- If the client loses the response after sending a money-moving request, retry with the same reference, same key and exact request body.

Do not create a second payout, refund or transfer merely because the original request is still pending.

## 6. Collections and payouts

Primary CPay v2 payment routes include:

- `POST /api/v2/native/payments/collect`
- `POST /api/v2/native/payments/payout`
- compatibility orchestration routes under `/api/v2/payments/...` where documented

Payouts can be subject to transaction, aggregate, beneficiary, balance, compliance and maker-checker controls. `APPROVAL_PENDING` is non-terminal. Persist the original reference and wait for approval/execution rather than submitting a replacement payout.

## 7. Status, balances, statements and refunds

Common payment integration routes include:

- `GET /api/v2/payments/{reference}?merchantNumber=...`
- `GET /api/v2/balances?merchantNumber=...`
- `GET /api/v2/statements?...`
- `POST /api/v2/refunds`

Follow each contract's pagination, limit and cursor rules. Balances, statements and financial timelines are sensitive merchant data.

## 8. Checkout, payment links and invoices

| Action | Endpoint |
| --- | --- |
| Create payment link | `POST /api/v2/payment-links` |
| Pay hosted checkout | `POST /api/v2/checkout/{token}/pay` |
| Create invoice/request-to-pay | `POST /api/v2/invoices` |
| List invoices | `GET /api/v2/invoices?merchantNumber=...` |
| Send invoice | `POST /api/v2/invoices/{reference}/actions/send` |
| Cancel invoice | `POST /api/v2/invoices/{reference}/actions/cancel` |
| Pay invoice | `POST /api/v2/invoices/pay/{token}` |

Public payment tokens are credentials. Avoid logging or unnecessarily exposing them.

## 9. Developer control plane

The Cito developer control plane provides merchant-scoped developer projects, environments, service accounts, scoped credentials, test events, request logs and readiness checks.

Production environment activation can require scope entitlement readiness. Service accounts should be granted the minimum service scopes required by the integration. Credential creation and revocation are privileged configuration operations even when exposed through the merchant workspace.

## 10. Intelligent routing and marketplace services

Intelligent routing allows entitled merchants to simulate routing, define policies/rules and inspect routing decisions. Marketplace services support subaccounts, split-payment rules, split simulations, execution history and refund allocation visibility.

Routing simulation is not itself money movement. A later payment execution using the selected route remains subject to the normal payment, risk and entitlement controls.

## 11. Recurring payments and virtual accounts

Recurring-payment APIs cover plans, customer mandates, subscriptions, subscription status and charge history. Virtual-account APIs cover account issuance, listing, closure and inbound transfer visibility.

Treat mandate creation, recurring execution configuration and virtual-account lifecycle actions as financially sensitive operations and audit them accordingly.

## 12. Analytics, embedded Cito and integrations

Merchant analytics exposes daily/provider aggregates, recommendations and acknowledgement/refresh operations. Embedded Cito supports partner configuration, branding, onboarding sessions, downstream merchant linkage, service delegation and commission rules. The integration marketplace supports connector installations, field mappings, event subscriptions and queued integration jobs.

Connector credentials and configuration remain internal references where possible. Clients should not depend on provider-specific credential shapes unless an endpoint explicitly exposes them.

## 13. Webhooks and callbacks

Verify callback signatures before changing business state or fulfilling an order. Make webhook consumption idempotent because duplicate delivery is normal in a reliable asynchronous system. Return 2xx only after the event has been durably accepted. Use delivery logs and replay tooling rather than fabricating replacement events.

See `Docs/Webhook-events.md`.

## 14. Errors and retry behavior

Prefer stable error codes, HTTP status and documented retry semantics over human-readable message text. The preferred v2 envelope contains `code`, `message` and `traceId`; some older or internal controllers still use simpler maps, so the operation contract is authoritative.

| Case | Recommended behavior |
| --- | --- |
| Unknown outcome after client network failure | Retry same request with same idempotency key |
| Provider unavailable/timeout | Follow returned transaction state and callback/status guidance |
| Validation error | Correct the request; do not blind-retry |
| Authentication/replay rejection | Rebuild timestamp, nonce and signature; never reuse a nonce |
| Entitlement failure | Request/activate the required service entitlement rather than retrying unchanged |

See `Docs/Error-catalog.md`.

## 15. Cross-border transfers

Where enabled, the flow is FX quote -> transfer intent -> compliance/treasury controls -> delivery.

- `POST /api/v2/fx/quotes`
- `POST /api/v2/cross-border/transfers`

An FX quote does not move money. Creating or authorizing a transfer is a high-impact action and should require explicit user approval in AI/agent integrations.

## 16. Security expectations

The current platform lineage strengthens production safety with fail-closed configuration checks, hardened session/security-header defaults, stronger password hashing and independent account/network login throttling budgets. API clients should preserve that posture rather than weakening it at integration boundaries.


### Admin password-reset semantics

`POST /auth/resetPassword` accepts a single-use token from the hardened
`password_reset_tokens` store. During the legacy-column transition, an operational recovery aligns
the administrator email-timestamp window with the token's configured expiry. Recovery may issue
only a hashed, short-lived token for an existing active administrator; it cannot create, activate,
or grant privileges to an account.

Minimum integration expectations:

- least-privilege credentials and scopes;
- no production private keys in client logs or general AI context;
- explicit human approval for money movement and privileged control changes;
- replay protection and idempotency for financial writes;
- verified webhooks before fulfillment;
- tenant isolation in all cached or persisted integration state;
- audit trails for credential, entitlement, payout, refund, recurring and admin actions.

## 17. AI and automated clients

Suggested execution policy:

| Risk class | Examples | Autonomous execution |
| --- | --- | --- |
| `read_only` | service catalog, health, channels, status | allowed within authorization scope |
| `read_only_sensitive` | balances, statements, financial timelines | scoped access and audit trail |
| `quote_only` | routing simulation, FX quote | may be automated when requested |
| `communication_send` | customer messaging | require user/business authorization |
| `validation_check` | identity/KYC checks | lawful purpose, consent/policy and scoped access |
| `money_movement` | payout, refund, cross-border transfer | explicit approval before execution |
| `secret_management` | credential or webhook-secret rotation | privileged workflow |
| `finance_close` / `admin_control` | reconciliation approval, close, repair | never general autonomous execution |

## 18. Go-live checklist

- [ ] Correct API contract selected for every integration surface
- [ ] RSA signing fixture passes for CPay server-to-server requests
- [ ] Merchant session and entitlement behavior tested for Cito platform APIs
- [ ] Callback verification, duplicate delivery and replay handling tested
- [ ] Sandbox collect, payout, status, balance and failure scenarios passed where payments are enabled
- [ ] Idempotency tested under timeout and retry conditions
- [ ] Provider/channel readiness and required merchant entitlements confirmed
- [ ] Recurring, virtual-account, marketplace, embedded or integration features tested if enabled
- [ ] Reconciliation/statement handling tested for integrations that settle money
- [ ] Production transaction limits, maker-checker controls and compliance requirements reviewed
- [ ] Monitoring covers provider failures, callback backlog, failed jobs and reconciliation exceptions
- [ ] Production activation approved

## 19. Documentation lifecycle

The repository validates both authoritative OpenAPI contracts on every relevant change. For pull requests, changed Spring controller paths must be present in at least one contract. Both contracts are linted and separate browsable HTML references are generated from the exact source commit.

Generated HTML is not the source of truth. The committed YAML and supporting documentation are.

## References

- Unified API catalog: `Docs/Api/README.md`
- CPay Payments OpenAPI: `Docs/Api/cpay-v2-openapi.yaml`
- Cito Platform OpenAPI: `Docs/Api/cito-platform-v2-openapi.yaml`
- Documentation lifecycle: `Docs/Api/AUTO_UPDATE_POLICY.md`
- Signing: `Docs/Api-v2-signing.md`
- Examples: `Docs/Api-v2-examples.md`
- Webhooks: `Docs/Webhook-events.md`
- Errors: `Docs/Error-catalog.md`
- Sandbox: `Docs/sandbox-guide.md`
- v1 migration: `Docs/v1-to-v2-migration.md`
- Platform/module boundary: `Docs/CITO_BRAND_AND_MODULE_BOUNDARY.md`
- Security architecture: `Docs/CITO_SECURITY_ARCHITECTURE.md`
