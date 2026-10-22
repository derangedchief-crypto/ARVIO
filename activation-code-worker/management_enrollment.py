"""Short-lived account assertions. No device/reseller ownership is accepted from APKs.

The separate control center verifies these assertions and device-key possession,
then requires an owner approval. This module never connects to a database itself.
"""
from __future__ import annotations

import base64
import hashlib
import hmac
import ipaddress
import json
import re
import secrets
import threading
import time
from collections import OrderedDict, deque
from urllib.parse import urlsplit

PACKAGES = frozenset({"com.arvio.tv", "com.arvio.tv.beta", "com.arvio.tv.test"})


def configured(secret: str, origin: str) -> bool:
    try:
        parsed = urlsplit(origin)
        if parsed.port is not None and not 1 <= parsed.port <= 65535:
            return False
    except ValueError:
        return False
    return bool(re.fullmatch(r"[a-f0-9]{64}", secret)) and (
        parsed.scheme == "https" and bool(parsed.hostname) and parsed.username is None
        and parsed.password is None and parsed.path in ("", "/")
        and not parsed.query and not parsed.fragment
    )


def _b64(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).decode("ascii").rstrip("=")


def issue_proof(secret: str, origin: str, canonical_username: str,
                installation_id: str, key_fingerprint: str, app_package: str,
                challenge_hash: str, *, now: int | None = None) -> str:
    if not configured(secret, origin):
        raise ValueError("Management enrollment is not configured")
    if not re.fullmatch(r"[a-f0-9]{32}", installation_id):
        raise ValueError("Invalid installation")
    if not all(re.fullmatch(r"[a-f0-9]{64}", x) for x in (key_fingerprint, challenge_hash)):
        raise ValueError("Invalid challenge/key")
    if app_package not in PACKAGES or not canonical_username or len(canonical_username) > 256:
        raise ValueError("Invalid account/package")
    key = bytes.fromhex(secret)
    issued = int(time.time()) if now is None else now
    subject = hmac.new(key, b"account-v1\n" + canonical_username.encode("utf-8"), hashlib.sha256).hexdigest()
    claims = {
        "v": 1, "iss": "extreme-activation", "aud": origin.rstrip("/"),
        "sub": subject, "hint": "…" + canonical_username[-4:],
        "install": installation_id, "key": key_fingerprint, "package": app_package,
        "challenge": challenge_hash, "iat": issued, "exp": issued + 60,
        "jti": secrets.token_hex(16),
    }
    payload = _b64(json.dumps(claims, separators=(",", ":"), ensure_ascii=False).encode("utf-8"))
    signature = hmac.new(key, b"proof-v1." + payload.encode("ascii"), hashlib.sha256).digest()
    return "ec1." + payload + "." + _b64(signature)


def credentials_match(rows: list[dict], password: str) -> bool:
    valid = len(rows) == 1 and isinstance(rows[0].get("password"), str) and bool(rows[0]["password"])
    stored = rows[0]["password"] if valid else "unavailable-account-placeholder"
    matched = hmac.compare_digest(stored.encode("utf-8"), password.encode("utf-8"))
    return valid and matched


def client_ip(peer: str, forwarded: str | None) -> str:
    """Only a loopback reverse proxy may supply the Nginx-set real-IP value."""
    try:
        address = ipaddress.ip_address(peer)
        if address.is_loopback and forwarded:
            return str(ipaddress.ip_address(forwarded))
        return str(address)
    except ValueError:
        return "unknown"


class AttemptLimiter:
    def __init__(self, limit: int, window: int, max_keys: int = 8192):
        self.limit, self.window, self.max_keys = limit, window, max_keys
        self.entries: OrderedDict[str, deque[float]] = OrderedDict()
        self.lock = threading.Lock()

    def allow(self, key: str, now: float | None = None) -> bool:
        instant = time.monotonic() if now is None else now
        with self.lock:
            while self.entries and next(iter(self.entries.values()))[-1] <= instant - self.window:
                self.entries.popitem(last=False)
            attempts = self.entries.get(key)
            if attempts is None:
                # Do not evict live limits (which would allow quota bypass).
                if len(self.entries) >= self.max_keys:
                    return False
                attempts = deque()
                self.entries[key] = attempts
            while attempts and attempts[0] <= instant - self.window:
                attempts.popleft()
            if len(attempts) >= self.limit:
                return False
            attempts.append(instant)
            self.entries.move_to_end(key)
            return True
