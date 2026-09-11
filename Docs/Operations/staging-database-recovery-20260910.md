# New staging database recovery — 10 September 2026

Brand baseline: Cito 1.2. Database, tests and operations only; no UI, API, permissions, provider activation or monetary posting changes.

## Scope and preservation

This operation targets only Railway project `c69c90a9-ab6f-48ff-8e04-1e10a10f92db`, environment `efde4ddb-0312-48bc-94b2-a096fd3678b0`. Its environment label is `production`, but it is the separate **Cito Staging** project. Actual production `8d361df2-d17e-4d15-984e-435735f22f6c` is not part of this recovery.

The backend was already running main `4e3d782e57234e6310f19173818397554398a779` against a fresh persistent MySQL database when recovery inspection began. Its startup applied 117 migrations through V126. This was service restoration, not proof that the previous staging data had been recovered.

Original service `4fe51073-a138-4ef3-965b-69245786594e` has no persistent volume. Do not restart, redeploy, delete or add a volume to that running source as a substitute for backing it up. The active persistent service is `8109d27e-5d8c-4ed8-af3e-bf6234e9ca3b`, volume `8f5a439b-6477-4506-9880-60a250900cf6` mounted at `/var/lib/mysql`.

A private one-shot recovery job used Railway secret references, TLS database connections and temporary mode-0600 client configuration files. It emitted only schema metadata, row counts and digests. It never logged credentials, message content or account data.

At 19:00 UTC, full logical snapshots of both original and active application schemas were persisted in `cito_recovery_archive_20260910.snapshots` on the persistent staging database. The original snapshot is 628,038 bytes, SHA-256 `904addcd7c4ece6747ab4aa7497328c4c35e43e922d549ea4e06810bd8e91be0`; the active pre-repair snapshot is 568,010 bytes, SHA-256 `90afb4e416e4e1a68745a7bb66735baa681a6929b8feced27e3c76e4cb5805cf`. Both stored payloads were checked against their digests and byte lengths.

The original dump was restored into the separate schema `cito_staging_preserved_20260910`. The first job imported it successfully but its verification shell lacked `cmp`. A subsequent read-only verification used SHA-256 table-list comparison and MySQL extended table checksums. Deployment `d527c57e-6375-41aa-981d-78d849cde448` verified all **350 tables** at 19:02:58 UTC. The original source and active `cpayadmin` were not overwritten. The preserved schema is an archive, not a second running application or outbox consumer.

These persistent same-instance snapshots protect against loss of the original ephemeral container. They are **not** an independently stored, off-site disaster-recovery backup. Do not claim cross-region resilience or native database HA from this operation.

## V123 boundary

The original database's actual history records `V123__api_endpoint_access_rates.sql`, checksum **-647850599**, successful. Its 195 API endpoint-rate records are preserved in the restored archive. It did not apply the unrelated `V123__airtel_recovery_cursors.sql`.

Main lacks both V123 feature migrations. Do not invent an empty V123, copy the unrelated Airtel file, disable validation, edit checksums or delete Flyway history. The active main-aligned schema has its own valid migration chain, with V123 intentionally absent. Preserved feature-branch tables/rates remain available for a separately reviewed integration; archiving them is not enabling that feature in the active application.

Inspection found no original SMS messages, queued communications, payment transactions, ledger entries, sender identities or provider credential rows. There are four seeded operational merchant accounts, not four newly recovered live customers. The archive retains all remaining configuration and history without guessing which branch-specific defaults should overwrite current main.

## Collation repair: V127

The live V126 backend still failed its MTN shared-recovery selector every minute with MySQL error 1267. `mtn_momo_correlations.provider_reference` uses `utf8mb4_unicode_ci`, while V104 left `provider_treasury_reservations.provider_reference` to inherit MySQL 8's `utf8mb4_0900_ai_ci` default. The anti-join comparing them cannot execute.

V127 aligns only the nullable, non-unique treasury provider-reference column to the correlation registry's established `utf8mb4_unicode_ci`. Length remains 191. It does not update any reference value, monetary amount, status, timestamp, ledger entry, provider credential or migration-history row. V104, V116, V122, V124–V126 are unchanged.

The clean MySQL gate first applies V1–V126, creates a synthetic pending reservation in the dedicated test database, and proves that the actual scheduler selector fails with 1267. It then applies V127, compares every reservation value against the pre-upgrade snapshot, executes both real scheduler selection queries without contacting a provider, and removes the test fixture. Existing ledger reservation, mobile-money and fake-SMS lifecycle scenarios then run unchanged. Flyway validation is rerun.

## Acceptance and rollback

Run repository CI, full Maven/Spotless verification, the clean MySQL/populated upgrade gate, brand and API/documentation gates before merging. Staging automatically tracks main; leave actual production's branch and Railway project untouched. Verify V127 applied, backend health, frontend proxy readiness, deployment SHAs and no recurrence of error 1267 across scheduler cycles. Do not accept the unrelated staged Railway environment patch.

A completed Flyway migration should not be manually removed from history. Preserve snapshot evidence and use a reviewed forward correction for later issues. The one-column collation alignment is compatible with the previous application code, so an application rollback must not roll back data or reconstruct financial history. Keep the original source until preservation and operational acceptance are explicitly reviewed.

Database recovery is not SMSMobilo activation, handset-delivery certification, payment-provider approval or authenticated business UAT.
