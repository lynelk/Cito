"""Consumer verification for the two existing Cito callback contracts.

This validates authenticity and scope, not settlement. Callers MUST persist a
unique durable inbox record before acknowledging and before business effects.
There is deliberately no process-local replay cache or fallback signature scheme.
"""
from __future__ import annotations
import base64,hashlib,hmac,json,re,time
from collections.abc import Iterable,Mapping
from dataclasses import dataclass

class CallbackRejected(ValueError):pass

@dataclass(frozen=True)
class VerifiedCallback:
    scheme: str
    tenant: str
    event_identity: str
    reference: str
    payload: dict
    body_sha256: str


def _headers(headers: Mapping[str,str] | Iterable[tuple[str,str]]) -> dict[str,str]:
    normalized={}
    for name,value in headers.items() if isinstance(headers,Mapping) else headers:
        key=name.lower()
        if key.startswith('x-cpay-'):
            if key in normalized or not isinstance(value,str) or len(value)>1024 or re.search(r'[\x00-\x1f\x7f]',value):
                raise CallbackRejected('Malformed or duplicate callback security header')
            normalized[key]=value
    return normalized


def _inputs(body: bytes,secret: str,max_body_bytes: int) -> None:
    if not isinstance(body,bytes) or not 0<len(body)<=max_body_bytes or not isinstance(secret,str) or not secret:
        raise CallbackRejected('Body limit or configured receiver secret is invalid')


def _object(body: bytes) -> dict:
    def unique(pairs):
        result={}
        for key,value in pairs:
            if key in result:raise CallbackRejected('Duplicate callback JSON field')
            result[key]=value
        return result
    try:payload=json.loads(body.decode('utf-8'),object_pairs_hook=unique,parse_constant=lambda _: (_ for _ in ()).throw(CallbackRejected('Nonfinite JSON constant')))
    except (UnicodeError,ValueError):raise CallbackRejected('Callback JSON is invalid') from None
    if not isinstance(payload,dict):raise CallbackRejected('Callback must be a JSON object')
    return payload


def verify_task_callback(body: bytes,headers,secret: str,*,expected_merchant_id: str,
                         now: int | None=None,max_age_seconds: int=300,future_skew_seconds: int=30,
                         max_body_bytes: int=1048576) -> VerifiedCallback:
    """Verify callback-v1 HMAC over its six exact fields, then decode JSON.

    Fresh nonce/timestamp do not imply a new business event. Deduplicate durably
    by (receiver tenant, callback task ID), not by the per-attempt nonce alone.
    """
    _inputs(body,secret,max_body_bytes);values=_headers(headers)
    try:
        if values['x-cpay-signature-version']!='callback-v1':raise CallbackRejected('Unexpected callback scheme')
        task=values['x-cpay-callback-task-id'];merchant=values['x-cpay-merchant-id'];reference=values['x-cpay-reference']
        timestamp=values['x-cpay-timestamp'];nonce=values['x-cpay-nonce']
        if not re.fullmatch(r'[1-9]\d{0,18}',task) or merchant!=expected_merchant_id or not re.fullmatch(r'[1-9]\d{0,18}',merchant) or not nonce:
            raise CallbackRejected('Callback tenant or delivery identity is invalid')
        if not re.fullmatch(r'\d{1,12}',timestamp) or max_age_seconds<0 or future_skew_seconds<0:raise CallbackRejected('Callback time policy is invalid')
        observed=int(time.time()) if now is None else now
        age=observed-int(timestamp)
        if not -future_skew_seconds<=age<=max_age_seconds:raise CallbackRejected('Callback is stale or from the future')
        canonical=('\n'.join((task,merchant,reference,timestamp,nonce))+'\n').encode('utf-8')+body
        received=base64.b64decode(values['x-cpay-signature'],validate=True)
        expected=hmac.new(secret.encode('utf-8'),canonical,hashlib.sha256).digest()
        if not hmac.compare_digest(received,expected):raise CallbackRejected('Invalid callback signature')
    except (KeyError,ValueError):raise CallbackRejected('Callback authentication or scope failed') from None
    return VerifiedCallback('callback-v1',merchant,task,reference,_object(body),hashlib.sha256(body).hexdigest())


def verify_merchant_event(body: bytes,headers,secret: str,*,expected_merchant_number: str,
                          expected_event_type: str,max_body_bytes: int=1048576) -> VerifiedCallback:
    """Verify the existing merchant-event digest; never treat unsigned headers as event authority.

    This compatibility scheme has no signed delivery timestamp/nonce. Enforce
    durable eventId uniqueness and business correlation; do not infer freshness.
    Only event payloads containing merchantNumber/eventType/reference are supported
    by this example. Other payload types need their own explicit schema/scope adapter.
    """
    _inputs(body,secret,max_body_bytes);values=_headers(headers)
    if 'x-cpay-signature-version' in values:raise CallbackRejected('Unexpected callback scheme')
    signature=values.get('x-cpay-signature','')
    if not re.fullmatch(r'[0-9a-f]{64}',signature) or not hmac.compare_digest(signature,hashlib.sha256(body+b'.'+secret.encode('utf-8')).hexdigest()):
        raise CallbackRejected('Invalid merchant event signature')
    payload=_object(body)
    if payload.get('merchantNumber')!=expected_merchant_number or payload.get('eventType')!=expected_event_type:
        raise CallbackRejected('Event body tenant/type differs from the configured subscription')
    identity=payload.get('eventId');reference=payload.get('reference')
    if not isinstance(identity,str) or not identity or not isinstance(reference,str) or not reference:
        raise CallbackRejected('Event identity/reference is missing')
    return VerifiedCallback('merchant-event-compat',expected_merchant_number,identity,reference,payload,hashlib.sha256(body).hexdigest())
