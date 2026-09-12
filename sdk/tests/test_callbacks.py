import base64,hashlib,hmac,json,sys
from pathlib import Path
import pytest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'Python'))
from cito_callbacks import verify_task_callback,verify_merchant_event,CallbackRejected
SECRET='TEST-ONLY-NOT-A-PRODUCTION-SECRET'
BODY=b'{"reference":"r1","status":"PENDING"}'
def task(body=BODY,**changes):
    headers={'X-CPay-Signature-Version':'callback-v1','X-CPay-Callback-Task-Id':'12','X-CPay-Merchant-Id':'42','X-CPay-Reference':'r1','X-CPay-Timestamp':'1800000000','X-CPay-Nonce':'synthetic-nonce'}
    headers.update(changes)
    base='\n'.join(headers[k] for k in ('X-CPay-Callback-Task-Id','X-CPay-Merchant-Id','X-CPay-Reference','X-CPay-Timestamp','X-CPay-Nonce')).encode()+b'\n'+body
    headers['X-CPay-Signature']=base64.b64encode(hmac.new(SECRET.encode(),base,hashlib.sha256).digest()).decode()
    return headers

def test_task_signature_and_stable_durable_identity():
    first=verify_task_callback(BODY,task(),SECRET,expected_merchant_id='42',now=1800000010)
    second=verify_task_callback(BODY,task(**{'X-CPay-Nonce':'another-attempt','X-CPay-Timestamp':'1800000010'}),SECRET,expected_merchant_id='42',now=1800000010)
    assert first.event_identity==second.event_identity=='12'
    assert first.tenant=='42' and first.payload['status']=='PENDING'

@pytest.mark.parametrize('body,headers,merchant,now',[
    (BODY+b' ',task(),'42',1800000010),
    (BODY,task(),'99',1800000010),
    (BODY,task(),'42',1800000400),
    (BODY,task(),'42',1799999900),
    (BODY,{**task(),'X-CPay-Signature-Version':'unknown'},'42',1800000010),
    (BODY,{**task(),'X-CPay-Signature':'not-base64'},'42',1800000010),
    (BODY,list(task().items())+[('x-cpay-signature','duplicate')],'42',1800000010),
])
def test_task_rejects_tampering_tenant_time_scheme_and_duplicate_headers(body,headers,merchant,now):
    with pytest.raises(CallbackRejected):verify_task_callback(body,headers,SECRET,expected_merchant_id=merchant,now=now)

@pytest.mark.parametrize('body',[b'{"x":1,"x":2}',b'[]',b'{"x":NaN}',b'not-json'])
def test_authenticated_bad_json_is_still_rejected(body):
    with pytest.raises(CallbackRejected):verify_task_callback(body,task(body),SECRET,expected_merchant_id='42',now=1800000010)

def event():
    body=json.dumps({'merchantNumber':'M1','eventType':'payment.updated','eventId':'stable-event','reference':'r1','status':'PENDING'},separators=(',',':')).encode()
    headers={'X-CPay-Signature':hashlib.sha256(body+b'.'+SECRET.encode()).hexdigest(),'X-CPay-Event':'UNTRUSTED_HEADER','X-CPay-Reference':'UNTRUSTED_REFERENCE'}
    return body,headers

def test_merchant_digest_uses_verified_body_not_unsigned_event_headers():
    body,headers=event();result=verify_merchant_event(body,headers,SECRET,expected_merchant_number='M1',expected_event_type='payment.updated')
    assert result.event_identity=='stable-event' and result.reference=='r1'
    assert result.scheme=='merchant-event-compat'

@pytest.mark.parametrize('field,value',[('expected_merchant_number','OTHER'),('expected_event_type','OTHER')])
def test_merchant_body_scope_cannot_be_overridden(field,value):
    body,headers=event();expected={'expected_merchant_number':'M1','expected_event_type':'payment.updated',field:value}
    with pytest.raises(CallbackRejected):verify_merchant_event(body,headers,SECRET,**expected)

def test_signature_schemes_never_auto_fallback():
    body,headers=event()
    with pytest.raises(CallbackRejected):verify_task_callback(body,headers,SECRET,expected_merchant_id='42',now=1800000010)
    with pytest.raises(CallbackRejected):verify_merchant_event(BODY,task(),SECRET,expected_merchant_number='M1',expected_event_type='payment.updated')
