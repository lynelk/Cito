"""Adversarial body whitespace must not trigger polynomial signer runtime."""
from pathlib import Path
import json
import subprocess


def test_node_and_shipped_postman_trim_long_internal_runs_with_a_bounded_deadline():
    root = Path(__file__).resolve().parents[2]
    result = subprocess.run(['node', str(Path(__file__).with_name('trim-regressions.cjs'))],
                            cwd=root, capture_output=True, text=True, timeout=10, check=True)
    evidence = json.loads(result.stdout)
    assert evidence['result'] == 'PASS'
    assert evidence['cases'] == 10
    assert evidence['providerRequests'] == 0
