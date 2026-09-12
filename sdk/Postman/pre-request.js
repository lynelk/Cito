// Cito consumer kit 2.0. Uses local Vault + Web Crypto; no network-loaded signing libraries.
// __CITO_POLICIES__ is replaced by the source-controlled generator, not hand-edited.
const policies = __CITO_POLICIES__;
function stop(message) { pm.execution.skipRequest(); throw new Error(message); }
function needed(name) {
  const value = pm.environment.get(name);
  if (typeof value !== 'string' || !value.trim() || /\{\{|REPLACE|YOUR_/.test(value)) stop(`Configure ${name} in the selected local environment`);
  return value;
}
function formEncode(s) { return encodeURIComponent(s).replace(/[!'()~]/g,c=>'%'+c.charCodeAt(0).toString(16).toUpperCase()).replace(/%20/g,'+'); }
function javaTrim(s) { return s.replace(/^[\x00-\x20]+|[\x00-\x20]+$/g,''); }
function canonicalQuery(params) {
  return [...params].sort((a,b)=>a[0]<b[0]?-1:a[0]>b[0]?1:a[1]<b[1]?-1:a[1]>b[1]?1:0).map(([k,v])=>formEncode(k)+'='+formEncode(v)).join('&');
}
try {
  if (!pm.execution || typeof pm.execution.skipRequest !== 'function') throw new Error('Use a current Postman desktop app with skipRequest, local Vault and Web Crypto');
  const policy=policies[pm.info.requestName];
  if (!policy) stop('Unknown request; regenerate the collection from the Cito consumer contract');
  if (pm.environment.get('allowRequests') !== 'true') stop('Set allowRequests=true after checking the approved environment and published API access prices');
  const approved=needed('baseUrl'); const environment=needed('environment');
  if (!['SANDBOX','PRODUCTION'].includes(environment)) stop('environment must be SANDBOX or PRODUCTION');
  const origin=new URL(approved);
  if (origin.protocol!=='https:' || origin.username || origin.password || origin.pathname!=='/' || origin.search || origin.hash || origin.hostname.endsWith('.invalid')) stop('baseUrl must be an approved HTTPS origin');
  const expanded=pm.variables.replaceIn(pm.request.url.toString());
  if (/\{\{|\}\}/.test(expanded)) stop('Resolve all path and query variables before sending');
  const url=new URL(expanded);
  const escaped=policy.path.split(/(\{[^}]+\})/).map(p=>p.startsWith('{')?'[^/]+':p.replace(/[.*+?^${}()|[\]\\]/g,'\\$&')).join('');
  if (url.origin!==origin.origin || url.username || url.password || url.hash || !new RegExp('^'+escaped+'$').test(url.pathname) || pm.request.method!==policy.method) stop('Request destination/method differs from the selected consumer contract');
  if (environment==='SANDBOX' && policy.environment==='NO_SANDBOX_SELECTOR' && policy.method!=='GET') stop('This operation has no verified sandbox selector; do not treat it as a sandbox test');
  if (policy.method!=='GET') {
    if (pm.environment.get('confirmOperation')!==pm.info.requestName) stop('Set confirmOperation to this exact operationId for one deliberate write');
    pm.environment.unset('confirmOperation'); // One-use intent, not a live-provider activation approval.
  }
  let body=pm.request.body && pm.request.body.mode==='raw' ? pm.variables.replaceIn(pm.request.body.raw) : '';
  if (/\{\{[^}]+\}\}/.test(body)) stop('Resolve all request-body variables before sending');
  if (body) { JSON.parse(body); pm.request.body.update({mode:'raw',raw:body,options:{raw:{language:'json'}}}); }
  if (policy.auth==='RSA_V2') {
    const merchant=needed('merchantNumber');
    if (/[\x00-\x20\x7f]/.test(merchant)) stop('merchantNumber is invalid');
    const parsed=body ? JSON.parse(body) : {};
    if (parsed.merchantNumber!==undefined && parsed.merchantNumber!==merchant) stop('Body merchant does not match configured merchant');
    if (url.searchParams.has('merchantNumber') && (url.searchParams.getAll('merchantNumber').length!==1 || url.searchParams.get('merchantNumber')!==merchant)) stop('Query merchant mismatch');
    if (parsed.merchantNumber===undefined && !url.searchParams.has('merchantNumber')) stop('This signed request needs the documented merchantNumber in its body or query');
    if (parsed.metadata && parsed.metadata.environment && parsed.metadata.environment!==environment) stop('Body and header environments conflict');
    const query=canonicalQuery(url.searchParams); url.search=query; pm.request.url.update(url.toString());
    pm.request.headers.upsert({key:'X-CPay-Environment',value:environment});
    if (policy.method!=='GET') {
      const idem=needed('idempotencyKey');
      if (/[\x00-\x20\x7f]/.test(idem) || idem.length>256) stop('idempotencyKey is invalid');
      pm.request.headers.upsert({key:'X-CPay-Idempotency-Key',value:idem});
    }
    const timestamp=new Date().toISOString(); const nonce=crypto.randomUUID();
    const digest=await crypto.subtle.digest('SHA-256',new TextEncoder().encode(javaTrim(body)));
    const hex=[...new Uint8Array(digest)].map(b=>b.toString(16).padStart(2,'0')).join('');
    const canonical=[policy.method,url.pathname,query,timestamp,nonce,hex].join('\n');
    const pem=await pm.vault.get('cito-rsa-private-key');
    if (typeof pem!=='string' || !pem.includes('-----BEGIN PRIVATE KEY-----')) stop('Local Vault cito-rsa-private-key must contain a PKCS#8 RSA private key');
    const raw=pem.replace(/-----BEGIN PRIVATE KEY-----|-----END PRIVATE KEY-----|\s/g,'');
    const bytes=Uint8Array.from(atob(raw),c=>c.charCodeAt(0));
    const key=await crypto.subtle.importKey('pkcs8',bytes,{name:'RSASSA-PKCS1-v1_5',hash:'SHA-256'},false,['sign']);
    if (key.algorithm.modulusLength<2048) stop('RSA key must be at least 2048 bits');
    const signature=await crypto.subtle.sign('RSASSA-PKCS1-v1_5',key,new TextEncoder().encode(canonical));
    const encoded=btoa(String.fromCharCode(...new Uint8Array(signature)));
    for (const [key,value] of Object.entries({'X-CPay-Merchant-Number':merchant,'X-CPay-Signature-Version':'v2','X-CPay-Timestamp':timestamp,'X-CPay-Nonce':nonce,'X-CPay-Signature':encoded})) pm.request.headers.upsert({key,value});
  } else if (policy.auth==='BAAS_API_KEY') {
    const apiKey=await pm.vault.get('cito-baas-api-key');
    if (typeof apiKey!=='string' || !apiKey || /[\x00-\x20\x7f]/.test(apiKey)) stop('Local Vault cito-baas-api-key is missing or invalid');
    pm.request.headers.upsert({key:'X-Cito-Api-Key',value:apiKey});
    pm.request.headers.upsert({key:'X-Cito-Environment',value:environment});
    pm.request.headers.upsert({key:'X-Request-Id',value:crypto.randomUUID()});
  } else stop('Unsupported auth contract');
} catch (error) {
  if (pm.execution && typeof pm.execution.skipRequest==='function') pm.execution.skipRequest();
  // Never log a key, raw provider body, private-key parser error or signed request.
  throw new Error('Cito request was not sent: verify local environment, body, permissions and Vault setup. No automatic retry.');
}
