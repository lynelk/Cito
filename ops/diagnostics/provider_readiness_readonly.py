#!/usr/bin/env python3
"""Read-only provider readiness inspection for Cito production.

Reports names/state only. Never prints credential values, decrypts secrets,
calls provider endpoints, activates a provider, changes settings or moves money.
"""
from __future__ import annotations
import json, os
from urllib.parse import urlsplit, unquote

PROJECT='8d361df2-d17e-4d15-984e-435735f22f6c'
ENV='bec50941-04c7-426d-8bc3-883cbdece892'
SERVICE='5f5b474d-b5b7-4c4f-aeac-664f42c4f1dc'
MTN=(
 'gw_mtn_api_env','gw_mtn_api_url','gw_mtn_api_collection_user','gw_mtn_api_collection_key',
 'gw_mtn_api_collection_subscription','gw_mtn_api_disbursement_user','gw_mtn_api_disbursement_key',
 'gw_mtn_api_disbursement_subscription','gw_mtn_api_base_currency')
AIRTEL=(
 'gw_airtelmoney_use_open_api','gw_airtelmoney_api_url','gw_airtelmoney_api_username',
 'gw_airtelmoney_api_password','gw_airtelmoney_disbursement_account','gw_airtelmoney_collections_account',
 'gw_airtelmoney_api_pin')
SENSITIVE_HINTS=('secret','password','key','token','pin','username','user','subscription','account')


def masked_present(value): return value is not None and str(value).strip() != ''

def main():
    assert (os.getenv('RAILWAY_PROJECT_ID'),os.getenv('RAILWAY_ENVIRONMENT_ID'),os.getenv('RAILWAY_SERVICE_ID'))==(PROJECT,ENV,SERVICE)
    import pymysql
    u=urlsplit(os.environ['DB_URL'].removeprefix('jdbc:'))
    assert u.hostname and u.hostname.endswith('.railway.internal')
    db=pymysql.connect(host=u.hostname,port=u.port or 3306,user=os.environ['DB_USERNAME'],password=os.environ['DB_PASSWORD'],database=unquote(u.path.lstrip('/')),connect_timeout=5,read_timeout=8,write_timeout=5,autocommit=False)
    report={'diagnostic':'PROVIDER_READINESS_READONLY','database_writes':0,'provider_requests':0,'secrets_printed':0,'providers':{}}
    try:
      with db.cursor() as c:
        c.execute('START TRANSACTION READ ONLY')
        c.execute("SELECT column_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='settings' AND column_name IN ('name','setting_name','setting_value')")
        cols={r[0] for r in c.fetchall()}; name='name' if 'name' in cols else 'setting_name' if 'setting_name' in cols else None
        if not name or 'setting_value' not in cols: raise RuntimeError('Unsupported settings schema')
        keys=MTN+AIRTEL
        c.execute('SELECT `'+name+'`,setting_value FROM settings WHERE `'+name+'` IN ('+','.join(['%s']*len(keys))+')',keys)
        raw={str(k):v for k,v in c.fetchall()}
        def assess(provider, required):
          missing=[k for k in required if not masked_present(raw.get(k))]
          presence={k:masked_present(raw.get(k)) for k in required}
          # Never include any setting values. Even non-secret selector values are deliberately omitted.
          return {'required_setting_names':list(required),'present_by_name':presence,'missing_setting_names':missing,'legacy_settings_complete':not missing}
        report['providers']['MTN']=assess('MTN',MTN)
        airtel_required=list(AIRTEL[:-1])
        if str(raw.get('gw_airtelmoney_use_open_api') or '').strip().lower() in ('yes','true','1'):
          airtel_required.append('gw_airtelmoney_api_pin')
        report['providers']['AIRTEL']=assess('AIRTEL',tuple(airtel_required))
        c.execute("SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND (LOWER(table_name) LIKE '%credential%' OR LOWER(table_name) LIKE '%certification%') ORDER BY table_name")
        tables=[r[0] for r in c.fetchall()]
        report['evidence_tables']=tables
        for table in tables:
          c.execute("SELECT column_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=%s ORDER BY ordinal_position",(table,))
          columns=[r[0] for r in c.fetchall()]
          safe_cols=[x for x in columns if x.lower() in {'provider_code','channel_code','status','environment','revision','tested_revision','last_test_status','evidence_status','scenario_name','approved_at','tested_at','last_tested_at','updated_at'}]
          if not safe_cols: continue
          quoted=','.join('`'+x+'`' for x in safe_cols)
          # Rows are summarized only through non-secret status/evidence columns. Limit avoids accidental bulk export.
          c.execute('SELECT '+quoted+' FROM `'+table+'` ORDER BY 1 DESC LIMIT 50')
          rows=[]
          for row in c.fetchall(): rows.append({safe_cols[i]:row[i] for i in range(len(safe_cols))})
          report.setdefault('evidence',{})[table]=rows
    finally:
      db.rollback(); db.close()
    print(json.dumps(report,default=str,sort_keys=True))

if __name__=='__main__': main()
