import tempfile
import unittest
from pathlib import Path

from fastapi.testclient import TestClient
from service import Store, create_app, ORIGIN, COOKIE, Unauthorized, RateLimited, Conflict


class ManagerTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.now = 1000000
        self.store = Store(Path(self.tmp.name) / "db.sqlite", lambda: self.now)
        self.store.set_admin("owner", "owner-unique-test-password")
        self.store.set_admin("second-admin", "other-unique-test-password")
        self.client = TestClient(create_app(self.store), base_url=ORIGIN)
        self.headers = {"Origin": ORIGIN, "X-Hub-Manager": "1"}

    def tearDown(self):
        self.client.close()
        self.tmp.cleanup()

    def post(self, path, data):
        return self.client.post(path, json=data, headers=self.headers)

    def login(self, second=False):
        return self.post("/api/admin/login", {"username": "second-admin" if second else "owner",
            "password": "other-unique-test-password" if second else "owner-unique-test-password"})

    def update(self, active=True, revision=0, message="Scheduled work"):
        return self.post("/api/admin/maintenance", {"active": active, "revision": revision, "message": message})

    def test_initial_status_is_off_and_does_not_disclose_admins(self):
        response = self.client.get("/api/maintenance")
        self.assertEqual(response.status_code, 200)
        self.assertFalse(response.json()["active"])
        self.assertNotIn("updated_by", response.json())
        self.assertIn("no-store", response.headers["cache-control"])

    def test_unauthenticated_user_cannot_enable_disable_or_read_admin_data(self):
        self.assertEqual(self.update().status_code, 401)
        self.assertEqual(self.client.get("/api/admin/status").status_code, 401)
        self.assertFalse(self.store.status()["active"])

    def test_both_admins_can_enable_and_end_maintenance_with_audit(self):
        login = self.login()
        cookie = login.headers["set-cookie"]
        for flag in ["HttpOnly", "Secure", "SameSite=strict", "Path=/"]:
            self.assertIn(flag, cookie)
        self.assertEqual(self.update().status_code, 200)
        self.assertTrue(self.client.get("/api/maintenance").json()["active"])
        self.login(second=True)
        result = self.update(False, revision=1)
        self.assertEqual(result.status_code, 200)
        self.assertEqual(result.json()["updated_by"], "second-admin")
        with self.store.connection() as db:
            self.assertEqual(db.execute("SELECT admin FROM audit ORDER BY revision").fetchall()[0][0], "owner")
            self.assertEqual(db.execute("SELECT COUNT(*) FROM audit").fetchone()[0], 2)
        self.assertFalse(self.client.get("/api/maintenance").json()["active"])

    def test_another_admin_stale_page_cannot_overwrite_newer_change(self):
        self.login()
        self.update(True)
        self.assertEqual(self.update(False, revision=0).status_code, 409)
        self.assertTrue(self.store.status()["active"])

    def test_cross_site_requests_are_rejected_even_with_admin_cookie(self):
        self.login()
        response = self.client.post("/api/admin/maintenance", json={"active": True, "revision": 0, "message": "x"},
                                    headers={"Origin": "https://evil.example", "X-Hub-Manager": "1"})
        self.assertEqual(response.status_code, 403)
        self.assertFalse(self.store.status()["active"])

    def test_login_without_origin_or_custom_header_is_rejected(self):
        self.assertEqual(self.client.post("/api/admin/login", json={"username": "owner", "password": "x"}).status_code, 403)

    def test_malformed_or_string_boolean_cannot_accidentally_toggle_maintenance(self):
        self.login()
        self.assertEqual(self.update(active="false").status_code, 422)
        self.assertEqual(self.update(message="x" * 501).status_code, 422)
        self.assertFalse(self.store.status()["active"])

    def test_logout_revokes_session(self):
        self.login()
        token = self.client.cookies.get(COOKIE)
        self.post("/api/admin/logout", {})
        with self.assertRaises(Unauthorized):
            self.store.authenticate(token)
        self.assertEqual(self.update().status_code, 401)

    def test_password_rotation_revokes_sessions(self):
        self.login()
        self.store.set_admin("owner", "new-unique-test-password")
        self.assertEqual(self.update().status_code, 401)

    def test_expired_session_cannot_toggle_state(self):
        self.login()
        self.now += 43201
        self.assertEqual(self.update().status_code, 401)

    def test_login_guesses_are_rate_limited(self):
        for _ in range(10):
            self.assertEqual(self.post("/api/admin/login", {"username": "owner", "password": "wrong"}).status_code, 401)
        self.assertEqual(self.login().status_code, 429)
        self.now += 901
        self.assertEqual(self.login().status_code, 200)

    def test_status_survives_service_restart(self):
        self.login()
        self.update(True, message="Please wait")
        restored = Store(self.store.path)
        self.assertTrue(restored.status()["active"])
        self.assertEqual(restored.status()["message"], "Please wait")

    def test_admin_page_and_script_have_security_headers(self):
        for path in ["/", "/admin.js"]:
            response = self.client.get(path)
            self.assertEqual(response.status_code, 200)
            self.assertEqual(response.headers["x-frame-options"], "DENY")
            self.assertIn("frame-ancestors 'none'", response.headers["content-security-policy"])


if __name__ == "__main__":
    unittest.main()
