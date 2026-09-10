#!/usr/bin/env python3
"""Read-only extension for the deployed legacy merchant-owned credential path.

Uses probe_mobile_money's strict allowlisted token-only client. Does not print
merchant identifiers, credentials, tokens, provider bodies or database errors.
"""
import os
import signal
from urllib.parse import urlsplit
import pymysql
from probe_mobile_money import probe, emit, deadline


def main():
    signal.signal(signal.SIGALRM, deadline)
    signal.alarm(180)
    env = os.environ.get("CUSTOM_GATEWAYSTATE", "").upper()
    if env not in ("PRODUCTION", "SANDBOX"):
        emit(result="RUNTIME_ENVIRONMENT_UNVERIFIED", probeComplete=False)
        return
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
            cursor.execute("SELECT setting_value FROM settings WHERE name='use_merchant_provider_credentials' LIMIT 1")
            mode = cursor.fetchone()
            if not mode or str(mode["setting_value"]).strip().lower() != "true":
                emit(result="LEGACY_MERCHANT_MODE_NOT_ACTIVE", probeComplete=True)
                return
            cursor.execute("SELECT setting_value FROM settings WHERE name='gw_airtelmoney_use_open_api' LIMIT 1")
            route = cursor.fetchone()
            emit(databaseReadOnly=True, airtelOpenApiSelected=bool(route and str(route["setting_value"]).strip().lower() == "yes"))
            cursor.execute("SELECT DISTINCT s.merchant_id FROM merchant_settings s JOIN merchants m ON m.id=s.merchant_id WHERE UPPER(m.status)='ACTIVE' AND (s.name LIKE 'gw_mtn_%' OR s.name LIKE 'gw_airtelmoney_%') ORDER BY s.merchant_id LIMIT 13")
            merchants = cursor.fetchall()
            emit(activeMerchantCredentialScopes=min(len(merchants), 12), scopesTruncated=len(merchants) > 12)
            for row in merchants[:12]:
                cursor.execute("SELECT name,setting_value FROM merchant_settings WHERE merchant_id=%s AND (name LIKE 'gw_mtn_%' OR name LIKE 'gw_airtelmoney_%')", (row["merchant_id"],))
                settings = {r["name"]: r["setting_value"] for r in cursor.fetchall()}
                suffix = "_sandbox" if env == "SANDBOX" else ""
                mtn = {"baseUrl": settings.get("gw_mtn_api_url" + suffix),
                       "targetEnvironment": settings.get("gw_mtn_api_env"),
                       "baseCurrency": settings.get("gw_mtn_api_base_currency" + suffix)}
                for product, legacy in (("collection", "collections"), ("disbursement", "disbursements")):
                    for field, old in (("ApiUser", "user_id"), ("ApiKey", "user_key"), ("SubscriptionKey", "subscription_key")):
                        mtn[product + field] = settings.get("gw_mtn_api_" + legacy + "_" + old + suffix)
                probe("MTN", env, mtn, "LEGACY_MERCHANT")
                names = {"baseUrl": "api_url", "clientId": "api_username", "clientSecret": "api_password", "tokenPath": "token_url", "apiPin": "api_pin", "publicKey": "api_public_key"}
                probe("AIRTEL", env, {k: settings.get("gw_airtelmoney_" + v) for k, v in names.items()}, "LEGACY_MERCHANT")
        conn.rollback()
        emit(probeComplete=True, paymentsSubmitted=0, databaseWrites=0)
    finally:
        conn.close()


if __name__ == "__main__":
    try:
        main()
    except Exception:
        emit(probeComplete=False, result="LEGACY_READ_ONLY_PROBE_FAILED", paymentsSubmitted=0, databaseWrites=0)
        raise SystemExit(1)
