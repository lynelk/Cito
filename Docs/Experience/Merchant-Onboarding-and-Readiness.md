# Merchant Onboarding and Readiness

## Objective

Cito uses one merchant activation lifecycle from account creation to monitored production activation. The onboarding experience must show that lifecycle coherently across merchant and administrator portals instead of reconstructing readiness independently on each screen.

`MerchantOnboardingReadinessService` is a read model over existing authoritative stores. It does not introduce a second onboarding workflow.

## Authoritative ownership

| Readiness area | Authoritative source |
| --- | --- |
| Overall activation status/current step/next action | `merchant_activation_lifecycles` |
| Step completion and blockers | `merchant_activation_steps` |
| Sandbox certification | `sandbox_certification_runs` |
| Product access | `cito_service_catalog` and `cito_service_entitlements` |
| Go-live request | `merchant_go_live_requests` |
| Controlled production rollout | `merchant_rollout_stages` |

Lifecycle mutations remain owned by `MerchantActivationLifecycleService`. Entitlement changes remain owned by `CitoEntitlementService`. The onboarding read model must never bypass these boundaries.

## Unified readiness response

`GET /api/v2/merchants/{merchantId}/onboarding` returns:

- lifecycle reference, current stage, next action and activation timestamp;
- required-step completion count and percentage;
- ordered lifecycle steps;
- actionable blockers with responsible party and guidance;
- sandbox configuration/testing and latest certification result;
- every active Cito service with sandbox and production entitlement state;
- latest go-live request status;
- current progressive rollout controls;
- a derived `readyForProduction` indicator.

The endpoint is available to administrators and the merchant that owns the requested merchant ID. Merchant scope is checked from authenticated context before the read model is loaded.

## Readiness semantics

A required lifecycle step counts as complete when its status is one of:

- `COMPLETED`
- `WAIVED`
- `WAIVED_LEGACY`
- `SKIPPED`

A blocker is any step in:

- `BLOCKED`
- `FAILED`
- `NEEDS_RESUBMISSION`

`readyForProduction=true` when the merchant is already `LIVE`, or when all of the following are complete and no blocker remains:

1. KYB review;
2. risk review;
3. commercial approval;
4. integration testing;
5. provider certification;
6. settlement configuration;
7. go-live approval.

This indicator is advisory/read-only. It does not activate production. Production activation must still pass the existing go-live and rollout controls.

## Product coherence

The products section is generated from the central Cito service catalog and entitlements. Each product reports its sandbox state and production state from the same entitlement records used for authorization.

Typical states include:

- `NOT_ENABLED`
- `REQUESTED`
- `APPROVED`
- `ACTIVE`
- `SUSPENDED`
- `REVOKED`

The portal should use these states rather than inventing labels from UI-local configuration. A service visible in sandbox is not automatically production-enabled.

## UX presentation requirements

Merchant and administrator portals should present onboarding in this order:

1. **Where you are:** lifecycle stage, completion percentage and current responsible party.
2. **What needs attention:** blockers and the single next action.
3. **What is ready:** sandbox test status and product/service access.
4. **What remains before live:** provider, settlement and go-live evidence.
5. **Production controls:** rollout stage and enabled money-movement operations.

Avoid showing all fifteen lifecycle steps as equally urgent. Completed stages should collapse; the current step and blockers should receive the strongest visual emphasis.

## Security and privacy

The readiness endpoint returns operational status and configuration state only. It must not include provider secrets, API credentials, private keys, customer PII or raw KYC evidence.

## Definition of done for onboarding changes

A future onboarding change is complete only when:

- the canonical lifecycle or owning subsystem is updated;
- the unified readiness projection reflects the change;
- merchant scope remains enforced;
- tests cover the new state;
- API documentation and portal copy are updated together;
- production activation cannot be achieved by UI state alone.
