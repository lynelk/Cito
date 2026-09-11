# MTN configuration entry point and release acceptance

Brand version: 1.2. Scope: admin Settings, provider credentials, merchant/admin integration guidance and deployment acceptance. This release is configuration safety and code promotion, not MTN certification or provider activation.

## Operator workflow

Admin Settings -> MTN MoMo now links to `/bo/provider-treasury?channel=mtn_momo#platform-provider-credentials`. The Settings page retains the separate pricing controls but no longer edits MTN connection secrets alongside the governed store. Existing legacy records are preserved; no automatic credential migration or provider activation occurs. Unchanged legacy connection values are not resubmitted when saving general settings.

The governed form starts in Sandbox, uses the official provider API origins, derives Uganda's target and currency, and clears unsaved secrets/callbacks when the provider or environment changes. MTN Sandbox uses `https://sandbox.momodeveloper.mtn.com`, `sandbox`, EUR. Uganda production uses `https://proxy.momoapi.mtn.com`, `mtnuganda`, UGX. The backend MTN schema remains authoritative for API submissions.

Collections and Disbursements each require an API user, its API key and the matching product subscription key. A subscription key is not an API key or portal password. A registered hostname and matching HTTPS callback URL are required. Use Save encrypted credential, then the server-side Verify connection action and independent approval. Authentication alone does not prove payment acceptance, settlement or callbacks. Current verification requires both products; product-only activation remains a separate enhancement, not a completed capability.

Generic settings no longer infer Connected from populated fields, invent request timestamps or expose an informational popup as Test connection. Email settings similarly distinguish configured from verified.

## Release controls

Use the active IDs in `ops/environments/cito-environments.json`. Stage code before production. Keep credentials, data and provider modes isolated. Confirm exact frontend/backend release markers and Flyway history; do not accept unrelated Railway staged changes. The old staged recovery patch is no longer pending at the latest read, but later independent patches must be inspected separately.

Authenticated staging acceptance may use dedicated synthetic QA principals with random credentials, created only in the designated isolated staging database. Exercise normal HTTP login, CSRF, server sessions, merchant/admin boundaries, API reference search/download and provider configuration navigation. Never forge a session or weaken authentication. Suspend QA principals and clear their sessions after the run; retain audit evidence. A production database backup and populated migration validation remain prerequisites for promotion. No live collection/payout/SMS is required or authorized by this code release.

## Evidence

Tests: providerConnectionProfile.test.ts; ModuleSettingsReadiness.test.jsx; existing MTN schema, gateway, financial lifecycle, frontend security, responsive/browser, API/brand/security governance gates. Deployment, authenticated acceptance and production backup evidence must be recorded on the release PR after completion; this document is not such evidence.
