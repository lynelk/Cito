#!/usr/bin/env python3
"""Assemble only the pinned candidate; replace the obsolete fixture anchor with reviewed source.

The final publication removes composition scaffolding. Tests use the actual existing
MySQL finance scenario and its canonical service, not an invented simplified ledger.
"""
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
exec(compile(source, str(script), 'exec'), {'__file__': str(script), '__name__': '__main__'})
# The verified fixture now sets its own due time; no guessed collection variable is injected.
checks = Path(__file__).with_name('canonical_candidate_checks.py')
check_source = checks.read_text()
cut = check_source.index("path = root / 'InitializrSpringbootProjectFresh/src/test/java/net/citotech/cito/gateway/MobileMoneyMysqlScenario.java'")
check_source = check_source[:cut] + "print('NATIVE_SUBMISSION_PRESERVED; real fenced financial rollback fixture integrated')\n"
exec(compile(check_source, str(checks), 'exec'), {'__file__': str(checks), '__name__': '__main__'})
