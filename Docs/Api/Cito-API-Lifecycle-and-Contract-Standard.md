# Cito API Lifecycle and Contract Standard

**Status:** Normative  
**Scope:** Public, merchant, administrator, embedded/BaaS, webhook and provider-facing HTTP APIs.

## 1. API ownership

Every API route MUST have one owning domain and one documented source contract. Controllers should remain thin. Validation, tenant scope and business rules belong in services or dedicated boundary components rather than being duplicated across controllers.

New routes MUST be documented in OpenAPI in the same pull request. The repository's API documentation gate is intentionally blocking.

## 2. Versioning

- Stable product APIs MUST use an explicit versioned path such as `/api/v2/...`.
- Backward-incompatible contract changes require a new version or an approved compatibility transition.
- Adding optional response fields is normally backward-compatible; removing or renaming fields is not.
- Deprecated routes MUST publish deprecation/sunset metadata where the platform supports it and MUST have a migration path before removal.
- Internal implementation refactors MUST NOT leak into public contract versioning.

## 3. Authentication and authorization

Each route MUST define its authentication method and authorization scope.

- Merchant routes MUST use authenticated merchant context for merchant identity.
- Administrator routes MUST require explicit administrator authorization.
- Embedded/BaaS routes MUST enforce partner/customer scope according to their API-key/service-account contract.
- Provider callbacks MUST use provider-specific verification and exact correlation rules.
- Sensitive write operations SHOULD require step-up/MFA where the platform's risk model defines it.

Authentication failure MUST return an intentional 401/403 class response, not an uncaught 500.

## 4. Tenant scoping

A merchant-facing API MUST NOT allow a caller to escape its authenticated merchant scope by changing a request parameter or body field.

Administrator routes that accept `merchantId` MUST validate the identifier and preserve auditability. Unknown or malformed identifiers MUST resolve to a deterministic 4xx or documented empty/not-configured response.

## 5. Request validation

API-boundary validation MUST reject invalid input before it reaches provider, ledger or billing side effects.

Validate at minimum where applicable:

- required fields;
- identifier format and positive numeric ranges;
- currency and amount scale;
- country/channel/provider enums;
- callback URL safety;
- mutually exclusive fields;
- pagination/range limits;
- request body size and file type;
- environment (`SANDBOX` versus `PRODUCTION`).

Normal invalid user input MUST NOT cause an uncaught 5xx.

## 6. Idempotency

Every externally retryable operation that can create money movement, billing usage, message delivery, subscription changes or other durable side effects MUST define an idempotency contract.

The idempotency key MUST be scoped to the appropriate merchant/tenant and operation. A repeated request with the same key and equivalent payload MUST return the original semantic result rather than create a duplicate side effect.

A repeated key with a materially different payload MUST fail deterministically.

## 7. Correlation

Every production transaction or asynchronous operation SHOULD expose a Cito correlation/reference identifier suitable for support and reconciliation.

Provider references are evidence, not Cito primary identity. Provider callbacks and status responses MUST be resolved to an exact internal record before state mutation.

## 8. Error contract

APIs MUST distinguish:

- validation errors: 400/422 as documented;
- unauthenticated: 401;
- unauthorized: 403;
- missing resource: 404;
- conflict/idempotency/state conflict: 409 where appropriate;
- rate limit: 429;
- genuine unexpected platform failure: 5xx.

An empty dataset, unknown optional configuration or unsupported route preview MUST use a documented empty/not-configured/4xx state rather than a generic server failure.

Error responses MUST NOT include stack traces, secrets, raw provider credentials or sensitive internal SQL details.

## 9. Money and currency

Amounts MUST use decimal semantics and a declared currency. A response containing monetary aggregates MUST either:

1. group totals by currency; or
2. identify the approved FX rate/source/effective time used for conversion.

APIs MUST NOT silently sum different currencies.

## 10. Pagination and ranges

List endpoints expected to grow beyond operationally small datasets MUST paginate or enforce a bounded limit. Maximum limits MUST be enforced server-side.

Date/time ranges MUST be bounded where unbounded queries could degrade production.

Times SHOULD be ISO-8601 at external boundaries and MUST have an unambiguous timezone/offset.

## 11. Webhooks and callbacks

Cito-to-merchant webhooks MUST define:

- event name/version;
- stable event/reference ID;
- signing algorithm and signature headers;
- timestamp/replay protection;
- retry schedule and terminal dead-letter behavior;
- idempotent merchant-consumption expectation.

Provider-to-Cito callbacks MUST follow provider-specific verification, correlation and status-recovery contracts. A callback alone MUST NOT override stronger provider evidence when the provider integration requires verification.

## 12. Sandbox and production

Sandbox behavior SHOULD exercise the same Cito contract shapes as production while keeping financial and credential state isolated.

Production activation MUST be controlled by existing entitlement, provider-readiness and go-live mechanisms. An API parameter alone must never bypass production readiness.

## 13. PII and secrets

OpenAPI examples, logs, analytics and error responses MUST use synthetic/redacted values. Never publish live credentials, tokens, passwords, private keys, full card data or unnecessary identity data in API documentation.

## 14. OpenAPI requirements

Every production route MUST document:

- method/path;
- authentication/security scheme;
- request parameters/body;
- success response;
- material 4xx states;
- asynchronous behavior where relevant;
- idempotency/correlation expectations where relevant.

Controller changes that affect the contract MUST update OpenAPI in the same PR.

## 15. API review checklist

Before merge, reviewers MUST confirm:

- tenant scope is explicit;
- authorization matches the route purpose;
- invalid/empty states are deterministic;
- idempotency and correlation are defined for side effects;
- money/currency is safe;
- secrets/PII are excluded;
- range limits are bounded;
- OpenAPI and tests reflect the implementation;
- provider/ledger/billing ownership is not bypassed.
