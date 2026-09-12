"""Actual HTTPS transports against a disposable loopback verifier, never Cito or a provider.

The ephemeral CA is trusted only by the test child processes. Certificate/hostname
validation stays enabled. This does not substitute for deployed API acceptance.
"""
from __future__ import annotations
import base64,datetime,ipaddress,json,os,ssl,subprocess,sys,threading
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit,parse_qsl
import pytest
from cryptography import x509
from cryptography.hazmat.primitives import hashes,serialization
from cryptography.hazmat.primitives.asymmetric import rsa,padding
from cryptography.x509.oid import NameOID
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'sdk/Python'))
from cpay_signing import canonical_string

@pytest.fixture
def wire(tmp_path):
    key=rsa.generate_private_key(public_exponent=65537,key_size=2048)
    now=datetime.datetime.now(datetime.timezone.utc)
    name=x509.Name([x509.NameAttribute(NameOID.COMMON_NAME,'Cito loopback test')])
    cert=(x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key())
          .serial_number(x509.random_serial_number()).not_valid_before(now-datetime.timedelta(minutes=1))
          .not_valid_after(now+datetime.timedelta(hours=1))
          .add_extension(x509.BasicConstraints(ca=True,path_length=None),critical=True)
          .add_extension(x509.SubjectAlternativeName([x509.DNSName('localhost'),x509.IPAddress(ipaddress.ip_address('127.0.0.1'))]),critical=False)
          .sign(key,hashes.SHA256()))
    keyfile=tmp_path/'ephemeral.key';cafile=tmp_path/'ephemeral-ca.pem'
    keyfile.write_bytes(key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()))
    keyfile.chmod(0o600);cafile.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    calls=[];nonces=set();errors=[]
    class Handler(BaseHTTPRequestHandler):
        def log_message(self,*args):pass
        def do_GET(self):self.handle_request()
        def do_POST(self):self.handle_request()
        def handle_request(self):
            body=self.rfile.read(int(self.headers.get('Content-Length','0'))).decode('utf-8')
            target=urlsplit(self.path)
            try:
                assert self.client_address[0]=='127.0.0.1'
                assert self.headers['X-CPay-Merchant-Number']=='SYNTHETIC-WIRE'
                assert self.headers['X-CPay-Environment']=='SANDBOX'
                nonce=self.headers['X-CPay-Nonce'];assert nonce not in nonces;nonces.add(nonce)
                canonical=canonical_string(self.command,target.path,parse_qsl(target.query,keep_blank_values=True),self.headers['X-CPay-Timestamp'],nonce,body)
                key.public_key().verify(base64.b64decode(self.headers['X-CPay-Signature'],validate=True),canonical.encode(),padding.PKCS1v15(),hashes.SHA256())
                if self.command=='POST':
                    data=json.loads(body)
                    assert target.path=='/api/v2/native/payments/collect'
                    assert data['merchantNumber']=='SYNTHETIC-WIRE' and data['amount']=='1.2345'
                    assert self.headers['X-CPay-Idempotency-Key']=='stable-wire-operation'
                else:assert ('merchantNumber','SYNTHETIC-WIRE') in parse_qsl(target.query)
                calls.append({'path':target.path,'body':body,'key':self.headers.get('X-CPay-Idempotency-Key')})
                payload=json.dumps({'status':'PENDING','synthetic':True}).encode()
                self.send_response(202 if self.command=='POST' else 200)
            except Exception as error:
                errors.append(type(error).__name__);payload=b'{"code":"TEST_VERIFICATION_FAILED"}';self.send_response(401)
            self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(payload)));self.end_headers();self.wfile.write(payload)
    server=ThreadingHTTPServer(('127.0.0.1',0),Handler)
    context=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER);context.minimum_version=ssl.TLSVersion.TLSv1_2;context.load_cert_chain(cafile,keyfile)
    server.socket=context.wrap_socket(server.socket,server_side=True)
    thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
    env={**os.environ,'CITO_TEST_ORIGIN':f'https://localhost:{server.server_address[1]}','CITO_TEST_KEY':str(keyfile),'CITO_TEST_CA':str(cafile),'NODE_EXTRA_CA_CERTS':str(cafile),'REQUESTS_CA_BUNDLE':str(cafile)}
    try:yield env,calls,errors
    finally:server.shutdown();server.server_close();thread.join(timeout=5)

@pytest.mark.parametrize('language',['python','node','php'])
def test_real_tls_signature_and_idempotent_retry(language,wire):
    if language=='php':
        available=subprocess.run(['php','-r','exit(extension_loaded("curl")?0:1);']).returncode==0
        if not available:
            assert os.environ.get('GITHUB_ACTIONS')!='true','CI must have PHP cURL; do not skip a released language'
            pytest.skip('Local PHP lacks cURL; the release CI requires it')
    env,calls,errors=wire
    if language=='python':
        program="""import os,sys
from pathlib import Path
sys.path.insert(0,'sdk/Python')
from cpay_client import CPayClient
c=CPayClient(os.environ['CITO_TEST_ORIGIN'],'SYNTHETIC-WIRE',Path(os.environ['CITO_TEST_KEY']).read_text(),environment='SANDBOX')
assert c.webhook_events()['synthetic']
p={'amount':'1.2345','reference':'wire-original','country':'UG','currency':'UGX','channel':'synthetic','payer':{'type':'MSISDN','value':'not-a-real-number'}}
assert c.collect(p,idempotency_key='stable-wire-operation')['status']=='PENDING'
assert c.collect(p,idempotency_key='stable-wire-operation')['status']=='PENDING'
"""
        command=[sys.executable,'-c',program]
    elif language=='node':
        program="""const fs=require('node:fs'),assert=require('node:assert/strict');
const {CPayClient}=require('./sdk/Node/cpay-client');
const c=new CPayClient({baseUrl:process.env.CITO_TEST_ORIGIN,merchantNumber:'SYNTHETIC-WIRE',privateKeyPem:fs.readFileSync(process.env.CITO_TEST_KEY,'utf8'),environment:'SANDBOX'});
(async()=>{assert.equal((await c.webhookEvents()).synthetic,true);const p={amount:'1.2345',reference:'wire-original',country:'UG',currency:'UGX',channel:'synthetic',payer:{type:'MSISDN',value:'not-a-real-number'}};assert.equal((await c.collect(p,{idempotencyKey:'stable-wire-operation'})).status,'PENDING');assert.equal((await c.collect(p,{idempotencyKey:'stable-wire-operation'})).status,'PENDING');})().catch(()=>process.exit(1));"""
        command=['node','-e',program]
    else:
        program="""require 'sdk/Php/CPayClient.php';
$c=new CPayClient(getenv('CITO_TEST_ORIGIN'),'SYNTHETIC-WIRE',file_get_contents(getenv('CITO_TEST_KEY')),'SANDBOX');
if(!$c->webhookEvents()['synthetic'])exit(1);
$p=['amount'=>'1.2345','reference'=>'wire-original','country'=>'UG','currency'=>'UGX','channel'=>'synthetic','payer'=>['type'=>'MSISDN','value'=>'not-a-real-number']];
if($c->collect($p,'stable-wire-operation')['status']!=='PENDING')exit(1);
if($c->collect($p,'stable-wire-operation')['status']!=='PENDING')exit(1);"""
        command=['php','-d','curl.cainfo='+env['CITO_TEST_CA'],'-r',program]
    result=subprocess.run(command,cwd=ROOT,env=env,text=True,capture_output=True,timeout=20)
    assert result.returncode==0,result.stderr
    assert not errors
    assert len(calls)==3
    assert calls[0]['path']=='/api/v2/webhooks/events'
    assert calls[1]['key']==calls[2]['key']=='stable-wire-operation'
    assert calls[1]['body']==calls[2]['body']
