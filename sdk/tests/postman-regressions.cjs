'use strict';
// Exercise the exact generated script in an isolated VM with standard Web Crypto.
// This is script conformance, not a claim that Postman's desktop UI was driven.
const vm=require('node:vm'),fs=require('node:fs'),crypto=require('node:crypto'),assert=require('node:assert/strict');
const signing=require('../Node/cpay-signing');
const path=require('node:path');
const root=path.resolve(__dirname,'../..');
const repositoryCollection=path.join(root,'Docs/Api/cpay-v2-postman-collection.json');
const collection=JSON.parse(fs.readFileSync(fs.existsSync(repositoryCollection)?repositoryCollection:path.join(root,'Cito-External-API.postman_collection.json'),'utf8'));
const source=collection.event.find(e=>e.listen==='prerequest').script.exec.join('\n');
const pair=crypto.generateKeyPairSync('rsa',{modulusLength:2048});
const pem=pair.privateKey.export({format:'pem',type:'pkcs8'});
async function run(options={}) {
  const environment=new Map(Object.entries({baseUrl:'https://cito.test',environment:'SANDBOX',allowRequests:'true',merchantNumber:'TEST',idempotencyKey:'operation-key',...options.env}));
  const headers=new Map();let url=options.url||'{{baseUrl}}/api/v2/webhooks/events?merchantNumber={{merchantNumber}}',body=options.body||'';let skipped=false,vaultReads=0;
  const pm={environment:{get:k=>environment.get(k),unset:k=>environment.delete(k)},variables:{replaceIn:s=>s.replace(/\{\{([^}]+)\}\}/g,(m,k)=>environment.get(k)??m)},info:{requestName:options.operation||'listWebhookEvents'},execution:{skipRequest:()=>{skipped=true}},vault:{get:async k=>{vaultReads++;return k==='cito-rsa-private-key'?pem:'SYNTHETIC-BAAS-KEY'}},request:{method:options.method||'GET',url:{toString:()=>url,update:s=>{url=s}},body:body?{mode:'raw',raw:body,update:v=>{body=v.raw}}:null,headers:{upsert:({key,value})=>headers.set(key,value)}}};
  let error=null;
  try {await vm.runInNewContext('(async()=>{'+source+'})()',{pm,crypto:crypto.webcrypto,TextEncoder,Uint8Array,URL,atob,btoa,Date,JSON,Error},{timeout:5000});}catch(e){error=e;}
  return {headers,url,body,skipped,error,vaultReads,environment};
}
(async()=>{
  let cases=0;
  let r=await run();assert.equal(r.error,null);assert.equal(r.skipped,false);assert.equal(r.headers.get('X-CPay-Environment'),'SANDBOX');
  const parsed=new URL(r.url);const h=Object.fromEntries(r.headers);
  const canonical=signing.canonicalString({method:'GET',path:parsed.pathname,query:parsed.searchParams,timestamp:h['X-CPay-Timestamp'],nonce:h['X-CPay-Nonce'],body:''});
  assert.ok(crypto.verify('sha256',Buffer.from(canonical),pair.publicKey,Buffer.from(h['X-CPay-Signature'],'base64')));cases++;
  r=await run({url:'{{baseUrl}}/api/v2/webhooks/events?merchantNumber={{merchantNumber}}&note=A%20B&n=~*%2B&tag=z&tag=a'});
  assert.equal(r.error,null);assert.match(r.url,/note=A\+B/);assert.match(r.url,/tag=a&tag=z/);cases++;
  for(const options of [{env:{allowRequests:'false'}},{env:{environment:'TYPO'}},{env:{baseUrl:'https://not-configured.invalid'}},{url:'https://evil.test/api/v2/webhooks/events?merchantNumber=TEST'},{url:'{{baseUrl}}/api/v2/webhooks/events?merchantNumber=OTHER'},{operation:'unknown'}]){r=await run(options);assert.ok(r.error);assert.equal(r.skipped,true);assert.equal(r.vaultReads,0);cases++;}
  const body=JSON.stringify({merchantNumber:'TEST',amount:'1.0000',reference:'test',metadata:{environment:'SANDBOX'}});
  r=await run({operation:'nativeCollect',method:'POST',url:'{{baseUrl}}/api/v2/native/payments/collect',body});assert.equal(r.skipped,true);cases++;
  r=await run({operation:'nativeCollect',method:'POST',url:'{{baseUrl}}/api/v2/native/payments/collect',body,env:{confirmOperation:'nativeCollect'}});
  assert.equal(r.error,null);assert.equal(r.environment.has('confirmOperation'),false);assert.equal(r.headers.get('X-CPay-Idempotency-Key'),'operation-key');cases++;
  r=await run({operation:'createPaymentLink',method:'POST',url:'{{baseUrl}}/api/v2/payment-links',body,env:{confirmOperation:'createPaymentLink'}});assert.equal(r.skipped,true);assert.equal(r.vaultReads,0);cases++;
  r=await run({operation:'listBillingCustomers',url:'{{baseUrl}}/api/v2/native/billing/baas/customers'});assert.equal(r.error,null);assert.equal(r.headers.get('X-Cito-Environment'),'SANDBOX');assert.equal(r.headers.get('X-Cito-Api-Key'),'SYNTHETIC-BAAS-KEY');assert.equal(r.headers.has('X-CPay-Signature'),false);cases++;
  console.log(JSON.stringify({runtime:'Postman script VM + WebCrypto',cases,providerRequests:0,result:'PASS'}));
})().catch(e=>{console.error(e);process.exit(1)});
