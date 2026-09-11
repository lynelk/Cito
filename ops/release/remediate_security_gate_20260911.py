#!/usr/bin/env python3
"""Apply the reviewed security fixes in a candidate worktree; never deploy.

Sources checked 2026-09-11:
- https://spring.io/security/cve-2026-47884/
- https://spring.io/security/cve-2026-59283/
- https://spring.io/security/cve-2026-59313/
- https://spring.io/security/cve-2026-59270/
- https://tomcat.apache.org/security-11
- https://github.com/netty/netty/security/advisories/GHSA-272m-gcwp-mpwg
- https://github.com/github/codeql-action/blob/main/analyze/action.yml

No vulnerability suppressions, lower thresholds, waived tests or account-level
security changes. The regular advanced scan still executes security-extended
queries and explicitly fails on high/critical SARIF results; repository default
setup remains responsible for uploading findings into GitHub code scanning.
"""
from pathlib import Path
import re

root = Path(__file__).resolve().parents[2]
pom = root / 'InitializrSpringbootProjectFresh/pom.xml'
text = pom.read_text()
patches = {
    'spring-framework.version': '7.0.9',
    'spring-security.version': '7.1.1',
    'tomcat.version': '11.0.25',
    'netty.version': '4.2.16.Final',
}
for name, version in patches.items():
    pattern = rf'<{re.escape(name)}>[^<]+</{re.escape(name)}>'
    replacement = f'<{name}>{version}</{name}>'
    if re.search(pattern, text):
        text = re.sub(pattern, replacement, text)
    else:
        text = text.replace('<java.version>21</java.version>',
            '<java.version>21</java.version>\n        ' + replacement, 1)
# This product uses its own protected API workbench, never the bundled Swagger UI.
# Keep runtime OpenAPI generation while removing unused browser assets.
assert 'springdoc-openapi-starter-webmvc-ui' in text
text = text.replace('springdoc-openapi-starter-webmvc-ui', 'springdoc-openapi-starter-webmvc-api')
pom.write_text(text)

workflow = root / '.github/workflows/ci.yml'
text = workflow.read_text()
old = '''      - name: Perform CodeQL Analysis
        uses: github/codeql-action/analyze@v4
        with:
          category: "/language:java"
'''
new = '''      - name: Perform CodeQL Analysis
        uses: github/codeql-action/analyze@v4
        with:
          category: "/language:java"
          # Default setup remains enabled and owns repository uploads. An
          # advanced upload otherwise fails processing as a duplicate setup.
          upload: never
          output: codeql-results
      - name: Enforce high and critical CodeQL findings
        run: python3 ops/release/check_codeql_sarif.py codeql-results
      - name: Retain complete CodeQL findings for review
        if: always()
        uses: actions/upload-artifact@v7
        with:
          name: codeql-security-extended-${{ github.sha }}
          path: codeql-results/*.sarif
          if-no-files-found: error
'''
assert old in text, 'CodeQL configuration changed; re-review before patching'
workflow.write_text(text.replace(old, new, 1))

(root / 'ops/release/check_codeql_sarif.py').write_text('''#!/usr/bin/env python3
"""Fail closed on absent/invalid scans and every high/critical security finding."""
from pathlib import Path
import json
import sys

files = list(Path(sys.argv[1]).glob('*.sarif'))
if not files:
    raise SystemExit('No CodeQL SARIF produced; security gate cannot pass')
findings = []
for filename in files:
    data = json.loads(filename.read_text())
    runs = data.get('runs')
    if not isinstance(runs, list) or not runs:
        raise SystemExit('Invalid or empty CodeQL run container')
    for run in runs:
        for invocation in run.get('invocations', []):
            if invocation.get('executionSuccessful') is False:
                raise SystemExit('CodeQL execution was not successful')
        rules = {rule['id']: rule for rule in run['tool']['driver'].get('rules', [])}
        for result in run.get('results', []):
            rule_id = result.get('ruleId', '')
            rule = rules.get(rule_id, {})
            properties = rule.get('properties', {})
            severity = float(properties.get('security-severity', 0))
            level = result.get('level', rule.get('defaultConfiguration', {}).get('level', 'warning'))
            if severity >= 7 or level == 'error':
                locations = result.get('locations', [])
                where = locations[0].get('physicalLocation', {}) if locations else {}
                findings.append({'rule': rule_id, 'securitySeverity': severity,
                                 'level': level, 'location': where})
print(json.dumps({'sarifFiles': len(files), 'blockingFindings': findings}, indent=2))
if findings:
    raise SystemExit('CodeQL high/critical findings require remediation; no suppressions applied')
''')

release = root / 'Docs/Api/API-REFERENCE-RELEASE.md'
with release.open('a') as output:
    output.write('''\n## Restored security gates — 11 September 2026\n\nCI had been manually disabled and has been re-enabled. Its dependency scan identified affected Spring Framework 7.0.8, Spring Security 7.1.0, Netty 4.2.15 and Tomcat 11.0.22. The candidate pins vendor-published fixes: Spring Framework 7.0.9, Security 7.1.1, Netty 4.2.16.Final and Tomcat 11.0.25. Runtime OpenAPI uses the API-only Springdoc starter; unused Swagger UI assets are no longer packaged. This does not expose full-system APIs or alter session permissions. All tests and fresh vulnerability scans must pass after dependency resolution.\n\nGitHub default CodeQL setup already uploads repository analyses. The advanced security-extended scan now retains complete SARIF as an exact-SHA artifact and fails explicitly on high/critical findings instead of making a second, rejected upload. Default setup stays enabled. No scan is skipped, vulnerability is suppressed, CVSS threshold is lowered, or security failure is marked successful.\n\nThe merchant portal, admin workbench and public website remain coupled to the same release; no visual change is required for these dependency/configuration fixes. Brand baseline remains 1.2.\n''')
print('Security candidate prepared:', patches, 'Swagger UI removed; strict local CodeQL gate retained')
