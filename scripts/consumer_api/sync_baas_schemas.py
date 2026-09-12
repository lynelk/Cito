#!/usr/bin/env python3
"""Fill canonical BaaS request schemas from the owning controller records and reviewed validators.

This is source-derived documentation, not a change to authentication or runtime validation.
Unknown Java record types / undocumented mappings fail the build instead of producing '{}'.
"""
from pathlib import Path
import argparse,copy,re,yaml
ROOT=Path(__file__).resolve().parents[2]
DIRECTORY=ROOT/'InitializrSpringbootProjectFresh/src/main/java/net/citotech/cito/billing/baas'
TARGET=ROOT/'Docs/Api/cito-billing-baas-v2.1-openapi.yaml'
REQUIRED={
 'CustomerRequest':['externalReference','displayName'],
 'AccountRequest':['customerReference','accountReference','currency'],
 'ContractRequest':['customerReference','contractReference','currency'],
 'SubscriptionRequest':['customerReference','accountReference','contractReference','subscriptionReference','serviceCode','planCode'],
 'EntitlementRequest':['entitlementCode'],
 'AuthorizeChargeRequest':['billingAccountReference','serviceCode','usageQuantity','netAmount','currency','idempotencyKey'],
 'ProtectedActionRequest':['actionType','resourceType','resourceReference'],
 'DecisionRequest':[],
 'PricingQuoteRequest':['billingAccountReference','serviceCode','meterCode','ratingBaseAmount','sourceCurrency'],
 'RatedAuthorizationRequest':['billingAccountReference','serviceCode','meterCode','usageQuantity','ratingBaseAmount','sourceCurrency','idempotencyKey'],
 'PriceOverrideRequest':['serviceCode','meterCode','priceBookVersionId'],
 'UsageEventRequest':['serviceCode','meterCode','quantity','sourceReference','idempotencyKey'],
 'WebhookRequest':['eventType','endpointUrl'],
 'WebhookTestRequest':['eventType'],
}
SOURCES=['BillingBaasController.java','BillingBaasPricingController.java','BillingBaasUsageController.java','BillingBaasWebhookController.java']

def fields(record):return [re.sub(r'\s+',' ',v.strip()).rsplit(' ',1) for v in re.split(r',\s*(?![^<]*>)',record) if v.strip()]

def schema(java_type,name,required):
    if java_type=='String':value={'type':'string'}
    elif java_type=='Instant':value={'type':'string','format':'date-time','description':'ISO-8601 instant. Preserve the original value when retrying the same business operation.'}
    elif java_type in ('long','Long','int','Integer'):value={'type':'integer','format':'int64' if 'ong' in java_type else 'int32'}
    elif java_type=='BigDecimal':value={'oneOf':[{'type':'string','pattern':r'^-?\d+(?:\.\d+)?$'},{'type':'number'}],'description':'Exact decimal; send as a JSON string to avoid binary floating-point precision loss. Business validation and canonical four-decimal accounting still apply.','example':'1.0000'}
    elif java_type=='Map<String, String>':value={'type':'object','additionalProperties':{'type':'string'}}
    else:raise ValueError('Unmapped request field: '+java_type+' '+name)
    if name in ('metadataJson','termsJson'):value.update(description='Optional JSON encoded as a string, not a nested JSON object.',example='{}')
    if name=='idempotencyKey':value.update(description='Persist one stable key for the original business operation; reuse it with commercially identical input. This is a JSON body field, not the CPay signature header.',example='operation-unique-stable-key')
    if name in ('currency','sourceCurrency'):value.update(description='Currency accepted by the configured billing account and pricing contract.',example='UGX')
    if name in ('externalReference','accountReference','customerReference','contractReference','subscriptionReference','billingAccountReference','sourceReference'):value.update(description='Caller reference or existing reference in the authenticated billing tenant. Sample identifiers are not provisioned records.',example='example-'+name)
    if name=='endpointUrl':value.update(format='uri',description='Approved HTTPS merchant receiver. Registering or testing it can enqueue real outbound delivery; do not batch-run this action.',example='https://receiver.example/cito/events')
    if name=='priceBookVersionId':value.update(description='Existing effective price-book version ID; changes remain independently approved.',example=1)
    if not required:
        if 'type' in value:value['type']=[value['type'],'null']
        else:value['oneOf'].append({'type':'null'})
    return value

def rendered():
    spec=yaml.safe_load(TARGET.read_text())
    for filename in SOURCES:
        text=(DIRECTORY/filename).read_text()
        base=re.search(r'@RequestMapping\("([^"\n]+)"\)',text).group(1)
        for match in re.finditer(r'public record (\w+)\s*\(([\s\S]*?)\)\s*\{\}',text):
            name,body=match.groups()
            if name not in REQUIRED:raise ValueError('Review requiredness of '+name)
            parsed=fields(body);expected=REQUIRED[name]
            props={field:schema(t,field,field in expected) for t,field in parsed}
            if not set(expected)<=props.keys():raise ValueError('Record drift in '+name)
            entry={'type':'object','description':'Request fields from '+filename+'#'+name+'. Field presence does not bypass tenant scope, independent approval, effective dates, quota or financial validation.','properties':props,'x-java-record':filename+'#'+name}
            if expected:entry['required']=expected
            # Unknown fields remain extensible exactly as a JSON object; do not claim a stricter server.
            spec['components'].setdefault('schemas',{})[name]=entry
        mappings=list(re.finditer(r'@(Get|Post)Mapping(?:\(\s*"([^"\n]*)"\s*\))?',text))
        for i,mapping in enumerate(mappings):
            method,suffix=mapping.groups();path=base+(suffix or '')
            chunk=text[mapping.end():mappings[i+1].start() if i+1<len(mappings) else len(text)]
            if path not in spec['paths']:raise ValueError('Undocumented BaaS path '+path)
            op=spec['paths'][path][method.lower()]
            body=re.search(r'@RequestBody(?:\(([^)]*)\))?\s+(\w+)\s+body',chunk)
            if body:
                flags,record=body.groups()
                op['requestBody']={'required':not(flags and 'false' in flags),'content':{'application/json':{'schema':{'$ref':'#/components/schemas/'+record}}}}
            scopes=re.findall(r'"(BILLING_READ|BILLING_WRITE|BILLING_CHARGE|BILLING_USAGE)"',chunk)
            scope=scopes[0] if scopes else ('BILLING_WRITE' if method=='Post' else 'BILLING_READ')
            op['x-cito-required-scope']=scope
            op['description']=(re.split(r'(?:\n\n|^)Consumer authentication:',op.get('description',''),maxsplit=1)[0]+'\n\nConsumer authentication: X-Cito-Api-Key, explicit X-Cito-Environment, and service-account scope '+scope+'. Tenant mapping, quotas and independent maker/checker controls remain enforced. No CPay RSA signature is used for this operation.').lstrip()
    spec['info']['version']='2.1.1'
    return yaml.safe_dump(spec,sort_keys=False,allow_unicode=True,width=100000)

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--check',action='store_true');args=p.parse_args()
    value=rendered()
    if args.check:
        if TARGET.read_text()!=value:raise SystemExit('BaaS request schema drift; regenerate from reviewed controller records')
    else:TARGET.write_text(value)
