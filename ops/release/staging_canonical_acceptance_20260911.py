#!/usr/bin/env python3
"""Extend the previously accepted authentication harness, without weakening its assertions.

Only the designated isolated staging project/worker may execute this. Prior QA
accounts are not reused; the harness creates, suspends and revokes synthetic ones.
No payment, provider call, valid tariff change or commercial message is initiated.
"""
import hashlib
import os
import urllib.request

assert os.environ.get('RAILWAY_PROJECT_ID') == 'c69c90a9-ab6f-48ff-8e04-1e10a10f92db'
assert os.environ.get('RAILWAY_ENVIRONMENT_ID') == 'efde4ddb-0312-48bc-94b2-a096fd3678b0'
assert os.environ.get('RAILWAY_SERVICE_ID') == '72b7b913-82e8-4616-805f-9461f0f05cd5'
url = 'https://raw.githubusercontent.com/lynelk/Cito/7b2c76021fbfd7376bb2f69f3ac5b36bf347e080/ops/release/staging_acceptance_runner.py'
data = urllib.request.urlopen(url,timeout=30).read()
assert hashlib.sha1(b'blob '+str(len(data)).encode()+b'\0'+data).hexdigest() == 'c148f333d0d3cbf494d3d92cfe7a8fbc86e98d12'
# Run the adapter's verified corrections but take control before its final execution.
source = data.decode()
marker = "try:\n    exec(compile(source, 'staging_authenticated_acceptance.py'"
assert source.count(marker) == 1
prefix = source[:source.index(marker)]
context = {'__name__':'canonical_acceptance_adapter'}
exec(compile(prefix,'verified_staging_acceptance_adapter.py','exec'),context)
harness = context['source']

def patch(old, new, count=1):
    global harness
    assert harness.count(old) == count, 'Pinned acceptance source changed'
    harness = harness.replace(old,new)

patch("check('flyway_head_128', cursor.fetchone()[0] == '128')", "check('flyway_head_129', cursor.fetchone()[0] == '129')\n            cursor.execute(\"SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='mobile_money_executions' AND column_name IN ('recovery_claim_token','recovery_claim_until','recovery_attempt_count','recovery_last_code','recovery_last_signal_at')\")\n            check('canonical_recovery_schema', cursor.fetchone()[0] == 5)")
patch("            viewer, _ = login('merchant', viewer_email)", '''            own_path = '/api/v2/merchants/' + str(merchant_ids[0]) + '/onboarding'
            other_path = '/api/v2/merchants/' + str(merchant_ids[1]) + '/onboarding'
            own = merchant.request.get(BASE + own_path)
            check('merchant_readiness_authorized', own.status == 200)
            assessment = own.json().get('readinessAssessment', {})
            check('synthetic_unconfigured_not_production_ready', assessment.get('state') == 'NOT_CONFIGURED' and assessment.get('readyForProduction') is False and assessment.get('productionEnabled') is False and assessment.get('providerActivationImplied') is False)
            check('readiness_cross_tenant_denied', merchant.request.get(BASE + other_path).status in (401,403,404))
            admin_view = admin.request.get(BASE + own_path)
            check('administrator_same_readiness_evidence', admin_view.status == 200 and admin_view.json().get('readinessAssessment') == assessment)
            admin_page.goto(BASE + '/bo/admin/merchant-readiness?merchantId=' + str(merchant_ids[0]))
            check('admin_readiness_page', visible(admin_page.get_by_role('heading',name='Merchant readiness',exact=True)))
            check('admin_readiness_truthful_state', visible(admin_page.get_by_text('Not configured',exact=True)))
            admin_page.get_by_label('Merchant ID',exact=True).fill(str(merchant_ids[1]))
            admin_page.get_by_role('button',name='Inspect readiness',exact=True).click()
            check('admin_readiness_deliberate_scope_switch', visible(admin_page.get_by_text('Not configured',exact=True)) and 'merchantId=' + str(merchant_ids[1]) in admin_page.url)
            for width in (320,390,768,1440):
                admin_page.set_viewport_size({'width':width,'height':1000})
                check('readiness_responsive_' + str(width), admin_page.evaluate('document.documentElement.scrollWidth <= window.innerWidth + 2'))
            merchant_page.goto(BASE + '/fo/developers')
            quickstart = merchant_page.get_by_role('complementary',name='Safe developer quickstart')
            check('merchant_safe_quickstart_visible', visible(quickstart.get_by_role('button',name='Explore capability documentation',exact=True)))
            quickstart.get_by_role('button',name='Explore capability documentation',exact=True).click()
            check('quickstart_filters_reference_only', merchant_page.get_by_role('region',name='Developer API reference').get_by_role('searchbox').input_value() == 'capabilities')
            check('no_unsupported_async_capability_in_guide', 'asynchronous callbacks are intentionally unsupported' in merchant.request.get(BASE + '/api/v2/portal/api-reference/guide').text().lower())
            viewer, _ = login('merchant', viewer_email)''')
exec(compile(harness,'canonical_authenticated_staging_acceptance.py','exec'), {'__name__':'__main__','visible':context['visible']})
