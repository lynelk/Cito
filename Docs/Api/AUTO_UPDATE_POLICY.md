# Cito API Documentation Auto-Update Policy

This repository treats the OpenAPI contract and developer documentation as code.

## Source of truth

- Machine-readable contract: `Docs/Api/cpay-v2-openapi.yaml`
- Developer onboarding: `Docs/developer-guide.md`
- Signing contract: `Docs/Api-v2-signing.md`
- Examples: `Docs/Api-v2-examples.md`
- Error catalog: `Docs/Error-catalog.md`
- Webhook catalog: `Docs/Webhook-events.md`

Generated HTML/Markdown/client artifacts must be derived from these sources. Generated files are not the source of truth and should not be edited manually.

## Change rule

Any pull request that changes an externally callable HTTP endpoint, request/response DTO, authentication/signing rule, status enum, error code, webhook event, idempotency behavior, pagination/export format, or API-visible feature flag must update the API contract in the same pull request.

The API documentation workflow fails when API-affecting source files change without an accompanying contract/documentation change. This prevents the application from silently moving ahead of its public contract.

## Automatic workflow

`.github/workflows/api-docs.yml` runs on every pull request and push that can affect the API. It:

1. parses and validates the OpenAPI YAML;
2. checks required metadata and stable operation identifiers;
3. checks local `$ref` targets;
4. checks for duplicate `operationId` values;
5. scans Spring controller mappings and reports paths absent from OpenAPI;
6. applies a documented allowlist for browser-only, legacy, provider-callback, and intentionally internal routes;
7. requires an OpenAPI/docs change when API-affecting code changes;
8. generates a browsable HTML reference and uploads it as a CI artifact;
9. records the repository commit SHA in the generated reference metadata.

The workflow does not invent API semantics. Intentional endpoint changes still require the developer making the code change to update the contract in the same pull request. CI makes that requirement automatic, so no separate documentation request is needed.

## Contract-first expectation

For new public APIs, update the OpenAPI path/schema first or in the same commit as the controller implementation. The contract must contain:

- stable `operationId`;
- summary and description;
- authentication/security requirements;
- path/query/header parameters;
- request body schema and example;
- all expected success and error responses;
- idempotency behavior for retryable writes;
- asynchronous status semantics where applicable;
- `x-risk-level` for AI/automation consumers;
- `x-requires-human-approval: true` for high-impact operations such as payouts, refunds, finance close, and secret rotation.

## Release gate

A client-facing release is documentation-ready only when:

- backend tests pass;
- OpenAPI validation passes;
- controller/OpenAPI drift check passes or every reported route is explicitly allowlisted with a reason;
- examples remain valid;
- generated HTML artifact is produced;
- deployment verification is green for the release candidate.

## Versioning

Backward-compatible additions increment the documentation minor version. Breaking changes require a new API version or an explicitly approved deprecation/migration plan. Legacy v1 behavior must not be changed silently.

## Security

Do not place private keys, provider secrets, callback secrets, production tokens, real customer identifiers, or unmasked PII in OpenAPI examples or generated documentation.

## Merchant portal, admin portal, public web and portable exports

All affected application surfaces must ship together with their contracts. Every PR must assess the merchant portal, admin portal and public website; update changed capabilities, forms, validation, rate display, permissions and truthful public descriptions in the same PR. Do not expose admin-only APIs through public pages or merchant downloads. The existing delivery-parity and brand gates remain mandatory.

Owning contracts are listed in `Docs/Api/README.md` and `scripts/api_docs/build_portal_reference.py`; the legacy CPay contract alone does not own the whole application. The portal's private OpenAPI JSON and copied guide are generated and checked for exact freshness. Full system OpenAPI is generated from the running backend and requires an admin portal session.

The API workflow runs for every PR and every push to main, sandbox and production, including portal, configuration and policy-only changes. It builds `Cito-Gateway-API-Reference.html`, the matching OpenAPI JSON and integration guide from the checked-out revision, together with `source-commit.txt`. Download the artifact named for the accepted commit. Static files already downloaded cannot update themselves; regenerate and replace them when delivering a newer revision. CI checks generation and structure; reviewers must still check semantic accuracy, authorization, billing behavior and applicable policies.

Regeneration: `python3 scripts/api_docs/build_portal_reference.py`, then `python3 scripts/api_docs/build_portal_reference.py --check`, then `python3 scripts/api_docs/build_offline_reference.py build/api-docs/Cito-Gateway-API-Reference.html`. Use the current brand pointer. Never claim that a generated artifact proves a deployment or provider certification.
