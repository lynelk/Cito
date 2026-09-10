"""Read only endpoint identity; do not send credentials or make provider requests."""
import os
import urllib.parse
import pymysql
from provider_no_money import emit


def run():
    parsed = urllib.parse.urlsplit(os.environ["DB_URL"].removeprefix("jdbc:"))
    connection = pymysql.connect(host=parsed.hostname, port=parsed.port or 3306, user=os.environ["DB_USERNAME"], password=os.environ["DB_PASSWORD"], database=parsed.path.lstrip("/"), connect_timeout=10, read_timeout=15, write_timeout=15, autocommit=False, cursorclass=pymysql.cursors.DictCursor)
    try:
        with connection.cursor() as cursor:
            cursor.execute("START TRANSACTION READ ONLY")
            cursor.execute("SELECT name, setting_value FROM settings WHERE name IN ('gw_mtn_api_url','gw_mtn_api_url_sandbox','gw_mtn_api_env')")
            for row in cursor.fetchall():
                if row["name"] == "gw_mtn_api_env":
                    value = str(row["setting_value"] or "").strip().lower()
                    emit({"setting": row["name"], "targetEnvironment": value if value in {"mtnuganda", "sandbox"} else "OTHER_VALUE"})
                    continue
                url = urllib.parse.urlsplit(str(row["setting_value"] or ""))
                host = (url.hostname or "").lower()
                public_provider_domain = host == "mtn.com" or host.endswith(".mtn.com") or host == "mtn.co.ug" or host.endswith(".mtn.co.ug")
                emit({"setting": row["name"], "hostname": host if public_provider_domain else "OTHER_HOST", "isKnownPublicMtnDomain": public_provider_domain, "pathIsRoot": url.path in {"", "/"}, "containsTokenPath": "/token" in url.path, "containsApiVersion": "/v1_0" in url.path, "containsCollectionSegment": "/collection" in url.path, "https": url.scheme == "https"})
            emit({"result": "ENDPOINT_IDENTITY_READ_COMPLETE", "paymentsSubmitted": 0, "databaseWrites": 0})
    finally:
        connection.rollback()
        connection.close()

if __name__ == "__main__":
    try:
        run()
    except Exception as error:
        emit({"result": "ENDPOINT_IDENTITY_READ_FAILED", "errorClass": type(error).__name__})
        raise SystemExit(1)
