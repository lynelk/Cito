#!/usr/bin/env python3
"""Exact-source staging acceptance adapter; no application or security policy changes.

Corrects test routing/selectors/DTOs and checks global administrator lists as
forbidden to merchants. Merchant report controls use their real scoped APIs and
only two read privileges on newly created synthetic owner fixtures.
"""
import hashlib
import json
import os
from pathlib import Path
import traceback
import urllib.request

assert os.environ.get('RAILWAY_PROJECT_ID') == 'c69c90a9-ab6f-48ff-8e04-1e10a10f92db'
assert os.environ.get('RAILWAY_ENVIRONMENT_ID') == 'efde4ddb-0312-48bc-94b2-a096fd3678b0'
url = 'https://raw.githubusercontent.com/lynelk/Cito/de3011367b67eac2cad65e508e72966a3f70b5ea/ops/release/staging_authenticated_acceptance.py'
data = urllib.request.urlopen(url, timeout=30).read()
assert hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest() == 'f2fed95694b435713d378efa6e9e7ed42a12aee3'
source = data.decode()

def replace(old, new, count=1):
    global source
    assert source.count(old) == count, 'Acceptance source changed; review required'
    source = source.replace(old, new)

replace('urlsplit(response.url).path == endpoint', "urlsplit(response.url).path.removeprefix('/api/ui') == endpoint")
replace("BASE + '/auth/csrf'", "BASE + '/api/ui/auth/csrf'", 2)
replace("BASE + '/auth/logout'", "BASE + '/api/ui/auth/logout'")
replace("form.get_by_label('Environment', exact=True)", "form.locator('label').filter(has_text=re.compile(r'^Environment')).locator('select')", 2)
for parent in ('workbench', 'reference'):
    replace(parent + ".get_by_text('MTN configuration ownership and verification', exact=True).is_visible(timeout=20000)", "visible(" + parent + ".get_by_text('MTN configuration ownership and verification', exact=True))")
replace("            check('admin_csrf_enforced', admin.request.post(BASE + '/api/v2/admin/api-reference/rates', data={}).status == 403)", '''            invalid_rate = json.dumps({'method': 'POST', 'path': '/__qa_nonexistent_rate_route__', 'amount': '0.0000', 'currency': 'UGX', 'expectedVersion': 0})
            negative = admin.request.post(BASE + '/api/v2/admin/api-reference/rates', headers={'Content-Type': 'application/json'}, data=invalid_rate)
            token = admin.request.get(BASE + '/api/ui/auth/csrf').json()
            control = admin.request.post(BASE + '/api/v2/admin/api-reference/rates', headers={token['headerName']: token['token'], 'Content-Type': 'application/json'}, data=invalid_rate)
            control_code = control.json().get('code') if 'application/json' in control.headers.get('content-type', '') else None
            print(json.dumps({'csrf_denial_http': negative.status, 'csrf_control_http': control.status, 'csrf_control_code': control_code}), flush=True)
            check('admin_csrf_enforced', negative.status in (401, 403) and control.status == 400 and control_code == 'INVALID_API_RATE')''')
replace("                merchant_user_ids.append(cursor.lastrowid)", '''                merchant_user_ids.append(cursor.lastrowid)
                if role == 'OWNER':
                    for privilege in ('ACCESS_TRANSACTION_LOG', 'ACCESS_SMS_LOG'):
                        cursor.execute('INSERT INTO merchant_admin_privileges(admin_id,privilege) VALUES(%s,%s)', (merchant_user_ids[-1], privilege))''')
replace("'/api/v2/portal/dashboard/summary', '/api/v2/portal/transactions', '/api/v2/portal/sms'", "'/api/v2/portal/dashboard/summary', '/api/v2/merchant-self-service/environment'")
replace("            for endpoint in ('/v3/api-docs', '/api/v2/admin/api-reference/commercial', '/api/v2/admin/shared-provider/credentials'):", '''            token = merchant.request.get(BASE + '/api/ui/auth/csrf').json()
            read_body = json.dumps({'pageSize': 10, 'currentPage': 0, 'searchingValue': {'category': 'all', 'value': ''}, 'search_rules': {'start_date': '', 'end_date': '', 'status': '', 'tx_type': ''}})
            for action in ('getMerchantTransactions', 'getMerchantSms'):
                result = merchant.request.post(BASE + '/api/ui/transactions/' + action, headers={token['headerName']: token['token'], 'Content-Type': 'application/json', 'X-CPay-Environment': 'SANDBOX'}, data=read_body)
                body = result.json()
                check('merchant_scoped_report_' + action, result.status == 200 and body.get('code') == '000' and body.get('data') == [])
            for endpoint in ('/v3/api-docs', '/api/v2/admin/api-reference/commercial', '/api/v2/admin/shared-provider/credentials', '/api/v2/portal/transactions', '/api/v2/portal/sms'):''')
replace("ctx.request.post(BASE + '/api/ui/auth/logout', headers={csrf['headerName']: csrf['token']}, data={})", "ctx.request.post(BASE + '/api/ui/auth/logout', headers={csrf['headerName']: csrf['token'], 'Content-Type': 'application/json'}, data='{}')")
replace("                    cursor.execute('UPDATE merchant_admins SET status=%s WHERE id=%s AND name=%s', ('SUSPENDED', uid, fixture_name))", "                    cursor.execute('UPDATE merchant_admins SET status=%s WHERE id=%s AND name=%s', ('SUSPENDED', uid, fixture_name))\n                    cursor.execute('DELETE FROM merchant_admin_privileges WHERE admin_id=%s', (uid,))")

def visible(locator):
    locator.wait_for(state='visible', timeout=20000)
    return locator.is_visible()

print('QA_TEST_ADAPTER: canonical auth/select routing; paired CSRF control; global-list denial and actual merchant-scoped read APIs; no provider or valid rate write', flush=True)
try:
    exec(compile(source, 'staging_authenticated_acceptance.py', 'exec'), {'__name__': '__main__', 'visible': visible})
except SystemExit as error:
    if error.code and error.__context__:
        locations = [{'file': Path(f.filename).name, 'line': f.lineno, 'function': f.name} for f in traceback.extract_tb(error.__context__.__traceback__)]
        print(json.dumps({'failure_locations': locations}), flush=True)
    raise
