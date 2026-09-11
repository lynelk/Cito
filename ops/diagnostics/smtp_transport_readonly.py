#!/usr/bin/env python3
"""Read-only SMTP DNS/TCP/TLS diagnosis. Never authenticates or sends mail.

Run only in the existing production diagnostic worker, not the application.
Only non-secret SMTP settings are selected in a read-only DB transaction.
The result describes this worker's egress, not a successful application send.
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


def main() -> None:
    if (os.environ.get('RAILWAY_PROJECT_ID'), os.environ.get('RAILWAY_ENVIRONMENT_ID'), os.environ.get('RAILWAY_SERVICE_ID')) != (PROJECT, ENVIRONMENT, SERVICE):
        raise RuntimeError('Diagnostic worker scope mismatch')
    import pymysql
    url = urlsplit(os.environ['DB_URL'].removeprefix('jdbc:'))
    if not url.hostname or not url.hostname.endswith('.railway.internal'):
        raise RuntimeError('Private database reference required')
    connection = pymysql.connect(host=url.hostname, port=url.port or 3306,
        user=os.environ['DB_USERNAME'], password=os.environ['DB_PASSWORD'],
        database=unquote(url.path.lstrip('/')), connect_timeout=5, read_timeout=5,
        write_timeout=5, autocommit=False)
    try:
        with connection.cursor() as cursor:
            cursor.execute('START TRANSACTION READ ONLY')
            cursor.execute('SELECT setting_name, setting_value FROM settings WHERE setting_name IN (' + ','.join(['%s'] * len(KEYS)) + ')', tuple('mail.smtp.' + key for key in KEYS))
            settings = {key.removeprefix('mail.smtp.'): str(value or '').strip() for key, value in cursor.fetchall()}
    finally:
        connection.rollback()
        connection.close()
    # These non-secret overrides are referenced from the backend service, not guessed.
    for key, env in (('port', 'PORT'), ('ssl.enable', 'SSL'), ('starttls.enable', 'STARTTLS')):
        value = os.environ.get('CITO_SMTP_' + env)
        if value:
            settings[key] = value.strip()
    host = settings.get('host', '')
    if not re.fullmatch(r'[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?', host) or '.' not in host:
        raise RuntimeError('SMTP host missing or invalid')
    port = int(settings.get('port') or '587')
    if port not in (25, 465, 587, 2525):
        raise RuntimeError('Configured SMTP port is outside this bounded diagnostic allowlist')
    report = {'diagnostic': 'SMTP_TRANSPORT_READONLY', 'observed_at': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()),
        'vantage': 'existing-production-sibling-worker-not-backend', 'host': host,
        'configured_port': port, 'configured_implicit_tls': settings.get('ssl.enable'),
        'configured_starttls': settings.get('starttls.enable'), 'checks': [],
        'auth_attempts': 0, 'messages_sent': 0, 'application_delivery': 'NOT_TESTED', 'database_writes': 0}
    addresses = socket.getaddrinfo(host, port, type=socket.SOCK_STREAM)
    if not addresses or any(not ipaddress.ip_address(row[4][0]).is_global for row in addresses):
        raise RuntimeError('SMTP destination must resolve exclusively to public IPs')
    report['dns'] = 'PASS'
    for selected in dict.fromkeys((port, 465, 587)):
        started = time.monotonic()
        record = {'port': selected, 'tcp': 'NOT_TESTED', 'tls': 'NOT_TESTED', 'smtp': 'NOT_TESTED'}
        try:
            # Use the already validated IPv4 address; do not re-resolve an untrusted host.
            ipv4 = next((row for row in addresses if row[0] == socket.AF_INET), None)
            if ipv4 is None:
                record['tcp'] = 'NO_IPV4_ADDRESS'
                continue
            sock = socket.create_connection((ipv4[4][0], selected), timeout=5)
            record['tcp'] = 'PASS'
            try:
                if selected == 465:
                    sock = ssl.create_default_context().wrap_socket(sock, server_hostname=host)
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
                    client.starttls(context=ssl.create_default_context())
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
    # Bound even DNS/connect stalls. No raw exception messages or settings are printed.
    signal.signal(signal.SIGALRM, lambda *_: (_ for _ in ()).throw(TimeoutError('diagnostic deadline')))
    signal.alarm(90)
    try:
        main()
    except Exception as error:
        print(json.dumps({'diagnostic': 'SMTP_TRANSPORT_READONLY', 'result': 'BLOCKED', 'error_class': type(error).__name__, 'messages_sent': 0, 'database_writes': 0}))
        raise SystemExit(1)
