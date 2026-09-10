# Cito Frontend, Backend and Environment Parity Standard

**Status:** Mandatory engineering and release standard  
**Repository of record:** `https://github.com/lynelk/Cito`  
**Canonical Railway project:** `Cito` / `8d361df2-d17e-4d15-984e-435735f22f6c`

## 1. Non-negotiable source-of-truth rule

GitHub is the authoritative source of truth for Cito. Application code, API contracts, database migrations, release workflows, operational runbooks, environment contracts and deployment markers must be committed to `lynelk/Cito`.

No Railway-only application or configuration change may be treated as complete until the corresponding repository state has been updated. Emergency runtime changes are permitted only when necessary to restore service and must be reconciled back to GitHub immediately after containment.

A successful runtime change that exists only in Railway is drift, not completion.

## 2. Frontend/backend parity rule

Cito is one product. `clientside` and `InitializrSpringbootProjectFresh` are two delivery surfaces of the same capability set and must not evolve independently when a change affects merchant or operator behavior.

For every feature, workflow or process change, the pull request must identify whether it affects:

- both frontend and backend;
- backend only with no user-visible/process-flow change;
- frontend only with no API/domain/process requirement change; or
- neither application surface.

A change is considered parity-sensitive when it changes any of the following:

- user-visible capability, state or permission;
- onboarding, activation, transaction, communication, billing, reconciliation, KYC/KYB, provider or administration workflow;
- API request/response semantics or validation;
- lifecycle state or transition;
- business rule, entitlement, feature flag or service availability;
- database state that changes what a user or operator can see/do;
- error, pending, success, empty or degraded-state behavior;
- navigation or process sequencing.

For parity-sensitive work, backend capability must have a corresponding frontend representation where the capability is intended for a UI, and frontend controls must never imply backend capability that does not exist.

## 3. Allowed one-sided changes

One-sided application changes are acceptable only when genuinely non-parity-sensitive, for example:

- internal refactoring with unchanged contracts and behavior;
- test-only changes;
- logging/observability changes with no UI/API semantic change;
- dependency or build-tool maintenance;
- backend-only scheduled/internal jobs with no user/operator surface;
- frontend-only visual refinement with no change to capability, permissions, data semantics or process flow.

The PR must state the reason and evidence. Silence is not an exception.

## 4. Required release invariants

Before merge or deployment:

1. Backend tests, formatting and package/compile gates pass for affected backend code.
2. Frontend lint, type-check, unit/component, production build and browser/responsive gates pass for affected frontend code.
3. Changed controllers/API contracts pass OpenAPI drift validation.
4. Parity declaration matches the actual changed surfaces.
5. A parity rationale is recorded for every application change.
6. Cross-surface features have test evidence for both the backend behavior and the frontend state/workflow.
7. Backend and frontend deployed into the same Railway environment must resolve to the same Git commit SHA.
8. That deployed SHA must be the head of the environment's configured GitHub branch.
9. Railway must have no uncommitted staged configuration after a release is declared complete.
10. `main` and `production` must not be left unintentionally diverged after a completed production release.

## 5. Environment model

### Production

Production remains in the active Railway project only:

- project: `Cito`
- project ID: `8d361df2-d17e-4d15-984e-435735f22f6c`
- environment ID: `bec50941-04c7-426d-8bc3-883cbdece892`
- source branch: `production`
- backend: `cito-backend`
- frontend: `cito-frontend`

Production promotion must be deliberate and gated. Both services must deploy the same accepted `production` commit.

### Staging, when created

Staging should be created as a separate Railway **environment within the same active Cito project**, not as another Cito project. This removes project-ID ambiguity while preserving environment isolation.

The staging model is:

- source repository: `lynelk/Cito`;
- source branch: `main`;
- auto-deploy every accepted `main` commit;
- staging backend and frontend must deploy the same `main` SHA;
- separate staging database;
- separate environment variables and encryption material;
- no production provider credentials, balances, callbacks or settlement destinations;
- synthetic or explicitly approved sanitized data only;
- sandbox/staging provider endpoints where available;
- staging health, migration, browser, API, integration and UAT checks must pass before production promotion;
- production promotion must identify the exact staging-tested SHA.

The preferred lifecycle is:

`feature branch -> pull request -> CI/parity gates -> main -> automatic staging deploy -> staging smoke/UAT -> main-to-production promotion PR -> production -> runtime verification`

No feature branch deploys directly to production.

## 6. Staging readiness requirements

Before creating the staging environment, prepare or confirm:

1. dedicated staging environment in project `8d361df2-d17e-4d15-984e-435735f22f6c`;
2. cloned backend/frontend service configuration with staging-safe variables;
3. staging-only database and backup/restore policy;
4. provider sandbox credentials and callback URLs;
5. staging domain and TLS after the domain is formally approved;
6. seed/synthetic test data;
7. automatic `main` branch deployment;
8. post-deploy `/healthz`, `/readyz` and release-identity checks;
9. frontend/backend SHA equality check;
10. release evidence retained against the GitHub commit/PR.

## 7. Operational drift handling

If runtime state differs from GitHub:

1. identify the exact Railway environment/service and Git SHA;
2. do not mask the mismatch with a redeploy of an old snapshot;
3. restore the correct GitHub source relationship or deploy the exact intended branch head;
4. verify frontend/backend SHA equality;
5. reconcile any required runtime configuration change back into repository-controlled documentation/configuration records;
6. clear staged Railway changes;
7. record the verification evidence.

## 8. Definition of done

A Cito change is complete only when all applicable code, API, migration, UI, documentation and environment-contract changes exist in GitHub; CI/parity gates are green; the intended environment runs the accepted SHA on both frontend and backend; and no undocumented runtime drift remains.
