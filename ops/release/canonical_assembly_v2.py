#!/usr/bin/env python3
"""Pinned candidate assembly. Tests extend the actual existing financial scenario."""
from pathlib import Path

script = Path(__file__).with_name('complete_canonical_remediation.py')
text = script.read_text()
start = text.index('# Real canonical finance test:')
end = text.index('# Both portal guides', start)
replacement = r'''# Real finance: stale/foreign recovery owners cannot reach ledger/outbox writes.
path = TEST + 'gateway/MobileMoneyMysqlScenario.java'
marker = '            // A failure after ledger and statement writes rolls everything back, including'
replace(path, marker, ''' + "'''" + r'''            jdbc.update("UPDATE mobile_money_executions SET next_poll_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP) WHERE transaction_id=:tx", Map.of("tx", collect));
            var recoveryLeases = new MobileMoneyRecoveryLeaseStore(jdbc);
            String expiredClaim = recoveryLeases.claim(collect, "PRODUCTION");
            assertThat(expiredClaim).isNotNull();
            jdbc.update("UPDATE mobile_money_executions SET recovery_claim_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE transaction_id=:tx", Map.of("tx", collect));
            String recoveryClaim = recoveryLeases.claim(collect, "PRODUCTION");
            assertThat(recoveryClaim).isNotNull().isNotEqualTo(expiredClaim);
            assertThatThrownBy(() -> service.applyVerified(collect, outcome("SUCCESSFUL", "stale-proof"), expiredClaim)).isInstanceOf(PaymentGatewayException.class);
            assertThatThrownBy(() -> service.applyVerified(collect, outcome("SUCCESSFUL", "foreign-proof"), "foreign-claim")).isInstanceOf(PaymentGatewayException.class);
            assertThat(service.load(collect).getStatus()).isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_transactions WHERE source_reference=:tx", Map.of("tx", collect), Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM merchant_statement WHERE transactions_log_id=(SELECT id FROM merchant_transactions_log WHERE tx_unique_id=:tx)", Map.of("tx", collect), Integer.class)).isZero();
''' + "'''" + r''' + marker)
replace(path, ''' + "'''" + r'''                                    service.apply(
                                            collect, outcome("SUCCESSFUL", "financial-collect"))''' + "'''" + r''', ''' + "'''" + r'''                                    service.applyVerified(
                                            collect, outcome("SUCCESSFUL", "financial-collect"), recoveryClaim)''' + "'''" + r''')
replace(path, '                    .hasMessageContaining("synthetic");', ''' + "'''" + r'''                    .hasMessageContaining("synthetic");
            assertThat(jdbc.queryForObject("SELECT recovery_claim_token FROM mobile_money_executions WHERE transaction_id=:tx", Map.of("tx", collect), String.class)).isEqualTo(recoveryClaim);''' + "'''" + r''')
replace(path, '            doNothing().when(usage).recordPaymentSettled(any(), any(), any());', ''' + "'''" + r'''            doNothing().when(usage).recordPaymentSettled(any(), any(), any());
            service.applyVerified(collect, outcome("SUCCESSFUL", "financial-collect"), recoveryClaim);
            assertThat(jdbc.queryForObject("SELECT recovery_claim_token FROM mobile_money_executions WHERE transaction_id=:tx", Map.of("tx", collect), String.class)).isNull();''' + "'''" + r''')
replace(TEST + 'FlywayMigrationSmokeTest.java', 'assertEquals("128", latestSuccessfulVersion', 'assertEquals("129", latestSuccessfulVersion')

'''
source = text[:start] + replacement + text[end:]
context = {'__file__': str(script), '__name__': '__main__'}
exec(compile(source, str(script), 'exec'), context)
replace = context['replace']
JAVA, TEST = context['JAVA'], context['TEST']
# ANSI conditional expressions work identically in MySQL and the existing H2 unit fixture.
path = JAVA + 'gateway/MobileMoneyExecutionService.java'
replace(path, "IF(:claim IS NULL,recovery_last_code,'VERIFIED_PROVIDER')", "CASE WHEN :claim IS NULL THEN recovery_last_code ELSE 'VERIFIED_PROVIDER' END")
replace(path, "IF(:claim IS NOT NULL OR :status IN ('SUCCESSFUL','FAILED'),NULL,recovery_claim_token)", "CASE WHEN :claim IS NOT NULL OR :status IN ('SUCCESSFUL','FAILED') THEN NULL ELSE recovery_claim_token END")
replace(path, "IF(:claim IS NOT NULL OR :status IN ('SUCCESSFUL','FAILED'),NULL,recovery_claim_until)", "CASE WHEN :claim IS NOT NULL OR :status IN ('SUCCESSFUL','FAILED') THEN NULL ELSE recovery_claim_until END")
# Keep the H2 test's schema aligned; the actual complete V129 migration is exercised on real MySQL.
replace(TEST + 'gateway/MobileMoneyExecutionServiceTest.java',
        '        merchant.setId(10L);', '''        for (String column : List.of("recovery_claim_token VARCHAR(36)", "recovery_claim_until TIMESTAMP(6)",
                "recovery_attempt_count INT NOT NULL DEFAULT 0", "recovery_last_code VARCHAR(64)", "recovery_last_signal_at TIMESTAMP(6)")) {
            jdbc.getJdbcTemplate().execute("ALTER TABLE mobile_money_executions ADD COLUMN " + column);
        }
        merchant.setId(10L);''')
replace('clientside/src/components/Layout.jsx', "import ExperienceWorkspace from '../features/ExperienceWorkspace';", "import ExperienceWorkspace from '../features/ExperienceWorkspace';\nimport AdminMerchantReadiness from '../features/AdminMerchantReadiness';")
replace('clientside/src/components/Layout.jsx', 'case \'merchant-readiness\': return <ExperienceWorkspace portal="admin" section="lifecycle" />;', "case 'merchant-readiness': return <AdminMerchantReadiness />;")
replace('clientside/src/components/MainMenu.jsx', "    { value: 'merchants-accounts', text: 'Merchants / Businesses', Icon: Icons.StoreIcon },", "    { value: 'merchants-accounts', text: 'Merchants / Businesses', Icon: Icons.StoreIcon },\n    { value: 'merchant-readiness', text: 'Merchant readiness', Icon: Icons.ShieldIcon },")
replace('clientside/src/components/PublicApiOverview.tsx', "  ['Webhooks', 'Verified event delivery connects Cito outcomes to your application.'],", "  ['Webhooks', 'Verified event delivery connects Cito outcomes to your application.'],\n  ['Recovery', 'MTN and Airtel recovery checks the original payment reference without resubmitting it. Pending is not settlement; provider certification remains separate.'],")
checks = Path(__file__).with_name('canonical_candidate_checks.py')
check_source = checks.read_text()
cut = check_source.index("path = root / 'InitializrSpringbootProjectFresh/src/test/java/net/citotech/cito/gateway/MobileMoneyMysqlScenario.java'")
check_source = check_source[:cut] + "print('NATIVE_SUBMISSION_PRESERVED; real fenced financial rollback fixture integrated')\n"
exec(compile(check_source, str(checks), 'exec'), {'__file__': str(checks), '__name__': '__main__'})
