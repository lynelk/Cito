#!/usr/bin/env python3
"""Read-only public login rendering diagnostic, restricted to isolated staging."""
import json
import os
from urllib.parse import urlsplit
from playwright.sync_api import sync_playwright

assert os.environ.get('RAILWAY_PROJECT_ID') == 'c69c90a9-ab6f-48ff-8e04-1e10a10f92db'
assert os.environ.get('RAILWAY_ENVIRONMENT_ID') == 'efde4ddb-0312-48bc-94b2-a096fd3678b0'
base = 'https://cito-staging-web-production.up.railway.app'
with sync_playwright() as p:
    browser = p.chromium.launch(headless=True)
    context = browser.new_context(viewport={'width': 1440, 'height': 1000})
    context.route('**/*', lambda route: route.continue_() if urlsplit(route.request.url).netloc == urlsplit(base).netloc else route.abort())
    for path in ('/bo', '/fo'):
        page = context.new_page()
        errors = []
        failures = []
        page.on('pageerror', lambda error: errors.append(str(error)[:500]))
        page.on('requestfailed', lambda req: failures.append(urlsplit(req.url).path))
        response = page.goto(base + path, wait_until='domcontentloaded', timeout=20000)
        page.wait_for_timeout(5000)
        print(json.dumps({'path': path, 'http': response.status, 'title': page.title(), 'page_path': urlsplit(page.url).path,
                          'inputs': page.locator('input').evaluate_all('(xs) => xs.map(x => ({id:x.id,name:x.name,type:x.type,visible:!!x.getClientRects().length}))'),
                          'buttons': page.locator('button').all_text_contents(),
                          'public_text': page.locator('body').inner_text()[:1200],
                          'page_errors': errors, 'failed_paths': failures}), flush=True)
        page.close()
    context.close()
    browser.close()
