#!/usr/bin/env python3
"""Integrate reviewed recovery improvements into current canonical domains, never old money paths."""
from pathlib import Path
import json
import subprocess

ROOT = Path(__file__).resolve().parents[2]
MAIN = 'de3011367b67eac2cad65e508e72966a3f70b5ea'
REVIEW = 'e8a124c1b87ddfa64cbf8465c264b20cf146344b'
JAVA = 'InitializrSpringbootProjectFresh/src/main/java/net/citotech/cito/'
TEST = 'InitializrSpringbootProjectFresh/src/test/java/net/citotech/cito/'


def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT, text=True)


def replace(path, before, after, count=1):
    file = ROOT / path
    text = file.read_text()
    if text.count(before) != count:
        raise RuntimeError(f'Unexpected source context in {path}: {before[:100]!r}')
    file.write_text(text.replace(before, after))


if git('rev-parse', 'origin/main').strip() != MAIN:
    raise SystemExit('Main moved. Re-review instead of overwriting concurrent work.')
# Preserve approved UI scope + complete synthetic billing fixtures. Do NOT import
# the parallel Airtel registry/store/adapter or its HTTP-4xx finality shortcut.
for path in git('diff', '--name-only', MAIN, REVIEW).splitlines():
    if path.startswith('clientside/') or path.startswith(TEST + 'billing/') or path == TEST + 'ledger/DoubleEntryLedgerServiceTestcontainersTest.java':
        target = ROOT / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(git('show', f'{REVIEW}:{path}'))
replace('clientside/src/features/AirtelMoneyOperations.tsx',
        'Recovery scans existing merchant transaction records and CPay Shared Payments reservations.\n            Scan progress survives restarts.',
        'Recovery uses the canonical mobile-money execution journal and its original encrypted account snapshot.\n            Pending work survives restarts; fenced claims prevent stale workers from posting outcomes.')

path = JAVA + 'gateway/MobileMoneyExecutionService.java'
replace(path, '    public void apply(String transactionId, GateWayResponse response) {\n', '''    public void apply(String transactionId, GateWayResponse response) {
        apply(transactionId, response, null);
    }

    public void applyVerified(String transactionId, GateWayResponse response, String recoveryClaim) {
        if (recoveryClaim == null || recoveryClaim.isBlank()) {
            throw new PaymentGatewayException("A recovery claim is required");
        }
        apply(transactionId, response, recoveryClaim);
    }

    private void apply(String transactionId, GateWayResponse response, String recoveryClaim) {
''')
replace(path, 'MapSqlParameterSource p = new MapSqlParameterSource("tx", transactionId);',
        'MapSqlParameterSource p = new MapSqlParameterSource("tx", transactionId).addValue("claim", recoveryClaim, java.sql.Types.VARCHAR);')
replace(path, '"SELECT * FROM mobile_money_executions WHERE transaction_id=:tx FOR UPDATE",',
        '''"SELECT * FROM mobile_money_executions WHERE transaction_id=:tx "
                                            + (recoveryClaim == null ? "" : "AND recovery_claim_token=:claim AND recovery_claim_until>CURRENT_TIMESTAMP(6) ")
                                            + "FOR UPDATE",''')
replace(path, '"UPDATE mobile_money_executions SET last_polled_at=CURRENT_TIMESTAMP,next_poll_at=TIMESTAMPADD(SECOND,60,CURRENT_TIMESTAMP) WHERE transaction_id=:tx",',
        '''"UPDATE mobile_money_executions SET last_polled_at=CURRENT_TIMESTAMP,next_poll_at=TIMESTAMPADD(SECOND,60,CURRENT_TIMESTAMP),"
                                    + "recovery_last_code=IF(:claim IS NULL,recovery_last_code,'VERIFIED_PROVIDER'),"
                                    + "recovery_claim_token=IF(:claim IS NOT NULL OR :status IN ('SUCCESSFUL','FAILED'),NULL,recovery_claim_token),"
                                    + "recovery_claim_until=IF(:claim IS NOT NULL OR :status IN ('SUCCESSFUL','FAILED'),NULL,recovery_claim_until) WHERE transaction_id=:tx",''')

# Reuse current authenticated lookup parsing and the canonical atomic financial finalizer.
path = JAVA + 'gateway/MobileMoneyRecoveryService.java'
source = (ROOT / path).read_text()
start = source.index('    public void verify(Map<String, Object> row) {')
end = source.index('    private static String text(', start)
lookup = source[start:end].replace('public void verify(', 'GateWayResponse readOnlyOutcome(')
lookup = lookup.replace('        String id = text(row, "transaction_id"), operation = text(row, "operation");', '''        String id = text(row, "transaction_id"), operation = text(row, "operation");
        if (!MobileMoneyExecutionService.managed(text(row, "channel_code"))
                || (!"COLLECT".equals(operation) && !"PAYOUT".equals(operation))
                || (!"PRODUCTION".equals(runtimeEnvironment) && !"SANDBOX".equals(text(row, "environment")))) {
            throw new PaymentGatewayException("Recovery scope does not match this runtime");
        }''')
lookup = lookup.replace('        executions.apply(id, response);', '        return response;')
header = '''package net.citotech.cito.gateway;

import java.util.*;
import net.citotech.cito.Model.*;
import net.citotech.cito.money.MoneyAmount;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Callbacks are hints. Only bounded authenticated lookup plus a current fenced claim can settle. */
@Service
public class MobileMoneyRecoveryService {
    private final NamedParameterJdbcTemplate jdbc;
    private final MobileMoneyExecutionService executions;
    private final MtnMomoStatusClient mtn;
    private final MobileMoneyRecoveryLeaseStore leases;
    private final BoundedProviderVerification verification;
    private final String runtimeEnvironment;
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MobileMoneyRecoveryService.class);

    public MobileMoneyRecoveryService(NamedParameterJdbcTemplate jdbc,
            MobileMoneyExecutionService executions, MtnMomoStatusClient mtn,
            MobileMoneyRecoveryLeaseStore leases, BoundedProviderVerification verification,
            @Value("${custom.gatewaystate:SANDBOX}") String runtimeEnvironment) {
        this.jdbc = jdbc;
        this.executions = executions;
        this.mtn = mtn;
        this.leases = leases;
        this.verification = verification;
        this.runtimeEnvironment = runtimeEnvironment.trim().toUpperCase(Locale.ROOT);
        MobileMoneyRecoveryLeaseStore.requireRuntime(this.runtimeEnvironment);
    }

    public boolean signal(String channel, String reference, String externalId) {
        if (!MobileMoneyExecutionService.managed(channel) || reference == null || reference.length() > 64) return false;
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT transaction_id FROM mobile_money_executions WHERE channel_code=:channel AND provider_reference=:reference "
                        + "AND (:runtime='PRODUCTION' OR environment='SANDBOX')",
                new MapSqlParameterSource("channel", channel).addValue("reference", reference).addValue("runtime", runtimeEnvironment));
        if (rows.isEmpty()) return false;
        if (rows.size() != 1) throw new PaymentGatewayException("Provider reference is ambiguous");
        Map<String, Object> row = rows.get(0);
        if (externalId != null && !externalId.isBlank() && !externalId.equals(row.get("transaction_id")))
            throw new PaymentGatewayException("Provider external reference does not match payment");
        leases.signal(String.valueOf(row.get("transaction_id")));
        return true;
    }

    @Scheduled(fixedDelayString = "${cpay.mobile-money.status-poll.delay-ms:15000}")
    @SchedulerLock(name = "mobileMoneyRecovery", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1S")
    public void reconcilePending() {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(90);
        for (Map<String, Object> row : leases.due(runtimeEnvironment, 10)) {
            if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) break;
            String id = text(row, "transaction_id");
            String claim = leases.claim(id, runtimeEnvironment);
            if (claim == null) continue;
            try {
                GateWayResponse outcome = verification.execute(() -> readOnlyOutcome(row));
                executions.applyVerified(id, outcome, claim);
            } catch (RuntimeException error) {
                leases.retry(id, claim, "VERIFICATION_DEFERRED");
                log.warn("Mobile-money verification deferred for transaction {} ({})", id, error.getClass().getSimpleName());
            }
        }
    }

'''
(ROOT / path).write_text(header + lookup + source[end:])
# Preserve invalid-attribute tests while preventing any test from implying unfenced finalization.
path = TEST + 'gateway/MobileMoneyRecoveryServiceTest.java'
replace(path, 'new MobileMoneyRecoveryService(jdbc, executions, mtn)',
        'new MobileMoneyRecoveryService(jdbc, executions, mtn, mock(MobileMoneyRecoveryLeaseStore.class), mock(BoundedProviderVerification.class), "PRODUCTION")')
replace(path, 'recovery.verify(row)', 'recovery.readOnlyOutcome(row)', 2)
replace(path, 'verify(executions).apply(eq("transaction-id"), any());',
        'verify(executions, never()).apply(anyString(), any());\n        verify(executions, never()).applyVerified(anyString(), any(), anyString());')

# Never re-enter the historical mutable-credential path from an unauthenticated callback.
path = JAVA + 'Api.java'
s = (ROOT / path).read_text()
a = s.index('            if (!mobileMoneyRecovery.signal("airtel_open_api", id, id)) {')
b = s.index('            response.setStatus(202);', a)
s = s[:a] + '            mobileMoneyRecovery.signal("airtel_open_api", id, id);\n' + s[b:]
(ROOT / path).write_text(s)

path = JAVA + 'experience/MerchantOnboardingReadinessService.java'
replace(path, 'response.put("readyForProduction", readyForProduction(lifecycle, steps));',
        '''Map<String, Object> assessment = MerchantReadinessAssessment.assess(lifecycle, steps);
        response.put("readinessAssessment", assessment);
        response.put("readyForProduction", assessment.get("readyForProduction"));''')
s = (ROOT / path).read_text()
a = s.index('    private boolean readyForProduction(')
b = s.index('    private boolean stepDone(', a)
(ROOT / path).write_text(s[:a] + s[b:])

# Shared merchant/admin presentation: neither a service entitlement nor a missing metric is green.
replace('clientside/src/features/ExperienceWorkspace.tsx', "import React from 'react';", "import React from 'react';\nimport MerchantReadinessPanel from './MerchantReadinessPanel';")
replace('clientside/src/features/ExperienceWorkspace.tsx', '      {state.data?.blocked_reason ?', '      <MerchantReadinessPanel merchantId={merchantId} />\n      {state.data?.blocked_reason ?')
replace('clientside/src/features/ExperienceWorkspace.tsx', '  const merchantId = merchantIdFrom(user as Record<string, unknown>);',
        '''  const [params] = useSearchParams();
  const scoped = Number(params.get('merchantId'));
  const merchantId = portal === 'admin' && Number.isSafeInteger(scoped) && scoped > 0 ? scoped : merchantIdFrom(user as Record<string, unknown>);''')
replace('clientside/src/components/Layout.jsx', "      case 'search': return <ExperienceWorkspace", "      case 'merchant-readiness': return <ExperienceWorkspace portal=\"admin\" section=\"lifecycle\" />;\n      case 'search': return <ExperienceWorkspace")
replace('clientside/src/features/MerchantServicePortfolio.tsx', "'Enabled for your account', tone: 'success'", "'Access granted · readiness separate', tone: 'neutral'")
replace('clientside/src/features/MerchantServicePortfolio.tsx', '>Test in sandbox</Button>', '>Open developer reference</Button>')
# Correct obsolete BO partner redirects that otherwise discard a desired destination.
p = ROOT / 'clientside/src/features/MerchantServicePortfolio.tsx'
p.write_text(p.read_text().replace('/bo/partner/', '/fo/'))
replace('clientside/src/features/ApiReference.tsx', "import ReactMarkdown from 'react-markdown';", "import ReactMarkdown from 'react-markdown';\nimport DeveloperQuickstart from './DeveloperQuickstart';")
replace('clientside/src/features/ApiReference.tsx', '    <div className="cito-api-toolbar">', '    <DeveloperQuickstart onExplore={() => { setTab(\'reference\'); setQuery(\'capabilities\'); }} />\n    <div className="cito-api-toolbar">')

# Real canonical finance test: stale/foreign claims must produce ZERO financial side effects.
path = TEST + 'gateway/MobileMoneyMysqlScenario.java'
replace(path, '            GateWayResponse done = successful("mtn-financial-1");', '''            var recoveryLeases = new MobileMoneyRecoveryLeaseStore(jdbc);
            String expiredClaim = recoveryLeases.claim(collection.getTransactionId(), "PRODUCTION");
            assertTrue(expiredClaim != null);
            jdbc.update("UPDATE mobile_money_executions SET recovery_claim_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE transaction_id=:tx",
                    new MapSqlParameterSource("tx", collection.getTransactionId()));
            String recoveryClaim = recoveryLeases.claim(collection.getTransactionId(), "PRODUCTION");
            assertTrue(recoveryClaim != null && !recoveryClaim.equals(expiredClaim));
            assertThrows(PaymentGatewayException.class, () -> executions.applyVerified(collection.getTransactionId(), successful("stale-proof"), expiredClaim));
            assertThrows(PaymentGatewayException.class, () -> executions.applyVerified(collection.getTransactionId(), successful("foreign-proof"), "foreign-claim"));
            assertEquals("PENDING", executions.load(collection.getTransactionId()).getStatus());
            assertEquals(0, count(jdbc, "ledger_entries"));
            assertEquals(0, count(jdbc, "merchant_statements"));
            GateWayResponse done = successful("mtn-financial-1");''')
replace(path, '                executions.apply(collection.getTransactionId(), done);',
        '                executions.applyVerified(collection.getTransactionId(), done, recoveryClaim);')
replace(path, '            assertEquals("PENDING", executions.load(collection.getTransactionId()).getStatus());\n            assertEquals(0, count(jdbc, "ledger_entries"));',
        '''            assertEquals("PENDING", executions.load(collection.getTransactionId()).getStatus());
            assertEquals(recoveryClaim, jdbc.queryForObject("SELECT recovery_claim_token FROM mobile_money_executions WHERE transaction_id=:tx",
                    new MapSqlParameterSource("tx", collection.getTransactionId()), String.class));
            assertEquals(0, count(jdbc, "ledger_entries"));''', 2)
replace(TEST + 'FlywayMigrationSmokeTest.java', 'assertEquals("128", latestSuccessfulVersion', 'assertEquals("129", latestSuccessfulVersion')

# Both portal guides and generated exports reflect exact scope, never provider certification.
guide = ROOT / 'Docs/Api/Cito-Gateway-Integration-Guide.md'
guide.write_text(guide.read_text() + '''\n\n## 14. Canonical recovery and evidence-derived onboarding\n\nMTN and Airtel native payments, compatibility APIs and merchant batches use the same durable `mobile_money_executions` lifecycle. Recovery uses the originally stored provider reference and encrypted credential snapshot; it does not create another payment. Provider callbacks are rate-limited wake-up hints, not settlement authority. A failed or timed-out lookup leaves the outcome pending and holds intact. Fenced claims are checked inside the canonical transaction that posts status, ledger, treasury, projections and outbox evidence. Stale/expired claims cannot finalize. Status lookups have a bounded deadline and scheduler budget; a sandbox runtime cannot recover production entries. Historical entries without canonical attribution require controlled reconciliation rather than guessed finality.\n\nThe merchant activation journey and administrator merchant-readiness view use the same onboarding assessment. Empty required-step sets, untimestamped completions, waived tests and a LIVE label without activation evidence never imply provider certification. `readyForProduction` remains a readiness assessment, not an activation command. Individual provider, service, country, currency, credential and environment approvals remain separate. Administrators inspect `/bo/admin/merchant-readiness?merchantId=APPROVED_MERCHANT_ID`; merchant scope is enforced by the existing backend controller.\n\nThe developer quickstart button filters documentation locally and never sends a request. Review the connected deployment, authentication and current access price before an explicitly approved non-money test. The workbench itself is not a sandbox.\n\nProduction SMTP transport was independently reachable from a sibling diagnostic worker on 11 September 2026; this is not proof of application authentication or inbox delivery. Do not claim email delivery from transport success alone.\n''')
# Freeze applied main migrations; the only schema extension is additive V129.
for path in git('ls-tree', '-r', '--name-only', MAIN, 'InitializrSpringbootProjectFresh/src/main/resources/db/migration').splitlines():
    if (ROOT / path).read_text() != git('show', f'{MAIN}:{path}'):
        raise RuntimeError('Historical migration altered: ' + path)
print('CANONICAL_REMEDIATION_COMPOSED; main native adapters and V1-V128 preserved; verification still required')
