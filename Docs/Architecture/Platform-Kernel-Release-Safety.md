# Connect Once. Operate Everything. — shared-kernel release boundary

Cito remains one platform above Payments/CPay, Communications, Identity/Risk and Vending. This release extends existing owners rather than introducing a second tenant store, ledger, usage journal, audit writer or event broker. It does not claim that every domain workflow has already migrated onto the facade or that any external provider is certified.

## Shared ownership

`PlatformTenantContextResolver` reads the authenticated portal identity and the existing `cito_organizations` table. It never provisions organizations or entitlements on a read or denied administrator request. Organization provisioning and entitlement changes remain owned by `CitoEntitlementService`. A merchant cannot switch tenant through request headers; the application identifier is derived from the authenticated access channel, not an unverified application header. Administrator cross-tenant access requires both administrator actor type and verified administrator role.

`PlatformEntitlementGateway` delegates to the canonical entitlement service. `PlatformUsageContract` is implemented by the existing `UsageGatewayService`; Communications consumes that contract without changing its canonical delivery metering/idempotency owner. `PlatformServiceRuntime.usage` requires the original occurrence timestamp and a production merchant context. It rejects sandbox usage rather than silently entering production billing. Sandbox simulations remain in the existing sandbox domain; no second billing store is introduced. Domain commands must still enforce their resource authorization, entitlement, idempotency, provider and financial controls.

`PlatformEventOutboxGateway` joins an existing business transaction using MANDATORY propagation. An event cannot be independently committed before its owning business operation succeeds. The existing outbox relay remains the dispatcher and retries every matching idempotent consumer. `PlatformOperationalSignalService` commits its signal event and audit together; failure of either rolls back both.

`PlatformAuditGateway` delegates to `Common.recordAction` and `Common.recordMerchantAction`, the existing canonical audit writers. It does not implement another hash-chain writer. A non-success result fails the surrounding transaction. Summary values are not copied into audit metadata; only bounded resource metadata and field names are retained. Existing audit-store integrity controls continue to apply; this release is not an independent audit-concurrency certification.

`PlatformProviderRegistry` exposes one immutable catalogue across domains while keeping typed provider operations inside each domain engine. Adapter metadata never substitutes for credentials, environment-specific acceptance, live delivery or settlement evidence.

## Acceptance evidence

`PlatformKernelGuardTest` covers read-only tenant resolution, spoofed application/tenant headers, administrator scope, sandbox billing exclusion, original usage timestamps, canonical audit delegation and failed audit writes. `PlatformTransactionBoundaryTest` uses actual Spring transaction proxies and JDBC rollback to prove MANDATORY publication and atomic signal/audit behavior. `PlatformProviderRegistryTest` covers shared provider metadata, domain isolation and immutable capability sets. Frontend evidence tests preserve unavailable/partial states. The exact production MySQL image is separately exercised by the existing canonical migration/recovery matrix and the new evidence read-model regression.

Release acceptance still requires the final immutable PR revision to pass all gates, followed by authenticated no-money staging acceptance and read-only production verification. Main, sandbox and production refs must fast-forward to the same accepted release; both application deployments must report that revision. Temporary source-inspection/formatting workflows are removed before merge. No failed gate is waived by this document.

## Explicit limits

External provider certification, merchant adoption, commercial results, database HA, failover and restore evidence are operational outcomes. Code presence, configured replicas, a declared target or a successful build must never be reported as completion of those outcomes. The existing single-primary database is not relabelled as highly available by this release.
