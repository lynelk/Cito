#!/usr/bin/env python3
"""Exact-source acceptance runner with UI namespace and asynchronous-render waits.

The application under test is unchanged. This corrects the test's response URL
matcher to include the existing /api/ui prefix and records sanitized stack locations.
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
old = 'urlsplit(response.url).path == endpoint'
assert source.count(old) == 1
source = source.replace(old, "urlsplit(response.url).path.removeprefix('/api/ui') == endpoint")
source = source.replace("BASE + '/auth/csrf'", "BASE + '/api/ui/auth/csrf'")
source = source.replace("BASE + '/auth/logout'", "BASE + '/api/ui/auth/logout'")
for parent in ('workbench', 'reference'):
    old = parent + ".get_by_text('MTN configuration ownership and verification', exact=True).is_visible(timeout=20000)"
    assert source.count(old) == 1
    source = source.replace(old, "visible(" + parent + ".get_by_text('MTN configuration ownership and verification', exact=True))")

def visible(locator):
    locator.wait_for(state='visible', timeout=20000)
    return locator.is_visible()

print('QA_TEST_ADAPTER: canonical /api/ui auth namespace; no acceptance assertion removed', flush=True)
try:
    exec(compile(source, 'staging_authenticated_acceptance.py', 'exec'), {'__name__': '__main__', 'visible': visible})
except SystemExit as error:
    if error.code and error.__context__:
        locations = [{'file': Path(f.filename).name, 'line': f.lineno, 'function': f.name} for f in traceback.extract_tb(error.__context__.__traceback__)]
        print(json.dumps({'failure_locations': locations}), flush=True)
    raise
