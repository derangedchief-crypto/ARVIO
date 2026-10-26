"""Read-only, authenticated line status for the control center. No panel writes."""
import hashlib
import hmac
import json
import re
import time
from fastapi import HTTPException, Request
from starlette.concurrency import run_in_threadpool
from management_enrollment import AttemptLimiter, bridge_signature, configured, credentials_match, client_ip


def line_result(row, customer_id, account_ref, secret, now):
    valid = False
    reseller = None
    seconds = 0
    identity_valid = False
    if row:
        actual = hmac.new(bytes.fromhex(secret), ("account-v1\n" + row['username']).encode(), hashlib.sha256).hexdigest()
        if hmac.compare_digest(actual, account_ref):
            identity_valid = True
            expiry = row.get('exp_date')
            expiry = int(expiry) if expiry is not None else 0
            valid = str(row.get('enabled')) == '1' and str(row.get('activated')) == '1' and (expiry == 0 or expiry > now)
            reseller = str(row['member_id']) if row.get('member_id') is not None else None
            seconds = min(900, expiry - now) if valid and expiry else (900 if valid else 0)
    return {'id': customer_id, 'account_ref': account_ref, 'reseller_id': reseller, 'identity_valid': identity_valid, 'active': bool(valid), 'valid_for_seconds': max(0, seconds)}


def install_primary_status_routes(app, settings, panel_cursor):
    limiter = AttemptLimiter(12000, 60)
    account_limiter = AttemptLimiter(240, 600)

    @app.get('/management/primary-line-health')
    def primary_health():
        return {'schema_version': 1, 'credential_validation_version': 1}

    @app.post('/management/primary-verify')
    async def primary_verify(request: Request):
        raw = bytearray()
        async for chunk in request.stream():
            raw.extend(chunk)
            if len(raw) > 4096: raise HTTPException(413, 'Request is too large.')
        try:
            body = json.loads(raw)
            username, password = body['username'], body['password']
            if (set(body) != {'username', 'password'} or not isinstance(username, str) or not 1 <= len(username) <= 256
                    or not isinstance(password, str) or not 1 <= len(password) <= 512): raise ValueError()
        except (ValueError, TypeError, KeyError):
            raise HTTPException(422, 'Invalid primary verification.') from None
        peer = request.client.host if request.client else 'unknown'
        ip = client_ip(peer, request.headers.get('x-real-ip'))
        bucket = hashlib.sha256((ip+'\n'+username.strip().casefold()).encode()).hexdigest()
        if not account_limiter.allow(bucket) or not limiter.allow('credential-bridge'):
            raise HTTPException(429, 'Too many primary checks.')
        def lookup_credentials():
            with panel_cursor() as cursor:
                cursor.execute('SELECT username,password,enabled,activated,exp_date FROM customers WHERE username=%s LIMIT 2', (username.strip(),))
                return cursor.fetchall()
        try:
            rows = await run_in_threadpool(lookup_credentials)
            if not credentials_match(rows, password): return {'schema_version': 1, 'active': False, 'valid_for_seconds': 0}
            row = rows[0]; now = int(time.time()); expiry = int(row['exp_date']) if row['exp_date'] is not None else 0
            active = str(row['enabled']) == '1' and str(row['activated']) == '1' and (expiry == 0 or expiry > now)
            seconds = min(900, expiry-now) if active and expiry else (900 if active else 0)
            return {'schema_version': 1, 'active': bool(active), 'valid_for_seconds': max(0,seconds)}
        except Exception:
            raise HTTPException(503, 'Primary verification is temporarily unavailable.') from None

    @app.post('/management/primary-line-status')
    async def primary_status(request: Request):
        secret = settings.management_enrollment_secret
        if not configured(secret, settings.management_control_origin):
            raise HTTPException(503, 'Management bridge is not configured.')
        raw = bytearray()
        async for chunk in request.stream():
            raw.extend(chunk)
            if len(raw) > 2048:
                raise HTTPException(413, 'Request is too large.')
        timestamp = request.headers.get('x-management-timestamp', '')
        nonce = request.headers.get('x-management-nonce', '')
        signature = request.headers.get('x-management-signature', '')
        if (not re.fullmatch(r'[0-9]{10,12}', timestamp) or abs(int(timestamp) - int(time.time())) > 60
                or not re.fullmatch(r'[a-f0-9]{32}', nonce) or not re.fullmatch(r'[a-f0-9]{64}', signature)
                or not hmac.compare_digest(signature, bridge_signature(secret, 'primary-line-request-v1', timestamp, nonce, raw))):
            raise HTTPException(403, 'Invalid bridge authentication.')
        if not limiter.allow('primary-bridge'):
            raise HTTPException(429, 'Too many checks.')
        try:
            body = json.loads(raw)
            customer_id, ref = body['customer_id'], body['account_ref']
            if (set(body) != {'customer_id', 'account_ref'} or not isinstance(customer_id, str)
                    or not re.fullmatch(r'[1-9][0-9]{0,19}', customer_id) or not isinstance(ref, str)
                    or not re.fullmatch(r'[a-f0-9]{64}', ref)):
                raise ValueError()
        except (ValueError, TypeError, KeyError):
            raise HTTPException(422, 'Invalid primary lookup.') from None

        def lookup():
            with panel_cursor() as cursor:
                cursor.execute('SELECT username, member_id, enabled, activated, exp_date FROM customers WHERE id=%s LIMIT 1', (customer_id,))
                return cursor.fetchone()
        try:
            row = await run_in_threadpool(lookup)
            result = line_result(row, customer_id, ref, secret, int(time.time()))
        except Exception:
            raise HTTPException(503, 'Primary validation is unavailable.') from None
        payload = json.dumps(result, separators=(',', ':'), ensure_ascii=False)
        return {'payload': payload, 'signature': bridge_signature(secret, 'primary-line-result-v1', timestamp, nonce, payload.encode())}
