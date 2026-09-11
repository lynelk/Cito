#!/usr/bin/env python3
"""Reviewed final assembly checks; no deployment or provider/data access."""
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[2]
main = 'de3011367b67eac2cad65e508e72966a3f70b5ea'
java = root / 'InitializrSpringbootProjectFresh/src/main/java/net/citotech/cito'
for relative in ('gateway/AirtelOpenApiAdapter.java', 'gateway/MobileMoneyCompatibilityBridge.java', 'DoPayGateway.java'):
    path = java / relative
    original = subprocess.check_output(['git', 'show', f'{main}:{path.relative_to(root)}'], text=True)
    if path.read_text() != original:
        raise RuntimeError('Native/compatibility submission must remain unchanged: ' + relative)
if list(java.rglob('AirtelRecoveryRegistry.java')) or list(java.rglob('AirtelRecoveryService.java')):
    raise RuntimeError('A competing Airtel financial lifecycle must not be introduced')
path = root / 'clientside/src/features/MerchantReadinessPanel.tsx'
text = path.read_text()
if text.count('Alert variant="info"') != 1:
    raise RuntimeError('Unexpected readiness caveat surface')
path.write_text(text.replace('Alert variant="info"', 'Alert variant="warning"'))
path = root / 'InitializrSpringbootProjectFresh/src/test/java/net/citotech/cito/gateway/MobileMoneyMysqlScenario.java'
text = path.read_text()
marker = '            var recoveryLeases = new MobileMoneyRecoveryLeaseStore(jdbc);'
if text.count(marker) != 1:
    raise RuntimeError('Canonical finance fixture changed')
text = text.replace(marker, '''            jdbc.update("UPDATE mobile_money_executions SET next_poll_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP) WHERE transaction_id=:tx",
                    new MapSqlParameterSource("tx", collection.getTransactionId()));
''' + marker)
path.write_text(text)
print('NATIVE_SUBMISSION_PRESERVED; canonical financial failure/lease fixture retained')
