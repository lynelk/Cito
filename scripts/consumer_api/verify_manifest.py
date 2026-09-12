#!/usr/bin/env python3
"""Verify an extracted handover bundle's manifest. No network or secret access."""
from pathlib import Path
import hashlib
import json
import re
import sys

root=Path(__file__).resolve().parent
manifest=json.loads((root/'MANIFEST.json').read_text())
if not re.fullmatch('[0-9a-f]{40}',manifest.get('sourceRevision','')):
    raise SystemExit('Missing immutable source revision')
for name,expected in manifest['files'].items():
    path=(root/name).resolve()
    if not path.is_relative_to(root) or not re.fullmatch('[0-9a-f]{64}',expected):
        raise SystemExit('Unsafe manifest entry')
    if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest()!=expected:
        raise SystemExit('Checksum mismatch: '+name)
print(json.dumps({'result':'PASS','sourceRevision':manifest['sourceRevision'],'filesVerified':len(manifest['files']),'providerRequests':0}))
