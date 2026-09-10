import unittest
from build_portal_reference import build
class PortalReferenceTest(unittest.TestCase):
    def test_private_paths_and_unused_admin_components_are_excluded(self):
        doc=build()
        self.assertTrue(doc['paths'])
        for path,item in doc['paths'].items():
            self.assertNotIn('/admin/',path)
            self.assertNotIn('callback',path.lower())
            self.assertNotIn('/api-reference',path)
            for operation in item.values():
                self.assertTrue(operation['security'])
                self.assertEqual(operation['x-cito-billing']['defaultRate'],'0.0000')
        for name in doc['components'].get('securitySchemes',{}):
            self.assertNotIn('AdminBasicAuth',name)
    def test_method_level_billing_includes_reads_and_writes(self):
        doc=build()
        self.assertIn('get',doc['paths']['/api/v2/payments/{reference}'])
        self.assertIn('post',doc['paths']['/api/v2/native/payments/collect'])
    def test_workspace_is_not_advertised_as_service_account_access(self):
        doc=build()
        self.assertEqual(doc['paths']['/api/v2/merchant-self-service/developer/projects']['get']['x-cito-audience'],'MERCHANT_WORKSPACE')
if __name__=='__main__': unittest.main()
