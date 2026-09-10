"""One-use, assertion-guarded source edits for the Airtel recovery release."""
from pathlib import Path

ROOT = Path('.')
JAVA = ROOT / 'InitializrSpringbootProjectFresh/src/main/java/net/citotech/cito'
TEST = ROOT / 'InitializrSpringbootProjectFresh/src/test/java/net/citotech/cito'

def replace(text, old, new, count=1):
    assert text.count(old) == count, 'Unexpected source shape: ' + old[:80]
    return text.replace(old, new)

# The isolated Testcontainers databases need the same trigger capability the migration
# preflight requires. Do not disable that preflight or any financial tests.
changed = []
for path in TEST.rglob('*.java'):
    text = path.read_text()
    if 'new MySQLContainer("mysql:8.0.36")' in text and '--log-bin-trust-function-creators=1' not in text:
        text = text.replace('new MySQLContainer("mysql:8.0.36")', 'new MySQLContainer("mysql:8.0.36")\n                    .withCommand("--log-bin-trust-function-creators=1")')
        path.write_text(text)
        changed.append(path)
assert len(changed) == 12, f'Expected 12 isolated MySQL fixtures, found {len(changed)}'

path = ROOT/'InitializrSpringbootProjectFresh/src/main/resources/db/migration/V123__airtel_recovery_cursors.sql'
text = path.read_text()
assert 'airtel_recovery_scopes' not in text
text += '''
-- Immutable application attribution captured before sending an Airtel request.
-- No PIN, client secret, access token or customer number is stored.
CREATE TABLE airtel_recovery_scopes (
    merchant_id BIGINT NOT NULL,
    transaction_reference VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    operation VARCHAR(12) NOT NULL,
    identity_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (merchant_id, transaction_reference, operation)
) ENGINE=InnoDB;
'''
path.write_text(text)

# The legacy dispatcher already has the exact selected settings and commits the
# initial transaction before the remote call. Persist attribution before dispatch.
path = JAVA/'DoPayGateway.java'
text = path.read_text()
text = replace(text, 'public class DoPayGateway {', '''public class DoPayGateway {
    private boolean requireRecordedAirtelScope;
    public DoPayGateway withRecordedAirtelScope() {
        this.requireRecordedAirtelScope = true;
        return this;
    }
''')
collect_marker = 'return airteloapimm_mmpgw.doPayIn(amount, msisdn, ref, narrative);'
record = '''net.citotech.cito.gateway.AirtelRecoveryScopeStore.record(
                    jdbcTemplate, ref, merchantId, "%s",
                    useMerchantCreds ? "LEGACY_MERCHANT" : "LEGACY_PLATFORM",
                    global_url, api_username, "UG", "UGX");
                '''
text = replace(text, collect_marker, record % 'COLLECT' + collect_marker)
payout_marker = 'GateWayResponse pResponse = airteloapimm_mmpgw.doPayOut(amount, msisdn, ref, narrative);'
text = replace(text, payout_marker, record % 'PAYOUT' + payout_marker)
status_marker = 'GateWayResponse pResponse = airteloapimm_mmpgw.checkStatus(ref);'
text = replace(text, status_marker, '''if (requireRecordedAirtelScope) {
                    net.citotech.cito.gateway.AirtelRecoveryScopeStore.require(
                        jdbcTemplate, ref, merchantId,
                        "disbursement".equalsIgnoreCase(tx_type) ? "PAYOUT" : "COLLECT",
                        useMerchantCreds ? "LEGACY_MERCHANT" : "LEGACY_PLATFORM",
                        global_url, api_username, "UG", "UGX");
                }
                ''' + status_marker)
# Collection does not encrypt a payout PIN. Remove the obsolete caller-side key check.
start = text.index('public GateWayResponse runPayGatewayDoPayIn(')
end = text.index('public double[] runPayGatewayNetworkBalances(', start)
section = text[start:end]
a = section.index('                if (airteloapimm_mmpgw.getPublicKey().isEmpty()) {')
b = section.index('                net.citotech.cito.gateway.AirtelRecoveryScopeStore.record(', a)
assert 'return err;' in section[a:b]
section = section[:a] + section[b:]
text = text[:start] + section + text[end:]
# Remove pre-existing raw credential logging from this dispatcher. Retain safe errors elsewhere.
text = '\n'.join(line for line in text.split('\n') if 'logger.error("API User Details:' not in line)
path.write_text(text)

# Shared-provider attribution is committed with its reservation before execution.
path = JAVA/'treasury/ProviderTreasuryService.java'
text = path.read_text()
marker = '        String operation = context.operation();'
text = replace(text, marker, marker + '''
        if (net.citotech.cito.gateway.AirtelOpenApiCredentialSchema.CHANNEL_CODE.equals(channelCode)) {
            net.citotech.cito.gateway.AirtelRecoveryScopeStore.record(
                jdbc, reference, merchant.getId(), operation, "PLATFORM_SHARED",
                String.valueOf(context.credentials().get("baseUrl")),
                String.valueOf(context.credentials().get("clientId")),
                context.countryCode(), context.currencyCode());
        }
''')
path.write_text(text)

path = JAVA/'scheduler/AirtelOpenApiStatusPollScheduler.java'
text = path.read_text()
text = replace(text, 'new DoPayGateway()\n', 'new DoPayGateway().withRecordedAirtelScope()\n')
text = replace(text, '"SELECT r.id, r.operation, r.provider_reference, r.currency_code,"', '"SELECT r.id, r.merchant_id, r.operation, r.provider_reference, r.currency_code,"')
marker = '        AirtelMoneyOpenApiPaymentGateway gateway = new AirtelMoneyOpenApiPaymentGateway();'
text = replace(text, marker, '''        net.citotech.cito.gateway.AirtelRecoveryScopeStore.require(
                jdbcTemplate, submittedReference, number(row.get("merchant_id")), operation,
                "PLATFORM_SHARED", value(credentials, "baseUrl"), value(credentials, "clientId"), country, currency);
''' + marker)
text = replace(text, '"SELECT status, merchant_reference, treasury_account_id, operation, currency_code"', '"SELECT status, merchant_id, merchant_reference, treasury_account_id, operation, currency_code"')
marker = 'if (!submittedReference.equals(text(current.get("merchant_reference")))'
text = replace(text, marker, 'if (!text(row.get("merchant_id")).equals(text(current.get("merchant_id")))\n                                    || !submittedReference.equals(text(current.get("merchant_reference")))')
marker = '        String segment =\n'
text = replace(text, marker, '''        if (!"UGX".equalsIgnoreCase(text(observed.getCurrency())))
            throw new PaymentGatewayException("Legacy Airtel recovery requires its recorded UGX scope");
''' + marker)
path.write_text(text)

# Saved global credentials can be authenticated without selecting them for live traffic.
path = ROOT/'ops/readiness/provider_legacy_no_money.py'
text = path.read_text()
marker = '                emit({"source": "LEGACY_GLOBAL_NOT_SELECTED",'
lines = text.splitlines()
for index, line in enumerate(lines):
    if line.startswith(marker):
        lines.insert(index + 1, '                inspect(values, "LEGACY_GLOBAL_NOT_SELECTED")')
        break
else:
    raise AssertionError('Global non-selected diagnostic marker not found')
path.write_text('\n'.join(lines) + '\n')
