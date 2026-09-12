#!/usr/bin/env python3
"""Deterministic consumer-only contract and Postman generator; never contacts a provider.

Owning API specifications remain authoritative. The generated projection includes only
THIRD_PARTY v2 operations, not merchant sessions, administrators or legacy body signatures.
"""
from __future__ import annotations
import argparse,copy,hashlib,html,json,re,shutil,subprocess,sys,zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'scripts/api_docs'))
from build_portal_reference import build as merchant_reference
VERSION='2.0.0'
METHODS={'get','post','put','patch','delete','head','options'}
OUTPUT=ROOT/'Docs/Api/consumer'

def resolve(spec,value):
    seen=set()
    while isinstance(value,dict) and '$ref' in value:
        ref=value['$ref']
        if not ref.startswith('#/') or ref in seen:raise ValueError('Nonlocal or circular direct reference')
        seen.add(ref); value=spec
        for part in ref[2:].split('/'): value=value[part]
    return value

def project():
    spec=merchant_reference()
    spec['info']={'title':'Cito External Developer API','version':VERSION,'description':'Connect Once. Operate Everything. Server-to-server v2 APIs only. Merchant portal/session and administrator APIs are intentionally excluded. See START-HERE for authentication, environments, pricing and release acceptance.'}
    spec['paths']={p:{m:o for m,o in item.items() if m in METHODS and o.get('x-cito-audience')=='THIRD_PARTY' and not o.get('x-cito-body-signature')} for p,item in spec['paths'].items() if p.startswith('/api/v2/')}
    spec['paths']={p:i for p,i in spec['paths'].items() if i}
    needed=set()
    def walk(value):
        if isinstance(value,dict):
            if '$ref' in value and value['$ref'] not in needed:
                needed.add(value['$ref']);walk(resolve(spec,value))
            for item in value.get('security',[]):
                for name in item:needed.add('#/components/securitySchemes/'+name)
            for item in value.values():walk(item)
        elif isinstance(value,list):
            for item in value:walk(item)
    walk(spec['paths'])
    spec['components']={g:{n:v for n,v in values.items() if f'#/components/{g}/{n}' in needed} for g,values in spec['components'].items()}
    spec['components']={g:v for g,v in spec['components'].items() if v}
    for path,item in spec['paths'].items():
        for method,op in item.items():
            names=[n for r in op['security'] for n in r]
            auth='BAAS_API_KEY' if any('CitoBaasApiKey' in n for n in names) else 'RSA_V2'
            if auth=='BAAS_API_KEY':env='BAAS_SELECTOR'
            elif path in ('/api/v2/native/payments/collect','/api/v2/native/payments/payout','/api/v2/payments/collect','/api/v2/payments/payout') or path.startswith('/api/v2/refunds') or path.startswith('/api/v2/batch-payouts/'):env='PAYMENT_SELECTOR'
            else:env='NO_SANDBOX_SELECTOR'
            op['x-cito-consumer-auth']=auth;op['x-cito-environment-policy']=env
            op['x-cito-readiness-note']='Contract availability is not provider activation, settlement or delivery certification.'
    return spec

def schema_example(spec,schema,depth=0):
    if depth>8:return None
    s=resolve(spec,schema)
    if 'example' in s:return copy.deepcopy(s['example'])
    if 'examples' in s and isinstance(s['examples'],list) and s['examples']:return copy.deepcopy(s['examples'][0])
    if 'default' in s:return copy.deepcopy(s['default'])
    if 'enum' in s:return next((x for x in s['enum'] if x is not None),None)
    if 'oneOf' in s or 'anyOf' in s:return schema_example(spec,(s.get('oneOf') or s.get('anyOf'))[0],depth+1)
    if 'allOf' in s:
        merged={}
        for part in s['allOf']:
            example=schema_example(spec,part,depth+1)
            if isinstance(example,dict):merged.update(example)
        return merged
    t=s.get('type'); t=next((x for x in t if x!='null'),None) if isinstance(t,list) else t
    if t=='object' or 'properties' in s:
        return {k:schema_example(spec,v,depth+1) for k,v in s.get('properties',{}).items() if k in s.get('required',[]) and not resolve(spec,v).get('readOnly')}
    if t=='array':return [schema_example(spec,s.get('items',{}),depth+1)]
    if t=='integer':return int(s.get('minimum',1))
    if t=='number':return s.get('minimum',1)
    if t=='boolean':return False
    if s.get('format')=='date-time':return '{{eventTime}}'
    if s.get('format')=='date':return '{{startDate}}'
    return '{{REPLACE_VALUE}}'

def collection(spec):
    groups={};policies={}; inventory=[]
    for path,item in spec['paths'].items():
        for method,op in item.items():
            name=op['operationId'];auth=op['x-cito-consumer-auth']
            policies[name]={'method':method.upper(),'path':path,'auth':auth,'environment':op['x-cito-environment-policy']}
            inventory.append({'operationId':name,**policies[name],'sourceContract':op['x-source-contract']})
            params=[resolve(spec,p) for p in op.get('parameters',[])]
            target='{{baseUrl}}'+re.sub(r'\{([^}]+)\}',r'{{\1}}',path)
            query=[{'key':p['name'],'value':'{{'+p['name']+'}}','description':p.get('description',''),'disabled':not p.get('required',False)} for p in params if p['in']=='query']
            # Both current discovery controllers require merchantNumber; don't rely on an empty inherited header.
            if auth=='RSA_V2' and method=='get' and not any(p['key']=='merchantNumber' for p in query):
                query.insert(0,{'key':'merchantNumber','value':'{{merchantNumber}}','disabled':False,'description':'Required signed merchant scope.'})
            enabled=[p for p in query if not p['disabled']]
            raw=target+('?'+'&'.join(p['key']+'='+p['value'] for p in enabled) if enabled else '')
            description=op.get('description',op.get('summary',''))+'\n\nAuth: '+auth+'. Environment policy: '+policies[name]['environment']+'. Read the contract schemas; example identifiers are not provisioned accounts. Every write requires one-use confirmOperation='+name+'. The collection never retries automatically.'
            req={'method':method.upper(),'header':[{'key':'Accept','value':'application/json'}], 'url':{'raw':raw,'host':['{{baseUrl}}'],'path':re.sub(r'\{([^}]+)\}',r'{{\1}}',path.lstrip('/')).split('/'),'query':query},'description':description,'auth':{'type':'noauth'}}
            if 'requestBody' in op:
                rb=resolve(spec,op['requestBody']); content=rb.get('content',{}).get('application/json')
                if content:
                    payload=copy.deepcopy(content.get('example')) if 'example' in content else schema_example(spec,content.get('schema',{}))
                    if payload is None:payload={}
                    if isinstance(payload,dict) and auth=='RSA_V2':payload['merchantNumber']='{{merchantNumber}}'
                    def placeholders(v):
                        if isinstance(v,dict):
                            out={}
                            for k,x in v.items():
                                if k in ('merchantNumber','reference','idempotencyKey','billingAccountReference','customerReference','accountReference','contractReference','subscriptionReference','serviceCode','meterCode','planCode','currency','country','channel','callbackUrl','eventTime','startDate','endDate','externalReference','displayName','amount'):out[k]='{{'+k+'}}'
                                else:out[k]=placeholders(x)
                            return out
                        if isinstance(v,list):return list(map(placeholders,v))
                        return v
                    payload=placeholders(payload)
                    if path in ('/api/v2/native/payments/collect','/api/v2/native/payments/payout'):
                        party='payer' if path.endswith('collect') else 'payee'
                        payload={'merchantNumber':'{{merchantNumber}}','amount':'{{amount}}','currency':'{{currency}}','country':'{{country}}','channel':'{{channel}}','reference':'{{reference}}','description':'Developer integration example',party:{'type':'MSISDN','value':'{{testMsisdn}}'},'metadata':{'environment':'{{environment}}'}}
                    req['body']={'mode':'raw','raw':json.dumps(payload,indent=2,ensure_ascii=False),'options':{'raw':{'language':'json'}}}
                    req['header'].append({'key':'Content-Type','value':'application/json'})
            group='Billing / BaaS' if auth=='BAAS_API_KEY' else 'Identity' if '/identity/' in path else 'Communications' if '/communication/' in path else 'Payments and discovery'
            groups.setdefault(group,[]).append({'name':name,'request':req,'response':[], 'protocolProfileBehavior':{'followRedirects':False}})
    pre=(ROOT/'sdk/Postman/pre-request.js').read_text().replace('__CITO_POLICIES__',json.dumps(policies,sort_keys=True,separators=(',',':')))
    return {'info':{'name':'Cito External Developer API '+VERSION,'schema':'https://schema.getpostman.com/json/collection/v2.1.0/collection.json','description':'Connect Once. Operate Everything. Consumer-only collection generated from canonical contracts. Requires current Postman desktop, local Vault and Web Crypto. Configure approved origin/environment. Requests are disabled by default; writes require exact one-use confirmation. Never disable TLS checks. Do not run the whole collection against production.'},'event':[{'listen':'prerequest','script':{'type':'text/javascript','exec':pre.splitlines()}},{'listen':'test','script':{'type':'text/javascript','exec':(ROOT/'sdk/Postman/post-response.js').read_text().splitlines()}}], 'item':[{'name':g,'item':items} for g,items in groups.items()], 'variable':[], 'protocolProfileBehavior':{'followRedirects':False}},inventory

def portable(spec,guide):
    encoded=json.dumps(spec,ensure_ascii=False).replace('<','\\u003c')
    return '''<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Cito External Developer API</title>
<style>:root{font:16px/1.6 system-ui,sans-serif;color:#0b2545;background:#f8fafc}*{box-sizing:border-box}body{margin:0}header{padding:2rem max(1rem,5vw);background:#0b2545;color:white}main{max-width:1200px;margin:auto;padding:1rem}input,button{font:inherit;min-height:44px;border:1px solid #64748b;border-radius:8px;padding:.6rem;max-width:100%}input{width:100%}:focus-visible{outline:3px solid #0066ff;outline-offset:3px}details{margin:1rem 0;padding:1rem;background:white;border:1px solid #94a3b8;border-radius:12px;overflow-wrap:anywhere}summary{cursor:pointer;min-height:44px}pre{white-space:pre-wrap;overflow-wrap:anywhere;font-size:.9rem;background:#e6f2ff;padding:1rem}h1{font-size:clamp(1.5rem,4vw,2.5rem)}.hint{color:#475569}code{overflow-wrap:anywhere}</style>
<header><p>Connect Once. Operate Everything.</p><h1>Cito External Developer API — '''+VERSION+'''</h1><p>Server-to-server v2 reference. No administrator, merchant-session or legacy body-signature operations.</p></header>
<main><details><summary>Start here and integration handbook</summary><pre>'''+html.escape(guide)+'''</pre></details><label for="search">Search endpoint, operation or authentication</label><input id="search" type="search" placeholder="channels, invoices, RSA, identity…"><p id="count" role="status"></p><div id="operations"></div><h2>Request and response schema dictionary</h2><p class="hint">Expand a schema referenced by an operation. These are local contract definitions, not a live provider test.</p><div id="schemas"></div><noscript>Enable JavaScript for local searching or read external-openapi.json and ENDPOINTS.md. No network calls are made.</noscript></main>
<script type="application/json" id="spec">'''+encoded+'''</script><script>
const spec=JSON.parse(document.getElementById('spec').textContent);const operations=Object.entries(spec.paths).flatMap(([path,item])=>Object.entries(item).map(([method,operation])=>({path,method,operation})));function render(){const q=document.getElementById('search').value.toLowerCase();const rows=operations.filter(x=>JSON.stringify(x).toLowerCase().includes(q));document.getElementById('count').textContent=rows.length+' operations';const root=document.getElementById('operations');root.replaceChildren();for(const row of rows){const d=document.createElement('details'),s=document.createElement('summary'),p=document.createElement('p'),pre=document.createElement('pre');s.textContent=row.method.toUpperCase()+' '+row.path+' — '+row.operation.operationId;p.textContent=row.operation.description||row.operation.summary||'';pre.textContent=JSON.stringify(row.operation,null,2);d.append(s,p,pre);root.append(d)}}document.getElementById('search').addEventListener('input',render);render();
const dictionary=document.getElementById('schemas');for(const [name,schema] of Object.entries(spec.components.schemas||{})){const details=document.createElement('details'),summary=document.createElement('summary'),pre=document.createElement('pre');summary.textContent=name;details.id='schema-'+name;pre.textContent=JSON.stringify(schema,null,2);details.append(summary,pre);dictionary.append(details);}

</script></html>'''

def outputs():
    spec=project();postman,inventory=collection(spec)
    envkeys={'baseUrl':'https://not-configured.invalid','environment':'SANDBOX','allowRequests':'false','confirmOperation':'','merchantNumber':'','idempotencyKey':'','reference':'','amount':'','currency':'','country':'','channel':'','testMsisdn':'','callbackUrl':'','startDate':'','endDate':'','eventTime':'','externalReference':'','displayName':'','customerReference':'','accountReference':'','billingAccountReference':'','contractReference':'','subscriptionReference':'','serviceCode':'','meterCode':'','planCode':''}
    for item in inventory:
        for key in re.findall(r'\{([^}]+)\}',item['path']):envkeys.setdefault(key,'')
    environment={'name':'Cito — configure approved environment','values':[{'key':k,'value':v,'type':'default','enabled':True} for k,v in envkeys.items()],'_postman_variable_scope':'environment'}
    jd=lambda obj:json.dumps(obj,indent=2,ensure_ascii=False)+'\n'
    rows=['# External endpoint catalogue','',f'Package {VERSION}. Generated from the owning contracts; counts are API descriptions, not live-service certifications.','', '| Method | Path | Operation | Authentication | Environment policy |','|---|---|---|---|---|']
    rows += [f"| {r['method']} | `{r['path']}` | `{r['operationId']}` | {r['auth']} | {r['environment']} |" for r in inventory]
    return {'Docs/Api/consumer/external-openapi.json':jd(spec),'Docs/Api/consumer/operation-manifest.json':jd(inventory),'Docs/Api/consumer/ENDPOINTS.md':'\n'.join(rows)+'\n', 'Docs/Api/cpay-v2-postman-collection.json':jd(postman),'Docs/Api/consumer/cito-environment.postman_environment.json':jd(environment)}

def generate(check=False):
    for relative,content in outputs().items():
        path=ROOT/relative
        if check:
            if not path.exists() or path.read_text()!=content:raise SystemExit('Stale consumer output: '+relative)
        else:path.parent.mkdir(parents=True,exist_ok=True);path.write_text(content)

def bundle(destination,revision):
    if not re.fullmatch('[0-9a-f]{40}',revision):raise ValueError('An immutable source revision is required')
    actual=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
    if revision!=actual:raise ValueError('Bundle revision must be the actual checked-out Git commit')
    relevant=['sdk','scripts/consumer_api','Docs/Api/consumer','Docs/Api/cpay-v2-postman-collection.json','Docs/Api/Cito-Gateway-Integration-Guide.md']
    if subprocess.check_output(['git','status','--porcelain','--',*relevant],cwd=ROOT,text=True).strip():raise ValueError('Commit the handover sources before building an immutable delivery')
    destination=Path(destination);destination.mkdir(parents=True,exist_ok=True)
    files={}
    for path in (ROOT/'sdk').rglob('*'):
        if path.is_file() and path.suffix in ('.py','.js','.cjs','.php','.md','.txt','.json','.java') and not any(x in path.parts for x in ('__pycache__','codegen')) and path.name not in ('test_package.py',):files['sdk/'+str(path.relative_to(ROOT/'sdk'))]=path.read_bytes()
    for path in OUTPUT.glob('*'):
        if path.is_file():files[str(path.relative_to(OUTPUT))]=path.read_bytes()
    files['Cito-External-API.postman_collection.json']=(ROOT/'Docs/Api/cpay-v2-postman-collection.json').read_bytes()
    files['INTEGRATION-GUIDE.md']=(ROOT/'Docs/Api/Cito-Gateway-Integration-Guide.md').read_bytes()
    files['WEBHOOKS.md']=(ROOT/'Docs/Api/consumer/CALLBACK-RECEIVER.md').read_bytes()
    files['verify_manifest.py']=(ROOT/'scripts/consumer_api/verify_manifest.py').read_bytes()
    files['index.html']=portable(json.loads(files['external-openapi.json']),files.get('START-HERE.md',b'').decode()).encode()
    manifest={'package':'Cito external developer handover','version':VERSION,'brandVersion':'1.2','sourceRevision':revision,'runtimeCertification':'NOT_IMPLIED_BY_PACKAGE','files':{name:hashlib.sha256(data).hexdigest() for name,data in sorted(files.items())}}
    files['MANIFEST.json']=(json.dumps(manifest,indent=2)+'\n').encode()
    filename=destination/f'Cito-External-Developer-Kit-{VERSION}-{revision[:8]}.zip'
    with zipfile.ZipFile(filename,'w',zipfile.ZIP_DEFLATED) as archive:
        for name,data in sorted(files.items()):
            info=zipfile.ZipInfo(name,date_time=(2026,9,12,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;info.external_attr=0o100644<<16;archive.writestr(info,data)
    digest=hashlib.sha256(filename.read_bytes()).hexdigest()
    filename.with_suffix('.zip.sha256').write_text(digest+'  '+filename.name+'\n')
    print(filename, 'SHA256', digest)
    return filename

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--check',action='store_true');parser.add_argument('--bundle');parser.add_argument('--revision');args=parser.parse_args()
    generate(args.check)
    if args.bundle:bundle(args.bundle,args.revision or subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip())
