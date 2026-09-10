#!/usr/bin/env python3
"""Generate a private, pruned merchant reference from owning contracts; never bundle admin data in JS."""
import argparse, copy, hashlib, json, re
from pathlib import Path
import yaml
ROOT = Path(__file__).resolve().parents[2]
OWNERS = ['cpay-v2-openapi.yaml','cito-platform-v2-openapi.yaml','cito-communications-v2-openapi.yaml',
          'cito-billing-baas-v2.1-openapi.yaml','cito-onboarding-v2-openapi.yaml']
METHODS = {'get','post','put','patch','delete','head','options'}
DENIED = ('/api/v2/admin', '/api/v2/provider-callbacks', '/api/public/', '/api/v2/production-maturity',
          '/api/v2/portal/api-reference', '/api/v2/product-experience','/api/v2/cross-border','/api/v2/beneficiaries','/api/v2/fx')
AUTH = {'CPayV2Signature','CitoBaasApiKey','signedMerchantRequest','MerchantSession','merchantSession'}

def rewrite(value, prefix):
    if isinstance(value, dict):
        if value.pop('nullable', False) and isinstance(value.get('type'), str): value['type']=[value['type'],'null']
        for k, v in list(value.items()):
            if k == '$ref' and isinstance(v,str) and v.startswith('#/components/'):
                a=v.split('/'); a[3]=prefix+a[3]; value[k]='/'.join(a)
            elif k == 'security':
                value[k]=[{prefix+name:scopes for name,scopes in req.items()} for req in v]
            else: rewrite(v,prefix)
    elif isinstance(value,list):
        for x in value: rewrite(x,prefix)

def build():
    out={'openapi':'3.1.0','info':{'title':'Cito Gateway integration and merchant API reference', 'version':'2.0',
         'description':'Authenticated integration and merchant workspace APIs. Session operations require the merchant portal; they are not API-key integrations. API access is metered separately from service charges. See the integration guide and current portal rates.'},
         'servers':[{'url':'https://YOUR-CITO-HOST','description':'Replace with the approved environment URL supplied by Cito.'}],
         'paths':{},'components':{}}
    digest=hashlib.sha256()
    for filename in OWNERS:
        raw=(ROOT/'Docs/Api'/filename).read_bytes(); digest.update(raw)
        spec=yaml.safe_load(raw); prefix=filename.split('-')[0]+'_'+hashlib.sha256(filename.encode()).hexdigest()[:6]+'_'
        for path, item in spec.get('paths',{}).items():
            if path.startswith(DENIED) or 'callback' in path.lower(): continue
            for method,operation in item.items():
                if method not in METHODS: continue
                security=operation.get('security',spec.get('security',[]))
                names={n for s in security for n in s}
                body_signature=operation.get('x-cito-body-signature')
                if not body_signature and (not names or not names <= AUTH): continue
                op=copy.deepcopy(operation); op['security']=security
                if 'parameters' in item: op['parameters']=copy.deepcopy(item['parameters'])+op.get('parameters',[])
                op['x-cito-audience']='MERCHANT_WORKSPACE' if names & {'MerchantSession','merchantSession'} else 'THIRD_PARTY'
                op['x-cito-billing']={'unit':'AUTHENTICATED_REQUEST','defaultRate':'0.0000','currency':'UGX','currentRates':'/api/v2/portal/api-reference/rates'}
                op['x-source-contract']=filename
                rewrite(op,prefix)
                out['paths'].setdefault(path,{})[method]=op
        components=copy.deepcopy(spec.get('components',{})); rewrite(components,prefix)
        for section, values in components.items():
            out['components'].setdefault(section,{}).update({prefix+k:v for k,v in values.items()})
    # Strip every component not reachable from allowed operations, including admin examples/schemas.
    needed=set()
    def walk(value):
        if isinstance(value,dict):
            ref=value.get('$ref')
            if ref and ref.startswith('#/components/') and ref not in needed:
                needed.add(ref); _,_,section,name=ref.split('/')
                walk(out['components'][section][name])
            for security in value.get('security',[]):
                for name in security: needed.add('#/components/securitySchemes/'+name)
            for x in value.values(): walk(x)
        elif isinstance(value,list):
            for x in value: walk(x)
    walk(out['paths'])
    out['components']={section:{name:v for name,v in values.items() if '#/components/'+section+'/'+name in needed}
                       for section,values in out['components'].items()}
    out['components']={k:v for k,v in out['components'].items() if v}
    out['x-source-sha256']=digest.hexdigest()
    return out

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--check',action='store_true');args=parser.parse_args()
    target=ROOT/'InitializrSpringbootProjectFresh/src/main/resources/api-reference/merchant-openapi.json'
    content=json.dumps(build(),indent=2,ensure_ascii=False)+'\n'
    guide_target=target.parent/'integration-guide.md'
    guide=(ROOT/'Docs/Api/Cito-Gateway-Integration-Guide.md').read_text()
    if args.check:
        if not guide_target.exists() or guide_target.read_text()!=guide: raise SystemExit('Portal guide is stale')
        if not target.exists() or target.read_text()!=content: raise SystemExit('Portal reference is stale: run scripts/api_docs/build_portal_reference.py')
    else:
        target.parent.mkdir(parents=True,exist_ok=True);target.write_text(content);guide_target.write_text(guide)
    print('Merchant reference generated/verified:',len(json.loads(content)['paths']),'paths')
if __name__=='__main__': main()
