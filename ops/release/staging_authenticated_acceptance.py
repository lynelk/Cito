#!/usr/bin/env python3
"""Authenticated, no-money acceptance on the designated isolated staging deployment.

Requires requests, pymysql, bcrypt and Playwright with Chromium. Credentials are
random in-memory fixture values, never printed. This cannot target production.
"""
from __future__ import annotations

import json
import os
import re
import secrets
import sys
import time
from urllib.parse import urlsplit

PROJECT = 'c69c90a9-ab6f-48ff-8e04-1e10a10f92db'
ENVIRONMENT = 'efde4ddb-0312-48bc-94b2-a096fd3678b0'
BASE = 'https://cito-staging-web-production.up.railway.app'
DB_HOST = 'cito-staging-main-mysql.railway.internal'
CHECKS: list[dict] = []


def check(name: str, condition: bool) -> None:
    CHECKS.append({'check': name, 'passed': bool(condition)})
    print(json.dumps(CHECKS[-1]), flush=True)
    if not condition:
        raise RuntimeError(name)


def main() -> int:
    check('designated_staging_project', os.environ.get('RAILWAY_PROJECT_ID') == PROJECT)
    check('designated_staging_environment', os.environ.get('RAILWAY_ENVIRONMENT_ID') == ENVIRONMENT)
    expected = os.environ.get('QA_RELEASE_SHA', '')
    check('immutable_expected_revision', bool(re.fullmatch('[0-9a-f]{40}', expected)))
    database = urlsplit(os.environ.get('DB_URL', '').removeprefix('jdbc:'))
    check('isolated_database_host', database.hostname == DB_HOST and database.path == '/cpayadmin')
    import bcrypt
    import pymysql
    import requests
    from playwright.sync_api import sync_playwright

    def parity() -> None:
        release = requests.get(BASE + '/releasez', timeout=20).json()
        health = requests.get(BASE + '/readyz', timeout=20).json()
        check('live_exact_revision', release.get('release_sha') == expected and health.get('release_sha') == expected)
        check('database_up_and_sandbox', health.get('db') == 'UP' and health.get('gateway_mode') == 'SANDBOX' and health.get('status') == 'UP')

    parity()
    db = pymysql.connect(host=DB_HOST, port=database.port or 3306,
                         user=os.environ['DB_USERNAME'], password=os.environ['DB_PASSWORD'],
                         database='cpayadmin', charset='utf8mb4', connect_timeout=15, autocommit=False)
    suffix = secrets.token_hex(10)
    fixture_name = 'Release QA ' + suffix
    password = secrets.token_urlsafe(32)
    password_hash = bcrypt.hashpw(password.encode(), bcrypt.gensalt(rounds=12)).decode()
    admin_email = 'release-qa-admin-' + suffix + '@example.invalid'
    owner_email = 'release-qa-owner-' + suffix + '@example.invalid'
    viewer_email = 'release-qa-viewer-' + suffix + '@example.invalid'
    merchant_numbers = ['QA-' + suffix + '-A', 'QA-' + suffix + '-B']
    admin_id = None
    merchant_ids: list[int] = []
    merchant_user_ids: list[int] = []
    cleanup_ok = False
    browser_ok = False
    try:
        with db.cursor() as cursor:
            cursor.execute('SELECT version FROM flyway_schema_history WHERE success=1 AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1')
            check('flyway_head_128', cursor.fetchone()[0] == '128')
            cursor.execute('INSERT INTO admins(name,email,phone,password,status) VALUES(%s,%s,%s,%s,%s)', (fixture_name, admin_email, '', password_hash, 'ACTIVE'))
            admin_id = cursor.lastrowid
            for account in merchant_numbers:
                cursor.execute('INSERT INTO merchants(name,status,account_number,created_by,account_type,short_name,allowed_apis) VALUES(%s,%s,%s,%s,%s,%s,%s)', (fixture_name, 'ACTIVE', account, 'release-acceptance', 'business', 'QA', ''))
                merchant_ids.append(cursor.lastrowid)
            for email, role in ((owner_email, 'OWNER'), (viewer_email, 'VIEWER')):
                cursor.execute('INSERT INTO merchant_admins(merchant_id,name,email,phone,password,status,role,email_verified_at) VALUES(%s,%s,%s,%s,%s,%s,%s,NOW())', (merchant_ids[0], fixture_name, email, '', password_hash, 'ACTIVE', role))
                merchant_user_ids.append(cursor.lastrowid)
            cursor.execute('INSERT INTO audit_trail(user_name,user_id,action) VALUES(%s,%s,%s)', ('release-acceptance', str(admin_id), 'Created isolated synthetic QA fixtures for release ' + expected + '; no funds or provider credentials provisioned'))
        db.commit()
        check('dedicated_synthetic_fixtures', len(merchant_ids) == 2 and len(merchant_user_ids) == 2)
        with sync_playwright() as playwright:
            browser = playwright.chromium.launch(headless=True)
            contexts = []
            errors: list[str] = []

            def context():
                ctx = browser.new_context(base_url=BASE, viewport={'width': 1440, 'height': 1000}, reduced_motion='reduce')
                ctx.route('**/*', lambda route: route.continue_() if urlsplit(route.request.url).netloc == urlsplit(BASE).netloc else route.abort())
                contexts.append(ctx)
                return ctx

            def login(kind: str, email: str):
                ctx = context()
                page = ctx.new_page()
                page.on('pageerror', lambda error: errors.append(type(error).__name__))
                page.goto(BASE + ('/bo' if kind == 'admin' else '/fo'), wait_until='domcontentloaded')
                if kind != 'admin':
                    page.locator('#merchant-account').fill(merchant_numbers[0])
                page.locator('#' + kind + '-username').fill(email)
                page.locator('#' + kind + '-password').fill(password)
                endpoint = '/auth/authenticate' if kind == 'admin' else '/auth/authenticateMerchantUser'
                with page.expect_response(lambda response: urlsplit(response.url).path == endpoint and response.request.method == 'POST', timeout=30000) as response:
                    page.locator('form button[type=submit]').click()
                payload = response.value.json()
                check(kind + '_normal_password_login', payload.get('code') == '000')
                session = ctx.request.get(BASE + '/api/v2/session/me').json()
                check(kind + '_server_session', session.get('actorType') == ('ADMIN' if kind == 'admin' else 'MERCHANT'))
                if kind != 'admin':
                    check('merchant_session_scope', int(session.get('merchantId', 0)) == merchant_ids[0])
                return ctx, page

            anonymous = context()
            for endpoint in ('/v3/api-docs', '/api/v2/admin/api-reference/commercial', '/api/v2/portal/api-reference/openapi'):
                check('anonymous_denied_' + endpoint, anonymous.request.get(BASE + endpoint).status in (401, 403))

            admin, admin_page = login('admin', admin_email)
            for endpoint in ('/api/v2/admin/api-reference/commercial', '/api/v2/admin/api-reference/rates', '/v3/api-docs', '/api/v2/admin/communication/routing/providers', '/api/v2/admin/shared-provider/credentials'):
                response = admin.request.get(BASE + endpoint)
                check('admin_authorized_' + endpoint, response.status == 200)
                response.json()
            check('admin_csrf_enforced', admin.request.post(BASE + '/api/v2/admin/api-reference/rates', data={}).status == 403)
            admin_page.goto(BASE + '/bo/admin/api-reference')
            workbench = admin_page.get_by_role('region', name='Administrator API workbench')
            workbench.get_by_role('button', name='Integration guide', exact=True).click()
            workbench.get_by_role('searchbox').fill('MTN configuration ownership')
            check('admin_searchable_updated_guide', workbench.get_by_text('MTN configuration ownership and verification', exact=True).is_visible(timeout=20000))
            with admin_page.expect_download() as download:
                workbench.get_by_role('button', name='Download guide', exact=True).click()
            check('admin_guide_download', download.value.failure() is None)

            admin_page.goto(BASE + '/bo/admin/settings')
            admin_page.get_by_text('MTN MoMo', exact=True).first.click()
            link = admin_page.get_by_role('link', name='Configure and verify MTN MoMo')
            link.wait_for(state='visible')
            check('settings_canonical_link', link.get_attribute('href') == '/bo/provider-treasury?channel=mtn_momo#platform-provider-credentials')
            check('settings_no_false_test_button', admin_page.get_by_role('button', name='Test connection', exact=True).count() == 0)
            link.click()
            api_key = admin_page.get_by_label('Collection API key', exact=True)
            api_key.wait_for(state='visible')
            form = admin_page.locator('form').filter(has=api_key)
            check('safe_sandbox_default', form.get_by_label('MTN API base URL', exact=True).input_value() == 'https://sandbox.momodeveloper.mtn.com' and form.get_by_label('MTN base currency', exact=True).input_value() == 'EUR')
            api_key.fill('synthetic-unsaved-value')
            form.get_by_label('Environment', exact=True).select_option('PRODUCTION')
            check('production_profile_derived', form.get_by_label('X-Target-Environment', exact=True).input_value() == 'mtnuganda' and form.get_by_label('MTN base currency', exact=True).input_value() == 'UGX')
            check('scope_switch_clears_unsaved_key', api_key.input_value() == '')
            form.get_by_label('Environment', exact=True).select_option('SANDBOX')
            for width in (320, 390, 768, 1440):
                admin_page.set_viewport_size({'width': width, 'height': 1000})
                check('provider_layout_' + str(width), admin_page.evaluate('document.documentElement.scrollWidth <= window.innerWidth + 2'))
            admin_page.evaluate("document.documentElement.style.fontSize='200%'")
            api_key.focus()
            admin_page.keyboard.press('Tab')
            check('keyboard_and_200_percent_text', admin_page.evaluate("document.activeElement !== document.body") and api_key.is_visible())

            merchant, merchant_page = login('merchant', owner_email)
            for endpoint in ('/api/v2/portal/api-reference/openapi', '/api/v2/portal/api-reference/rates', '/api/v2/merchant-self-service/channels', '/api/v2/portal/dashboard/summary', '/api/v2/portal/transactions', '/api/v2/portal/sms'):
                response = merchant.request.get(BASE + endpoint)
                check('merchant_authorized_' + endpoint, response.status == 200)
                response.json()
            for endpoint in ('/v3/api-docs', '/api/v2/admin/api-reference/commercial', '/api/v2/admin/shared-provider/credentials'):
                check('merchant_admin_boundary_' + endpoint, merchant.request.get(BASE + endpoint).status in (401, 403))
            check('merchant_cross_tenant_denied', merchant.request.get(BASE + '/api/v2/merchants/' + str(merchant_ids[1]) + '/overview').status in (401, 403, 404))
            merchant_page.goto(BASE + '/fo/developers')
            reference = merchant_page.get_by_role('region', name='Developer API reference')
            reference.get_by_role('button', name='Integration guide', exact=True).click()
            reference.get_by_role('searchbox').fill('MTN configuration ownership')
            check('merchant_searchable_updated_guide', reference.get_by_text('MTN configuration ownership and verification', exact=True).is_visible(timeout=20000))
            with merchant_page.expect_download() as download:
                reference.get_by_role('button', name='Download OpenAPI', exact=True).click()
            check('merchant_openapi_download', download.value.failure() is None)

            viewer, _ = login('merchant', viewer_email)
            csrf = viewer.request.get(BASE + '/auth/csrf').json()
            response = viewer.request.post(BASE + '/api/v2/merchant-self-service/channels/save', headers={csrf['headerName']: csrf['token']}, data={'channelCode': 'mtn_momo', 'environment': 'SANDBOX', 'credentials': {}})
            check('viewer_credential_write_denied', response.status in (400, 403) and 'CHANNEL_SAVE_REJECTED' in response.text())
            for ctx in (admin, merchant, viewer):
                csrf = ctx.request.get(BASE + '/auth/csrf').json()
                ctx.request.post(BASE + '/auth/logout', headers={csrf['headerName']: csrf['token']}, data={})
                check('logout_revokes_session', ctx.request.get(BASE + '/api/v2/session/me').status == 401)
            check('no_browser_runtime_errors', not errors)
            for ctx in contexts:
                ctx.close()
            browser.close()
            browser_ok = True
        parity()
    finally:
        try:
            db.rollback()
            with db.cursor() as cursor:
                if admin_id:
                    cursor.execute('UPDATE admins SET status=%s WHERE id=%s AND email=%s', ('SUSPENDED', admin_id, admin_email))
                    cursor.execute('DELETE FROM SPRING_SESSION WHERE PRINCIPAL_NAME=%s', ('admin-user:' + str(admin_id),))
                for uid in merchant_user_ids:
                    cursor.execute('UPDATE merchant_admins SET status=%s WHERE id=%s AND name=%s', ('SUSPENDED', uid, fixture_name))
                    cursor.execute('DELETE FROM SPRING_SESSION WHERE PRINCIPAL_NAME=%s', ('merchant-user:' + str(uid),))
                for mid in merchant_ids:
                    cursor.execute('UPDATE merchants SET status=%s WHERE id=%s AND name=%s', ('SUSPENDED', mid, fixture_name))
                cursor.execute('INSERT INTO audit_trail(user_name,user_id,action) VALUES(%s,%s,%s)', ('release-acceptance', str(admin_id or ''), 'Suspended isolated QA fixtures and revoked their sessions for release ' + expected))
            db.commit()
            cleanup_ok = True
        finally:
            db.close()
        print(json.dumps({'qa_cleanup_completed': cleanup_ok, 'release_sha': expected, 'authenticated_browser_acceptance': browser_ok, 'provider_transactions': 0, 'production_changes': 0, 'checks': CHECKS}), flush=True)
    check('authenticated_acceptance_complete', browser_ok and cleanup_ok)
    return 0


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except Exception as error:
        print(json.dumps({'acceptance': 'FAILED', 'error_type': type(error).__name__, 'last_check': CHECKS[-1] if CHECKS else None}), flush=True)
        sys.exit(1)
