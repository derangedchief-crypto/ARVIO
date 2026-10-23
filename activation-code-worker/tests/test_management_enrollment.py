import base64
from contextlib import contextmanager
import hashlib
import hmac
import json
import os
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from management_enrollment import AttemptLimiter, client_ip, configured, credentials_match, issue_proof, bridge_signature

SECRET = '12' * 32  # Synthetic test key, never deployment configuration.
ORIGIN = 'https://control.example.test'


class ProofTests(unittest.TestCase):
    def proof(self, **kwargs):
        return issue_proof(SECRET, ORIGIN, 'fixture-user', 'a' * 32, 'b' * 64,
                           'com.arvio.tv.test', 'c' * 64, now=1000, **kwargs)

    def test_proof_is_short_lived_and_binds_all_identity_fields(self):
        _, encoded, signature = self.proof().split('.')
        claims = json.loads(base64.urlsafe_b64decode(encoded + '=' * (-len(encoded) % 4)))
        self.assertEqual(60, claims['exp'] - claims['iat'])
        self.assertEqual('a' * 32, claims['install'])
        self.assertEqual('b' * 64, claims['key'])
        self.assertEqual('c' * 64, claims['challenge'])
        self.assertEqual(ORIGIN, claims['aud'])
        self.assertEqual('com.arvio.tv.test', claims['package'])
        expected = hmac.new(bytes.fromhex(SECRET), b'proof-v1.' + encoded.encode(), hashlib.sha256).digest()
        self.assertEqual(expected, base64.urlsafe_b64decode(signature + '=' * (-len(signature) % 4)))

    def test_account_reference_is_stable_but_challenge_proof_ids_are_unique(self):
        def claims(token):
            part = token.split('.')[1]
            return json.loads(base64.urlsafe_b64decode(part + '=' * (-len(part) % 4)))
        a, b = claims(self.proof()), claims(self.proof())
        self.assertEqual(a['sub'], b['sub']); self.assertNotEqual(a['jti'], b['jti'])
        self.assertNotIn('fixture-user', self.proof())
        self.assertNotIn('password', a)

    def test_no_unverified_reseller_or_customer_id_can_be_signed(self):
        with self.assertRaises(TypeError):
            self.proof(tenant_id='foreign')
        with self.assertRaises(ValueError):
            issue_proof(SECRET, ORIGIN, 'fixture', 'a' * 32, 'b' * 64, 'foreign.app', 'c' * 64)
    def test_brand_format_is_account_bound_not_a_claim_of_authentic_apk_or_registry_admission(self):
        package='com.extreme.brand.b'+'d'*32+'.test'
        token=issue_proof(SECRET,ORIGIN,'fixture','a'*32,'b'*64,package,'c'*64,now=1000)
        encoded=token.split('.')[1];claims=json.loads(base64.urlsafe_b64decode(encoded+'='*(-len(encoded)%4)))
        self.assertEqual(package,claims['package']);self.assertNotIn('brand_owner',claims);self.assertNotIn('signer',claims)
        for invalid in ['com.extreme.brand.arbitrary','com.extreme.brand.b'+'d'*32+'.beta',package+'.evil']:
            with self.assertRaises(ValueError):issue_proof(SECRET,ORIGIN,'fixture','a'*32,'b'*64,invalid,'c'*64)

    def test_settings_fail_closed_without_affecting_legacy_activation(self):
        self.assertFalse(configured('', ORIGIN))
        self.assertFalse(configured(SECRET, 'http://unsafe.example.test'))
        self.assertFalse(configured(SECRET, 'https://user:password@example.test'))
        self.assertTrue(configured(SECRET, ORIGIN))

    def test_credentials_must_be_exactly_one_active_row_and_match_password(self):
        self.assertTrue(credentials_match([{'password': '秘密 value'}], '秘密 value'))
        self.assertFalse(credentials_match([{'password': 'other'}], '秘密 value'))
        self.assertFalse(credentials_match([], 'value'))
        self.assertFalse(credentials_match([{'password': None}], 'None'))
        self.assertFalse(credentials_match([{'password': ''}], ''))
        self.assertFalse(credentials_match([{'password': 'value'}, {'password': 'value'}], 'value'))

    def test_untrusted_forwarded_ip_cannot_bypass_limits(self):
        self.assertEqual('8.8.8.8', client_ip('8.8.8.8', '1.1.1.1'))
        self.assertEqual('1.1.1.1', client_ip('127.0.0.1', '1.1.1.1'))
        self.assertEqual('unknown', client_ip('127.0.0.1', 'malformed'))

    def test_limiter_is_bounded_and_does_not_evict_live_quotas(self):
        limit = AttemptLimiter(2, 60, 1)
        self.assertTrue(limit.allow('ip', 0)); self.assertTrue(limit.allow('ip', 1))
        self.assertFalse(limit.allow('ip', 2)); self.assertFalse(limit.allow('other', 2))
        self.assertTrue(limit.allow('other', 62)); self.assertEqual(1, len(limit.entries))


class EndpointTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        # All settings are synthetic and startup tunnel creation is patched out.
        for name in ['PANEL_SSH_HOST', 'PANEL_SSH_USERNAME', 'PANEL_SSH_PASSWORD']:
            os.environ.setdefault(name, 'test-only')
        os.environ['ACTIVATION_WORKER_LOG_FILE'] = '/tmp/opencode/management-worker-tests.log'
        from fastapi.testclient import TestClient
        import main
        cls.main = main; cls.TestClient = TestClient

    def setUp(self):
        self.main._management_ips = AttemptLimiter(120, 3600)
        self.main._management_accounts = AttemptLimiter(20, 600)
        self.main._management_lookups = AttemptLimiter(60, 60)
        self.settings = patch.multiple(self.main.settings, management_enrollment_secret=SECRET, management_control_origin=ORIGIN)
        self.settings.start(); self.addCleanup(self.settings.stop)
        self.startup = patch.object(self.main, 'ensure_tunnel_and_pool')
        self.startup.start(); self.addCleanup(self.startup.stop)

    def body(self):
        return {'username': 'fixture-user', 'password': 'fixture-password', 'installation_id': 'a' * 32,
                'key_fingerprint': 'b' * 64, 'app_package': 'com.arvio.tv.test', 'challenge_hash': 'c' * 64}

    @contextmanager
    def cursor(self, rows):
        class Cursor:
            sql = ''; args = ()
            def execute(inner, sql, args): inner.sql, inner.args = sql, args
            def fetchall(inner): return rows
        value = Cursor()
        with patch.object(self.main, 'panel_cursor') as mocked:
            @contextmanager
            def factory(): yield value
            mocked.side_effect = factory
            yield value

    def test_valid_endpoint_uses_known_columns_and_never_returns_password(self):
        with self.cursor([{'username': 'fixture-user', 'password': 'fixture-password'}]) as cursor, self.TestClient(self.main.app) as client:
            response = client.post('/management/enrollment-proof', json=self.body())
        self.assertEqual(200, response.status_code); self.assertTrue(response.json()['ok'])
        self.assertNotIn('fixture-password', response.text)
        self.assertIn('cu.enabled = 1 AND cu.activated = 1', cursor.sql)
        self.assertNotIn('reseller_id', cursor.sql); self.assertNotIn('fixture-password', str(cursor.args))

    def test_bad_and_duplicate_accounts_have_the_same_generic_error(self):
        responses = []
        for rows in [[], [{'username': 'fixture-user', 'password': 'wrong'}], [{'password': 'fixture-password'}] * 2]:
            with self.cursor(rows), self.TestClient(self.main.app) as client:
                response = client.post('/management/enrollment-proof', json=self.body())
            responses.append((response.status_code, response.json()))
        self.assertTrue(all(r == responses[0] for r in responses))

    def test_validation_does_not_echo_credentials_or_accept_tenant_claim(self):
        body = self.body(); body['tenant_id'] = 'foreign-tenant'
        with self.TestClient(self.main.app) as client:
            response = client.post('/management/enrollment-proof', json=body)
        self.assertEqual(422, response.status_code)
        self.assertNotIn('fixture-password', response.text); self.assertNotIn('foreign-tenant', response.text)

    def test_legacy_activation_still_returns_its_original_credentials(self):
        with self.cursor([{'username': 'fixture-user', 'password': 'fixture-password'}]), self.TestClient(self.main.app) as client:
            response = client.post('/activate', json={'activation_code': '123456'})
        self.assertEqual(200, response.status_code)
        self.assertEqual('fixture-password', response.json()['password'])

    def test_missing_bridge_settings_disable_only_management(self):
        with patch.object(self.main.settings, 'management_enrollment_secret', ''), self.TestClient(self.main.app) as client:
            response = client.post('/management/enrollment-proof', json=self.body())
            self.assertEqual(503, response.status_code)
            self.assertEqual({'status': 'ok'}, client.get('/health').json())

    def test_verified_line_ownership_comes_only_from_database_join(self):
        row = {'id': 2001, 'username': 'fixture-user', 'password': 'fixture-password', 'member_id': 1001,
               'reseller_username': 'PanelOwner', 'reseller_status': 1}
        with self.cursor([row]) as cursor, self.TestClient(self.main.app) as client:
            response = client.post('/management/enrollment-proof', json=self.body())
        self.assertEqual(200, response.status_code)
        encoded = response.json()['proof'].split('.')[1]
        claim = json.loads(base64.urlsafe_b64decode(encoded + '=' * (-len(encoded) % 4)))
        self.assertEqual({'customer_id': '2001', 'reseller_id': '1001', 'reseller_username': 'PanelOwner'}, claim['panel'])
        self.assertIn('u.id = cu.member_id', cursor.sql)

    def test_inactive_reseller_cannot_get_automatic_ownership_claim(self):
        row = {'id': 2001, 'username': 'fixture-user', 'password': 'fixture-password', 'member_id': 1001,
               'reseller_username': 'PanelOwner', 'reseller_status': 0}
        with self.cursor([row]), self.TestClient(self.main.app) as client:
            response = client.post('/management/enrollment-proof', json=self.body())
        encoded = response.json()['proof'].split('.')[1]
        claim = json.loads(base64.urlsafe_b64decode(encoded + '=' * (-len(encoded) % 4)))
        self.assertNotIn('panel', claim)

    def lookup(self, client, username='PanelOwner', signed=True, timestamp=None):
        import time
        raw = json.dumps({'username': username}, separators=(',', ':'))
        timestamp = str(int(time.time())) if timestamp is None else timestamp
        nonce = 'a' * 32
        headers = {'Content-Type': 'application/json', 'X-Management-Timestamp': timestamp, 'X-Management-Nonce': nonce}
        headers['X-Management-Signature'] = bridge_signature(SECRET, 'reseller-lookup-v1', timestamp, nonce, raw.encode()) if signed else '0' * 64
        return client.post('/management/reseller-lookup', content=raw, headers=headers), timestamp, nonce

    def test_reseller_lookup_needs_bridge_auth_and_rejects_stale_signatures(self):
        with patch.object(self.main, 'panel_cursor') as cursor, self.TestClient(self.main.app) as client:
            self.assertEqual(403, self.lookup(client, signed=False)[0].status_code)
            self.assertEqual(403, self.lookup(client, timestamp='1000000000')[0].status_code)
            cursor.assert_not_called()

    def test_exact_active_unique_reseller_lookup_returns_nonce_bound_signature(self):
        with self.cursor([{'id': 1001, 'username': 'PanelOwner', 'status': 1}]) as cursor, self.TestClient(self.main.app) as client:
            response, timestamp, nonce = self.lookup(client)
        self.assertEqual(200, response.status_code)
        result = response.json()
        self.assertEqual({'id': '1001', 'username': 'PanelOwner'}, json.loads(result['payload']))
        self.assertEqual(bridge_signature(SECRET, 'reseller-result-v1', timestamp, nonce, result['payload'].encode()), result['signature'])
        self.assertEqual(('PanelOwner',), cursor.args)
        self.assertNotIn('password', cursor.sql)

    def test_lookup_never_guesses_case_ambiguous_or_disabled_accounts(self):
        for rows in [[], [{'id': 1, 'username': 'panelowner', 'status': 1}], [{'id': 1, 'username': 'PanelOwner', 'status': 0}],
                     [{'id': 1, 'username': 'PanelOwner', 'status': 1}] * 2]:
            with self.cursor(rows), self.TestClient(self.main.app) as client:
                self.assertEqual(404, self.lookup(client)[0].status_code)


if __name__ == '__main__':
    unittest.main()
