# Platform Evidence Scorecard API

**Status:** Administrator control-plane contract  
**Endpoint:** `GET /api/v2/admin/platform-evidence/scorecard`

## Purpose

The platform evidence scorecard exposes factual maturity evidence already stored in Cito. It is deliberately read-only and does not turn targets, configuration, commercial intent or missing evidence into achieved production claims.

The response always declares:

- `evidenceBasis: DURABLE_RECORDS_ONLY`;
- `targetsReportedAsActuals: false`.

## Authorization

The endpoint requires an authenticated administrator context. Merchant users and other non-administrator principals are rejected with the existing administrator authorization boundary. Authentication failures remain governed by the standard Cito session/API security controls.

## Response groups

### `commercial`

Counts durable commercial-programme evidence including Founding 20 participation, active production commercial-package assignments and live Embedded Cito programme records.

### `developer`

Counts active developer projects, production-eligible project environments and recent API-request evidence. These metrics describe recorded developer/API activity; they do not assert merchant adoption when no durable activity exists.

### `adoption`

Counts recent production merchant usage, production commands, live onboarding workflows and approved/live go-live checklists.

### `providerCertification`

Returns grouped required-scenario and approved-scenario counts by provider/channel from the canonical provider-certification requirement and evidence records. A configured provider, credential form or routing rule is not certification evidence.

## Safety and truthfulness

The endpoint:

- performs no provider request;
- moves no money;
- changes no credential, entitlement, tariff or production state;
- does not manufacture evidence when a table contains zero qualifying records;
- preserves provider certification as an independent evidence process;
- keeps commercial targets separate from observed activation, usage, retention and revenue.

The Production Maturity frontend consumes this read model and must tolerate partial or unavailable evidence without fabricating green status.

## Errors

- `401` when the caller is not authenticated under the applicable Cito security boundary;
- `403` when an authenticated caller is not an administrator;
- normal bounded platform error handling applies if durable evidence cannot be read.

## Versioning

This endpoint is part of the Cito v2 administrator control plane. Additive response fields may be introduced as new durable evidence sources become authoritative. Existing field meaning must not be silently redefined to make maturity appear higher.
