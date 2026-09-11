#!/usr/bin/env python3
"""One controlled Cito delivery test to the existing owner's admin address.

Uses the production SMTP settings as the application does. Reads credentials
only in memory; no secret values, SMTP replies or message bodies enter logs.
No automatic retry after an uncertain outcome. No database or account changes.
"""
from __future__ import annotations
import json
import os
import signal
import smtplib
import ssl
from email.message import EmailMessage
from email.utils import formatdate, parseaddr
from urllib.parse import urlsplit, unquote

PROJECT = '8d361df2-d17e-4d15-984e-435735f22f6c'
ENVIRONMENT = 'bec50941-04c7-426d-8bc3-883cbdece892'
SERVICE = '5f5b474d-b5b7-4c4f-aeac-664f42c4f1dc'
TEST_ID = 'CITO-195-EMAIL-20260911-01'
RECIPIENT = 'lynelk@gmail.com'
STAGE = 'SCOPE'


def main() -> None:
    global STAGE
    if (os.environ.get('RAILWAY_PROJECT_ID'), os.environ.get('RAILWAY_ENVIRONMENT_ID'), os.environ.get('RAILWAY_SERVICE_ID')) != (PROJECT, ENVIRONMENT, SERVICE):
        raise RuntimeError('Worker scope mismatch')
    if os.environ.get('CITO_SINGLE_ADMIN_DELIVERY_CHECK') != TEST_ID:
        raise RuntimeError('Explicit single-check marker is required')
    import pymysql
    url = urlsplit(os.environ['DB_URL'].removeprefix('jdbc:'))
    if not url.hostname or not url.hostname.endswith('.railway.internal'):
        raise RuntimeError('Private DB required')
    STAGE = 'CONFIGURATION_READ'
    db = pymysql.connect(host=url.hostname, port=url.port or 3306,
        database=unquote(url.path.lstrip('/')), user=os.environ['DB_USERNAME'],
        password=os.environ['DB_PASSWORD'], connect_timeout=5, read_timeout=5,
        write_timeout=5, autocommit=False)
    try:
        with db.cursor() as cursor:
            cursor.execute('START TRANSACTION READ ONLY')
            suffixes = ('host','port','username','password','auth','ssl.enable','starttls.enable','from')
            cursor.execute('SELECT name,setting_value FROM settings WHERE name IN (' + ','.join(['%s']*len(suffixes)) + ')', tuple('mail.smtp.'+key for key in suffixes))
            settings = {key.removeprefix('mail.smtp.'): str(value or '') for key,value in cursor.fetchall()}
    finally:
        db.rollback()
        db.close()
    def value(key: str, suffix: str, default: str = '') -> str:
        selected = os.environ.get('CITO_SMTP_'+suffix) or settings.get(key) or default
        return selected if key == 'password' else selected.strip()
    host = value('host','HOST')
    port = int(value('port','PORT','587'))
    username = value('username','USERNAME')
    password = value('password','PASSWORD')
    implicit = value('ssl.enable','SSL',str(port==465)).lower() == 'true'
    starttls = not implicit and value('starttls.enable','STARTTLS','true').lower() == 'true'
    sender = value('from','FROM',username)
    if host != 'mail.coresynergi.es' or port not in (465,587) or not username or not password:
        raise RuntimeError('Approved SMTP configuration missing')
    if not implicit and not starttls or port == 465 and not implicit:
        raise RuntimeError('Secure SMTP handshake required')
    if parseaddr(sender)[1] != sender or '@' not in sender or any(c in sender for c in '\r\n'):
        raise RuntimeError('Invalid configured sender')
    STAGE = 'TLS_CONNECTION'
    client = smtplib.SMTP_SSL(host,port,timeout=10,context=ssl.create_default_context()) if implicit else smtplib.SMTP(host,port,timeout=10)
    try:
        client.ehlo()
        if starttls:
            client.starttls(context=ssl.create_default_context())
            client.ehlo()
        STAGE = 'AUTHENTICATION'
        client.login(username,password)
        print(json.dumps({'test_id':TEST_ID,'smtp_authentication':'PASS','vantage':'existing-production-sibling-worker'}),flush=True)
        message = EmailMessage()
        message['From'] = sender
        message['To'] = RECIPIENT
        message['Date'] = formatdate(localtime=False)
        message['Message-ID'] = '<cito-195-email-20260911-01@cito.coresynergi.es>'
        message['Subject'] = 'Cito email delivery verification | '+TEST_ID
        message.set_content('This is one controlled Cito email-service verification sent to the existing administrator address during remediation.\n\nReference: '+TEST_ID+'\n\nNo password, OTP, payment instruction or customer data is included. Receiving this message confirms this diagnostic email reached your mailbox. It does not confirm that every application email workflow has passed acceptance.\n')
        STAGE = 'SINGLE_TEST_SUBMISSION'
        refused = client.send_message(message,from_addr=sender,to_addrs=[RECIPIENT])
        print(json.dumps({'test_id':TEST_ID,'smtp_acceptance':'PASS' if not refused else 'REJECTED',
            'attempted_messages':1,'recipient':'l***@gmail.com','inbox_delivery':'NOT_YET_VERIFIED',
            'application_workflow':'NOT_TESTED','database_writes':0}),flush=True)
    finally:
        # Do not let a QUIT timeout hide an already recorded acceptance result.
        client.close()


if __name__ == '__main__':
    signal.signal(signal.SIGALRM,lambda *_: (_ for _ in ()).throw(TimeoutError()))
    signal.alarm(75)
    try:
        main()
    except Exception as error:
        print(json.dumps({'test_id':TEST_ID,'result':'BLOCKED_OR_UNCERTAIN','stage':STAGE,
            'error_class':type(error).__name__,'retry_performed':False,'database_writes':0}),flush=True)
        raise SystemExit(1)
