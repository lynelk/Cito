"""Inspect only the configured legacy source; never fall back across merchants."""
import json
import os
import sys
import urllib.parse
import pymysql
from provider_no_money import check, emit


def inspect(values, source):
    mtn_environment = str(values.get("gw_mtn_api_env") or "").strip()
    suffix = "" if mtn_environment == "mtnuganda" else "_sandbox"
    mtn = {"baseUrl": values.get("gw_mtn_api_url" + suffix), "targetEnvironment": mtn_environment, "baseCurrency": values.get("gw_mtn_api_base_currency" + suffix), "callbackUrl": values.get("gw_mtn_api_callback_url"), "callbackHost": values.get("gw_mtn_api_callback_host")}
    for product, key in [("collection", "collections"), ("disbursement", "disbursements")]:
        for target, ending in [("ApiUser", "user_id"), ("ApiKey", "user_key"), ("SubscriptionKey", "subscription_key")]:
            mtn[product + target] = values.get("gw_mtn_api_" + key + "_" + ending + suffix)
    check("mtn_momo", "PRODUCTION" if suffix == "" else "SANDBOX", source, "CONFIGURED_LEGACY", mtn)
    airtel = {"baseUrl": values.get("gw_airtelmoney_api_url"), "clientId": values.get("gw_airtelmoney_api_username"), "clientSecret": values.get("gw_airtelmoney_api_password"), "tokenPath": values.get("gw_airtelmoney_token_url"), "callbackUrl": values.get("gw_airtelmoney_callback_url"), "callbackHost": values.get("gw_airtelmoney_callback_host")}
    host = urllib.parse.urlsplit(str(airtel.get("baseUrl") or "")).hostname
    environment = "PRODUCTION" if host == "openapi.airtel.africa" else "SANDBOX" if host == "openapiuat.airtel.africa" else "UNKNOWN"
    check("airtel_open_api", environment, source, "CONFIGURED_LEGACY" if str(values.get("gw_airtelmoney_use_open_api") or "").lower() == "yes" else "NOT_SELECTED", airtel)


def run():
    parsed = urllib.parse.urlsplit(os.environ["DB_URL"].removeprefix("jdbc:"))
    connection = pymysql.connect(host=parsed.hostname, port=parsed.port or 3306, user=os.environ["DB_USERNAME"], password=os.environ["DB_PASSWORD"], database=parsed.path.lstrip("/"), connect_timeout=10, read_timeout=15, write_timeout=15, autocommit=False, cursorclass=pymysql.cursors.DictCursor)
    try:
        with connection.cursor() as cursor:
            cursor.execute("START TRANSACTION READ ONLY")
            cursor.execute("SELECT name, setting_value FROM settings WHERE name LIKE 'gw_mtn_api_%' OR name LIKE 'gw_airtelmoney_%' OR name IN ('use_merchant_provider_credentials','application_settings_state','simulate_transactions')")
            values = {row["name"]: row["setting_value"] for row in cursor.fetchall()}
            merchant_mode = str(values.get("use_merchant_provider_credentials") or "").lower() in {"true", "yes", "1"}
            emit({"legacyMerchantCredentialMode": merchant_mode, "legacySandboxSimulation": str(values.get("application_settings_state") or "").lower() == "sandbox" and str(values.get("simulate_transactions") or "").lower() == "yes"})
            if merchant_mode:
                names = ["gw_mtn_api_collections_user_id", "gw_mtn_api_collections_user_key", "gw_mtn_api_collections_subscription_key", "gw_mtn_api_disbursements_user_id", "gw_mtn_api_disbursements_user_key", "gw_mtn_api_disbursements_subscription_key", "gw_airtelmoney_api_username", "gw_airtelmoney_api_password"]
                emit({"source": "LEGACY_GLOBAL_NOT_SELECTED", "configuredFieldNames": [name for name in names if str(values.get(name) or "").strip()], "missingFieldNames": [name for name in names if not str(values.get(name) or "").strip()], "tokenObtained": False})
                inspect(values, "LEGACY_GLOBAL_NOT_SELECTED")
            if merchant_mode:
                cursor.execute("SELECT merchant_id, name, setting_value FROM merchant_settings WHERE name LIKE 'gw_mtn_api_%' OR name LIKE 'gw_airtelmoney_%' ORDER BY merchant_id LIMIT 2001")
                rows = cursor.fetchall()
                if len(rows) > 2000:
                    emit({"result": "SCOPE_LIMIT_EXCEEDED"})
                    return
                grouped = {}
                for row in rows:
                    grouped.setdefault(row["merchant_id"], {})[row["name"]] = row["setting_value"]
                if len(grouped) > 10:
                    emit({"result": "MERCHANT_SCOPE_LIMIT_EXCEEDED"})
                    return
                emit({"legacyMerchantConfigurations": len(grouped)})
                for scope in grouped.values():
                    inspect(scope, "LEGACY_MERCHANT")
            else:
                inspect(values, "LEGACY_PLATFORM")
            emit({"result": "LEGACY_READ_ONLY_PREFLIGHT_COMPLETE", "paymentsSubmitted": 0, "databaseWrites": 0})
    finally:
        connection.rollback()
        connection.close()

if __name__ == "__main__":
    try:
        run()
    except Exception as error:
        emit({"result": "LEGACY_PREFLIGHT_COULD_NOT_COMPLETE", "errorClass": type(error).__name__, "paymentsSubmitted": 0})
        sys.exit(1)
