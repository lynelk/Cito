# Platform Evidence Scorecard API

**Status:** Administrator control-plane contract  
**Endpoint:** `GET /api/v2/admin/platform-evidence/scorecard`

## Purpose

The platform evidence scorecard exposes factual maturity evidence already stored in Cito. It is deliberately read-only and does not turn targets, configuration, commercial intent or missing evidence into achieved production claims. Measured counters declare `evidenceBasis: DURABLE_RECORDS_ONLY` and `targetsReportedAsActuals: false`. Code-level provider definitions are separate metadata, not measured adoption or certification.

## Authorization

The endpoint requires an authenticated administrator context. The portal uses the existing `SESSION` cookie and administrator authorization boundary. Merchant users and other non-administrator principals are rejected. Reading a tenant context never provisions an organization or grants an entitlement. Merchant identity comes from authenticated access, not tenant headers; arbitrary application headers do not establish an application identity.

## Response groups and measurement definitions

### `commercial`

Founding 20 counters report stored programme states. `founding20Active` includes ACTIVE, ONBOARDING and LIVE records. `activePackageAssignments` includes only ACTIVE PRODUCTION assignments whose effective period includes the database observation time; future and expired assignments are excluded. `embeddedProgrammesLive` counts records whose programme state is LIVE. None of these counters is revenue, retention, settlement or proof of external partner usage.

### `developer`

Counts active projects, production-eligible project-environment records and API-request log records. Request counters include sandbox and production activity. The 7-day and 30-day windows use `created_at` between the database observation time minus the stated interval and the observation time; future-dated rows are excluded. `successfulApiRequests7d` counts HTTP responses from 200 through 399, not completed business transactions.

### `adoption`

`merchant_production_usage` stores one idempotent production command reservation/attempt per operation/reference, not a daily aggregate or successful settlement. `productionCommands30d` counts those attempts, and `productionMerchants30d` counts distinct merchants with those attempts. Both use the bounded rolling 30-day `created_at` window. Live onboarding workflows and APPROVED/LIVE go-live checklists remain separate stored-state counters.

### `providerDefinitions`

Returns immutable code-level provider/domain/capability/environment definitions from the common provider registry. Multiple channel adapters can share one provider/domain definition. Capability and environment sets describe adapter metadata; they neither enable a provider nor establish that every capability is certified in every listed environment.

### `providerCertification`

The field name is retained, but the result is **provider-specific historical reviewed-scenario coverage**, not a certification decision. Concrete provider/channel pairs come from canonical evidence and concrete required-scenario records. Wildcard requirements are expanded independently for each concrete pair; duplicate global/specific requirements for the same scenario are counted once. Wildcard evidence groups are never presented as a provider.

An approved scenario needs a matching concrete provider, channel and scenario, status APPROVED, a nonblank approver and an approval timestamp no later than the database observation time. Duplicate evidence does not increase scenario coverage. PASSED/CAPTURED records, missing approvers and future approvals are not approved evidence. Evidence from different providers never combines into one coverage claim.

These records do not by themselves establish current credentials, environment-specific live delivery, settlement, recency or a current provider acceptance. Missing provider-specific records remain missing. The separate canonical certification/readiness process continues to govern activation.

## Safety and truthfulness

No provider requests, money movement, credential changes, entitlement grants, tariff changes or production mutations occur. Missing database results or database failures are errors, not zero counts. A genuine COUNT result of zero is displayed as zero. Targets are never substituted for observed activation, usage, retention or revenue.

The frontend validates the evidence-basis markers, each expected counter and provider coverage shape. Missing, negative, non-integer, unsafe numeric or contradictory values remain unavailable. Partial responses do not acquire a green status; a missing `targetsReportedAsActuals` value is not coerced to false.

## Errors

401 means authentication is required; 403 means the authenticated caller lacks administrator authority. Bounded platform error handling applies when authoritative evidence cannot be read. The API is administrator-only; merchant/public contracts and merchant downloadable references deliberately do not expose this platform-wide read model.

## Versioning and tests

This is a Cito v2 administrator contract. Additive evidence sources must preserve existing metric meanings. The initial unreleased PR was corrected to exclude future assignments, bound observation windows and prevent cross-provider wildcard aggregation before production promotion.

The release includes frontend malformed/partial-response regressions, authenticated-scope unit tests and `PlatformEvidenceMysqlTest`. The latter runs after the full migration chain on disposable MySQL 8.4, 9.4 and the exact production image 9.7.2. Its synthetic fixtures are rolled back; passing software tests is not external provider certification.
