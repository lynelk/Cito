# Cito API Documentation

Cito is the platform. CPay is the payments capability within Cito. API documentation is split by security and product boundary rather than pretending every endpoint belongs to one giant payments contract.

## Authoritative contracts

| Contract | File | Audience | Primary authentication | Ownership rule |
| --- | --- | --- | --- | --- |
| CPay Payments API | `Docs/Api/cpay-v2-openapi.yaml` | Server-to-server payment integrations and payment-adjacent compatibility surfaces | CPay v2 request signature or explicitly documented admin/public auth | Owns payment execution and compatibility routes that are not assigned to a more specific Cito contract |
| Cito Platform API | `Docs/Api/cito-platform-v2-openapi.yaml` | Signed-in merchant workspace, platform services, developer control plane and merchant capability APIs | Cito merchant session plus service/environment entitlements | Owns merchant-facing platform and capability routes |
| Cito Merchant Onboarding API | `Docs/Api/cito-onboarding-v2-openapi.yaml` | Merchant and administrator onboarding/readiness clients | Cito merchant/admin session with tenant-scope enforcement | Owns `/api/v2/merchants/{merchantId}/onboarding` and its readiness schema as a specialized Cito Platform sub-contract |
| Cito Admin API | `Docs/Api/cito-admin-v2-openapi.yaml` | Administrator-only Cito operations and control-plane routes | Cito administrator session/RBAC | Owns `/api/v2/admin/**` routes unless a route is explicitly designated as a payment-compatibility alias |
| Cito Billing BaaS API | `Docs/Api/cito-billing-baas-v2.1-openapi.yaml` | Billing/BaaS tenants, merchants and embedded billing integrations | Billing/BaaS tenant/API-key/service-account contract as documented | Owns current Billing BaaS routes and schemas; this is the current versioned source for Billing BaaS |

The dedicated onboarding contract is a specialized Cito Platform sub-contract. For its onboarding path and schemas it is authoritative, not a duplicate compatibility copy. It is validated, linted and published by the API documentation workflow alongside the broader platform contract.

### Compatibility specifications

`Docs/Api/billing-baas-v2-openapi.yaml` and `Docs/Api/cito-billing-baas-v2-openapi.yaml` are compatibility/reference snapshots for earlier Billing BaaS contracts. They MUST NOT override or diverge silently from the current owner contract `cito-billing-baas-v2.1-openapi.yaml`. Any retained overlapping path must either mirror the current contract for its supported compatibility version or carry explicit deprecation/version notes.

If any route appears in more than one OpenAPI file, the ownership table above decides which contract is authoritative. The other occurrence is compatibility/reference only and MUST NOT define conflicting security, status semantics or schemas. New duplicate authoritative definitions are prohibited.

Supporting documentation remains authoritative for behavior that does not belong cleanly inside OpenAPI schemas:

- `Docs/developer-guide.md` - integration and onboarding guide
- `Docs/Api-v2-signing.md` - CPay v2 canonical signing
- `Docs/Webhook-events.md` - webhook contracts
- `Docs/Error-catalog.md` - error semantics and recovery guidance
- `Docs/Api/AUTO_UPDATE_POLICY.md` - documentation lifecycle and CI policy
- `Docs/CITO_BRAND_AND_MODULE_BOUNDARY.md` - platform/module naming boundary
- `Docs/CITO_SECURITY_ARCHITECTURE.md` - layered platform security architecture
- `Docs/Api/Cito-API-Lifecycle-and-Contract-Standard.md` - normative API ownership and lifecycle rules
- `Docs/Experience/Merchant-Onboarding-and-Readiness.md` - canonical onboarding/readiness projection and UX semantics

## Platform API groups

The Cito Platform contract currently includes merchant self-service APIs for:

- service catalog and merchant entitlements;
- coherent merchant onboarding and production readiness;
- developer projects, service accounts, credentials, test events, request logs and readiness;
- intelligent payment routing simulation, policies, rules and decisions;
- marketplace subaccounts, split rules, executions, refund allocations and recovery events;
- recurring plans, mandates, subscriptions and charges;
- merchant-workspace refunds and financial timelines;
- virtual accounts and inbound transfer visibility;
- merchant analytics and recommendations;
- embedded/white-label partner onboarding, delegation, branding and commissions;
- integration marketplace installations, mappings, subscriptions and jobs.

## Documentation quality gate

Every pull request that changes API-facing Java code is checked in two ways:

1. it must include an API documentation change; and
2. every path declared by a changed Spring controller must exist in the appropriate authoritative OpenAPI contract.

The API contracts are parsed, structurally validated and linted by repository workflows. Generated browsable references are build artifacts; they are not source contracts.

## Change rule

If an implementation changes a public request, response, path, authentication requirement, entitlement requirement, asynchronous state, webhook, error condition or security-sensitive behavior, update the owning OpenAPI contract in the same pull request. Compatibility copies must be synchronized or explicitly deprecated. Generated HTML is output, never the source of truth.

## Searchable portal references

Start with [Cito Gateway integration guide](Cito-Gateway-Integration-Guide.md). Merchant Developers provides a private, component-pruned OpenAPI 3.1 projection of the owning contracts. It labels external integration and merchant workspace operations separately. Admin API workbench also provides the full runtime system schema and API access rate controls. The public hero page exposes searchable capability topics only. Generated references never replace the source contracts listed above. See [release notes](API-REFERENCE-RELEASE.md) for migration, authentication compatibility and verification requirements.
