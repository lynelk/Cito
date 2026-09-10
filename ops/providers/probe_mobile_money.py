#!/usr/bin/env python3
"""Token-only readiness probe. No financial calls or database writes.

Use trusted-network references for DB_URL, DB_USERNAME, DB_PASSWORD,
MERCHANT_CHANNEL_ENCRYPTION_KEY, CUSTOM_GATEWAYSTATE and APP_BASE_URL.
Dependencies: PyMySQL==1.1.2, cryptography==46.0.5. Never print secrets or raw errors.
"""
import base64
import hashlib
import http.client
import json
import os
import signal
import ssl
from urllib.parse import urlsplit, urljoin
import pymysql
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

CHANNELS = {"mtn_momo": "MTN", "airtel_open_api": "AIRTEL"}
MAX_BODY = 65536


def emit(**values):
    print(json.dumps(values, sort_keys=True), flush=True)


def deadline(*_):
    emit(probeComplete=False, result="PROBE_DEADLINE", paymentsSubmitted=0, databaseWrites=0)
    raise SystemExit(2)


def token_request(url, host, headers, body):
    p = urlsplit(url)
    if (p.scheme != "https" or p.hostname != host or p.username or p.password
            or p.port not in (None, 443) or p.query or p.fragment):
        return {"tokenObtained": False, "result": "TOKEN_ENDPOINT_REJECTED"}
    connection = http.client.HTTPSConnection(host, timeout=10, context=ssl.create_default_context())
    try:
        connection.request("POST", p.path or "/", body=body, headers=headers)
        response = connection.getresponse()
        payload = response.read(MAX_BODY + 1)
        code = response.status
        if len(payload) > MAX_BODY:
            return {"httpStatus": code, "tokenObtained": False, "result": "RESPONSE_TOO_LARGE"}
        try:
            data = json.loads(payload)
        except (ValueError, UnicodeError):
            data = {}
        obtained = bool(200 <= code < 300 and isinstance(data, dict)
                        and isinstance(data.get("access_token"), str) and data["access_token"])
        return {"httpStatus": code, "tokenObtained": obtained,
                "result": "AUTHENTICATED" if obtained else "AUTH_REJECTED" if code in (401, 403) else "TOKEN_NOT_RETURNED"}
    except Exception:
        return {"tokenObtained": False, "result": "TLS_OR_NETWORK_FAILURE"}
    finally:
        connection.close()


def probe(provider, environment, raw, source):
    values = {k: str(v).strip() for k, v in raw.items() if v is not None}
    live = environment == "PRODUCTION"
    if environment not in ("PRODUCTION", "SANDBOX"):
        emit(provider=provider, source=source, result="ENVIRONMENT_UNVERIFIED")
        return
    app_host = urlsplit(os.environ.get("APP_BASE_URL", "")).hostname
    callback = values.get("callbackUrl", "")
    callback_host = values.get("callbackHost", "")
    p = urlsplit(callback)
    callback_ok = bool(callback and p.scheme == "https" and p.hostname == app_host)
    if callback_host:
        callback_ok = callback_ok and callback_host == app_host
    meta = {"provider": provider, "environment": environment, "source": source,
            "callbackConfigured": bool(callback or callback_host), "callbackMatchesApplication": callback_ok,
            "operatorCallbackRegistration": "UNVERIFIED"}
    if provider == "MTN":
        host = "proxy.momoapi.mtn.com" if live else "sandbox.momodeveloper.mtn.com"
        base = values.get("baseUrl", "")
        p = urlsplit(base)
        scope_ok = values.get("targetEnvironment") == ("mtnuganda" if live else "sandbox") and values.get("baseCurrency") == ("UGX" if live else "EUR")
        for product, prefix in (("COLLECTION", "collection"), ("DISBURSEMENT", "disbursement")):
            required = ["baseUrl", "targetEnvironment", "baseCurrency", prefix + "ApiUser", prefix + "ApiKey", prefix + "SubscriptionKey"]
            missing = [key for key in required if not values.get(key)]
            if missing:
                emit(**meta, product=product, missingFields=missing, tokenObtained=False, result="CONFIGURATION_INCOMPLETE")
                continue
            if not scope_ok or p.hostname != host or p.path not in ("", "/"):
                emit(**meta, product=product, tokenObtained=False, result="ENVIRONMENT_SCOPE_MISMATCH")
                continue
            auth = base64.b64encode((values[prefix + "ApiUser"] + ":" + values[prefix + "ApiKey"]).encode()).decode()
            result = token_request(base.rstrip("/") + "/" + prefix + "/token/", host,
                                   {"Authorization": "Basic " + auth, "Ocp-Apim-Subscription-Key": values[prefix + "SubscriptionKey"], "Content-Type": "application/json"}, b"")
            emit(**meta, product=product, **result)
    else:
        host = "openapi.airtel.africa" if live else "openapiuat.airtel.africa"
        missing = [key for key in ("baseUrl", "clientId", "clientSecret") if not values.get(key)]
        meta.update(product="OAUTH", payoutEncryptionConfigured=bool(values.get("apiPin") and values.get("publicKey")))
        if missing:
            emit(**meta, missingFields=missing, tokenObtained=False, result="CONFIGURATION_INCOMPLETE")
            return
        if values.get("country", "UG") != "UG" or values.get("currency", "UGX") != "UGX":
            emit(**meta, tokenObtained=False, result="ENVIRONMENT_SCOPE_MISMATCH")
            return
        base = values["baseUrl"]
        if urlsplit(base).hostname != host or urlsplit(base).path not in ("", "/"):
            emit(**meta, tokenObtained=False, result="ENVIRONMENT_SCOPE_MISMATCH")
            return
        url = urljoin(base.rstrip("/") + "/", values.get("tokenPath") or "/auth/oauth2/token")
        body = json.dumps({"client_id": values["clientId"], "client_secret": values["clientSecret"], "grant_type": "client_credentials"}).encode()
        emit(**meta, **token_request(url, host, {"Content-Type": "application/json", "Accept": "application/json"}, body))


def main():
    signal.signal(signal.SIGALRM, deadline)
    signal.alarm(180)
    url = urlsplit(os.environ["DB_URL"].removeprefix("jdbc:"))
    if not url.hostname or not url.hostname.endswith(".railway.internal"):
        raise ValueError("private database required")
    conn = pymysql.connect(host=url.hostname, port=url.port or 3306,
                           user=os.environ["DB_USERNAME"], password=os.environ["DB_PASSWORD"],
                           database=url.path.lstrip("/"), connect_timeout=10, read_timeout=10,
                           write_timeout=10, cursorclass=pymysql.cursors.DictCursor, autocommit=False)
    try:
        with conn.cursor() as cursor:
            cursor.execute("SET SESSION TRANSACTION READ ONLY")
            cursor.execute("START TRANSACTION READ ONLY")
            cursor.execute("SELECT name,setting_value FROM settings WHERE name LIKE 'gw_mtn_%' OR name LIKE 'gw_airtelmoney_%' OR name='use_merchant_provider_credentials'")
            settings = {r["name"]: r["setting_value"] for r in cursor.fetchall()}
            owned = str(settings.get("use_merchant_provider_credentials", "false")).lower() == "true"
            emit(databaseReadOnly=True, legacyMerchantCredentialMode=owned, legacyMerchantCredentialsNotProbed=owned)
            if not owned:
                env = os.environ.get("CUSTOM_GATEWAYSTATE", "").upper()
                if env in ("PRODUCTION", "SANDBOX"):
                    suffix = "_sandbox" if env == "SANDBOX" else ""
                    mtn = {"baseUrl": settings.get("gw_mtn_api_url" + suffix),
                           "targetEnvironment": "sandbox" if suffix else settings.get("gw_mtn_api_env"),
                           "baseCurrency": settings.get("gw_mtn_api_base_currency" + suffix)}
                    for product, legacy in (("collection", "collections"), ("disbursement", "disbursements")):
                        for field, old in (("ApiUser", "user_id"), ("ApiKey", "user_key"), ("SubscriptionKey", "subscription_key")):
                            mtn[product + field] = settings.get("gw_mtn_api_" + legacy + "_" + old + suffix)
                    probe("MTN", env, mtn, "LEGACY_PLATFORM")
                    names = {"baseUrl": "api_url", "clientId": "api_username", "clientSecret": "api_password", "tokenPath": "token_url", "apiPin": "api_pin", "publicKey": "api_public_key"}
                    probe("AIRTEL", env, {k: settings.get("gw_airtelmoney_" + v) for k, v in names.items()}, "LEGACY_PLATFORM")
                else:
                    emit(result="RUNTIME_ENVIRONMENT_UNVERIFIED")
            for table, source in (("platform_channel_credentials", "PLATFORM_SHARED"), ("merchant_channel_credentials", "MERCHANT")):
                cursor.execute("SELECT channel_code,environment,status,COUNT(*) AS count_value FROM " + table + " WHERE channel_code IN ('mtn_momo','airtel_open_api') GROUP BY channel_code,environment,status")
                emit(source=source, credentialRecordGroups=cursor.fetchall())
                cursor.execute("SELECT channel_code,environment,credential_payload FROM " + table + " WHERE channel_code IN ('mtn_momo','airtel_open_api') AND (status='ACTIVE' OR (environment='SANDBOX' AND status='SANDBOX_TESTED')) ORDER BY id LIMIT 12")
                for row in cursor.fetchall():
                    try:
                        packed = base64.b64decode(row["credential_payload"], validate=True)
                        key = hashlib.sha256(os.environ["MERCHANT_CHANNEL_ENCRYPTION_KEY"].encode()).digest()
                        values = json.loads(AESGCM(key).decrypt(packed[:12], packed[12:], None))
                        probe(CHANNELS[row["channel_code"].lower()], row["environment"], values, source)
                    except Exception:
                        emit(source=source, result="APPROVED_CREDENTIAL_PROBE_UNAVAILABLE", tokenObtained=False)
        conn.rollback()
        emit(probeComplete=True, paymentsSubmitted=0, databaseWrites=0)
    finally:
        conn.close()


if __name__ == "__main__":
    try:
        main()
    except Exception:
        emit(probeComplete=False, result="READ_ONLY_PROBE_FAILED", paymentsSubmitted=0, databaseWrites=0)
        raise SystemExit(1)
