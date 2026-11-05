"""Signed, read-only reseller notes for already-bound IPTV lines."""
import hashlib
import hmac
import json
import re
import time
from fastapi import HTTPException, Request
from starlette.concurrency import run_in_threadpool
from management_enrollment import AttemptLimiter, bridge_signature, configured


def install_line_notes_routes(app, settings, panel_cursor):
    limiter = AttemptLimiter(600, 60)

    @app.get('/management/line-notes-health')
    def notes_health():
        return {'schema_version': 1, 'source': 'customers.reseller_notes'}

    @app.post('/management/line-notes')
    async def line_notes(request: Request):
        secret = settings.management_enrollment_secret
        if not configured(secret, settings.management_control_origin):
            raise HTTPException(503, 'Management bridge is not configured.')
        raw = bytearray()
        async for chunk in request.stream():
            raw.extend(chunk)
            if len(raw) > 16384: raise HTTPException(413, 'Request is too large.')
        timestamp = request.headers.get('x-management-timestamp', '')
        nonce = request.headers.get('x-management-nonce', '')
        signature = request.headers.get('x-management-signature', '')
        if (not re.fullmatch(r'[0-9]{10,12}', timestamp) or abs(int(timestamp)-int(time.time())) > 60
                or not re.fullmatch(r'[a-f0-9]{32}', nonce) or not re.fullmatch(r'[a-f0-9]{64}', signature)
                or not hmac.compare_digest(signature, bridge_signature(secret, 'line-notes-request-v1', timestamp, nonce, raw))):
            raise HTTPException(403, 'Invalid bridge authentication.')
        if not limiter.allow('line-notes'): raise HTTPException(429, 'Too many lookups.')
        try:
            body = json.loads(raw)
            lines = body['lines']
            if set(body) != {'lines'} or not isinstance(lines, list) or not 1 <= len(lines) <= 25: raise ValueError()
            seen = set()
            for line in lines:
                if not isinstance(line, dict) or set(line) != {'id', 'account_ref', 'reseller_id'}: raise ValueError()
                for key in ['id', 'reseller_id']:
                    if not isinstance(line[key], str) or not re.fullmatch(r'[1-9][0-9]{0,19}', line[key]): raise ValueError()
                if not isinstance(line['account_ref'], str) or not re.fullmatch(r'[a-f0-9]{64}', line['account_ref']): raise ValueError()
                if line['id'] in seen: raise ValueError()
                seen.add(line['id'])
        except (ValueError, TypeError, KeyError):
            raise HTTPException(422, 'Invalid line notes lookup.') from None

        def lookup():
            with panel_cursor() as cursor:
                placeholders = ','.join(['%s'] * len(lines))
                cursor.execute('SELECT id,username,member_id,reseller_notes FROM customers WHERE id IN ('+placeholders+')', tuple(line['id'] for line in lines))
                return cursor.fetchall()
        try:
            rows = {str(row['id']): row for row in await run_in_threadpool(lookup)}
            result = []
            for line in lines:
                row = rows.get(line['id'])
                valid = False
                if row and str(row['member_id']) == line['reseller_id']:
                    actual = hmac.new(bytes.fromhex(secret), ('account-v1\n'+row['username']).encode(), hashlib.sha256).hexdigest()
                    valid = hmac.compare_digest(actual, line['account_ref'])
                notes = (row.get('reseller_notes') or '') if valid else None
                if valid and (not isinstance(notes, str) or len(notes.encode('utf-8')) > 65535):
                    valid, notes = False, None
                result.append({**line, 'state': 'current' if valid else 'unavailable', 'notes': notes})
            payload = json.dumps({'lines': result}, separators=(',', ':'), ensure_ascii=False)
        except Exception:
            raise HTTPException(503, 'Panel line notes are temporarily unavailable.') from None
        return {'payload': payload, 'signature': bridge_signature(secret, 'line-notes-result-v1', timestamp, nonce, payload.encode())}
