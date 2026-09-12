'use strict';
const assert=require('node:assert/strict'),crypto=require('node:crypto');
const {CPayClient,CPayError,CPayTransportError}=require('../Node/cpay-client');
const {publicKey,privateKey}=crypto.generateKeyPairSync('rsa',{modulusLength:2048});
const pem=privateKey.export({type:'pkcs8',format:'pem'});
const base={baseUrl:'https://cito.test',merchantNumber:'TEST',privateKeyPem:pem,environment:'SANDBOX'};
const calls=[];
let next=()=>new Response(JSON.stringify({status:'PENDING'}),{status:202,headers:{'Content-Type':'application/json'}});
const c=new CPayClient({...base,fetchImpl:async(url,options)=>{calls.push({url,...options});return next();}});
const request={amount:'12.3456',reference:'order-1',currency:'UGX',country:'UG',channel:'test',payer:{type:'MSISDN',value:'SYNTHETIC'}};
let cases=0;
(async()=>{
  assert.throws(()=>new CPayClient({...base,environment:undefined}));cases++;
  for(const baseUrl of ['http://cito.test','https://u:p@cito.test','https://cito.test/path','https://not-configured.invalid','https://cito.test?x=y']){assert.throws(()=>new CPayClient({...base,baseUrl}));cases++;}
  assert.throws(()=>c.collect(request));cases++;
  assert.throws(()=>c.collect({...request,merchantNumber:'OTHER'},{idempotencyKey:'i'}));cases++;
  assert.throws(()=>c.collect({...request,metadata:{environment:'PRODUCTION'}},{idempotencyKey:'i'}));cases++;
  assert.throws(()=>c.collect({...request,amount:12.34},{idempotencyKey:'i'}));cases++;
  assert.equal(calls.length,0);
  await c.collect(request,{idempotencyKey:'order-key'});await c.collect(request,{idempotencyKey:'order-key'});
  assert.equal(calls[0].url,'https://cito.test/api/v2/native/payments/collect');
  assert.equal(calls[0].headers['X-CPay-Environment'],'SANDBOX');
  assert.equal(calls[0].headers['X-CPay-Idempotency-Key'],calls[1].headers['X-CPay-Idempotency-Key']);
  assert.notEqual(calls[0].headers['X-CPay-Nonce'],calls[1].headers['X-CPay-Nonce']);
  assert.equal(calls[0].body,calls[1].body);assert.equal(calls[0].redirect,'manual');assert.ok(calls[0].signal instanceof AbortSignal);cases+=4;
  await c.webhookEvents();assert.match(calls.at(-1).url,/\/webhooks\/events\?merchantNumber=TEST$/);assert.ok(calls.at(-1).headers['X-CPay-Signature']);cases++;
  next=()=>new Response('x,y\na,b\n',{status:200,headers:{'Content-Type':'text/csv'}});
  assert.equal(await c.statements({startDate:'2026-01-01',endDate:'2026-01-02',format:'csv'}),'x,y\na,b\n');cases++;
  next=()=>new Response(null,{status:204});assert.equal(await c.channels(),null);cases++;
  for(const status of [301,302,307,401,403,409,429,500]){
    next=()=>new Response('{}',{status,headers:{'Content-Type':'application/json','X-Request-Id':'req-1'}});
    const before=calls.length;
    await assert.rejects(c.channels(),e=>e instanceof CPayError && e.status===status && e.requestId==='req-1');assert.equal(calls.length,before+1);cases++;
  }
  next=()=>{throw new Error('transport detail with secret')};const before=calls.length;
  await assert.rejects(c.collect(request,{idempotencyKey:'order-key'}),e=>e instanceof CPayTransportError && !e.message.includes('secret'));assert.equal(calls.length,before+1);cases++;
  next=()=>new Response('not-json',{status:200,headers:{'Content-Type':'application/json'}});
  await assert.rejects(c.channels(),CPayTransportError);cases++;
  assert.throws(()=>c.createPaymentLink({},{idempotencyKey:'i'}));assert.throws(()=>c.validateAccount({}));cases+=2;
  process.stdout.write(JSON.stringify({runtime:'Node',cases,providerRequests:0,result:'PASS'})+'\n');
})().catch(e=>{console.error(e);process.exit(1)});
