import asyncio
import base64
import hashlib
import hmac
import json
import time
import unittest

from websockets.asyncio.client import connect
from websockets.asyncio.server import serve
from websockets.exceptions import InvalidStatus
from service import Relay, decode_ticket

KEY = "ab" * 32


def ticket(device="a" * 32, tenant="b" * 32, **overrides):
    import secrets
    claims = dict(v=1, device=device, tenant=tenant, exp=int(time.time()) + 300, nonce=secrets.token_hex(16))
    claims.update(overrides)
    encoded = base64.urlsafe_b64encode(json.dumps(claims).encode()).decode().rstrip("=")
    return encoded + "." + hmac.new(bytes.fromhex(KEY), encoded.encode(), hashlib.sha256).hexdigest()


class TicketTests(unittest.TestCase):
    def test_tampered_expired_future_or_wrong_scope_ticket_rejected(self):
        for token in [ticket() + "x", ticket(exp=int(time.time()) - 1), ticket(exp=int(time.time()) + 1000), ticket(device="wrong"), ticket(v=2)]:
            with self.assertRaises(ValueError):
                decode_ticket(token, KEY)

    def test_ticket_has_no_credentials_and_exact_scope(self):
        claims = decode_ticket(ticket(), KEY)
        self.assertEqual(set(claims), {"v", "device", "tenant", "exp", "nonce"})
        self.assertEqual(claims["device"], "a" * 32)


class RelayTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.relay = Relay(KEY)
        self.server = await serve(self.relay.connected, "127.0.0.1", 0, process_request=self.relay.authenticate)
        self.url = f"ws://127.0.0.1:{self.server.sockets[0].getsockname()[1]}/api/device/live"
        self.http = await asyncio.start_server(self.relay.http, "127.0.0.1", 0, limit=8192)
        self.port = self.http.sockets[0].getsockname()[1]

    async def asyncTearDown(self):
        self.server.close()
        await self.server.wait_closed()
        self.http.close()
        await self.http.wait_closed()

    def client(self, token=None, **kw):
        return connect(self.url, additional_headers={"Authorization": "Bearer " + (token or ticket())}, **kw)

    async def test_authenticated_connection_gets_ready_then_targeted_wake(self):
        async with self.client() as first, self.client(ticket(device="c" * 32, tenant="d" * 32)) as second:
            self.assertEqual(json.loads(await first.recv()), {"type": "ready"})
            await second.recv()
            self.assertEqual(self.relay.wake({"device": "a" * 32}), 1)
            self.assertEqual(json.loads(await asyncio.wait_for(first.recv(), 1)), {"type": "check_in"})
            with self.assertRaises(asyncio.TimeoutError):
                await asyncio.wait_for(second.recv(), .05)

    async def test_workspace_wake_stays_scoped(self):
        async with self.client() as first, self.client(ticket(device="c" * 32)) as second:
            await first.recv(); await second.recv()
            self.assertEqual(self.relay.wake({"tenant": "b" * 32}), 2)
            self.assertEqual(json.loads(await first.recv())["type"], "check_in")
            self.assertEqual(json.loads(await second.recv())["type"], "check_in")

    async def test_ticket_cannot_be_replayed_and_browser_origin_is_rejected(self):
        token = ticket()
        async with self.client(token) as ws:
            await ws.recv()
            with self.assertRaises(InvalidStatus):
                async with self.client(token): pass
        with self.assertRaises(InvalidStatus):
            async with self.client(origin="https://untrusted.example"): pass

    async def test_client_cannot_inject_commands(self):
        async with self.client() as ws:
            await ws.recv()
            await ws.send('{"type":"check_in","device":"other"}')
            await ws.wait_closed()
            self.assertEqual(ws.close_code, 1008)

    async def test_ticket_expiry_closes_socket_and_fresh_ticket_reconnects(self):
        async with self.client(ticket(exp=int(time.time())+1)) as ws:
            await ws.recv()
            await asyncio.wait_for(ws.wait_closed(), 2)
            self.assertEqual(ws.close_code, 4001)
        async with self.client() as ws:
            self.assertEqual(json.loads(await ws.recv()), {"type": "ready"})

    async def test_internal_wake_auth_and_scope_validation(self):
        async def request(key, body):
            reader, writer = await asyncio.open_connection("127.0.0.1", self.port)
            data = json.dumps(body).encode()
            writer.write(f"POST /wake HTTP/1.1\r\nAuthorization: Bearer {key}\r\nContent-Length: {len(data)}\r\n\r\n".encode() + data)
            await writer.drain()
            response = await reader.read()
            writer.close(); await writer.wait_closed()
            return int(response.split(b" ")[1])
        self.assertEqual(await request("bad", {"device": "a" * 32}), 401)
        self.assertEqual(await request(KEY, {"all": True}), 400)
        async with self.client() as ws:
            await ws.recv()
            self.assertEqual(await request(KEY, {"device": "a" * 32}), 200)
            self.assertEqual(json.loads(await ws.recv())["type"], "check_in")

    async def test_bursts_are_bounded_and_bad_targets_rejected(self):
        with self.assertRaises(ValueError): self.relay.wake({"device": "a" * 32, "tenant": "b" * 32})
        async with self.client() as ws:
            await ws.recv()
            for _ in range(1000): self.relay.wake({"device": "a" * 32})
            self.assertTrue(all(q.qsize() <= 1 for _, q in self.relay.clients.values()))
            self.assertEqual(json.loads(await ws.recv())["type"], "check_in")


if __name__ == "__main__":
    unittest.main()
