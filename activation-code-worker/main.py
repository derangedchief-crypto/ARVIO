"""Activation-code resolver for the Extreme IPTV panel.

The Android app never receives database or SSH credentials. Netlify signs each
request with a server-only HMAC, this worker resolves the supplied six-digit
activation code over an SSH tunnel, and only an active unexpired customer line
may be returned. Do not expose this service directly to clients.
"""

import logging
import threading
import time
import hashlib
import json
import re
from collections import defaultdict, deque
from contextlib import contextmanager

import mysql.connector
import mysql.connector.pooling
from fastapi import FastAPI, HTTPException, Request
from pydantic import BaseModel, Field, ConfigDict, ValidationError
from sshtunnel import SSHTunnelForwarder
from starlette.concurrency import run_in_threadpool

from config import settings
from management_enrollment import AttemptLimiter, client_ip, configured, credentials_match, issue_proof, bridge_signature
import hmac


logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    handlers=[logging.FileHandler(settings.log_file), logging.StreamHandler()],
)
log = logging.getLogger("iptv-activation-worker")

app = FastAPI(title="Extreme IPTV Activation Worker")

_tunnel: SSHTunnelForwarder | None = None
_pool: mysql.connector.pooling.MySQLConnectionPool | None = None
_tunnel_lock = threading.Lock()


_attempts: dict[str, deque[float]] = defaultdict(deque)
_attempt_lock = threading.Lock()
_management_ips = AttemptLimiter(120, 3600)
_management_accounts = AttemptLimiter(20, 600)
_management_lookups = AttemptLimiter(60, 60)


def allow_attempt(client_ip: str) -> bool:
    """Bound online guessing for the public, six-digit activation endpoint."""
    now = time.time()
    with _attempt_lock:
        attempts = _attempts[client_ip]
        while attempts and attempts[0] <= now - 600:
            attempts.popleft()
        if len(attempts) >= 6:
            return False
        attempts.append(now)
        return True


def ensure_tunnel_and_pool(force: bool = False) -> None:
    global _tunnel, _pool
    with _tunnel_lock:
        if _tunnel is not None and _tunnel.is_active and not force:
            return
        if _tunnel is not None:
            try:
                _tunnel.stop()
            except Exception:
                pass
        _tunnel = SSHTunnelForwarder(
            (settings.ssh_host, settings.ssh_port),
            ssh_username=settings.ssh_username,
            ssh_password=settings.ssh_password,
            remote_bind_address=(settings.db_host, settings.db_port),
            local_bind_address=("127.0.0.1",),
        )
        _tunnel.start()
        _pool = mysql.connector.pooling.MySQLConnectionPool(
            pool_name="activation_pool",
            pool_size=5,
            host="127.0.0.1",
            port=_tunnel.local_bind_port,
            database=settings.db_name,
            user=settings.db_username,
            password=settings.db_password,
            connection_timeout=15,
            use_pure=True,
            autocommit=True,
        )
        log.info("SSH tunnel and MariaDB pool established")


@app.on_event("startup")
def start_worker() -> None:
    ensure_tunnel_and_pool()


@app.on_event("shutdown")
def stop_worker() -> None:
    if _tunnel is not None:
        try:
            _tunnel.stop()
        except Exception:
            pass


@contextmanager
def panel_cursor():
    global _pool
    ensure_tunnel_and_pool()
    try:
        connection = _pool.get_connection()
        connection.ping(reconnect=True, attempts=1, delay=0)
    except Exception:
        ensure_tunnel_and_pool(force=True)
        connection = _pool.get_connection()
        connection.ping(reconnect=True, attempts=1, delay=0)
    try:
        cursor = connection.cursor(dictionary=True)
        cursor.execute("SET SESSION max_statement_time=10")
        yield cursor
    finally:
        cursor.close()
        connection.close()


class ActivationRequest(BaseModel):
    activation_code: str = Field(..., pattern=r"^\d{6}$")


class ActivationResponse(BaseModel):
    ok: bool
    username: str | None = None
    password: str | None = None
    error: str | None = None


class ManagementProofRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    username: str = Field(min_length=1, max_length=256)
    password: str = Field(min_length=1, max_length=512)
    installation_id: str = Field(pattern=r"^[a-f0-9]{32}$")
    key_fingerprint: str = Field(pattern=r"^[a-f0-9]{64}$")
    app_package: str = Field(pattern=r"^(?:com\.arvio\.tv(?:\.beta|\.test)?|com\.extreme\.brand\.b[a-f0-9]{32}(?:\.test)?)$")
    challenge_hash: str = Field(pattern=r"^[a-f0-9]{64}$")


@app.post('/management/reseller-lookup')
async def management_reseller_lookup(request: Request) -> dict:
    secret = settings.management_enrollment_secret
    if not configured(secret, settings.management_control_origin):
        raise HTTPException(status_code=503, detail='Management bridge is not configured.')
    raw = bytearray()
    async for chunk in request.stream():
        raw.extend(chunk)
        if len(raw) > 2048:
            raise HTTPException(status_code=413, detail='Request is too large.')
    timestamp = request.headers.get('x-management-timestamp', '')
    nonce = request.headers.get('x-management-nonce', '')
    signature = request.headers.get('x-management-signature', '')
    if (not re.fullmatch(r'[0-9]{10,12}', timestamp) or abs(int(timestamp) - int(time.time())) > 60
        or not re.fullmatch(r'[a-f0-9]{32}', nonce) or not re.fullmatch(r'[a-f0-9]{64}', signature)
        or not hmac.compare_digest(signature, bridge_signature(secret, 'reseller-lookup-v1', timestamp, nonce, raw))):
        raise HTTPException(status_code=403, detail='Invalid bridge authentication.')
    if not _management_lookups.allow('bridge'):
        raise HTTPException(status_code=429, detail='Too many lookups.')
    try:
        body = json.loads(raw)
        username = body['username']
        if set(body) != {'username'} or not isinstance(username, str) or not 1 <= len(username) <= 150:
            raise ValueError()
    except (ValueError, TypeError, KeyError):
        raise HTTPException(status_code=422, detail='Invalid reseller lookup.') from None
    def lookup():
        with panel_cursor() as cursor:
            cursor.execute('SELECT id, username, status FROM users WHERE username = %s LIMIT 2', (username,))
            return cursor.fetchall()
    try:
        rows = await run_in_threadpool(lookup)
    except Exception:
        raise HTTPException(status_code=503, detail='Panel lookup is unavailable.') from None
    if len(rows) != 1 or rows[0]['username'] != username or str(rows[0]['status']) != '1':
        raise HTTPException(status_code=404, detail='No unique active reseller with that exact username.')
    reseller = {'id': str(rows[0]['id']), 'username': rows[0]['username']}
    payload = json.dumps(reseller, separators=(',', ':'), ensure_ascii=False)
    return {'payload': payload, 'signature': bridge_signature(secret, 'reseller-result-v1', timestamp, nonce, payload.encode())}


@app.get("/management/health")
def management_health() -> dict:
    enabled = configured(settings.management_enrollment_secret, settings.management_control_origin)
    return {"enabled": enabled, "schema_version": 1, "ownership_schema_version": 1}


@app.post("/management/enrollment-proof")
async def management_proof(request: Request) -> dict:
    if not configured(settings.management_enrollment_secret, settings.management_control_origin):
        raise HTTPException(status_code=503, detail="Management enrollment is not configured yet.")
    raw = bytearray()
    async for chunk in request.stream():
        raw.extend(chunk)
        if len(raw) > 8192:
            raise HTTPException(status_code=413, detail="Request is too large.")
    try:
        body = ManagementProofRequest.model_validate_json(raw)
    except (ValidationError, ValueError):
        # Pydantic's default errors can echo input values, including passwords.
        raise HTTPException(status_code=422, detail="Invalid management enrollment request.") from None
    peer = request.client.host if request.client else "unknown"
    ip = client_ip(peer, request.headers.get("x-real-ip"))
    account_bucket = hashlib.sha256((ip + "\n" + body.username.strip().casefold()).encode()).hexdigest()
    if not _management_ips.allow(ip) or not _management_accounts.allow(account_bucket):
        raise HTTPException(status_code=429, detail="Too many enrollment attempts. Try again later.")
    def lookup_account():
        with panel_cursor() as cursor:
            # Ownership schema verified against the uploaded Overcast auth worker:
            # customers.member_id -> users.id. Read-only; no panel schema changes.
            cursor.execute(
                "SELECT cu.id, cu.username, cu.password, cu.member_id, u.username AS reseller_username, u.status AS reseller_status "
                "FROM customers cu LEFT JOIN users u ON u.id = cu.member_id WHERE cu.username = %s "
                "AND cu.enabled = 1 AND cu.activated = 1 "
                "AND (cu.exp_date IS NULL OR cu.exp_date = 0 OR cu.exp_date > %s) LIMIT 2",
                (body.username.strip(), int(time.time())),
            )
            return cursor.fetchall()
    try:
        rows = await run_in_threadpool(lookup_account)
    except Exception:
        log.error("Management account verification unavailable")
        raise HTTPException(status_code=503, detail="Account verification is temporarily unavailable.") from None
    if not credentials_match(rows, body.password):
        raise HTTPException(status_code=403, detail="Account is unavailable or credentials are invalid.")
    try:
        row = rows[0]
        ownership = None
        if row.get('reseller_username') and str(row.get('reseller_status')) == '1':
            ownership = {'customer_id': str(row['id']), 'reseller_id': str(row['member_id']), 'reseller_username': row['reseller_username']}
        proof = issue_proof(settings.management_enrollment_secret, settings.management_control_origin,
            str(rows[0]["username"]), body.installation_id, body.key_fingerprint,
            body.app_package, body.challenge_hash, ownership=ownership)
    except ValueError:
        raise HTTPException(status_code=422, detail="Invalid management enrollment request.") from None
    return {"ok": True, "schema_version": 1, "proof": proof}


@app.get("/health")
def health() -> dict:
    return {"status": "ok"}


@app.post("/activate", response_model=ActivationResponse)
async def activate(
    request: Request,
):
    client_ip = request.headers.get("x-real-ip") or request.client.host
    if not allow_attempt(client_ip):
        raise HTTPException(status_code=429, detail="Too many attempts. Try again later.")
    body = ActivationRequest.model_validate_json(await request.body())
    try:
        with panel_cursor() as cursor:
            # The code column is expected to be unique. Detect bad/duplicate data
            # explicitly rather than silently authenticating an arbitrary account.
            cursor.execute(
                "SELECT username, password FROM customers "
                "WHERE activation_code = %s "
                "AND enabled = 1 AND activated = 1 "
                # Unlimited panel lines have no expiry (`NULL` or `0`).
                "AND (exp_date IS NULL OR exp_date = 0 OR exp_date > %s) "
                "LIMIT 2",
                (body.activation_code, int(time.time())),
            )
            matches = cursor.fetchall()
    except Exception:
        # Do not disclose DB or panel details to an untrusted caller.
        log.exception("Activation lookup failed")
        return ActivationResponse(ok=False, error="Activation is temporarily unavailable.")

    if len(matches) != 1:
        # Same response for an unknown, expired, disabled, or duplicate code.
        log.warning("Activation rejected for client=%s", client_ip)
        return ActivationResponse(ok=False, error="Invalid or inactive activation code.")

    row = matches[0]
    log.info("Activation succeeded for client=%s", client_ip)
    return ActivationResponse(ok=True, username=str(row["username"]), password=str(row["password"]))
