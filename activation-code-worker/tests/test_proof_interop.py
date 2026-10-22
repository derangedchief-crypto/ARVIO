import hashlib
import hmac
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'activation-code-worker'))
from management_enrollment import issue_proof, bridge_signature


class PhpInteropTests(unittest.TestCase):
    def test_python_worker_assertion_verifies_in_actual_php_backend(self):
        with tempfile.TemporaryDirectory(prefix='control-proof-interop-', dir='/tmp/opencode') as directory:
            env = {**os.environ, 'CONTROL_INTEROP_ROOT': directory}
            command = ['bash', 'control-center/dev/php.sh', 'control-center/tests/proof-interop.php']
            key = subprocess.check_output(command + ['key'], cwd=ROOT, env=env, text=True).strip()
            token = issue_proof(key, 'https://control.example.test', 'fixture-user', 'a' * 32,
                                'b' * 64, 'com.arvio.tv.test', 'c' * 64, now=1000)
            verified = json.loads(subprocess.check_output(command + ['verify'], input=token, cwd=ROOT, env=env, text=True))
            self.assertEqual('extreme-activation', verified['issuer'])
            self.assertEqual('a' * 32, verified['install'])
            self.assertEqual('b' * 64, verified['key'])
            self.assertEqual('c' * 64, verified['challenge'])
            self.assertEqual('com.arvio.tv.test', verified['package'])
            self.assertEqual(hmac.new(bytes.fromhex(key), b'account-v1\nfixture-user', hashlib.sha256).hexdigest(), verified['sub'])
            panel = {'customer_id': '2001', 'reseller_id': '1001', 'reseller_username': 'PanelOwner'}
            owned = issue_proof(key, 'https://control.example.test', 'fixture-user', 'a' * 32,
                                'b' * 64, 'com.arvio.tv.test', 'c' * 64, now=1000, ownership=panel)
            verified = json.loads(subprocess.check_output(command + ['verify'], input=owned, cwd=ROOT, env=env, text=True))
            self.assertEqual(panel, verified['panel'])
            for domain in ['reseller-lookup-v1', 'reseller-result-v1']:
                fixture = {'domain': domain, 'timestamp': '1792630000', 'nonce': 'a' * 32, 'payload': '{"username":"PanelOwner"}'}
                signature = subprocess.check_output(command + ['bridge-signature'], input=json.dumps(fixture), cwd=ROOT, env=env, text=True).strip()
                self.assertEqual(bridge_signature(key, domain, fixture['timestamp'], fixture['nonce'], fixture['payload'].encode()), signature)
