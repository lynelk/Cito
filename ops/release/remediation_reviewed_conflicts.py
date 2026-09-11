"""Explicitly reviewed integration decisions for four pinned-source conflicts.

No blanket ours/theirs strategy. Unknown conflict hunks remain unresolved.
Current main's money helpers, endpoint policy and MTN safe profiles win over
older equivalents; only the requested Airtel orchestration/scoping is added.
"""
import re


def airtel_dispatch_on_current(current: str) -> str:
    marker = 'if (AirtelMoneyOpenApiPaymentGateway.isValidMisdn(msisdn)) {'
    starts = [match.start() for match in re.finditer(re.escape(marker), current)]
    replacements = []
    operations = []
    tokens = re.compile(r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*[\s\S]*?\*/|[{}]')
    for start in starts:
        opening = start + len(marker) - 1
        depth = 0
        closing = None
        for token in tokens.finditer(current, opening):
            if token.group() == '{':
                depth += 1
            elif token.group() == '}':
                depth -= 1
                if depth == 0:
                    closing = token.start()
                    break
        if closing is None:
            raise RuntimeError('Unbalanced Airtel branch')
        body = current[opening + 1:closing]
        inbound = 'airteloapimm_mmpgw.doPayIn(amount, msisdn, ref, narrative)' in body
        outbound = 'airteloapimm_mmpgw.doPayOut(amount, msisdn, ref, narrative)' in body
        if not inbound and not outbound:
            # Legacy status enquiries also validate MSISDN. They are not initiation.
            continue
        if inbound == outbound or 'gw_airtelmoney_api_public_key' not in body:
            raise RuntimeError('Unexpected Airtel initiation branch contents')
        operation = 'COLLECT' if inbound else 'PAYOUT'
        operations.append(operation)
        replacement = ('\n                return net.citotech.cito.gateway.AirtelRecoveryRegistry.submit(\n'
                       f'                        merchantId, amount, msisdn, ref, narrative, "{operation}");\n'
                       '            ')
        replacements.append((opening + 1, closing, replacement))
    if sorted(operations) != ['COLLECT', 'PAYOUT']:
        raise RuntimeError('Expected exactly one collection and one payout initiation branch')
    result = current
    for start, end, replacement in reversed(replacements):
        result = result[:start] + replacement + result[end:]
    for method in ('calculateLegacyFee', 'getCustomerInboundChargesDecimal',
                   'getCustomerOutboundChargesDecimal', 'getCostOfInboundChargesDecimal',
                   'getCostOfOutboundChargesDecimal'):
        if result.count(method) != current.count(method):
            raise RuntimeError('Current-main money helpers must remain unchanged')
    return result


def resolve_known_hunks(path: str, text: str) -> str:
    pattern = re.compile(r'^<<<<<<<[^\n]*\n(.*?)^\|\|\|\|\|\|\|[^\n]*\n(.*?)^=======\n(.*?)^>>>>>>>[^\n]*\n', re.M | re.S)

    def reviewed(match: re.Match) -> str:
        ours, ancestor, theirs = match.group(1, 2, 3)
        if path.endswith('/ledger/DoubleEntryLedgerServiceTestcontainersTest.java'):
            if ('LedgerReservationMysqlScenario.run(dataSource)' in ours
                    and 'void reservationsUseCurrentBalancesEvenWhenOuterTransactionHasAnOlderSnapshot()' in theirs
                    and not ancestor.strip()):
                return ours
        if path == 'clientside/src/features/ProviderTreasuryConsole.tsx':
            if ('import { changeProviderScope, mtnProfile }' in ours
                    and "import { filterProviderRows, filterProviderAdjustments } from './providerScope';" in theirs
                    and not ancestor.strip()):
                return ours + theirs
            if ('const [entitlement, setEntitlement]' in ours
                    and 'useState(() => changeProviderScope({' in ours
                    and "channelCode: channelScope || 'airtel_money'" in theirs):
                return ours.replace("channelCode: 'airtel_money'", "channelCode: channelScope || 'airtel_money'", 1)
            if ("new URLSearchParams(window.location.search).get('channel')" in ours
                    and "'SANDBOX'));" in ours and 'const [filters, setFilters]' in ours
                    and "channel: channelScope || ''" in theirs):
                return ours.replace('}, new URLSearchParams', '}, channelScope || (new URLSearchParams', 1).replace(
                    ": 'mtn_momo', 'SANDBOX'));", ": 'mtn_momo'), 'SANDBOX'));", 1).replace(
                    "channel: ''", "channel: channelScope || ''", 1)
        return match.group()

    return pattern.sub(reviewed, text)
