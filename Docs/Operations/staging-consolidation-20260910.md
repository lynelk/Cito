# Staging consolidation and reservation safety — 10 September 2026

Brand baseline: Cito 1.2. Backend and operational documentation only; no UI artwork, routes or provider activation changes.

## Accepted source and preserved work

Consolidation starts from main `9b3198eeda24ef8887d5e48422c55948381f0904`, which includes the managed Airtel/MTN lifecycle from PR #190. Preserve `feature/airtel-money-openapi-hardening-v3` and PR #184 as the source history for the older staging work. Branch history is not a backup of runtime data.

Use the newer Cito Staging project `c69c90a9-ab6f-48ff-8e04-1e10a10f92db`; its existing environment is `efde4ddb-0312-48bc-94b2-a096fd3678b0`. The older duplicate is project `20b66160-463b-4e8e-8909-eaf5291cde40`, environment `075f2585-7285-46dd-b09b-df3fd40de63e`. Railway labels both environments production, despite their separate staging projects. Verify IDs, source SHA, application configuration and preserved data before retiring resources. These identifiers and this code change do not prove that anything was moved, stopped or deleted.

`ops/environments/cito-environments.json` records the retained project's separate project/environment IDs and both application service IDs. Its existing main-tracking policy remains the desired configuration; `PLANNED` is retained because runtime acceptance is incomplete. At inspection, both services were pinned to sandbox commit `9b3198eeda24ef8887d5e48422c55948381f0904`, the backend release failed, and the frontend release succeeded. Remove the persistent source pin and implement the intended main-tracking source only after resolving the database and staged-variable blockers; verify both deployed SHAs. Continue Git release promotion through main → sandbox → production.

The direct Railway status API reported 35 staged variable changes in the retained environment and six in the old environment. OAuth withheld their values. The agent cannot apply only selected changes from these patches, create an additional environment, stop a deployment or delete an entire project. Its attempted zero-replica update was rejected because the supported configuration requires at least one replica. No stop, deletion, database move or new database creation was completed during this consolidation review. Review pending variables and preserve required runtime data through supported Railway controls before continuing.

## Selective integration

The older branch contains a necessary MySQL reservation repair that main lacked. An enclosing REPEATABLE READ transaction can retain a balance snapshot from before another reservation commits. Acquiring the merchant/currency serialization lock alone does not refresh that snapshot. The repair locks the scope before looking up an existing reservation and uses current locking reads for both posted liability and active reservations during funding decisions. Read-only balance reporting keeps its ordinary read behavior.

The deterministic regression establishes an old snapshot, then reduces 100,000 synthetic available funds to 20,000 from another transaction using either an 80,000 reservation or balanced posted debit. A subsequent 80,000 request must be rejected through both single and batch reservation paths. Only the original reservation, if any, may remain, with 20,000 available. The scenario runs in both the Docker ledger test and the existing MySQL migration/payment lifecycle CI gate. No provider request is made.

The older Airtel scheduler, cursor tables and credential fingerprint hooks are superseded for newly submitted managed payments by `MobileMoneyExecutionService` and `MobileMoneyRecoveryService`. Those services persist encrypted credential attribution before submission and reconcile authenticated Airtel/MTN status through one canonical outcome transaction. Do not install a second recovery worker. Historical transactions lacking the new execution record still require independently controlled reconciliation; current credentials must not be assumed to belong to the original payment.

Preserve the older branch's Airtel-scoped treasury page, provider filtering helpers, no-money readiness utilities and dated diagnostic evidence for selective follow-up. The generic treasury console on main already has newer credential revision and verification controls. Copying the old console wholesale would lose those controls; the old operational report also contains historical deployment and readiness statements. Its extra Docker validation workflow should be assessed against current CI before adopting it.

## Flyway boundary

Two unrelated feature branches allocated V123:

- `V123__airtel_recovery_cursors.sql` on the older Airtel branch creates recovery cursors and credential attribution.
- `V123__api_endpoint_access_rates.sql` on `feature/api-reference-endpoint-billing` defines endpoint billing access/rates and was reported applied in the newer staging database.

Main at the consolidation base ends at V126 without either V123 file. These scripts are not interchangeable. Adding the Airtel V123 cannot resolve a database that applied the endpoint-billing V123. This repair adds no migration and does not change Flyway validation, applied checksums or schema history. Any retained database must remain compatible with its actual applied history; resolve data preservation and schema compatibility before promotion.

Promote reviewed changes through feature → main → sandbox → production. Verify the deployed commit and health after promotion, preserve unrelated staged infrastructure changes, and obtain backup/runtime acceptance evidence. Provider credentials, certification and production rollout remain separately verifiable facts.
