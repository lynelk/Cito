"""Read-only diagnostics for saved, non-selected production configuration.
Never modify the selected environment/source and never submit a payment.
"""
import os
import urllib.parse
import pymysql
from provider_no_money import check, emit, callback_check


def run():
    parsed = urllib.parse.urlsplit(os.environ["DB_URL"].removeprefix("jdbc:"))
    connection = pymysql.connect(host=parsed.hostname, port=parsed.port or 3306, user=os.environ["DB_USERNAME"], password=os.environ["DB_PASSWORD"], database=parsed.path.lstrip("/"), connect_timeout=10, read_timeout=15, write_timeout=15, autocommit=False, cursorclass=pymysql.cursors.DictCursor)
    try:
        with connection.cursor() as cursor:
            cursor.execute("START TRANSACTION READ ONLY")
            cursor.execute("SELECT name, setting_value FROM settings WHERE name LIKE 'gw_mtn_api_%' OR name IN ('app_setting_app_url','application_base_url')")
            values = {row["name"]: row["setting_value"] for row in cursor.fetchall()}
            for key in ["gw_mtn_api_url", "gw_mtn_api_url_sandbox"]:
                url = urllib.parse.urlsplit(str(values.get(key) or ""))
                host = url.hostname or ""
                emit({"setting": key, "https": url.scheme == "https", "knownHost": host in {"proxy.momoapi.mtn.com", "sandbox.momodeveloper.mtn.com"}, "legacyAzureHost": host.endswith(".azure-api.net"), "basePathEmpty": url.path in {"", "/"}, "hasUserInfo": bool(url.username or url.password), "hasQuery": bool(url.query), "hasFragment": bool(url.fragment)})
            credentials = {"baseUrl": values.get("gw_mtn_api_url"), "targetEnvironment": "mtnuganda", "baseCurrency": values.get("gw_mtn_api_base_currency"), "callbackUrl": values.get("gw_mtn_api_callback_url"), "callbackHost": values.get("gw_mtn_api_callback_host")}
            for product, key in [("collection", "collections"), ("disbursement", "disbursements")]:
                for target, ending in [("ApiUser", "user_id"), ("ApiKey", "user_key"), ("SubscriptionKey", "subscription_key")]:
                    credentials[product + target] = values.get("gw_mtn_api_" + key + "_" + ending)
            check("mtn_momo", "PRODUCTION", "LEGACY_GLOBAL_SAVED_PRODUCTION_NOT_SELECTED", "CONFIGURED_LEGACY", credentials)
            # This proves only HTTPS certificate reachability, not operator registration.
            tls = callback_check({"callbackUrl": "https://cito.coresynergi.es", "callbackHost": "cito.coresynergi.es"})
            emit({"publicCitoTlsVerified": tls.get("callbackTlsVerified", False), "operatorRegistrationVerified": False, "result": "CALLBACK_HOST_TLS_ONLY"})
            emit({"result": "SAVED_PRODUCTION_PREFLIGHT_COMPLETE", "paymentsSubmitted": 0, "databaseWrites": 0, "credentialSourceChanged": False})
    finally:
        connection.rollback()
        connection.close()

if __name__ == "__main__":
    try:
        run()
    except Exception as error:
        emit({"result": "SAVED_PRODUCTION_PREFLIGHT_COULD_NOT_COMPLETE", "errorClass": type(error).__name__, "paymentsSubmitted": 0})
        raise SystemExit(1)
