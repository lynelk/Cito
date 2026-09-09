# Frontend dependency remediation — 2026-09-09

## Scope

This change remediates the frontend npm audit findings identified during Cito exact-head release verification.

## Findings remediated

- `js-yaml` upgraded from 4.3.1 to 4.3.2, addressing GHSA-2883-xcg3-v3hh (high severity).
- `vitest` upgraded from the 3.x line to 4.1.11, addressing GHSA-82fw-gwwq-j7x9 through the patched `@vitest/mocker` dependency.
- `@vitest/coverage-v8` upgraded to 4.1.11 to keep the Vitest toolchain aligned.

## Compatibility work

Vitest 4 tightened mock function typing. `statementExport.test.ts` now declares typed mocks for `URL.createObjectURL`, `URL.revokeObjectURL`, and anchor `click()` so TypeScript remains strict without weakening application types.

## Verification evidence

The isolated remediation workflow completed successfully after the upgrades and reported:

- `npm audit --audit-level=moderate`: 0 vulnerabilities
- lint: passed (existing warnings only)
- unit/component tests: 256 passed across 39 test files
- TypeScript typecheck: passed
- production frontend build: passed
- coverage run: passed

No application runtime dependency was intentionally upgraded by this remediation. The explicit package-version changes are confined to development/test tooling, while `js-yaml` is a transitive development dependency used by ESLint.
