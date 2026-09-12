"""Cito consumer SDK 2.0: the existing CPay v2 RSA contract, not a new protocol."""
from __future__ import annotations

import base64
import hashlib
import re
import uuid
from datetime import datetime, timezone
from urllib.parse import quote_plus

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa


def java_trim(value: str) -> str:
    """Java String.trim(), NOT Python's wider Unicode strip(). Server compatibility."""
    return value.strip("".join(map(chr, range(33))))


def sha256_hex(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8", errors="strict")).hexdigest()


def _scalar(value) -> str:
    if isinstance(value, str):
        value.encode("utf-8", errors="strict")
        return value
    if type(value) is int:
        return str(value)
    raise TypeError("Query values must be strings or integers; use decimal strings for amounts")


def canonical_query(query=None) -> str:
    """Sort decoded names AND repeated values as Java UTF-16, then form-encode UTF-8.

    Accept a mapping with scalar/list values or a sequence of (name, value) pairs.
    Null values are omitted; use an empty string for an explicitly empty value.
    """
    if query is None:
        return ""
    entries = query.items() if hasattr(query, "items") else query
    pairs = []
    for key, value in entries:
        if not isinstance(key, str):
            raise TypeError("Query names must be strings")
        for item in value if isinstance(value, (list, tuple)) else [value]:
            if item is not None:
                pairs.append((_scalar(key), _scalar(item)))
    pairs.sort(key=lambda pair: (pair[0].encode("utf-16-be"), pair[1].encode("utf-16-be")))
    def encode(value):
        return quote_plus(value, safe="*-._", encoding="utf-8", errors="strict").replace("~", "%7E")
    return "&".join(f"{encode(key)}={encode(value)}" for key, value in pairs)


def canonical_string(method: str, path: str, query, timestamp: str, nonce: str, body: str = "") -> str:
    # The released Java verifier trims U+0000..U+0020 at field/body boundaries.
    # Compact JSON is recommended; whitespace inside JSON is always significant.
    return "\n".join((java_trim(method).upper(), java_trim(path), canonical_query(query),
                      java_trim(timestamp), java_trim(nonce), sha256_hex(java_trim(body))))


def header_value(value: str, name: str) -> str:
    if not isinstance(value, str) or not value or len(value) > 256 or re.search(r"[\x00-\x20\x7f]", value):
        raise ValueError(f"{name} must be a nonempty, bounded header value without whitespace")
    return value


def sign_request(merchant_number: str, private_key_pem: str, method: str, path: str,
                 query=None, body: str = "", timestamp: str | None = None,
                 nonce: str | None = None, idempotency_key: str | None = None) -> dict[str, str]:
    merchant_number = header_value(merchant_number, "merchant_number")
    timestamp = header_value(timestamp or datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"), "timestamp")
    nonce = header_value(nonce or str(uuid.uuid4()), "nonce")
    if idempotency_key is not None:
        header_value(idempotency_key, "idempotency_key")
    key = serialization.load_pem_private_key(private_key_pem.encode("utf-8"), password=None)
    if not isinstance(key, rsa.RSAPrivateKey) or key.key_size < 2048:
        raise ValueError("An RSA private key of at least 2048 bits is required")
    canonical = canonical_string(method, path, query, timestamp, nonce, body)
    signature = key.sign(canonical.encode("utf-8"), padding.PKCS1v15(), hashes.SHA256())
    headers = {"X-CPay-Merchant-Number": merchant_number, "X-CPay-Signature-Version": "v2",
               "X-CPay-Timestamp": timestamp, "X-CPay-Nonce": nonce,
               "X-CPay-Signature": base64.b64encode(signature).decode("ascii")}
    # Never silently invent a new business-operation key on each retry.
    if idempotency_key is not None:
        headers["X-CPay-Idempotency-Key"] = idempotency_key
    return headers
