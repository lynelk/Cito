"""Offline TLS policy regression. Never invokes either diagnostic's main()."""
from __future__ import annotations

import ast
import importlib.util
from pathlib import Path
import ssl
import unittest

DIRECTORY = Path(__file__).resolve().parent
SCRIPTS = ('smtp_transport_readonly.py', 'smtp_single_admin_delivery_check.py')


def load_module(filename: str):
    spec = importlib.util.spec_from_file_location('cito_test_' + Path(filename).stem, DIRECTORY / filename)
    if spec is None or spec.loader is None:
        raise RuntimeError('Cannot load the committed diagnostic module')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class SmtpTlsPolicyTest(unittest.TestCase):
    def test_both_diagnostics_require_tls12_or_newer_and_certificate_validation(self):
        for filename in SCRIPTS:
            with self.subTest(script=filename):
                context = load_module(filename).tls_client_context()
                self.assertGreaterEqual(context.minimum_version, ssl.TLSVersion.TLSv1_2)
                self.assertTrue(context.check_hostname)
                self.assertEqual(context.verify_mode, ssl.CERT_REQUIRED)
                self.assertEqual(context.protocol, ssl.PROTOCOL_TLS_CLIENT)

    def test_contexts_are_not_shared_mutable_singletons(self):
        for filename in SCRIPTS:
            with self.subTest(script=filename):
                module = load_module(filename)
                first = module.tls_client_context()
                second = module.tls_client_context()
                self.assertIsNot(first, second)
                first.check_hostname = False
                self.assertTrue(second.check_hostname)

    def test_implicit_and_starttls_call_sites_use_the_verified_policy(self):
        for filename in SCRIPTS:
            with self.subTest(script=filename):
                tree = ast.parse((DIRECTORY / filename).read_text())
                functions = [node for node in tree.body if isinstance(node, ast.FunctionDef)]
                policy = next(node for node in functions if node.name == 'tls_client_context')
                default_calls = [node for node in ast.walk(tree) if isinstance(node, ast.Call)
                                 and isinstance(node.func, ast.Attribute)
                                 and node.func.attr == 'create_default_context']
                self.assertEqual(len(default_calls), 1)
                self.assertIn(default_calls[0], list(ast.walk(policy)))
                checked = 0
                for call in ast.walk(tree):
                    if not isinstance(call, ast.Call) or not isinstance(call.func, ast.Attribute):
                        continue
                    if call.func.attr in ('SMTP_SSL', 'starttls'):
                        contexts = [kw.value for kw in call.keywords if kw.arg == 'context']
                        self.assertEqual(len(contexts), 1)
                        self.assertIsInstance(contexts[0], ast.Call)
                        self.assertIsInstance(contexts[0].func, ast.Name)
                        self.assertEqual(contexts[0].func.id, 'tls_client_context')
                        checked += 1
                    elif call.func.attr == 'wrap_socket':
                        self.assertIsInstance(call.func.value, ast.Call)
                        self.assertIsInstance(call.func.value.func, ast.Name)
                        self.assertEqual(call.func.value.func.id, 'tls_client_context')
                        checked += 1
                self.assertEqual(checked, 2)


if __name__ == '__main__':
    unittest.main()
