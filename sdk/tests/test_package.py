import json,sys
from pathlib import Path
import pytest
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'scripts/consumer_api'))
import build_package as build

def test_outputs_are_fresh():build.generate(check=True)

def test_external_api_does_not_export_sessions_admin_or_legacy_auth():
    spec=build.project()
    assert sum(map(len,spec['paths'].values()))==67
    assert not any('admin' in p or 'merchant-self-service' in p or '/portal/' in p or 'provider-callback' in p for p in spec['paths'])
    for item in spec['paths'].values():
        for op in item.values():
            assert op['x-cito-audience']=='THIRD_PARTY'
            assert op['security'] and not op.get('x-cito-body-signature')
            assert not any('Session' in key or 'session' in key for sec in op['security'] for key in sec)
    for scheme in spec['components']['securitySchemes'].values():assert scheme.get('in')!='cookie'

def test_collection_is_complete_scoped_and_no_secrets_or_automatic_sends():
    c,inventory=build.collection(build.project())
    items=[i for group in c['item'] for i in group['item']]
    assert len(items)==len(inventory)==67
    assert c['protocolProfileBehavior']['followRedirects'] is False
    assert all(i['protocolProfileBehavior']['followRedirects'] is False for i in items)
    text=json.dumps(c)
    assert 'adminPassword' not in text and 'adminUser' not in text
    assert 'pm.sendRequest' not in text and 'pm.require(' not in text and 'eval(' not in text
    assert 'pm.vault.get' in text and 'crypto.subtle.sign' in text
    op=next(i for i in items if i['name']=='listWebhookEvents')
    assert any(q['key']=='merchantNumber' and not q['disabled'] for q in op['request']['url']['query'])

def test_environment_exports_no_keys_or_live_defaults():
    values=json.loads((ROOT/'Docs/Api/consumer/cito-environment.postman_environment.json').read_text())['values']
    env={x['key']:x['value'] for x in values}
    assert env['environment']=='SANDBOX' and env['allowRequests']=='false'
    assert env['baseUrl']=='https://not-configured.invalid'
    assert not any('password' in k.lower() or 'privatekey' in k.lower() or 'api-key' in k.lower() for k in env)

def test_all_internal_references_resolve():
    spec=build.project()
    def walk(v):
        if isinstance(v,dict):
            if '$ref' in v:assert isinstance(build.resolve(spec,v),dict)
            for x in v.values():walk(x)
        elif isinstance(v,list):
            for x in v:walk(x)
    walk(spec)


def test_baas_record_docs_are_source_derived_and_idempotent():
    import sync_baas_schemas as sync
    assert sync.rendered()==sync.TARGET.read_text()
    spec=build.project()
    for path,item in spec['paths'].items():
        for op in item.values():
            if op['x-cito-consumer-auth']=='BAAS_API_KEY' and 'requestBody' in op:
                schema=build.resolve(spec,op['requestBody']['content']['application/json']['schema'])
                assert 'x-java-record' in schema
                assert schema.get('properties')
                assert set(schema.get('required',[]))<=schema['properties'].keys()
