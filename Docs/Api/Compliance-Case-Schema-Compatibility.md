# Compliance Case Schema Compatibility

## Purpose

`GET /api/v2/admin/compliance/cases` is a read-only administrator projection over Cito's canonical `compliance_cases` store. The public API contract remains stable even though the production table originated from the earlier V9 compliance migration.

## Production schema boundary

V9 created `compliance_cases` with the operational columns `case_status`, `entity_type`, `entity_id`, `source_reference`, and `created_at`. V54 later described a richer shape using `status`, `subject_type`, `subject_reference`, and `opened_at`, but its `CREATE TABLE IF NOT EXISTS` statement could not evolve an already-existing V9 table.

The listing endpoint therefore projects the authoritative V9 fields into the established API response names:

| Stored field | API projection |
| --- | --- |
| `case_status` | `status` |
| `entity_type` | `subject_type` |
| `entity_id` | `subject_reference` |
| `source_reference` | `transaction_reference` |
| `created_at` | `opened_at` |

Fields that do not exist in the canonical legacy table are returned as `null` or `false` placeholders only where the existing API response shape expects them. No synthetic compliance decision, merchant identity, hold state, or case title is fabricated.

## Query and filter rules

- Status filtering uses `case_status`.
- Subject-reference filtering uses the string form of `entity_id`.
- Ordering uses `created_at DESC`.
- The endpoint must return a valid empty/result response for normal requests rather than fail because newer descriptive schema columns are absent.

## Ownership

`ComplianceCaseService` remains the operational owner of case status and risk-case creation and continues to use the V9 field names. This compatibility projection deliberately does not introduce a second `status` column or another compliance source of truth.

A future schema convergence must be implemented as an explicit forward-only migration that preserves existing case records and updates all readers/writers atomically. Editing historical Flyway migrations or creating parallel status fields is prohibited.
