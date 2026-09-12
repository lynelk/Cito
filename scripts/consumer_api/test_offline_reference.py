#!/usr/bin/env python3
"""Exercise the delivered offline reference only. Never load a Cito/provider endpoint."""
from pathlib import Path
import json
import sys
import tempfile
from urllib.parse import urlsplit
import zipfile
from playwright.sync_api import sync_playwright

archive = Path(sys.argv[1]).resolve()
evidence = Path(sys.argv[2]).resolve()
evidence.mkdir(parents=True, exist_ok=True)
checks = []

def check(name, value):
    checks.append({'check': name, 'passed': bool(value)})
    if not value:
        raise AssertionError(name)

with tempfile.TemporaryDirectory(prefix='cito-offline-docs-') as folder:
    root = Path(folder).resolve()
    with zipfile.ZipFile(archive) as package:
        for name in package.namelist():
            if not (root / name).resolve().is_relative_to(root):
                raise AssertionError('Unsafe archive member')
        package.extractall(root)
    source = json.loads((root / 'external-openapi.json').read_text())
    total = sum(len(item) for item in source['paths'].values())
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(headless=True)
        context = browser.new_context(viewport={'width': 1440, 'height': 1000}, reduced_motion='reduce')
        external = []
        errors = []
        def route(request):
            if urlsplit(request.request.url).scheme != 'file':
                external.append('EXTERNAL_REQUEST_BLOCKED')
                request.abort()
            else:
                request.continue_()
        context.route('**/*', route)
        page = context.new_page()
        page.on('pageerror', lambda error: errors.append(type(error).__name__))
        page.goto((root / 'index.html').as_uri(), wait_until='load')
        check('portable_heading', page.get_by_role('heading', name='Cito External Developer API — 2.0.0', exact=True).is_visible())
        check('all_external_operations', page.locator('#operations > details').count() == total == 67)
        search = page.get_by_role('searchbox', name='Search endpoint, operation or authentication')
        search.fill('listWebhookEvents')
        check('search_filters_operation', page.locator('#operations > details').count() == 1)
        result = page.locator('#operations > details').first
        result.locator('summary').focus()
        page.keyboard.press('Enter')
        check('keyboard_expands_contract', result.locator('pre').is_visible())
        check('contract_lists_merchant_scope', 'merchantNumber' in result.inner_text())
        search.fill('this-operation-does-not-exist')
        check('truthful_empty_search', page.get_by_role('status').inner_text() == '0 operations')
        search.fill('channels')
        check('schema_dictionary_present', page.locator('#schemas > details').count() == len(source['components']['schemas']))
        for width in (320, 390, 768, 1440):
            page.set_viewport_size({'width': width, 'height': 1000})
            check('responsive_' + str(width), page.evaluate('document.documentElement.scrollWidth <= window.innerWidth + 2'))
            if width in (390, 1440):
                page.screenshot(path=str(evidence / ('offline-reference-' + str(width) + '.png')), animations='disabled')
        page.set_viewport_size({'width': 390, 'height': 1000})
        page.evaluate("document.documentElement.style.fontSize='200%'")
        search.focus()
        page.keyboard.press('Tab')
        check('enlarged_text_no_overflow', page.evaluate('document.documentElement.scrollWidth <= window.innerWidth + 2'))
        check('keyboard_focus_visible', page.evaluate('document.activeElement !== document.body'))
        check('no_external_requests', not external)
        check('no_browser_runtime_errors', not errors)
        context.close()
        browser.close()
    output = {'offlineReference': 'PASS', 'providerRequests': 0, 'checks': checks}
    (evidence / 'offline-reference-browser.json').write_text(json.dumps(output, indent=2) + '\n')
    print(json.dumps(output))
