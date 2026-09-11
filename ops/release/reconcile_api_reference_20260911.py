#!/usr/bin/env python3
"""One-shot, reviewed reconciliation of PR 187 with the pinned recovery release.

Only updates a feature worktree. Never touches a running database or deploys.
Abort rather than guess if main or the conflict set differs from the reviewed audit.
"""
from pathlib import Path
import json
import re
import subprocess
import yaml

MAIN = '0ab5bd5cbc919949f132c7e0d6890bcfc38733d3'
ROOT = Path(__file__).resolve().parents[2]

def git(*args: str) -> str:
    return subprocess.check_output(['git', *args], cwd=ROOT, text=True)

def source(path: str) -> str:
    return git('show', f'{MAIN}:{path}')

assert git('rev-parse', 'origin/main').strip() == MAIN, 'main moved; repeat the release audit'
result = subprocess.run(['git', 'merge', '--no-commit', '--no-ff', MAIN], cwd=ROOT)
assert result.returncode in (0, 1), 'Unexpected merge failure'
conflicts = set(git('diff', '--name-only', '--diff-filter=U').splitlines())
expected = {
    'Docs/Api/cpay-v2-openapi.yaml',
    'InitializrSpringbootProjectFresh/src/main/java/net/citotech/cito/RefundController.java',
    'ops/environments/cito-environments.json',
}
assert conflicts <= expected, f'Unreviewed conflicts: {sorted(conflicts - expected)}'

# Preserve the current governed refund lifecycle, adding only admission metering
# after the existing signature boundary. Never restore the older direct payout.
refund_path = 'InitializrSpringbootProjectFresh/src/main/java/net/citotech/cito/RefundController.java'
refund = source(refund_path)
assert 'refundService.requestRefund(' in refund
assert 'Common.doPayOut(' not in refund
assert 'public class RefundController {' in refund
refund = refund.replace('public class RefundController {',
    'public class RefundController {\n\n    @Autowired\n    private net.citotech.cito.developer.reference.ApiAccessBillingService apiBilling;', 1)
pattern = r'(if\s*\(sigError\s*!=\s*null\)\s*(?:\{\s*return sigError;\s*\}|return sigError;))'
refund, count = re.subn(pattern,
    r'\1\n            apiBilling.admitted(merchant.getId(), null, "PRODUCTION");', refund)
assert count == 1, 'Refund authentication boundary changed'
(ROOT / refund_path).write_text(refund)

# Keep all mechanically merged API changes and the six other legacy operations;
# replace only the refund operation with the newer, authoritative main contract.
spec_path = ROOT / 'Docs/Api/cpay-v2-openapi.yaml'
spec_text = re.sub(r'(?ms)^<<<<<<< [^\n]*\n(.*?)^=======\n(.*?)^>>>>>>> [^\n]*\n',
                   lambda match: match.group(1), spec_path.read_text())
assert '<<<<<<<' not in spec_text
main_spec = yaml.safe_load(source('Docs/Api/cpay-v2-openapi.yaml'))
refund_item = main_spec['paths']['/api/doMobileMoneyRefund']
operation = refund_item['post']
operation['x-cito-body-signature'] = {
    'field': 'signature',
    'signedFields': ['merchant_number', 'original_reference', 'reference', 'description'],
}
operation['description'] += (' API access is metered once per authenticated HTTP admission at the '
    'published endpoint rate, separately from refund and provider charges. A fresh authenticated retry '
    'is another access admission; the shared refund lifecycle still prevents duplicate money movement.')
block = yaml.safe_dump({'/api/doMobileMoneyRefund': refund_item}, sort_keys=False, width=110)
block = ''.join('  ' + line for line in block.splitlines(True))
spec_text, count = re.subn(r'(?ms)^  /api/doMobileMoneyRefund:\n.*?(?=^  /|^components:)',
                         lambda match: block, spec_text)
assert count == 1, 'Refund contract location changed'
yaml.safe_load(spec_text)
spec_path.write_text(spec_text)

# The recovered active staging and production follow main's history, where this
# PR's V123 was never introduced. Add it AFTER V127; do not repair/alter history.
migration_dir = ROOT / 'InitializrSpringbootProjectFresh/src/main/resources/db/migration'
old = migration_dir / 'V123__api_endpoint_access_rates.sql'
new = migration_dir / 'V128__api_endpoint_access_rates.sql'
assert old.exists() and not new.exists(), 'Unexpected API rate migration state'
main_files = git('ls-tree', '-r', '--name-only', MAIN, str(migration_dir.relative_to(ROOT)))
assert old.name not in main_files and 'V128__' not in main_files
old.rename(new)
# Assert every migration already on main is byte-for-byte intact.
for filename in main_files.splitlines():
    if filename.endswith('.sql'):
        assert (ROOT / filename).read_text() == source(filename), f'Applied migration changed: {filename}'
versions = [p.name.split('__')[0] for p in migration_dir.glob('V*__*.sql')]
assert len(versions) == len(set(versions)), 'Duplicate migration versions'

# Preserve the canonical project and production contract from main. Record only
# verified staging identifiers, without asserting acceptance or exposing secrets.
env_path = 'ops/environments/cito-environments.json'
environments = json.loads(source(env_path))
staging = environments['railway']['staging']
staging['services']['database'] = {
    'name': 'cito-staging-main-mysql',
    'serviceId': '8109d27e-5d8c-4ed8-af3e-bf6234e9ca3b',
    'volumeId': '8f5a439b-6477-4506-9880-60a250900cf6',
    'mountPath': '/var/lib/mysql',
}
staging['acceptanceNote'] = (
    '2026-09-11: backend main 0ab5bd5c passed live health with DB UP and gateway SANDBOX; '
    'runtime logs verify the recovered persistent database and V127. The API-workbench candidate '
    'introduces V128 without modifying main migration history. Frontend/backend exact-revision '
    'parity, authenticated UAT and production promotion remain pending; PLANNED is not release acceptance.')
(ROOT / env_path).write_text(json.dumps(environments, indent=2) + '\n')

for filename in ('Readme.md', 'Installation.md', 'Deployment.md', 'CI_CD_SETUP.md'):
    path = ROOT / filename
    text = path.read_text()
    text = text.replace('This change requires Flyway V123', 'This change requires Flyway V128')
    path.write_text(text)
release = ROOT / 'Docs/Api/API-REFERENCE-RELEASE.md'
text = release.read_text().replace('Flyway V123 is additive.', 'Flyway V128 is additive.')
text += '''\n## Reconciliation after database recovery — 11 September 2026\n\nThe current candidate incorporates main 0ab5bd5cbc919949f132c7e0d6890bcfc38733d3. It preserves the newer governed refund lifecycle, cumulative refund limits, independent approvals, idempotency and settlement tracking. API admission metering is added only after signature verification.\n\nThe API-rate migration is now V128, because this PR's earlier V123 never entered main and the recovered active staging database has advanced to V127. Main's existing migration files remain byte-for-byte unchanged. The obsolete experimental V123 database must not be pointed at this release without a separately reviewed migration plan; no Flyway repair, history deletion, database reset or destructive operation is authorized by this reconciliation.\n\nOn 11 September the read-only health probe reported database UP, gateway SANDBOX and exact release 0ab5bd5c. Protected API routes rejected anonymous access. This is not authenticated API acceptance, an external provider certification or a production deployment. Exact candidate CI, MySQL migration/upgrade checks, both staging service SHAs, portal tests and rollback readiness remain release gates.\n\nBrand version: 1.2. Affected surfaces remain the merchant Developers page, administrator API workbench, public website and revision-derived API/HTML exports.\n'''
release.write_text(text)
subprocess.run(['git', 'add', '-A'], cwd=ROOT, check=True)
assert not git('diff', '--name-only', '--diff-filter=U').strip()
print('Reconciliation prepared. Main migrations preserved; API rates moved to V128; no runtime changes.')
