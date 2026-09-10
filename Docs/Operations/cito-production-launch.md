# Cito production launch checklist

## Purpose

This checklist covers the Cito public gateway and account-access launch. It distinguishes controls implemented in software from decisions and certifications that must be completed by authorized people or external providers.

## Canonical Railway target

All Cito Railway operations, diagnostics, deployments, verification, domain work, logs, configuration reviews and incident response **must use this target unless this section is deliberately changed through a reviewed repository change**:

- Railway project: `Cito`
- Project ID: `8d361df2-d17e-4d15-984e-435735f22f6c`
- Production environment ID: `bec50941-04c7-426d-8bc3-883cbdece892`
- Canonical backend service: `cito-backend` (`ba6fd4b7-e61d-48d1-987d-7f6dc56c1e84`)
- Canonical frontend service: `cito-frontend` (`fc3dae0c-f602-4242-b66f-9ba7e1491f1f`)
- Canonical database service: `MySQL` (`26c20665-9b31-47b6-ac2c-a984c9b0c6d4`)
- Public custom domain: `cito.coresynergi.es`, attached to `cito-frontend` on port 8080
- Backend network posture: private Railway service; public API traffic reaches it through the frontend/reverse-proxy path

Operational rule: never infer or reuse a Cito Railway target from an old ticket, chat, branch, deployment note or historical incident. Resolve the project by name, confirm the project ID above, and then use the canonical environment/service IDs above. Any other Cito Railway project ID is invalid until this document is formally updated.

## Implemented application controls

- `/login` is the single public Cito sign-in gateway.
- Merchant credentials are delegated to the established merchant authenticator and merchant session.
- Platform, administrator, operations, finance, compliance, support, and other approved internal credentials are delegated to the established platform authenticator and platform session.
- `/portal` is retained only as a compatibility redirect to `/login?realm=platform`.
- `/signup` is the single public Cito onboarding gateway.
- Merchant applicants continue through the established merchant self-service onboarding, email verification, approval, sandbox, and production-enablement lifecycle.
- Privileged users do not self-register into privileged roles. They submit a request to `/api/public/access-requests`.
- Privileged access requests are persisted with `PENDING` status only. The public endpoint cannot create a user, set a password, grant a permission, assign a role, approve a request, or activate production access.
- Privileged request types are server-side allowlisted to Administration, Operations, Finance, Compliance, Partner, and Other approved access.
- Public access requests are protected by the shared database-backed rate limiter, keyed by both requester email and source IP.
- Duplicate pending access requests for the same email and access type are suppressed and return the same generic accepted response to reduce enumeration signals.
- Input length, email, access-type, and business-reason validation is enforced server-side.
- Only a SHA-256 digest of the source IP is retained with the request record for abuse investigation; the raw IP is not stored by this workflow.
- Existing login controls remain in force, including the current password hashing, login throttling, MFA, session-fixation protection, session handling, and downstream authorization controls.
- Public Cito and authentication surfaces use the Core-Synergies copyright notice.

## Deployment sequence

1. Confirm the Railway project, environment and service IDs against the canonical target section above before any operational action.
2. Merge the reviewed change set only after automated build, typecheck, unit-test, and security/dependency gates are green.
3. Deploy the database migration before or together with the backend release. Flyway migration `V83__cito_access_requests.sql` is additive.
4. Deploy backend services and verify `/api/public/access-requests` returns HTTP 202 for a valid request and HTTP 429 after the configured shared rate limit is exhausted.
5. Deploy the client and verify `/`, `/login`, `/signup`, `/portal`, `/verify-email`, `/dashboard`, and `/dashboardMerchant` routing through the production reverse proxy.
6. Verify merchant login, platform login, MFA paths, password-reset paths, email verification, logout, and session expiry using non-production test accounts in the target environment.
7. Verify a privileged access request appears as `PENDING` in the production database and does not create an account or role.
8. Complete the human and external launch gates below before public production activation.

## Human or external launch gates

These items cannot be truthfully completed by application code or CI and remain release blockers until the responsible party records approval:

1. **Privileged-account approval:** verify identity, employment or partner relationship, business need, least-privilege role, and separation-of-duties requirements before provisioning each requested privileged account.
2. **Payment-provider production certification and credentials:** complete any outstanding MTN MoMo, Airtel Money/OpenAPI, Safaricom M-Pesa, Yo! Payments, banking, or other provider certification and production credential issuance.
3. **Production-like staging migration and UAT acceptance:** run migrations and critical workflows against representative data and record business acceptance.
4. **Real-provider callback/webhook verification:** confirm externally delivered production callbacks, signatures, retry behavior, allowlists, and reconciliation references end to end.
5. **Finance and reconciliation sign-off:** confirm settlement, fees, balances, exception handling, ledger/reconciliation outputs, and operational ownership.
6. **Independent security review:** complete penetration testing or equivalent independent review, resolve launch-blocking findings, and record risk acceptance for any residual findings.
7. **Compliance, legal, and regulatory approval:** confirm the production operating model, KYC/KYB, privacy, data retention, sanctions/AML obligations, licensing, contractual requirements, and applicable jurisdictional obligations.
8. **Production secrets and infrastructure authorization:** provision real secrets through the approved secrets manager, validate key rotation and recovery, approve infrastructure changes, and restrict operator access.
9. **Monitoring and incident-response sign-off:** assign on-call ownership, verify alert delivery, dashboards, logs, backups, restore procedures, incident contacts, escalation, and launch-day coverage.
10. **DNS, TLS, and public cutover authorization:** verify the production hostname, certificate chain, redirects, proxy/header configuration, caching, and rollback plan before directing public traffic to the release.

## Launch decision

Cito is technically releasable only when automated gates are green and every applicable human/external gate above has a named owner, dated evidence, and explicit approval. A deployment succeeding is not equivalent to a production launch being approved.
