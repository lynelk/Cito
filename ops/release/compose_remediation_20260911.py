#!/usr/bin/env python3
"""Compose a review-only candidate from pinned proposals without overwriting main.

No credentials, infrastructure, provider calls or financial data are accessed.
Unresolved three-way conflicts fail loudly; historical main migrations are immutable.
"""
from pathlib import Path
import json
import re
import subprocess
import tempfile
from remediation_reviewed_conflicts import airtel_dispatch_on_current, resolve_known_hunks

MAIN = 'de3011367b67eac2cad65e508e72966a3f70b5ea'
DURABLE = '292bccb6ec69b71199eb30eaa590fa9fa86cff2a'
RESERVATION = 'ae17f944f25caa41d1090194fd1681c3cc5ee53d'
ROOT = Path(__file__).resolve().parents[2]
MIGRATIONS = 'InitializrSpringbootProjectFresh/src/main/resources/db/migration/'
REPORT = Path('/tmp/cito-remediation-report')
REPORT.mkdir(exist_ok=True)


def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT, text=True).strip()


def contents(ref, path):
    result = subprocess.run(['git', 'show', f'{ref}:{path}'], cwd=ROOT, capture_output=True)
    return result.stdout.decode() if result.returncode == 0 else ''


def merge_source(ref, path, destination=None):
    target = ROOT / (destination or path)
    ancestor = git('merge-base', MAIN, ref)
    base, incoming = contents(ancestor, path), contents(ref, path)
    current = target.read_text() if target.exists() else ''
    if path.endswith('/DoPayGateway.java'):
        target.write_text(airtel_dispatch_on_current(current))
        print('REVIEWED_INITIATION_ONLY', path)
        return
    if path.endswith('/gateway/AirtelOpenApiCredentialSchema.java'):
        # Main has the stronger shared approved-origin/relative-path policy.
        # The older proposal's bespoke URI validator must not replace it.
        if not all(value in current for value in ('ProviderEndpointPolicy.requireOrigin',
                'ProviderEndpointPolicy.requireRelativePath', 'validateForOperation')):
            raise RuntimeError('Current Airtel credential safety contract changed')
        print('PRESERVED_CANONICAL_ENDPOINT_POLICY', path)
        return
    with tempfile.TemporaryDirectory() as tmp:
        files = [Path(tmp) / name for name in ('current', 'ancestor', 'proposal')]
        for file, text in zip(files, (current, base, incoming)):
            file.write_text(text)
        merged = subprocess.run(['git', 'merge-file', '-p', '--diff3', *map(str, files)], capture_output=True, text=True)
    if merged.returncode < 0 or merged.returncode > 127:
        raise RuntimeError('Three-way merge execution failed for ' + path)
    text = resolve_known_hunks(path, merged.stdout)
    if re.search(r'^(<<<<<<<|\|\|\|\|\|\|\||=======|>>>>>>>)', text, re.M):
        conflicts.append(path)
        (REPORT / (path.replace('/', '__') + '.conflict')).write_text(text)
        print('MERGE_CONFLICT', path)
        for hunk in re.findall(r'<<<<<<<[\s\S]*?>>>>>>>[^\n]*', text):
            print(hunk[:16000])
        return
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(text)
    print('MERGED_SOURCE', ref[:8], str(target.relative_to(ROOT)))


conflicts = []
if git('rev-parse', 'origin/main') != MAIN:
    raise SystemExit('Main moved: review/rebase the integration plan before proceeding')
main_migrations = {p: contents(MAIN, p) for p in git('ls-tree', '-r', '--name-only', MAIN, MIGRATIONS).splitlines()}
versions = [int(re.match(r'V(\d+)__', Path(p).name).group(1)) for p in main_migrations if re.match(r'V(\d+)__', Path(p).name)]
version = max(versions) + 1
renamed = f'V{version}__airtel_durable_recovery.sql'
# Durable journal/claims supersede cursor-only orchestration. Do not run both schedulers.
base = git('merge-base', MAIN, DURABLE)
for path in git('diff', '--name-only', base, DURABLE).splitlines():
    if path.startswith('.github/') or path.startswith('ops/'):
        continue
    if path.endswith('DoubleEntryLedgerServiceTestcontainersTest.java'):
        continue
    if path == 'Docs/Operations/mobile-money-final-mile-20260910.md':
        continue
    destination = MIGRATIONS + renamed if path.startswith(MIGRATIONS) else None
    merge_source(DURABLE, path, destination)
# Preserve current main's extracted deterministic reservation regression while
# integrating any remaining test-fixture and provider-scoped operational deltas.
base = git('merge-base', MAIN, RESERVATION)
for path in git('diff', '--name-only', base, RESERVATION).splitlines():
    if (path.endswith('/ledger/DoubleEntryLedgerService.java')
            or '/src/test/java/' in path and ('/billing/' in path or path.endswith('DoubleEntryLedgerServiceTestcontainersTest.java'))
            or path.startswith('clientside/')):
        merge_source(RESERVATION, path)
if conflicts:
    (REPORT / 'summary.json').write_text(json.dumps({'state': 'CONFLICTS', 'paths': conflicts}, indent=2))
    raise SystemExit('Unresolved conflicts; no candidate published')
# Include newly created imported files, not only git's already-tracked paths.
changed = set(git('diff', '--name-only', MAIN).splitlines()) | set(git('ls-files', '--others', '--exclude-standard').splitlines())
for path in changed:
    file = ROOT / path
    if file.is_file() and file.suffix in ('.java', '.md'):
        file.write_text(file.read_text().replace('V123__airtel_durable_recovery.sql', renamed))
for path, original in main_migrations.items():
    if (ROOT / path).read_text() != original:
        raise SystemExit('Refusing historical migration modification: ' + path)
(ROOT / 'Docs/Operations/airtel-consolidation-20260911.md').write_text(f'''# Airtel recovery consolidation\n\nTracking issue #195. Brand baseline 1.2. Candidate, not provider certification.\n\nCurrent-main base `{MAIN}`; durable journal/lease implementation from PR #185 `{DURABLE}`; reservation current-read correction, fixture improvements and provider-scoped admin operations from PR #184 `{RESERVATION}`. Current main's BigDecimal fee helpers, canonical endpoint policy, extracted deterministic reservation scenario and MTN safe profiles are preserved through explicit conflict decisions. Only one recovery scheduler is retained. Historical main migrations are byte-for-byte preserved. The unmerged V123 proposal is allocated additive V{version}; no Flyway repair or history edit is authorized.\n\nAcceptance requires full backend/formatting, Docker-tagged MySQL invariants, frontend/UI, API/docs, security and existing exact-head release checks. A generated candidate is not approval to merge. Authenticated UAT must be rerun on the new revision. Verify original merchant/reference/environment/account/amount/currency attribution; never resubmit, settle or release holds from a timeout or callback assertion. Historical payments without immutable attribution require controlled reconciliation.\n\nNo secrets are included. No database, provider or production operation is performed by composition. Production requires a verified completed backup, populated migration acceptance, exact-SHA staging acceptance and the existing release process.\n''')
(REPORT / 'summary.json').write_text(json.dumps({'state': 'COMPOSED_NOT_ACCEPTED', 'main': MAIN, 'durable': DURABLE, 'reservation': RESERVATION, 'new_migration': renamed, 'historical_migrations_unchanged': True}, indent=2))
print('COMPOSED_REVIEW_CANDIDATE', renamed)
