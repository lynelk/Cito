"""Run inside the authorized Railway runtime; emit no credentials or customer data.
Only SELECT statements, OAuth token POSTs and HTTPS callback TLS checks are used.
No payment, transfer, refund, activation, balance or credential write is permitted.
"""
import base64
import hashlib
import json
import os
import socket
import ssl
import sys
import urllib.error
import urllib.parse
import urllib.request

import pymysql
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

HOSTS = {
    "airtel_open_api": {"openapi.airtel.africa", "openapiuat.airtel.africa"},
    "mtn_momo": {"proxy.momoapi.mtn.com", "sandbox.momodeveloper.mtn.com"},
}

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None

OPENER = urllib.request.build_opener(NoRedirect(), urllib.request.HTTPSHandler(context=ssl.create_default_context()))

def emit(data):
    print(json.dumps(data, sort_keys=True), flush=True)

def token_check(channel, endpoint, headers, body):
    parsed = urllib.parse.urlsplit(endpoint)
    allowed_paths = {"/auth/oauth2/token"} if channel == "airtel_open_api" else {"/collection/token/", "/disbursement/token/"}
    if parsed.scheme != "https" or parsed.hostname not in HOSTS[channel] or parsed.path not in allowed_paths or parsed.username or parsed.password or parsed.query or parsed.fragment or parsed.port not in (None, 443):
        return {"tokenObtained": False, "result": "ENDPOINT_NOT_ALLOWLISTED"}
    try:
        request = urllib.request.Request(endpoint, data=body, headers=headers, method="POST")
        with OPENER.open(request, timeout=15) as response:
            payload = response.read(65537)
            if len(payload) > 65536:
                return {"tokenObtained": False, "result": "RESPONSE_TOO_LARGE"}
            token_json = json.loads(payload)
            return {"httpStatus": response.status, "tokenObtained": bool(token_json.get("access_token")), "result": "OAUTH_RESPONSE"}
    except urllib.error.HTTPError as error:
        return {"httpStatus": error.code, "tokenObtained": False, "result": "OAUTH_HTTP_ERROR"}
    except Exception:
        return {"tokenObtained": False, "result": "OAUTH_TRANSPORT_OR_FORMAT_ERROR"}

def callback_check(credentials):
    callback = str(credentials.get("callbackUrl") or "")
    host = str(credentials.get("callbackHost") or "").lower().strip()
    parsed = urllib.parse.urlsplit(callback)
    outcome = {"callbackConfigured": bool(callback), "callbackHostMatches": bool(host and parsed.hostname == host), "operatorRegistrationVerified": False}
    # Never probe arbitrary credential-controlled URLs or private addresses.
    if parsed.scheme == "https" and parsed.hostname == "cito.coresynergi.es" and parsed.port in (None, 443):
        try:
            with socket.create_connection((parsed.hostname, 443), timeout=10) as sock:
                with ssl.create_default_context().wrap_socket(sock, server_hostname=parsed.hostname):
                    outcome["callbackTlsVerified"] = True
        except Exception:
            outcome["callbackTlsVerified"] = False
    return outcome

def check(channel, environment, source, status, credentials):
    common = {"channel": channel, "environment": environment, "source": source, "credentialStatus": status, "tlsVerificationEnabled": True}
    common.update(callback_check(credentials))
    if status not in {"ACTIVE", "SANDBOX_TESTED", "CONFIGURED_LEGACY"}:
        emit(dict(common, result="NOT_APPROVED_OR_ACTIVE", tokenObtained=False))
        return
    base = str(credentials.get("baseUrl") or "").rstrip("/")
    products = ["collection", "disbursement"] if channel == "mtn_momo" else ["oauth"]
    for product in products:
        keys = ["baseUrl", "clientId", "clientSecret"] if channel == "airtel_open_api" else ["baseUrl", product + "ApiUser", product + "ApiKey", product + "SubscriptionKey"]
        missing = [key for key in keys if not str(credentials.get(key) or "").strip()]
        if missing:
            emit(dict(common, product=product, missingFields=missing, tokenObtained=False, result="MISSING_CONFIGURATION"))
            continue
        if channel == "airtel_open_api":
            path = str(credentials.get("tokenPath") or "/auth/oauth2/token")
            endpoint = path if path.startswith("https://") else base + "/" + path.lstrip("/")
            headers = {"Content-Type": "application/json", "Accept": "application/json"}
            body = json.dumps({"client_id": credentials["clientId"], "client_secret": credentials["clientSecret"], "grant_type": "client_credentials"}).encode()
        else:
            endpoint = str(credentials.get(product + "TokenUrl") or (base + "/" + product + "/token/"))
            pair = str(credentials[product + "ApiUser"]) + ":" + str(credentials[product + "ApiKey"])
            headers = {"Authorization": "Basic " + base64.b64encode(pair.encode()).decode(), "Ocp-Apim-Subscription-Key": str(credentials[product + "SubscriptionKey"]), "Accept": "application/json"}
            body = b""
        emit(dict(common, product=product, missingFields=[], **token_check(channel, endpoint, headers, body)))

def run():
    raw_url = os.environ["DB_URL"].removeprefix("jdbc:")
    parsed = urllib.parse.urlsplit(raw_url)
    connection = pymysql.connect(host=parsed.hostname, port=parsed.port or 3306, user=os.environ["DB_USERNAME"], password=os.environ["DB_PASSWORD"], database=parsed.path.lstrip("/"), connect_timeout=10, read_timeout=15, write_timeout=15, autocommit=False, cursorclass=pymysql.cursors.DictCursor)
    total = 0
    try:
        with connection.cursor() as cursor:
            cursor.execute("SET TRANSACTION READ ONLY")
            cursor.execute("START TRANSACTION READ ONLY")
            key = hashlib.sha256(os.environ["MERCHANT_CHANNEL_ENCRYPTION_KEY"].encode()).digest()
            for table, source in [("merchant_channel_credentials", "MERCHANT"), ("platform_channel_credentials", "PLATFORM_SHARED")]:
                cursor.execute("SELECT channel_code, environment, status, credential_payload FROM " + table + " WHERE channel_code IN (%s,%s) LIMIT 21", ("airtel_open_api", "mtn_momo"))
                rows = cursor.fetchall()
                if len(rows) > 20:
                    emit({"source": source, "result": "SCOPE_LIMIT_EXCEEDED"})
                    continue
                for row in rows:
                    total += 1
                    try:
                        payload = base64.b64decode(row["credential_payload"], validate=True)
                        credentials = json.loads(AESGCM(key).decrypt(payload[:12], payload[12:], None))
                    except Exception:
                        emit({"channel": row["channel_code"], "source": source, "environment": row["environment"], "result": "DECRYPTION_OR_FORMAT_ERROR", "tokenObtained": False})
                        continue
                    check(row["channel_code"], row["environment"], source, row["status"], credentials)
            emit({"result": "READ_ONLY_PREFLIGHT_COMPLETE", "credentialRowsInspected": total, "paymentsSubmitted": 0, "databaseWrites": 0, "operatorRegistrationVerified": False})
    finally:
        connection.rollback()
        connection.close()

if __name__ == "__main__":
    try:
        run()
    except Exception as error:
        emit({"result": "PREFLIGHT_COULD_NOT_COMPLETE", "errorClass": type(error).__name__, "paymentsSubmitted": 0})
        sys.exit(1)
