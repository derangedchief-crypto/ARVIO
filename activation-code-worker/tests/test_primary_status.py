from contextlib import contextmanager
import hashlib
import hmac
import json
from pathlib import Path
from types import SimpleNamespace
import sys
import unittest
from fastapi import FastAPI
from fastapi.testclient import TestClient

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from management_enrollment import bridge_signature
from primary_line_status import install_primary_status_routes, line_result

SECRET = '15' * 32
REF = hmac.new(bytes.fromhex(SECRET), b'account-v1\nfixture-primary', hashlib.sha256).hexdigest()


class PrimaryStatusTests(unittest.TestCase):
    def row(self, **changes):
        return dict(dict(username='fixture-primary', member_id=252, enabled=1, activated=1, exp_date=1100), **changes)

    def test_expired_trial_disabled_and_missing_lines_never_grant(self):
        for row in [None, self.row(exp_date=999), self.row(enabled=0), self.row(activated=0)]:
            self.assertFalse(line_result(row, '11', REF, SECRET, 1000)['active'])

    def test_positive_status_is_bounded_by_known_primary_expiry(self):
        result = line_result(self.row(), '11', REF, SECRET, 1000)
        self.assertTrue(result['active']); self.assertEqual(100, result['valid_for_seconds'])
        self.assertEqual(900, line_result(self.row(exp_date=0), '11', REF, SECRET, 1000)['valid_for_seconds'])
        self.assertFalse(line_result(self.row(), '11', 'a'*64, SECRET, 1000)['active'])

    def test_route_authentication_closed_inputs_and_nonce_bound_signature(self):
        import time
        queries = []
        row = self.row(exp_date=int(time.time())+300)
        class Cursor:
            def execute(self, sql, params): queries.append((sql, params))
            def fetchone(self): return row
        @contextmanager
        def cursor(): yield Cursor()
        app = FastAPI()
        install_primary_status_routes(app, SimpleNamespace(management_enrollment_secret=SECRET, management_control_origin='https://control.example.test'), cursor)
        client = TestClient(app)
        self.assertEqual(403, client.post('/management/primary-line-status', json={'customer_id':'11','account_ref':REF}).status_code)
        self.assertEqual([], queries)
        timestamp, nonce = str(int(time.time())), 'b'*32
        body = json.dumps({'customer_id':'11','account_ref':REF}, separators=(',', ':')).encode()
        headers = {'Content-Type':'application/json','X-Management-Timestamp':timestamp,'X-Management-Nonce':nonce,'X-Management-Signature':bridge_signature(SECRET,'primary-line-request-v1',timestamp,nonce,body)}
        response = client.post('/management/primary-line-status', content=body, headers=headers)
        self.assertEqual(200, response.status_code)
        envelope = response.json()
        self.assertEqual(bridge_signature(SECRET,'primary-line-result-v1',timestamp,nonce,envelope['payload'].encode()), envelope['signature'])
        self.assertTrue(json.loads(envelope['payload'])['active'])
        self.assertEqual(1,len(queries));self.assertTrue(queries[0][0].startswith('SELECT '));self.assertEqual(('11',),queries[0][1])
        self.assertNotIn('fixture-primary', envelope['payload'])

    def test_public_credential_check_rejects_expired_trials_and_wrong_passwords(self):
        import time
        row = self.row(exp_date=int(time.time())+25); row['password'] = 'fixture-password'
        class Cursor:
            def execute(self, sql, params):
                self.sql=sql; self.params=params
                assert sql.startswith('SELECT ') and params == ('fixture-primary',)
            def fetchall(self): return [row]
        @contextmanager
        def cursor(): yield Cursor()
        app=FastAPI();install_primary_status_routes(app, SimpleNamespace(management_enrollment_secret=SECRET,management_control_origin='https://control.example.test'),cursor)
        client=TestClient(app)
        result=client.post('/management/primary-verify',json={'username':'fixture-primary','password':'fixture-password'})
        self.assertEqual(200,result.status_code);self.assertTrue(result.json()['active']);self.assertLessEqual(result.json()['valid_for_seconds'],25)
        self.assertNotIn('fixture-password',result.text)
        result=client.post('/management/primary-verify',json={'username':'fixture-primary','password':'wrong'})
        self.assertFalse(result.json()['active'])
        row['exp_date']=int(time.time())-1
        result=client.post('/management/primary-verify',json={'username':'fixture-primary','password':'fixture-password'})
        self.assertFalse(result.json()['active']);self.assertEqual(0,result.json()['valid_for_seconds'])


if __name__ == '__main__': unittest.main()
