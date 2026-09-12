"""Cito payment SDK 2.0. Explicit environment, caller-owned idempotency, no retries.

Only the native collect/payout wrappers are environment-isolated payment commands.
An arbitrary sandbox header does not turn a legacy endpoint into a sandbox.
"""
from __future__ import annotations

import json
import re
from urllib.parse import urlsplit, quote
import requests
from cpay_signing import sign_request, canonical_query, header_value


class CPayError(Exception):
    def __init__(self, status_code: int, payload, request_id: str | None = None):
        super().__init__(f"Cito returned HTTP {status_code}; inspect the sanitized response and original operation status")
        self.status_code, self.payload, self.request_id = status_code, payload, request_id


class CPayTransportError(Exception):
    """No automatic retry: the server may have accepted the original request."""


def approved_origin(base_url: str) -> str:
    parsed = urlsplit(base_url)
    if (parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password
            or parsed.path not in ("", "/") or parsed.query or parsed.fragment
            or re.search(r"[\x00-\x20\x7f\\]", base_url)):
        raise ValueError("base_url must be the approved HTTPS origin, without path, query or credentials")
    if parsed.hostname.endswith(".invalid") or "YOUR" in base_url or "{{" in base_url:
        raise ValueError("Replace the placeholder with the approved Cito environment origin")
    return base_url.rstrip("/")


class CPayClient:
    def __init__(self, base_url: str, merchant_number: str, private_key_pem: str,
                 *, environment: str, timeout: tuple[float, float] = (5, 30), session=None):
        self.base_url = approved_origin(base_url)
        self.merchant_number = header_value(merchant_number, "merchant_number")
        if environment not in ("SANDBOX", "PRODUCTION"):
            raise ValueError("environment must explicitly be SANDBOX or PRODUCTION")
        if len(timeout) != 2 or any(type(v) not in (int, float) or not 0 < v <= 120 for v in timeout):
            raise ValueError("Connection/read timeouts must be positive and at most 120 seconds")
        if not private_key_pem:
            raise ValueError("private_key_pem is required")
        self.private_key_pem, self.environment, self.timeout = private_key_pem, environment, timeout
        self.session = session or requests.Session()

    def collect(self, request: dict, *, idempotency_key: str):
        return self._payment("collect", request, idempotency_key)

    def payout(self, request: dict, *, idempotency_key: str):
        return self._payment("payout", request, idempotency_key)

    def _payment(self, operation: str, request: dict, key: str):
        header_value(key, "idempotency_key")
        for name in ("reference", "currency", "country", "channel"):
            if not isinstance(request.get(name), str) or not request[name].strip():
                raise ValueError(f"{name} is required")
        if not isinstance(request.get("amount"), str) or not re.fullmatch(r"\d+(?:\.\d{1,4})?", request["amount"]) or not any(c in "123456789" for c in request["amount"]):
            raise ValueError("amount must be a positive decimal string with at most four decimal places")
        return self._post(f"/api/v2/native/payments/{operation}", request, idempotency_key=key)

    def channels(self):
        return self._get("/api/v2/channels", {})

    def webhook_events(self):
        return self._get("/api/v2/webhooks/events", {})

    def identity_capabilities(self):
        return self._get("/api/v2/identity/capabilities", {})

    def status(self, reference: str):
        if not isinstance(reference, str) or not re.fullmatch(r"[A-Za-z0-9_-][A-Za-z0-9._:-]{0,127}", reference) or reference in (".", ".."):
            raise ValueError("Use the original URL-safe reference returned by Cito")
        return self._get("/api/v2/payments/" + quote(reference, safe=""), {})

    def balances(self):
        return self._get("/api/v2/balances", {})

    def statements(self, start_date: str, end_date: str, format: str = "json", limit: int | None = None):
        if format not in ("json", "csv"):
            raise ValueError("format must be json or csv")
        return self._get("/api/v2/statements", {"startDate": start_date, "endDate": end_date, "format": format, "limit": limit})

    def validate_account(self, request: dict):
        self._require_production_only("Account validation")
        return self._post("/api/v2/accounts/validate", request)

    def create_payment_link(self, request: dict, *, idempotency_key: str):
        self._require_production_only("Payment links")
        header_value(idempotency_key, "idempotency_key")
        return self._post("/api/v2/payment-links", request, idempotency_key=idempotency_key)

    def _require_production_only(self, name: str):
        if self.environment != "PRODUCTION":
            raise ValueError(f"{name} has no verified sandbox selection contract; it is not a sandbox quickstart")

    def _payload(self, request: dict):
        if not isinstance(request, dict):
            raise TypeError("request must be a JSON object")
        if "merchantNumber" in request and request["merchantNumber"] != self.merchant_number:
            raise ValueError("Request merchantNumber differs from the configured merchant")
        metadata = request.get("metadata", {})
        if not isinstance(metadata, dict) or ("environment" in metadata and metadata["environment"] != self.environment):
            raise ValueError("Request metadata.environment conflicts with the configured environment")
        return {**request, "merchantNumber": self.merchant_number}

    def _post(self, path: str, request: dict, *, idempotency_key: str | None = None):
        body = json.dumps(self._payload(request), separators=(",", ":"), ensure_ascii=False, allow_nan=False)
        headers = sign_request(self.merchant_number, self.private_key_pem, "POST", path, body=body, idempotency_key=idempotency_key)
        headers.update({"Content-Type": "application/json", "X-CPay-Environment": self.environment})
        return self._send("POST", path, headers=headers, data=body.encode("utf-8"))

    def _get(self, path: str, query: dict):
        if "merchantNumber" in query and query["merchantNumber"] != self.merchant_number:
            raise ValueError("Query merchant mismatch")
        query = {**query, "merchantNumber": self.merchant_number}
        headers = sign_request(self.merchant_number, self.private_key_pem, "GET", path, query=query)
        headers["X-CPay-Environment"] = self.environment
        return self._send("GET", path + "?" + canonical_query(query), headers=headers)

    def _send(self, method: str, path: str, **kwargs):
        try:
            response = self.session.request(method, self.base_url + path, timeout=self.timeout,
                                            allow_redirects=False, **kwargs)
        except requests.RequestException:
            raise CPayTransportError("Transport failed or timed out; outcome may be unknown. Query the original reference before any retry; retain its idempotency key.") from None
        text = response.text
        media_type = response.headers.get("Content-Type", "").lower()
        if "json" in media_type:
            try:
                payload = response.json()
            except ValueError:
                raise CPayTransportError("Cito returned malformed JSON; do not infer a completed transaction") from None
        else:
            payload = text if text else None
        if not 200 <= response.status_code < 300:
            raise CPayError(response.status_code, payload, response.headers.get("X-Request-Id"))
        return payload
