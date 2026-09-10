# Cito Engineering Standards Index

The following documents are normative for new development and production changes:

1. [Cito Platform Engineering Standard](Architecture/Cito-Platform-Engineering-Standard.md) - domain ownership, financial invariants, tenant isolation, provider boundaries, asynchronous consistency, security, schema and release rules.
2. [Cito API Lifecycle and Contract Standard](Api/Cito-API-Lifecycle-and-Contract-Standard.md) - versioning, authentication, tenant scope, validation, idempotency, error contracts, money/currency, callbacks and OpenAPI requirements.
3. [Cito SLO, Incident and Service Maturity Standard](Operations/Cito-SLO-Incident-and-Service-Maturity-Standard.md) - service classes, SLIs/SLOs, error budgets, incident response, maturity levels, backup/restore and release evidence.

Existing ADRs, operational guides and provider contracts remain applicable. Where an older descriptive document conflicts with a normative standard, the normative standard governs unless a newer approved ADR explicitly supersedes it.
