"""Activation-code resolver for the Extreme IPTV panel.

The Android app never receives database or SSH credentials. Netlify signs each
request with a server-only HMAC, this worker resolves the supplied six-digit
activation code over an SSH tunnel, and only an active unexpired customer line
may be returned. Do not expose this service directly to clients.
"""

import hashlib
import hmac
import logging
import threading
import time
from contextlib import contextmanager

import mysql.connector
import mysql.connector.pooling
from fastapi import FastAPI, Header, HTTPException, Request
from pydantic import BaseModel, Field
from sshtunnel import SSHTunnelForwarder

from config import settings


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


def verify_signature(raw_body: bytes, signature: str | None, timestamp: str | None) -> bool:
    """Accept HMAC-SHA256(timestamp + '.' + raw JSON), max five minutes old."""
    if not signature or not timestamp:
        return False
    try:
        issued_at = int(timestamp)
    except ValueError:
        return False
    if abs(time.time() - issued_at) > 300:
        return False
    expected = hmac.new(
        settings.shared_secret.encode(),
        f"{timestamp}.".encode() + raw_body,
        hashlib.sha256,
    ).hexdigest()
    return hmac.compare_digest(expected, signature)


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
    client_id: str = Field(..., min_length=32, max_length=64, pattern=r"^[a-f0-9]+$")


class ActivationResponse(BaseModel):
    ok: bool
    username: str | None = None
    password: str | None = None
    error: str | None = None


@app.get("/health")
def health() -> dict:
    return {"status": "ok"}


@app.post("/activate", response_model=ActivationResponse)
async def activate(
    request: Request,
    x_activation_signature: str | None = Header(None),
    x_activation_timestamp: str | None = Header(None),
):
    raw_body = await request.body()
    if not verify_signature(raw_body, x_activation_signature, x_activation_timestamp):
        raise HTTPException(status_code=401, detail="Unauthorized")

    body = ActivationRequest.model_validate_json(raw_body)
    try:
        with panel_cursor() as cursor:
            # The code column is expected to be unique. Detect bad/duplicate data
            # explicitly rather than silently authenticating an arbitrary account.
            cursor.execute(
                "SELECT username, password FROM customers "
                "WHERE activation_code = %s "
                "AND enabled = 1 AND activated = 1 AND exp_date > %s "
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
        log.warning("Activation rejected for client=%s", body.client_id[:12])
        return ActivationResponse(ok=False, error="Invalid or inactive activation code.")

    row = matches[0]
    log.info("Activation succeeded for client=%s", body.client_id[:12])
    return ActivationResponse(ok=True, username=str(row["username"]), password=str(row["password"]))
