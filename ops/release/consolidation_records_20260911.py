#!/usr/bin/env python3
"""Companion source changes for the pinned Airtel reconciliation candidate; no runtime access."""
from pathlib import Path
import json

ROOT = Path(__file__).resolve().parents[2]
TEST = ROOT / 'InitializrSpringbootProjectFresh/src/test/java/net/citotech/cito'

p = TEST / 'scheduler/AirtelRecoverySafetyTest.java'
text = p.read_text()
point = text.index('    private Transaction tx(')
p.write_text(text[:point] + '''    @Test
    void journalOwnedLegacyAttemptDoesNotEnterHistoricalProviderLookup() {
        when(jdbc.queryForList(contains("FROM airtel_recovery"), any(MapSqlParameterSource.class)))
                .thenReturn(List.of(Map.of("id", 123L)));
        worker.reconcileLegacy(tx("PENDING"));
        verifyNoInteractions(credentials, treasury, ledger, manager);
        verify(jdbc).queryForList(contains("transaction_id=:transaction"), any(MapSqlParameterSource.class));
        verifyNoMoreInteractions(jdbc);
    }

    @Test
    @SuppressWarnings("unchecked")
    void historicalFinalizerRechecksJournalOwnershipUnderCanonicalRowLock() {
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(tx("PENDING")));
        when(jdbc.queryForList(contains("FROM airtel_recovery"), any(MapSqlParameterSource.class)))
                .thenReturn(List.of(Map.of("id", 123L)));
        try (MockedStatic<Common> common = mockStatic(Common.class)) {
            common.when(Common::getTransactionRowMapper).thenReturn(mock(RowMapper.class));
            worker.finalizeLegacy(tx("PENDING"), response("200", "OK", "SUCCESSFUL"));
            common.verify(() -> Common.updateTx(any(Transaction.class), eq(jdbc), eq(manager)), never());
            verifyNoInteractions(ledger, treasury);
        }
    }

    @Test
    void journalOwnedSharedAttemptDoesNotEnterHistoricalProviderLookup() {
        when(jdbc.queryForList(contains("FROM airtel_recovery"), any(MapSqlParameterSource.class)))
                .thenReturn(List.of(Map.of("id", 123L)));
        worker.reconcileSharedProvider(Map.of("id", 9L, "merchant_id", 42L,
                "operation", "COLLECT", "merchant_reference", "REF42", "environment", "SANDBOX"));
        verifyNoInteractions(credentials, treasury, ledger, manager);
        verify(jdbc).queryForList(contains("credential_source='PLATFORM_SHARED'"), any(MapSqlParameterSource.class));
        verifyNoMoreInteractions(jdbc);
    }

''' + text[point:])
p = TEST / 'gateway/AirtelRecoveryStoreTestcontainersTest.java'
text = p.read_text()
point = text.index('    @Test\n    void preparedRequestSurvivesRestart')
p.write_text(text[:point] + '''    @Test
    void caseSensitiveReferencesDoNotCollapseUnderDatabaseCollation() {
        var candidate = candidate();
        var first = tx.execute(x -> store.prepare(candidate));
        assertThat(store.findByMerchantReference(candidate.merchantId(),
                candidate.merchantReference().toUpperCase(java.util.Locale.ROOT))).isEmpty();
        assertThat(store.findByMerchantReference(candidate.merchantId(),
                candidate.merchantReference())).hasSize(1);
        assertThat(first.created()).isTrue();
    }

    @Test
    void immutableAttemptMismatchRollsBackAndRetainsOriginalCorrelation() {
        var c = candidate();
        var first = tx.execute(x -> store.prepare(c));
        var changed = new AirtelRecoveryStore.Entry(0, c.provider(), c.merchantId(), c.merchantNumber(),
                c.merchantReference(), 991L, c.operation(), c.environment(), c.country(), c.currency(),
                c.source(), c.identity(), c.amount(), c.requestHash(), c.callbackUrl(), 0, null, null);
        assertThatThrownBy(() -> tx.execute(x -> store.prepare(changed)))
                .isInstanceOf(PaymentGatewayException.class).hasMessage("AIRTEL_REFERENCE_CONFLICT");
        assertThat(store.findByMerchantReference(c.merchantId(), c.merchantReference()).get(0).id())
                .isEqualTo(first.entry().id());
        assertThat(store.findByMerchantReference(c.merchantId(), c.merchantReference()).get(0).transactionId())
                .isNull();
    }

''' + text[point:])

section = '''
## 15. Airtel recovery ownership and pending outcomes

Airtel payment initiation records an immutable original reference, merchant, operation,
amount/currency, environment and provider-account identity before dispatch. Equivalent
retries return the original recorded attempt; changed financial or identity attributes
are conflicts. Do not create a new payment to resolve an uncertain response.

Journaled attempts have one recovery owner, using restart-safe expiring database leases
and authenticated original-reference status checks. Historical scanning excludes these
attempts and rechecks ownership under the canonical financial row lock. Historical
records lacking immutable account attribution remain for controlled reconciliation.
Missing callbacks, 4xx submission responses, polling failures and elapsed time are not
terminal financial evidence and do not release a hold or authorize resubmission.

The admin Airtel Money OpenAPI page uses provider-scoped canonical treasury controls.
The merchant transactions view explains pending/uncertain outcomes. Existing MTN
configuration continues to default to sandbox and clears unsaved secrets when its
scope changes. Service configuration is not provider activation or certification.

The Airtel provider-callback route is an untrusted, bounded wake-up hint only. It can
advance a check for an existing opaque reference, not set a status, amount, merchant
or financial outcome. Its acknowledgement does not verify the payload or confirm
settlement. Provider registration and end-to-end acceptance remain external evidence.
These provider callbacks are not customer API-access admissions; merchant status
queries retain their existing authentication, scope and published access rates.

Schema changes are additive V129 and V130, after the existing V128 admission-pricing
migration. Neither previously applied migration history nor existing credentials,
prices, provider activation flags or balances are changed by the release. Deploy only
after exact-revision CI, populated database upgrade tests, isolated authenticated
staging acceptance and current production backup evidence. A successful source build
is not a completed production rollout.
'''
p = ROOT / 'Docs/Api/Cito-Gateway-Integration-Guide.md'
p.write_text(p.read_text().rstrip() + '\n' + section)
(ROOT / 'InitializrSpringbootProjectFresh/src/main/resources/api-reference/integration-guide.md').write_text(p.read_text())
p = ROOT / 'clientside/src/components/PublicApiOverview.tsx'
p.write_text(p.read_text().replace("  ['Authentication',", "  ['Payment recovery', 'Pending does not mean failed or paid. Keep the original reference; authenticated status verification and reconciliation resolve uncertain attempts without another payment.'],\n  ['Authentication',", 1))
for name in ('airtel-durable-recovery.md', 'airtel-recovery-release-20260910.md', 'mobile-money-final-mile-20260910.md'):
    p = ROOT / 'Docs/Operations' / name
    text = p.read_text().replace('V123__airtel_durable_recovery.sql', 'V130__airtel_durable_recovery.sql').replace('V123__airtel_recovery_cursors.sql', 'V129__airtel_recovery_cursors.sql')
    p.write_text(text.replace('V123', 'V130' if 'durable' in name else 'V129'))
p = ROOT / 'Docs/Operations/airtel-durable-recovery.md'
p.write_text(p.read_text().replace('cpay.airtel.recovery.interval-ms', 'cpay.airtel.recovery.delay-ms') + '''
## Consolidated release correction — 11 September 2026

V129 retains cursor and immutable historical scope evidence; V130 introduces the
journal. The journal owns each new attempt and historical scans explicitly exclude
it, including a current ownership recheck under the canonical row lock. This avoids
two implementations claiming the same attempt. New canonical attempts have a unique
transaction ID; native sandbox retries preserve the original provider reference.
Merchant references are case-sensitive in the new journal.

All terminal completion requires the authenticated status client. Submission 4xx
classification is retained only as diagnostic evidence, never as a shortcut to failed
settlement. The status client's bounded response and deadline include the complete
body, not only response headers. Missing historical provenance remains unresolved.

This supersedes the earlier alternatives in PRs #184/#185 only after the consolidated
replacement has passed its own gates and is merged. It does not assert production
provider certification, callback registration, deployment or successful funds movement.
''')
p = ROOT / 'clientside/src/features/AirtelMoneyOperations.tsx'
p.write_text(p.read_text().replace('Recovery scans existing merchant transaction records and CPay Shared Payments reservations.\n            Scan progress survives restarts.', 'New attempts use a durable original-reference journal with one recovery owner. Historical scans\n            exclude journal-owned attempts and preserve existing transaction and treasury evidence.\n            Recovery progress survives restarts.'))
for name in ('Readme.md', 'Installation.md', 'Deployment.md', 'CI_CD_SETUP.md'):
    p = ROOT / name
    p.write_text(p.read_text().rstrip() + '''

### Consolidated Airtel recovery release — 11 September 2026

The current recovery change introduces additive V129/V130 and reconciles original-reference
journaling with historical polling while preserving current MTN configuration and financial
controls. See `Docs/Operations/airtel-durable-recovery.md` and integration-guide section 15.
Full CI, populated MySQL upgrade tests, exact-SHA authenticated staging acceptance and a
completed production backup are required before promotion. No provider activation, real-money
test, credential change, historical migration edit or completed deployment is implied here.
''')
p = ROOT / 'ops/iso/governance.json'
register = json.loads(p.read_text())
for objective in register['objectives']:
    if objective['id'] == 'OBJ-FIN-01':
        objective.update(lastReviewedAt='2026-09-11', nextReviewAt='2026-09-12',
                         reviewOutcome='EVIDENCE_PENDING',
                         reviewEvidence='ops/iso/evidence/finance-objective-review-2026-09-11.md')
p.write_text(json.dumps(register, indent=2) + '\n')
(ROOT / 'ops/iso/evidence/finance-objective-review-2026-09-11.md').write_text('''# Financial integrity objective review — 11 September 2026

Objective: OBJ-FIN-01 — Daily reconciliation close. Accountable role: FINANCE_OWNER.
Review performed by the delegated executive/CTO operating assistant under the owner's
remediation instruction. This is a technical/governance evidence review, not an
independent Finance Owner approval, approved close, or certification.

## Evidence examined

The current main source de3011367b67eac2cad65e508e72966a3f70b5ea, financial-correctness
policy, previous September 7 objective review, issue #157, issue #195 release evidence,
PRs #184/#185 recovery alternatives and PR #197 staging acceptance were inspected.
The release record reports authenticated staging acceptance and populated V125-to-V128
MySQL upgrade tests. These are engineering evidence, not daily reconciliation records.
Production still reports source 56811e29fb872d1e595391c169b0149f15164f38. A completed
fresh production backup was not verified; Railway backup-agent reads timed out.

No approved current production reconciliation close, matching provider statements,
settlement-close record, or signed material-variance decision was available for this
review. No usage, balances, variance totals or finance approvals have been invented.
The previous nextReviewAt December 9 value did not reflect the documented DAILY cadence
or September 7 evidence's September 8 follow-up. It is replaced only after this actual
review, with the next review September 12; the accountable Finance Owner is unchanged.

## Outcome and required follow-up

EVIDENCE_PENDING. The target of zero material unexplained variances is not demonstrated
by green CI, deployment health or synthetic tests. The Finance Owner must supply the
reconciliation/settlement evidence and any material-variance decision. Technical source
freshness is distinguished from financial control effectiveness. Issue #195 retains
the operational dependency even if the stale-review-record defect in #157 is closed.
No financial history, ledger entry, maker-checker approval or service flag is changed.
''')
p = ROOT / 'ops/environments/cito-environments.json'
contract = json.loads(p.read_text())
staging = contract['railway']['staging']
staging['status'] = 'ACTIVE'
staging['acceptanceNote'] = '2026-09-11: designated isolated staging is active. Revision de3011367b67eac2cad65e508e72966a3f70b5ea passed authenticated browser/API acceptance at 08:56:29Z (QA deployment 03196a0c-af9d-4b9a-8590-d7803aa0b377; issue #195 comment 5632064254). New revisions including V129/V130 require their own exact-SHA CI, migration and authenticated acceptance; active infrastructure is not candidate acceptance or production promotion.'
p.write_text(json.dumps(contract, indent=2) + '\n')
p = ROOT / 'ops/release/staging_authenticated_acceptance.py'
text = p.read_text().replace("check('flyway_head_128', cursor.fetchone()[0] == '128')", "check('flyway_head_130', cursor.fetchone()[0] == '130')")
anchor = "            merchant, merchant_page = login('merchant', owner_email)"
text = text.replace(anchor, '''            admin_page.evaluate("document.documentElement.style.fontSize='100%'")
            admin_page.goto(BASE + '/bo/airtel-money')
            admin_page.get_by_role('heading', name='Airtel Money OpenAPI', exact=True).wait_for(state='visible')
            check('airtel_operations_truthful_recovery', admin_page.get_by_text('New attempts use a durable original-reference journal', exact=False).is_visible())
            check('airtel_operations_no_provider_switch', admin_page.get_by_label('Provider channel', exact=True).count() == 0)

''' + anchor)
text = text.replace('            with merchant_page.expect_download() as download:', '''            reference.get_by_role('searchbox').fill('Airtel recovery ownership')
            check('merchant_airtel_recovery_guide', reference.get_by_text('Airtel recovery ownership and pending outcomes', exact=True).is_visible(timeout=20000))
            with merchant_page.expect_download() as download:''', 1)
p.write_text(text)
