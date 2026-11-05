"""Loopback-only wake relay. No database access, app credentials or command payloads."""
import asyncio
import base64
import hashlib
import hmac
import json
import os
import re
import time
from http import HTTPStatus

from websockets.asyncio.server import serve

HEX_ID = re.compile(r"[a-f0-9]{32}\Z")


def decode_ticket(ticket, key, now=None):
    now = time.time() if now is None else now
    if not isinstance(ticket, str) or len(ticket) > 2048:
        raise ValueError("Invalid ticket")
    encoded, signature = ticket.split(".", 1)
    expected = hmac.new(bytes.fromhex(key), encoded.encode("ascii"), hashlib.sha256).hexdigest()
    if not hmac.compare_digest(expected, signature):
        raise ValueError("Invalid ticket")
    claims = json.loads(base64.b64decode(encoded + "=" * (-len(encoded) % 4), altchars=b"-_", validate=True))
    if set(claims) != {"v", "device", "tenant", "exp", "nonce"} or claims["v"] != 1:
        raise ValueError("Invalid ticket")
    if any(not isinstance(claims[k], str) or not HEX_ID.fullmatch(claims[k]) for k in ("device", "tenant", "nonce")):
        raise ValueError("Invalid ticket")
    if type(claims["exp"]) is not int or not now < claims["exp"] <= now + 305:
        raise ValueError("Expired ticket")
    return claims


class Relay:
    def __init__(self, key, maximum=5000):
        if not re.fullmatch(r"[a-f0-9]{64}", key):
            raise ValueError("Invalid relay configuration")
        self.key = key
        self.maximum = maximum
        self.clients = {}
        self.used = {}

    async def authenticate(self, connection, request):
        if request.path != "/api/device/live" or "Origin" in request.headers:
            return connection.respond(HTTPStatus.FORBIDDEN, "Unavailable\n")
        try:
            auth = request.headers.get("Authorization", "")
            if not auth.startswith("Bearer "):
                raise ValueError("Missing ticket")
            claims = decode_ticket(auth[7:], self.key)
            now = time.time()
            self.used = {k: expiry for k, expiry in self.used.items() if expiry > now}
            if claims["nonce"] in self.used or len(self.used) >= self.maximum * 4:
                raise ValueError("Ticket already used")
            if len(self.clients) >= self.maximum:
                return connection.respond(HTTPStatus.SERVICE_UNAVAILABLE, "Unavailable\n")
            self.used[claims["nonce"]] = claims["exp"]
            connection.live_claims = claims
        except Exception:
            return connection.respond(HTTPStatus.UNAUTHORIZED, "Invalid ticket\n")
        return None

    async def connected(self, connection):
        claims = connection.live_claims
        # A reconnect may overlap its predecessor; keep at most two per device.
        older = [ws for ws, (c, _) in self.clients.items() if c["device"] == claims["device"]]
        if len(older) >= 2:
            await older[0].close(4000, "Replaced connection")
        queue = asyncio.Queue(maxsize=1)
        self.clients[connection] = (claims, queue)

        async def expire():
            await asyncio.sleep(max(0, claims["exp"] - time.time()))
            await connection.close(4001, "Refresh ticket")

        async def receive():
            async for _ in connection:
                # Only protocol ping/pong is accepted; app changes use the normal API.
                await connection.close(1008, "Notification channel only")

        async def send():
            await connection.send('{"type":"ready"}')
            while True:
                await queue.get()
                await connection.send('{"type":"check_in"}')
                await asyncio.sleep(1)  # Coalesce bursts without buffering customer data.

        tasks = [asyncio.create_task(f()) for f in (expire, receive, send)]
        try:
            await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
        finally:
            self.clients.pop(connection, None)
            for task in tasks:
                task.cancel()
            await asyncio.gather(*tasks, return_exceptions=True)

    def wake(self, target):
        if not isinstance(target, dict) or len(target) != 1 or next(iter(target)) not in ("device", "tenant"):
            raise ValueError("Invalid target")
        kind, value = next(iter(target.items()))
        if not isinstance(value, str) or not HEX_ID.fullmatch(value):
            raise ValueError("Invalid target")
        count = 0
        for claims, queue in self.clients.values():
            if claims[kind] == value:
                if queue.empty():
                    queue.put_nowait(None)
                count += 1
        return count

    async def http(self, reader, writer):
        # This listener is never reverse-proxied. Bound parsing and no request logging.
        status, body = 400, b'{"ok":false}'
        try:
            raw = await asyncio.wait_for(reader.readuntil(b"\r\n\r\n"), 3)
            lines = raw.decode("ascii").split("\r\n")
            method, path, version = lines[0].split(" ")
            headers = {}
            for line in lines[1:]:
                if not line:
                    continue
                name, value = line.split(":", 1)
                name = name.lower()
                if name in headers:
                    raise ValueError("Duplicate header")
                headers[name] = value.strip()
            if method == "GET" and path == "/health":
                status, body = 200, b'{"ok":true}'
            elif method == "POST" and path == "/wake":
                if not hmac.compare_digest(headers.get("authorization", ""), "Bearer " + self.key):
                    status = 401
                else:
                    if "transfer-encoding" in headers:
                        raise ValueError("Unsupported framing")
                    length = int(headers.get("content-length", "0"))
                    if not 1 <= length <= 1024:
                        raise ValueError("Invalid size")
                    data = await asyncio.wait_for(reader.readexactly(length), 3)
                    self.wake(json.loads(data))
                    status, body = 200, b'{"ok":true}'
            else:
                status = 404
        except (ValueError, UnicodeError, asyncio.TimeoutError, asyncio.IncompleteReadError, asyncio.LimitOverrunError):
            pass
        try:
            writer.write(f"HTTP/1.1 {status} {HTTPStatus(status).phrase}\r\nContent-Type: application/json\r\nContent-Length: {len(body)}\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n".encode() + body)
            await asyncio.wait_for(writer.drain(), 3)
        except (OSError, asyncio.TimeoutError):
            pass
        finally:
            writer.close()
            await writer.wait_closed()


async def main():
    relay = Relay(os.environ["EXTREME_LIVE_KEY"])
    internal = await asyncio.start_server(relay.http, "127.0.0.1", 19095, limit=8192)
    async with internal, serve(relay.connected, "127.0.0.1", 19094,
                              process_request=relay.authenticate, max_size=1024, max_queue=2,
                              compression=None, ping_interval=15, ping_timeout=10,
                              open_timeout=5, close_timeout=3):
        await asyncio.Future()


if __name__ == "__main__":
    asyncio.run(main())
