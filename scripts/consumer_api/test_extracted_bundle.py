#!/usr/bin/env python3
"""Test the actual delivered archive, not only a repository working copy. No provider access."""
from pathlib import Path
import hashlib,json,subprocess,sys,tempfile,zipfile
archive=Path(sys.argv[1])
with tempfile.TemporaryDirectory(prefix='cito-consumer-kit-') as temporary:
    root=Path(temporary)
    with zipfile.ZipFile(archive) as z:
        for name in z.namelist():
            if not (root/name).resolve().is_relative_to(root):raise SystemExit('Unsafe archive member')
        z.extractall(root)
    for command in ([sys.executable,'verify_manifest.py'],[sys.executable,'-m','pytest','sdk/tests','-q'],['node','sdk/tests/client-regressions.cjs'],['php','sdk/tests/client-regressions.php'],['node','sdk/tests/postman-regressions.cjs']):
        subprocess.run(command,cwd=root,check=True,timeout=90)
    manifest=json.loads((root/'MANIFEST.json').read_text())
    for name in manifest['files']:
        if name.endswith(('.pem','.key','.p12','.pfx')):raise SystemExit('Private-key material must not ship')
        if name.endswith('.json') and name.startswith('sdk/tests'):
            assert 'BEGIN PRIVATE KEY' not in (root/name).read_text()
    print(json.dumps({'extractedBundle':'PASS','sourceRevision':manifest['sourceRevision'],'sha256':hashlib.sha256(archive.read_bytes()).hexdigest(),'providerRequests':0}))
