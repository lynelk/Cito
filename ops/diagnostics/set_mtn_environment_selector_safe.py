#!/usr/bin/env python3
"""Set only the non-secret MTN environment selector after proving execution credentials are absent.

This deliberately cannot activate MTN or make a provider request. It refuses to
run if any collection/disbursement credential is already present, because in
that case changing the selector could alter live behavior.
"""
import os
from urllib.parse import urlsplit, unquote

PROJECT='8d361df2-d17e-4d15-984e-435735f22f6c'; ENV='bec50941-04c7-426d-8bc3-883cbdece892'; SERVICE='5f5b474d-b5b7-4c4f-aeac-664f42c4f1dc'
CREDENTIAL_KEYS=('gw_mtn_api_collection_user','gw_mtn_api_collection_key','gw_mtn_api_collection_subscription','gw_mtn_api_disbursement_user','gw_mtn_api_disbursement_key','gw_mtn_api_disbursement_subscription')

def main():
  assert (os.getenv('RAILWAY_PROJECT_ID'),os.getenv('RAILWAY_ENVIRONMENT_ID'),os.getenv('RAILWAY_SERVICE_ID'))==(PROJECT,ENV,SERVICE)
  import pymysql
  u=urlsplit(os.environ['DB_URL'].removeprefix('jdbc:'))
  db=pymysql.connect(host=u.hostname,port=u.port or 3306,user=os.environ['DB_USERNAME'],password=os.environ['DB_PASSWORD'],database=unquote(u.path.lstrip('/')),autocommit=False)
  try:
    with db.cursor() as c:
      c.execute("SELECT column_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='settings' AND column_name IN ('name','setting_name','setting_value')")
      cols={r[0] for r in c.fetchall()}; name='name' if 'name' in cols else 'setting_name' if 'setting_name' in cols else None
      assert name and 'setting_value' in cols
      allkeys=('gw_mtn_api_env',)+CREDENTIAL_KEYS
      c.execute('SELECT `'+name+'`,setting_value FROM settings WHERE `'+name+'` IN ('+','.join(['%s']*len(allkeys))+')',allkeys)
      values={str(k):str(v or '').strip() for k,v in c.fetchall()}
      if any(values.get(k) for k in CREDENTIAL_KEYS):
        raise RuntimeError('Refusing selector mutation because an MTN execution credential is present')
      if values.get('gw_mtn_api_env','').lower()=='mtnuganda':
        print('MTN_ENV_SELECTOR_ALREADY_CORRECT; credential fields remain absent; provider_requests=0')
        db.rollback(); return
      c.execute('UPDATE settings SET setting_value=%s WHERE `'+name+'`=%s',('mtnuganda','gw_mtn_api_env'))
      if c.rowcount==0:
        c.execute('INSERT INTO settings (`'+name+'`,setting_value) VALUES (%s,%s)',('gw_mtn_api_env','mtnuganda'))
      if c.rowcount!=1: raise RuntimeError('Expected exactly one selector row mutation')
      db.commit()
      print('MTN_ENV_SELECTOR_SET=mtnuganda; credentials_absent=true; provider_requests=0; money_movement=0')
  except Exception:
    db.rollback(); raise
  finally: db.close()
if __name__=='__main__': main()
