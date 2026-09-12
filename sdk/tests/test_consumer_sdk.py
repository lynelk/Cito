"""No network or persistent secrets. Ephemeral RSA keys exist only for this test process."""
from __future__ import annotations
import base64
import json
from pathlib import Path
import subprocess
import sys
from unittest.mock import Mock
import pytest
import requests
from cryptography.hazmat.primitives import serialization,hashes
from cryptography.hazmat.primitives.asymmetric import rsa,padding
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'sdk/Python'))
import cpay_signing as signing
from cpay_client import CPayClient,CPayError,CPayTransportError
VECTORS=json.loads((ROOT/'sdk/tests/signing-vectors.json').read_text())

@pytest.fixture(scope='session')
def key():
    k=rsa.generate_private_key(public_exponent=65537,key_size=2048)
    return k,k.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()).decode()

@pytest.mark.parametrize('vector',VECTORS,ids=lambda v:v['name'])
def test_java_canonical_vectors(vector):
    args={k:vector[k] for k in ('method','path','query','timestamp','nonce','body')}
    assert signing.canonical_query(vector['query'])==vector['canonicalQuery']
    assert signing.canonical_string(**args)==vector['canonical']

def test_golden_vectors_still_match_java_oracle():
    lines=[]
    for v in VECTORS:
        values=[v[k] for k in ('method','path','timestamp','nonce','body')]+[x for pair in v['query'] for x in pair]
        lines.append('\t'.join(base64.b64encode(x.encode()).decode() for x in values))
    result=subprocess.run(['java',str(ROOT/'sdk/tests/SigningOracle.java')],input='\n'.join(lines)+'\n',text=True,capture_output=True,check=True,timeout=30)
    assert [base64.b64decode(x).decode() for x in result.stdout.splitlines()]==[v['canonical'] for v in VECTORS]

@pytest.mark.parametrize('runtime,probe',[('node','node_probe.cjs'),('php','php_probe.php')])
def test_cross_language_rsa_and_tamper_rejection(key,runtime,probe):
    private,pem=key
    output=subprocess.run([runtime,str(ROOT/'sdk/tests'/probe)],input=json.dumps({'key':pem,'vectors':VECTORS}),text=True,capture_output=True,check=True,timeout=30)
    for v,other in zip(VECTORS,json.loads(output.stdout),strict=True):
        args={k:v[k] for k in ('method','path','query','timestamp','nonce','body')}
        expected=signing.sign_request('TEST',pem,**args)
        assert other['canonical']==v['canonical']
        assert other['headers']['X-CPay-Signature']==expected['X-CPay-Signature']
        assert 'X-CPay-Idempotency-Key' not in other['headers']
        sig=base64.b64decode(other['headers']['X-CPay-Signature'])
        private.public_key().verify(sig,v['canonical'].encode(),padding.PKCS1v15(),hashes.SHA256())
        with pytest.raises(Exception):private.public_key().verify(sig,(v['canonical']+'altered').encode(),padding.PKCS1v15(),hashes.SHA256())

@pytest.mark.parametrize('query,expected',[
    ({'k':['b','a',None],'omit':None,'empty':''},'empty=&k=a&k=b'),
    ({'limit':100,'page':0},'limit=100&page=0'),
    ([['k','b'],['k','a']],'k=a&k=b'),
])
def test_repeated_values_and_omission(query,expected):assert signing.canonical_query(query)==expected

@pytest.mark.parametrize('value',[True,1.1,{},float('nan')])
def test_ambiguous_scalar_rejected(value):
    with pytest.raises(TypeError):signing.canonical_query({'x':value})

@pytest.mark.parametrize('origin',['http://cito.test','https://u:p@cito.test','https://cito.test/api','https://cito.test?x=1','https://cito.test/#x','https://YOUR-CITO-HOST','https://not-configured.invalid','https://cito.test\\@evil.test'])
def test_bad_origin_never_contacts_network(key,origin):
    session=Mock()
    with pytest.raises(ValueError):CPayClient(origin,'TEST',key[1],environment='SANDBOX',session=session)
    session.request.assert_not_called()

def response(status=200,data=None,content='application/json',text=None):
    r=Mock(status_code=status,headers={'Content-Type':content,'X-Request-Id':'req-1'})
    r.json.return_value=data if data is not None else {'status':'PENDING'}
    r.text=text if text is not None else json.dumps(r.json.return_value)
    return r

def client(key,env='SANDBOX'):
    transport=Mock(); transport.request.return_value=response()
    return CPayClient('https://cito.test','TEST',key[1],environment=env,session=transport),transport

def payment():return {'amount':'12.3456','reference':'order-1','currency':'UGX','country':'UG','channel':'test','payer':{'type':'MSISDN','value':'SYNTHETIC'}}

def test_retry_retains_key_but_renews_nonce_and_signs_sent_bytes(key):
    c,t=client(key); c.collect(payment(),idempotency_key='operation-1'); c.collect(payment(),idempotency_key='operation-1')
    a,b=t.request.call_args_list
    assert a.args[1].endswith('/api/v2/native/payments/collect')
    assert a.kwargs['headers']['X-CPay-Environment']=='SANDBOX'
    assert a.kwargs['headers']['X-CPay-Idempotency-Key']==b.kwargs['headers']['X-CPay-Idempotency-Key']=='operation-1'
    assert a.kwargs['headers']['X-CPay-Nonce']!=b.kwargs['headers']['X-CPay-Nonce']
    assert a.kwargs['data']==b.kwargs['data']
    assert a.kwargs['allow_redirects'] is False and a.kwargs['timeout']==(5,30)
    h=a.kwargs['headers']; canonical=signing.canonical_string('POST','/api/v2/native/payments/collect',{},h['X-CPay-Timestamp'],h['X-CPay-Nonce'],a.kwargs['data'].decode())
    key[0].public_key().verify(base64.b64decode(h['X-CPay-Signature']),canonical.encode(),padding.PKCS1v15(),hashes.SHA256())

def test_idempotency_is_required_not_invented(key):
    c,t=client(key)
    with pytest.raises(TypeError):c.collect(payment())
    with pytest.raises(ValueError):c.payout(payment(),idempotency_key='')
    t.request.assert_not_called()

def test_explicit_environment_required(key):
    with pytest.raises(TypeError):CPayClient('https://cito.test','TEST',key[1])
    with pytest.raises(ValueError):CPayClient('https://cito.test','TEST',key[1],environment='TYPO')

@pytest.mark.parametrize('patch',[{'merchantNumber':'OTHER'},{'metadata':{'environment':'PRODUCTION'}},{'amount':12.34},{'amount':'0'},{'amount':'1.12345'}])
def test_conflicts_rejected_before_send(key,patch):
    c,t=client(key)
    with pytest.raises(ValueError):c.collect({**payment(),**patch},idempotency_key='operation-1')
    t.request.assert_not_called()

def test_webhook_catalog_has_required_signed_merchant_query(key):
    c,t=client(key); c.webhook_events(); a=t.request.call_args
    assert a.args[1]=='https://cito.test/api/v2/webhooks/events?merchantNumber=TEST'
    assert a.kwargs['headers']['X-CPay-Signature']

@pytest.mark.parametrize('status',[301,302,307,401,403,409,429,500])
def test_redirects_and_errors_not_retried(key,status):
    c,t=client(key); t.request.return_value=response(status,{'code':'ERROR'})
    with pytest.raises(CPayError) as caught:c.channels()
    assert caught.value.status_code==status and caught.value.request_id=='req-1'
    assert t.request.call_count==1

def test_transport_timeout_unknown_and_no_retry(key):
    c,t=client(key); t.request.side_effect=requests.Timeout('may contain private transport detail')
    with pytest.raises(CPayTransportError,match='unknown') as caught:c.collect(payment(),idempotency_key='operation-1')
    assert 'private transport' not in str(caught.value) and t.request.call_count==1

def test_csv_and_empty_responses_are_not_json_failures(key):
    c,t=client(key); t.request.return_value=response(content='text/csv',text='reference,amount\nr1,1.0000\n')
    assert c.statements('2026-09-01','2026-09-02','csv').startswith('reference,amount')
    t.request.return_value=response(status=204,content='',text=''); assert c.channels() is None

def test_malformed_json_not_completion(key):
    c,t=client(key); t.request.return_value=response();t.request.return_value.json.side_effect=ValueError()
    with pytest.raises(CPayTransportError,match='malformed'):c.channels()

def test_production_only_methods_not_sandbox_tests(key):
    c,t=client(key)
    with pytest.raises(ValueError):c.validate_account({})
    with pytest.raises(ValueError):c.create_payment_link({},idempotency_key='k')
    t.request.assert_not_called()
