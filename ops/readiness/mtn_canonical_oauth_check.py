"""Token-only check of saved credentials against canonical MTN API endpoints.
The configured portal URLs are never sent credentials. All endpoint overrides exist
only in this diagnostic process; source, approval, environment and balances are unchanged.
"""
import os
import urllib.parse
import pymysql
from provider_no_money import check, emit


def run():
    parsed = urllib.parse.urlsplit(os.environ["DB_URL"].removeprefix("jdbc:"))
    connection = pymysql.connect(host=parsed.hostname, port=parsed.port or 3306, user=os.environ["DB_USERNAME"], password=os.environ["DB_PASSWORD"], database=parsed.path.lstrip("/"), connect_timeout=10, read_timeout=15, write_timeout=15, autocommit=False, cursorclass=pymysql.cursors.DictCursor)
    try:
        with connection.cursor() as cursor:
            cursor.execute("START TRANSACTION READ ONLY")
            cursor.execute("SELECT name, setting_value FROM settings WHERE name LIKE 'gw_mtn_api_%'")
            values = {row["name"]: row["setting_value"] for row in cursor.fetchall()}
            for environment, suffix, endpoint in [("PRODUCTION", "", "https://proxy.momoapi.mtn.com"), ("SANDBOX", "_sandbox", "https://sandbox.momodeveloper.mtn.com")]:
                credentials = {"baseUrl": endpoint}
                for product, key in [("collection", "collections"), ("disbursement", "disbursements")]:
                    for target, ending in [("ApiUser", "user_id"), ("ApiKey", "user_key"), ("SubscriptionKey", "subscription_key")]:
                        credentials[product + target] = values.get("gw_mtn_api_" + key + "_" + ending + suffix)
                emit({"environment": environment, "canonicalEndpointOverride": True, "storedConfigurationChanged": False, "credentialSourceSelected": False})
                check("mtn_momo", environment, "SAVED_GLOBAL_CANONICAL_DIAGNOSTIC_NOT_SELECTED", "CONFIGURED_LEGACY", credentials)
            emit({"result": "CANONICAL_OAUTH_PREFLIGHT_COMPLETE", "paymentsSubmitted": 0, "databaseWrites": 0, "storedConfigurationChanged": False})
    finally:
        connection.rollback()
        connection.close()

if __name__ == "__main__":
    try:
        run()
    except Exception as error:
        emit({"result": "CANONICAL_OAUTH_PREFLIGHT_FAILED", "errorClass": type(error).__name__, "paymentsSubmitted": 0})
        raise SystemExit(1)
