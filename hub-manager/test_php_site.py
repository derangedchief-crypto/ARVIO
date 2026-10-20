"""Real PHP HTTP tests using an isolated document root, never a deployed site.
Run: PHP_BINARY=/path/to/php python3 -m unittest -v test_php_site.py
"""
import http.client
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import time
import unittest


class PhpSiteTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.root = Path(cls.tmp.name) / "site"
        shutil.copytree(Path(__file__).parent / "php-site", cls.root)
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", 0))
            cls.port = sock.getsockname()[1]
        cls.origin = f"http://127.0.0.1:{cls.port}"
        (cls.root / "config.php").write_text("<?php return ['origin'=>'" + cls.origin + "','setup_key'=>''];")
        php = os.environ.get("PHP_BINARY", "php")
        cls.proc = subprocess.Popen([php, "-n", "-S", f"127.0.0.1:{cls.port}", "-t", str(cls.root)],
                                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        for _ in range(100):
            try:
                with socket.create_connection(("127.0.0.1", cls.port), timeout=.1):
                    return
            except OSError:
                time.sleep(.03)
        cls.proc.terminate()
        raise RuntimeError("PHP test server did not start")

    @classmethod
    def tearDownClass(cls):
        cls.proc.terminate()
        cls.proc.wait(timeout=5)
        cls.tmp.cleanup()

    def setUp(self):
        for name in ["state.php", "setup-key.php"]:
            (self.root / "_private" / name).unlink(missing_ok=True)
        self.cookie = ""

    def request(self, path, body=None, origin=None):
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=15)
        headers = {"Cookie": self.cookie}
        if body is not None:
            headers.update({"Content-Type": "application/json", "X-Hub-Manager": "1", "Origin": origin or self.origin})
        conn.request("POST" if body is not None else "GET", path, None if body is None else json.dumps(body), headers)
        response = conn.getresponse()
        status, data, received = response.status, response.read().decode(), dict(response.getheaders())
        if received.get("Set-Cookie"):
            self.cookie = received["Set-Cookie"].split(";", 1)[0]
        conn.close()
        return status, json.loads(data) if data.startswith("{") else data, received

    def provision(self):
        self.assertEqual(200, self.request("/api/maintenance/")[0])
        key = (self.root / "_private/setup-key.php").read_text().splitlines()[-1]
        self.assertEqual(64, len(key))
        response = self.request("/api/setup/", {"setup_key": key, "owner": "owner", "owner_password": "owner-unique-test-password",
                                               "second": "second", "second_password": "other-unique-test-password"})
        self.assertEqual(200, response[0], response)
        return key

    def login(self, second=False):
        return self.request("/api/admin/login/", {"username": "second" if second else "owner",
            "password": "other-unique-test-password" if second else "owner-unique-test-password"})

    def update(self, active=True, revision=0, message="Scheduled maintenance"):
        return self.request("/api/admin/maintenance/", {"active": active, "revision": revision, "message": message})

    def test_public_status_and_unconfigured_installation_are_safe(self):
        code, data, headers = self.request("/api/maintenance/")
        self.assertEqual(200, code)
        self.assertFalse(data["active"])
        self.assertNotIn("updated_by", data)
        self.assertIn("no-store", headers["Cache-Control"])
        self.assertEqual(401, self.update()[0])

    def test_owner_setup_key_is_generated_on_server_and_never_public(self):
        self.provision()
        for path in ["/_private/setup-key.php", "/_private/state.php", "/_private/_lock.php", "/_hub.php"]:
            code, body, _ = self.request(path)
            self.assertEqual(404, code, path)
            self.assertEqual("", body)
        self.assertEqual(403, self.request("/api/setup/", {"setup_key": "0" * 64})[0])

    def test_both_admins_can_control_maintenance_with_secure_cookie_and_audit(self):
        self.provision()
        code, _, headers = self.login()
        self.assertEqual(200, code)
        for flag in ["secure", "HttpOnly", "SameSite=Strict", "path=/"]:
            self.assertIn(flag, headers["Set-Cookie"])
        self.assertEqual(200, self.update()[0])
        self.assertTrue(self.request("/api/maintenance/")[1]["active"])
        self.assertEqual(200, self.login(second=True)[0])
        self.assertEqual("second", self.update(False, 1)[1]["updated_by"])
        raw = (self.root / "_private/state.php").read_text().split("\n", 1)[1]
        data = json.loads(raw)
        self.assertEqual(["owner", "second"], [e["updated_by"] for e in data["audit"]])
        self.assertNotIn("owner-unique-test-password", raw)
        self.assertNotIn(self.cookie.split("=", 1)[1], raw)

    def test_revision_conflicts_and_string_booleans_do_not_change_state(self):
        self.provision(); self.login()
        self.assertEqual(200, self.update()[0])
        self.assertEqual(409, self.update(False, 0)[0])
        self.assertEqual(400, self.update("false", 1)[0])
        self.assertEqual(400, self.update(False, 1, "x" * 501)[0])
        self.assertTrue(self.request("/api/maintenance/")[1]["active"])

    def test_cross_origin_and_unauthenticated_requests_cannot_toggle(self):
        self.provision()
        self.assertEqual(401, self.update()[0])
        self.login()
        self.assertEqual(403, self.request("/api/admin/maintenance/", {"active": True}, "https://evil.example")[0])
        self.assertFalse(self.request("/api/maintenance/")[1]["active"])

    def test_logout_revokes_session(self):
        self.provision(); self.login()
        stolen = self.cookie
        self.assertEqual(200, self.request("/api/admin/logout/", {})[0])
        self.cookie = stolen
        self.assertEqual(401, self.update()[0])

    def test_recovery_resets_admin_sessions_without_clearing_maintenance(self):
        self.provision(); self.login(); self.update()
        old_cookie = self.cookie
        self.provision()
        self.cookie = old_cookie
        self.assertEqual(401, self.update(False, 1)[0])
        self.assertTrue(self.request("/api/maintenance/")[1]["active"])

    def test_ten_login_guesses_are_rate_limited(self):
        self.provision()
        for _ in range(10):
            self.assertEqual(401, self.request("/api/admin/login/", {"username": "owner", "password": "wrong"})[0])
        self.assertEqual(429, self.login()[0])

    def test_php_pages_and_maintenance_url_without_slash(self):
        for path in ["/", "/setup/"]:
            self.assertEqual(200, self.request(path)[0])
        code, body, headers = self.request("/api/maintenance")
        self.assertIn(code, [200, 301, 302])
        if code != 200:
            self.assertTrue(headers["Location"].endswith("/api/maintenance/"))


if __name__ == "__main__":
    unittest.main()
