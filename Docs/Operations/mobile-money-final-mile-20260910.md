# Airtel and MTN final-mile repair — 10 September 2026

## Scope and release boundary

Backend-only repair against the Cito v1.2 brand baseline. No visual changes. Merchant messages distinguish acceptance, uncertainty, failure and completion. Existing CPAY identifiers, financial precision, ledger and maker-checker controls are preserved. This change does not activate providers, change credentials, initiate real-money transactions or modify database balances directly. No schema migration is added.

## Repairs

- Airtel accepts PEM public keys for the existing RSA PIN-encryption implementation.
- Airtel collection-only credentials do not require disbursement PIN/public key; payouts still require both.
- Airtel 2xx acceptance without a terminal provider status stays pending. Transport uncertainty, duplicate references, throttling, server errors and unsuccessful status reads are not evidence of payment failure.
- Airtel uncertain outcomes preserve the original reference and tell operators to check status, not blindly resubmit. A contradictory success and provider error stays undetermined.
- Airtel and MTN encrypted token-cache keys include the product, endpoint and relevant credential scope, including rotated secrets. Token expiry never exceeds the provider lifetime. MTN refresh locks are bounded.
- MTN collection status checks validate collection credentials only. Initiation and authenticated status lookup use the same canonical v2 token scope.
- The generic timeout scheduler no longer changes Airtel OpenAPI pending transactions to failed solely because time elapsed. Holds remain until authenticated reconciliation.

## Verification

Run from `InitializrSpringbootProjectFresh`:

```sh
mvn -B -ntp spotless:apply
mvn -B -ntp verify
```

The regression suite covers PEM encryption, accepted/pending and uncertain Airtel outcomes, product/client/secret token isolation, short token lifetimes, MTN collection-only status checks and timeout exclusions. Record the actual CI run, commit and results on the PR. This document is not evidence that tests ran or a release reached production.

## Remaining launch gates

1. The follow-on implementation is documented in `airtel-durable-recovery.md`: it adds immutable correlations, fenced polling, callback wake-up hints and transactional finalisation. Its source presence is not release evidence. Historical pending items without reliable credential/environment provenance still require controlled authenticated reconciliation. Never release a hold or resubmit based solely on elapsed time or a callback payload.
2. Obtain successful no-money OAuth checks for each configured product using approved credentials inside the runtime. Keep token values and secrets out of logs and PRs. Check endpoint, UG/UGX scope, TLS verification and callback registration.
3. Run provider-approved end-to-end sandbox cases for success, decline, timeout, duplicate and callback/status reconciliation. Confirm one ledger effect per original transaction. Production certification requires separately authorised controlled transactions; no such transaction is authorised by this repair.
4. Promote reviewed, tested source through feature -> main -> sandbox -> production. Pin the source SHA, preserve unrelated staged Railway changes, verify deployment identity and health, and record rollback evidence. A successful backend health check is not provider certification.

Do not apply unrelated staged infrastructure changes to ship this patch. Do not expose secret values in handoff material.
