from contextlib import contextmanager
import hashlib
import hmac
import json
from pathlib import Path
import sys
import time
from types import SimpleNamespace
import unittest
from fastapi import FastAPI
from fastapi.testclient import TestClient

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from management_enrollment import bridge_signature
from panel_line_notes import install_line_notes_routes

SECRET = '15' * 32
REF = hmac.new(bytes.fromhex(SECRET), b'account-v1\nfixture-line', hashlib.sha256).hexdigest()


class PanelNotesTests(unittest.TestCase):
    def setUp(self):
        self.queries = []
        self.rows = [{'id': 946, 'username': 'fixture-line', 'member_id': 252,
                      'reseller_notes': 'Sam Taylor\nCall after 6pm', 'admin_notes': 'PRIVATE ADMIN', 'password': 'PRIVATE PASSWORD'}]
        outer = self
        class Cursor:
            def execute(self, sql, params): outer.queries.append((sql, params))
            def fetchall(self): return outer.rows
        @contextmanager
        def cursor(): yield Cursor()
        app = FastAPI()
        install_line_notes_routes(app, SimpleNamespace(management_enrollment_secret=SECRET, management_control_origin='https://control.example.test'), cursor)
        self.client = TestClient(app)

    def request(self, lines=None):
        body = json.dumps({'lines': lines if lines is not None else [{'id':'946','reseller_id':'252','account_ref':REF}]}, separators=(',', ':')).encode()
        timestamp, nonce = str(int(time.time())), 'b'*32
        headers = {'X-Management-Timestamp':timestamp, 'X-Management-Nonce':nonce,
                   'X-Management-Signature':bridge_signature(SECRET, 'line-notes-request-v1', timestamp, nonce, body)}
        response = self.client.post('/management/line-notes', content=body, headers=headers)
        if response.status_code == 200:
            envelope = response.json()
            self.assertEqual(bridge_signature(SECRET,'line-notes-result-v1',timestamp,nonce,envelope['payload'].encode()), envelope['signature'])
        return response

    def test_reads_actual_reseller_notes_and_subsequent_panel_edits(self):
        first = self.request()
        self.assertEqual('Sam Taylor\nCall after 6pm', json.loads(first.json()['payload'])['lines'][0]['notes'])
        self.assertNotIn('PRIVATE', first.text)
        self.assertNotIn('fixture-line', first.text)
        self.assertTrue(self.queries[0][0].startswith('SELECT id,username,member_id,reseller_notes '))
        self.assertEqual(('946',), self.queries[0][1])
        self.rows[0]['reseller_notes'] = ''
        self.assertEqual('', json.loads(self.request().json()['payload'])['lines'][0]['notes'])

    def test_changed_owner_or_account_identity_returns_no_notes(self):
        for line in [{'id':'946','reseller_id':'999','account_ref':REF}, {'id':'946','reseller_id':'252','account_ref':'a'*64}]:
            result = json.loads(self.request([line]).json()['payload'])['lines'][0]
            self.assertEqual('unavailable', result['state'])
            self.assertIsNone(result['notes'])

    def test_missing_line_never_fabricates_notes(self):
        self.rows = []
        self.assertIsNone(json.loads(self.request().json()['payload'])['lines'][0]['notes'])

    def test_oversized_notes_are_unavailable_without_truncating(self):
        self.rows[0]['reseller_notes'] = 'x' * 65536
        row = json.loads(self.request().json()['payload'])['lines'][0]
        self.assertEqual('unavailable', row['state'])
        self.assertIsNone(row['notes'])

    def test_unsigned_requests_do_not_query_panel(self):
        self.assertEqual(403, self.client.post('/management/line-notes', json={'lines':[]}).status_code)
        self.assertEqual([], self.queries)

    def test_batches_are_bounded_and_duplicates_rejected(self):
        line = {'id':'946','reseller_id':'252','account_ref':REF}
        for lines in [[], [line, line], [dict(line,id=str(i+1)) for i in range(26)]]:
            self.assertEqual(422, self.request(lines).status_code)
        self.assertEqual([], self.queries)


if __name__ == '__main__': unittest.main()
