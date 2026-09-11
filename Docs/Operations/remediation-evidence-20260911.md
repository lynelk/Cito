# Canonical remediation evidence — 11 September 2026

Umbrella issue #195. Brand version 1.2. This record is a timestamped evidence snapshot, not closure of every recommendation or authorization to bypass release gates.

## Implemented and tested candidate

Review candidate 258b93dfe2775c1b9a1e6e47417df155e4f89ece was published by run34617958326, job103324492234, after all assembly checks passed. Source base is main de3011367b67eac2cad65e508e72966a3f70b5ea. The generated application source contains no composition scripts or alternate Airtel financial pipeline.

The actual full Maven verification recorded1159 tests, zero failures/errors and one default non-MySQL skip. The explicit populated real-MySQL migration/financial scenario subsequently passed with no skip. The separate Docker-backed ledger, billing and recovery suite recorded33 passing tests with no skips. Frontend typecheck, lint,293 tests across53 files and production build passed. API reference generation/check/tests and brand mirror check passed. Artifact10271323103, SHA256 2f82933b4062fc7324960fa9af9a6c20a3618304d7f3df61fb2fe574188c6722, preserves source and reports. These are candidate tests; a subsequent commit and PR must still pass their own exact-head release workflows.

A permanent Canonical recovery invariants workflow now checks populated migration and canonical financial rollback against MySQL8.4 and9.4, and real competing-worker/billing tests on the Docker-capable8.4 lane. Adding the workflow does not assert either new run has already passed. The new authenticated staging harness extends the previously accepted fixed authentication/CSRF/tenant test adapter; it checks schemaV129, merchant/admin assessment parity, cross-tenant denial, deliberate admin scope selection, responsive readiness and the non-money developer quickstart. It must be executed on the final merged revision, then its temporary fixture sessions/privileges and worker references must be cleaned up.

## Why old Airtel proposals are not merged verbatim

The first reconciled proposal e8a124c1 passed limited composition tests, but source review found it would reject native production Airtel requests already accepted by the current canonical service. It also introduced a parallel journal and a generic HTTP4xx finality shortcut. Neither behavior is accepted. The replacement retains canonical native/compatibility submission, immutable account attribution, BigDecimal fee helpers and atomic ledger/treasury/billing/outbox. It adds fenced90-second recovery claims, bounded authenticated lookup, callback hints, strict runtime isolation, provider-scoped operations and evidence-derived readiness. Existing V1–V128 migrations remain unchanged; only additiveV129 is introduced.

## SMTP controlled check

The existing production diagnostic worker verified mail.coresynergi.es DNS/TCP/TLS/SMTP on465 and587. Next, it performed exactly one separately logged authentication and email submission using the application's existing configured credentials in memory. Test reference CITO-195-EMAIL-20260911-01. Runtime deployment f81d6d59-9b84-4927-baec-2f3d29b68657 recorded authenticationPASS at2026-09-11T15:41:38.831Z and SMTPacceptancePASS at15:41:39.118Z. The only recipient was the existing owner's administrator address, masked l***@gmail.com. It contained no OTP, password, payment instruction or customer data. Database writes0; no retry was attempted after acceptance.

This proves sibling-worker SMTP authentication and submission, not the application workflow or inbox arrival. A narrowly scoped Gmail lookup of the exact test subject failed because the connected mailbox token could not be decrypted. Inbox delivery therefore remains unverified. Original intermittent application TIMEOUT is not claimed solved solely by this result.

After the single test, temporary worker database/SMTP references and send marker were blanked. The worker command was made inert with restartNEVER. Cleanup deployment8ed131bd-25d9-46d1-b3be-92b32bb13b8c reportedSUCCESS and printed the inert message at2026-09-11T15:42:18.793Z. Production frontend, backend and MySQL were not deployed, restarted or modified by these checks.

## Outstanding production and external conditions

Main and staging were atde301136 before this candidate; production remained56811e29 on schemaV125. Earlier de301136 authenticated staging acceptance is real but cannot certify newV129/application behavior. The old unexplained Railway staging patch had already been reconciled; it must not be reported as still open without new drift evidence.

The latest bounded native production backup request returned a timeout without a completed backupID. Do not promote the schema upgrade until an actual completed fresh backup is verified. A failing or unavailable connector is not backup evidence. Preserve existing exact-head review/CI/staging/promotion conditions; do not force branches or alter migration history.

Provider credentials/certification, full GnuGrid async authentication specification, governance owner evidence review and commercial design-partner/experiment outcomes remain separately owned external work. Code containment, a plan, a unit test and a real provider certification are distinct. Remaining recommendations are not labelled completed merely because they were specified or scaffolding exists.
