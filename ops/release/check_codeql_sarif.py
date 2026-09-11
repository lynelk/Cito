#!/usr/bin/env python3
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
