#!/usr/bin/env python3
"""Read-only SMTP DNS/TCP/TLS diagnosis; never authenticates or sends mail.

Use only the existing production diagnostic worker. Only allowlisted non-secret
SMTP settings are selected in a read-only transaction. The evidence describes
this worker's egress, not application authentication or inbox delivery.
"""
from __future__ import annotations
import ipaddress
import json
import os
import re
import signal
import smtplib
import socket
import ssl
import time
from urllib.parse import urlsplit, unquote

PROJECT = '8d361df2-d17e-4d15-984e-435735f22f6c'
ENVIRONMENT = 'bec50941-04c7-426d-8bc3-883cbdece892'
SERVICE = '5f5b474d-b5b7-4c4f-aeac-664f42c4f1dc'
KEYS = ('host', 'port', 'ssl.enable', 'starttls.enable', 'auth', 'connectiontimeout', 'timeout')
STAGE = 'SCOPE'


def tls_client_context() -> ssl.SSLContext:
    """Require verified TLS1.2+ on both implicit TLS and STARTTLS paths."""
    context = ssl.create_default_context(purpose=ssl.Purpose.SERVER_AUTH)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.check_hostname = True
    context.verify_mode = ssl.CERT_REQUIRED
    return context


def main() -> None:
    global STAGE
    if (os.environ.get('RAILWAY_PROJECT_ID'), os.environ.get('RAILWAY_ENVIRONMENT_ID'), os.environ.get('RAILWAY_SERVICE_ID')) != (PROJECT, ENVIRONMENT, SERVICE):
        raise RuntimeError('Diagnostic worker scope mismatch')
    import pymysql
    url = urlsplit(os.environ['DB_URL'].removeprefix('jdbc:'))
    if not url.hostname or not url.hostname.endswith('.railway.internal'):
        raise RuntimeError('Private database reference required')
    STAGE = 'DATABASE_CONNECTION'
    connection = pymysql.connect(host=url.hostname, port=url.port or 3306,
        user=os.environ['DB_USERNAME'], password=os.environ['DB_PASSWORD'],
        database=unquote(url.path.lstrip('/')), connect_timeout=5, read_timeout=5,
        write_timeout=5, autocommit=False)
    try:
        with connection.cursor() as cursor:
            STAGE = 'READ_ONLY_TRANSACTION'
            cursor.execute('START TRANSACTION READ ONLY')
            STAGE = 'SETTING_COLUMN_METADATA'
            cursor.execute("SELECT column_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='settings' AND column_name IN ('name','setting_name','setting_value')")
            columns = {row[0] for row in cursor.fetchall()}
            name = 'name' if 'name' in columns else 'setting_name' if 'setting_name' in columns else None
            if name is None or 'setting_value' not in columns:
                raise RuntimeError('Unsupported settings schema')
            STAGE = 'NON_SECRET_SETTINGS'
            # Identifier is selected exclusively from the fixed two-name allowlist above.
            cursor.execute('SELECT `' + name + '`, setting_value FROM settings WHERE `' + name + '` IN (' + ','.join(['%s'] * len(KEYS)) + ')', tuple('mail.smtp.' + key for key in KEYS))
            settings = {key.removeprefix('mail.smtp.'): str(value or '').strip() for key, value in cursor.fetchall()}
    finally:
        connection.rollback()
        connection.close()
    for key, env in (('port', 'PORT'), ('ssl.enable', 'SSL'), ('starttls.enable', 'STARTTLS')):
        value = os.environ.get('CITO_SMTP_' + env)
        if value:
            settings[key] = value.strip()
    STAGE = 'SMTP_CONFIGURATION'
    host = settings.get('host', '')
    if not re.fullmatch(r'[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?', host) or '.' not in host:
        raise RuntimeError('SMTP host missing or invalid')
    port = int(settings.get('port') or '587')
    if port not in (25, 465, 587, 2525):
        raise RuntimeError('Configured SMTP port outside bounded diagnostic allowlist')
    report = {'diagnostic': 'SMTP_TRANSPORT_READONLY', 'observed_at': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()),
        'vantage': 'existing-production-sibling-worker-not-backend', 'host': host,
        'configured_port': port, 'configured_implicit_tls': settings.get('ssl.enable'),
        'configured_starttls': settings.get('starttls.enable'), 'checks': [],
        'auth_attempts': 0, 'messages_sent': 0, 'application_delivery': 'NOT_TESTED', 'database_writes': 0}
    STAGE = 'SMTP_DNS'
    addresses = socket.getaddrinfo(host, port, type=socket.SOCK_STREAM)
    if not addresses or any(not ipaddress.ip_address(row[4][0]).is_global for row in addresses):
        raise RuntimeError('SMTP destination must resolve exclusively to public IPs')
    report['dns'] = 'PASS'
    STAGE = 'SMTP_TRANSPORT'
    for selected in dict.fromkeys((port, 465, 587)):
        started = time.monotonic()
        record = {'port': selected, 'tcp': 'NOT_TESTED', 'tls': 'NOT_TESTED', 'smtp': 'NOT_TESTED'}
        try:
            ipv4 = next((row for row in addresses if row[0] == socket.AF_INET), None)
            if ipv4 is None:
                record['tcp'] = 'NO_IPV4_ADDRESS'
                continue
            sock = socket.create_connection((ipv4[4][0], selected), timeout=5)
            record['tcp'] = 'PASS'
            try:
                if selected == 465:
                    sock = tls_client_context().wrap_socket(sock, server_hostname=host)
                    record['tls'] = 'PASS'
                client = smtplib.SMTP(timeout=5, local_hostname='diagnostic.cito.coresynergi.es')
                client._host = host
                client.sock = sock
                code, _ = client.getreply()
                if code != 220:
                    raise RuntimeError('SMTP greeting rejected')
                if client.ehlo()[0] != 250:
                    raise RuntimeError('SMTP EHLO rejected')
                record['smtp'] = 'PASS'
                if selected != 465:
                    if not client.has_extn('starttls'):
                        raise RuntimeError('STARTTLS not advertised')
                    client.starttls(context=tls_client_context())
                    record['tls'] = 'PASS'
                    client.ehlo()
                client.quit()
            finally:
                sock.close()
        except Exception as error:
            record['error_class'] = type(error).__name__
        finally:
            record['elapsed_ms'] = round((time.monotonic() - started) * 1000)
            report['checks'].append(record)
    print(json.dumps(report, sort_keys=True))


if __name__ == '__main__':
    signal.signal(signal.SIGALRM, lambda *_: (_ for _ in ()).throw(TimeoutError('diagnostic deadline')))
    signal.alarm(90)
    try:
        main()
    except Exception as error:
        code = error.args[0] if error.args and isinstance(error.args[0], int) else None
        print(json.dumps({'diagnostic': 'SMTP_TRANSPORT_READONLY', 'result': 'BLOCKED',
            'stage': STAGE, 'error_class': type(error).__name__, 'sql_error_number': code,
            'messages_sent': 0, 'database_writes': 0}))
        raise SystemExit(1)
