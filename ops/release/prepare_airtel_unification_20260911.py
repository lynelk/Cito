#!/usr/bin/env python3
"""Assemble a review-only Airtel candidate from pinned source changes.

Never deploys, changes release refs, edits applied migrations, uses provider
credentials or chooses a side of a merge conflict. The caller publishes only a
new review branch after inspecting the result and running the usual gates.
"""
from pathlib import Path
import json
import re
import subprocess

MAIN = "de3011367b67eac2cad65e508e72966a3f70b5ea"
RECOVERY = "ae17f944f25caa41d1090194fd1681c3cc5ee53d"
NOTICE = "292bccb6ec69b71199eb30eaa590fa9fa86cff2a"
BACKEND = "InitializrSpringbootProjectFresh/"
MIGRATIONS = BACKEND + "src/main/resources/db/migration/"


def git(*args: str) -> str:
    return subprocess.check_output(["git", *args], text=True).strip()


def apply(source: str, paths: list[str]) -> None:
    base = git("merge-base", MAIN, source)
    patch = subprocess.check_output(["git", "diff", "--binary", base, source, "--", *paths])
    if not patch:
        raise RuntimeError("Pinned source produced no selected changes")
    result = subprocess.run(["git", "apply", "--3way", "--index", "--whitespace=error"], input=patch)
    if result.returncode:
        conflicts = git("diff", "--name-only", "--diff-filter=U")
        print("UNRESOLVED_SOURCE_CONFLICTS", conflicts, flush=True)
        # Code-only conflict evidence. No runtime settings or customer data are read.
        subprocess.run(["git", "diff", "--cc"], check=False)
        raise RuntimeError("Manual source conflict review required; no side was selected")


def main() -> None:
    if git("rev-parse", "origin/main") != MAIN:
        raise RuntimeError("Main advanced: rebuild the candidate on the new baseline")
    migration_files = sorted(Path(MIGRATIONS).glob("V*__*.sql"))
    versions = [int(re.match(r"V(\d+)__", p.name).group(1)) for p in migration_files]
    if len(set(versions)) != len(versions) or max(versions) != 128:
        raise RuntimeError("Expected unique immutable baseline migrations through V128")
    old_hashes = {str(p): git("hash-object", str(p)) for p in migration_files}
    changed = git("diff", "--name-only", git("merge-base", MAIN, RECOVERY), RECOVERY).splitlines()
    selected = [p for p in changed if (
        p.startswith(BACKEND + "src/main/java/")
        or p.startswith(BACKEND + "src/test/java/")
        or p.startswith("clientside/src/")
    ) and not p.endswith("/FlywayMigrationSmokeTest.java")]
    apply(RECOVERY, selected)
    notice_paths = [
        "clientside/src/components/modules/merchant/MerchantModuleTransactions.jsx",
        "clientside/src/components/modules/merchant/PaymentRecoveryNotice.jsx",
        "clientside/src/components/modules/merchant/PaymentRecoveryNotice.test.jsx",
    ]
    apply(NOTICE, notice_paths)
    migration = subprocess.check_output([
        "git", "show", RECOVERY + ":" + MIGRATIONS + "V123__airtel_recovery_cursors.sql"
    ], text=True)
    new_migration = Path(MIGRATIONS + "V129__airtel_recovery_provenance_and_cursors.sql")
    if new_migration.exists():
        raise RuntimeError("V129 is already allocated")
    new_migration.write_text(migration, encoding="utf-8")
    smoke = Path(BACKEND + "src/test/java/net/citotech/cito/FlywayMigrationSmokeTest.java")
    content = smoke.read_text()
    expected = 'assertEquals("128", latestSuccessfulVersion(connection));'
    if content.count(expected) != 1:
        raise RuntimeError("Current-main migration smoke contract changed")
    content = content.replace(expected, 'assertEquals("129", latestSuccessfulVersion(connection));')
    content = content.replace("populated V126-to-V128 upgrade", "populated V126-to-V129 upgrade")
    content = content.replace("The V126 upgrade must execute V127 and V128", "The V126 upgrade must execute V127 through V129")
    smoke.write_text(content)
    for path, expected_hash in old_hashes.items():
        if git("hash-object", path) != expected_hash:
            raise RuntimeError("Applied migration changed: " + path)
    # No second journal or parallel finalizer is imported. The current transaction/
    # treasury records, immutable attribution, durable cursors and DB row locks
    # remain the one recovery model. Merchant guidance from #185 is retained.
    note = Path("Docs/Operations/airtel-recovery-consolidation-20260911.md")
    note.parent.mkdir(parents=True, exist_ok=True)
    note.write_text("""# Airtel recovery consolidation

Date: 11 September 2026. Brand version: 1.2. Parent issue: #195.

## Design decision

This candidate reconciles the canonical payment/treasury recovery and operations UI from PR #184 onto current main and retains the merchant pending-state notice from PR #185. It intentionally does not install two competing recovery schedulers/journals or replace current-main orchestration with the alternative submission path. Existing payment and treasury records remain authoritative; immutable provider-account attribution and persisted scan cursors are supporting evidence, not a second balance system.

The original proposals remain historical review evidence until this replacement passes all required gates. They must only be closed as superseded after acceptance, not described as merged. Source commits: #184 ae17f944f25caa41d1090194fd1681c3cc5ee53d; #185 292bccb6ec69b71199eb30eaa590fa9fa86cff2a. Main baseline: de3011367b67eac2cad65e508e72966a3f70b5ea.

## Recovery invariants

Recovery looks up the original submitted reference using the recorded credential source/application identity. A changed application, merchant, endpoint, currency or missing historical provenance cannot silently resolve an old payment. Same-application secret rotation does not rewrite provenance. There is no automatic financial resubmission and no final outcome inferred from elapsed time or a callback assertion. Authentication failures and ambiguous outcomes stay unresolved.

Scan progress is persisted. A database-backed scheduler lease prevents normal overlapping scans; row locks, conditional terminal transitions and canonical transactional ledger/statement/outbox operations protect financial effects even across retries or lease expiry. Shared references must be unique for the original treasury account and operation. Merchant and admin surfaces explain that pending does not mean success or failure and must not encourage duplicate submission.

No new Airtel provider callback endpoint is introduced. Callback loss is handled through authenticated status polling. No native-production orchestration bypass or invented provider signature is added. Real operator callback registration, credentials and certification remain externally verified dependencies.

## Migration and continuity

V129 adds provenance and cursor tables only. Every existing V1-V128 migration remains byte-for-byte unchanged. The migration does not backfill guessed identities, rewrite payments, copy credentials or move balances. Historical records without provenance remain for controlled original-account reconciliation.

Run full Maven/Spotless, Docker/MySQL financial and reservation concurrency tests, complete migration smoke/populated upgrade, merchant/admin frontend tests, API/doc generation checks, brand/parity/security/governance gates and exact-revision staging UAT. Retain database backup evidence before production migration. Never restore a production backup or alter historical migration checksums to make the release pass.

## Release boundary

This document records a candidate design, not live certification or completed deployment. Existing approval, provider readiness, tenant isolation, financial idempotency and release gates stay in force. No live provider is enabled by the preparation workflow, and no provider credentials, runtime database or production deployment are accessed by it.
""", encoding="utf-8")
    guide = Path("Docs/Api/Cito-Gateway-Integration-Guide.md")
    section = """

## Airtel recovery and pending outcomes

For the enabled canonical Airtel payment and shared-treasury paths, status recovery preserves the original reference and provider-account attribution. Do not create a replacement payment solely because a request timed out or a callback did not arrive. Finalization requires an authenticated terminal provider result and the platform's original merchant, currency, amount and accounting controls. Missing or conflicting historical provenance requires controlled reconciliation; it is not guessed from the currently selected account.

The merchant transaction view explains pending/uncertain states. Authorized administrators can inspect the provider-scoped Airtel operations surface. This documentation does not certify operator callback registration, enable a provider, create a callback signing contract or establish production readiness. Consult `Docs/Operations/airtel-recovery-consolidation-20260911.md` for migration and acceptance requirements.
"""
    guide.write_text(guide.read_text() + section, encoding="utf-8")
    public = Path("clientside/src/components/PublicApiOverview.tsx")
    public_text = public.read_text()
    anchor = "const topics = ["
    if public_text.count(anchor) != 1:
        raise RuntimeError("Public API topics structure changed")
    public_text = public_text.replace(anchor, anchor + "\n  ['Payment recovery', 'Pending or timed-out payments need status checks against the original reference. Do not submit a replacement payment while confirmation is uncertain.'],", 1)
    public.write_text(public_text)
    root_note = "\n\n## Airtel recovery release note\n\nThe current-main consolidation and additive V129 recovery migration are described in `Docs/Operations/airtel-recovery-consolidation-20260911.md`. Applied migration history is unchanged. Provider certification and production promotion require their separate evidence; this note does not enable a payment provider.\n"
    for name in ("Readme.md", "Installation.md", "Deployment.md", "CI_CD_SETUP.md"):
        path = Path(name)
        path.write_text(path.read_text() + root_note)
    manifest = {
        "baseline": MAIN, "canonical_recovery_source": RECOVERY,
        "merchant_notice_source": NOTICE, "selected_recovery_paths": selected,
        "selected_notice_paths": notice_paths, "migration": str(new_migration),
        "unchanged_applied_migration_hashes": old_hashes,
        "production_changes": 0, "provider_requests": 0,
        "acceptance": "PENDING_EXISTING_RELEASE_GATES",
    }
    Path("ops/release/airtel-unification-source-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print("ASSEMBLED", json.dumps({"baseline": MAIN, "migration": str(new_migration), "files_from_184": len(selected)}))


if __name__ == "__main__":
    main()
