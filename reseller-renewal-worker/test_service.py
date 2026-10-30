import ast
import hashlib
import hmac
import importlib.util
import json
import logging
import os
from pathlib import Path
import secrets
import sys
import tempfile
import time
from types import SimpleNamespace,ModuleType
import unittest
from unittest.mock import patch
from zipfile import ZipFile
from fastapi.testclient import TestClient

ROOT=Path(__file__).resolve().parent.parent
sys.path.insert(0,str(ROOT/'reseller-auth-worker'))
from build_native import build,FUNCTIONS


class ServiceTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory(prefix='renewal-http-',dir='/tmp/opencode');self.state=Path(self.tmp.name)/'state';self.state.mkdir(mode=0o700)
        self.key=secrets.token_bytes(32)
        for name,data in [('renewal.key',self.key),('renewals.sqlite',b'')]:
            p=self.state/name;p.write_bytes(data);p.chmod(0o600)
        self.secret='a'*64;native=ModuleType('native_panel');native._start_tunnel_and_pool=lambda:None;native._stop_tunnel=lambda:None
        self.stub=patch.dict(sys.modules,{'native_panel':native});self.stub.start()
        self.env=patch.dict(os.environ,{'RENEWAL_BRIDGE_SECRET':self.secret,'RENEWAL_STATE_DIR':str(self.state),'RENEWAL_LEDGER_KEY_SHA256':hashlib.sha256(self.key).hexdigest()});self.env.start()
        spec=importlib.util.spec_from_file_location('renewal_http_'+secrets.token_hex(4),Path(__file__).with_name('main.py'));self.module=importlib.util.module_from_spec(spec);spec.loader.exec_module(self.module)
        self.module.adapter.journal_ready=lambda cur:None
        import contextlib
        @contextlib.contextmanager
        def cursor(**kwargs):yield None,SimpleNamespace(rollback=lambda:None)
        native.panel_db_cursor=cursor
        self.client=TestClient(self.module.app);self.client.__enter__()
    def tearDown(self):
        self.client.__exit__(None,None,None);self.env.stop();self.stub.stop();self.tmp.cleanup()
    def send(self,action,value=None,stamp=None,key=None,nonce=None):
        raw=json.dumps(value if value is not None else {}).encode();stamp=str(int(time.time()) if stamp is None else stamp);nonce=nonce or 'b'*32
        signature=hmac.new((key or self.secret).encode(),b'branded-renewal-request-v1\n'+stamp.encode()+b'\n'+nonce.encode()+b'\n'+raw,hashlib.sha256).hexdigest()
        r=self.client.post('/branded-renewals/'+action,content=raw,headers={'X-Renewal-Timestamp':stamp,'X-Renewal-Nonce':nonce,'X-Renewal-Signature':signature})
        return r,stamp,nonce
    def test_full_service_reports_real_capabilities_and_nonce_bound_status(self):
        r,stamp,nonce=self.send('status');self.assertEqual(200,r.status_code);e=r.json();value=json.loads(e['payload'])
        self.assertEqual('renewals',value['mode']);self.assertTrue(value['writes_enabled']);self.assertFalse(value['catalogue_only']);self.assertTrue(value['current_package_only']);self.assertEqual(1,value['atomic_renewal'])
        expected=hmac.new(self.secret.encode(),('branded-renewal-result-v1\n'+stamp+'\n'+nonce+'\n'+e['payload']).encode(),hashlib.sha256).hexdigest();self.assertEqual(expected,e['signature'])
        self.assertEqual(200,self.client.get('/health').status_code)
    def test_startup_failure_evidence_survives_without_private_exception_values(self):
        import asyncio
        async def start():
            async with self.module.lifespan(self.module.app):pass
        with patch.object(sys.modules['native_panel'],'_start_tunnel_and_pool',side_effect=RuntimeError('SYNTHETIC-PRIVATE-PASSWORD')):
            with self.assertRaises(RuntimeError):asyncio.run(start())
        path=self.state/'startup-status.json';report=json.loads(path.read_text())
        self.assertEqual('ssh-tunnel-and-database-pool',report['stage']);self.assertEqual('RuntimeError',report['error_type'])
        self.assertNotIn('SYNTHETIC',path.read_text());self.assertEqual(0,path.stat().st_mode&0o077)
        from branded_renewals import RenewalRejected
        with patch.object(self.module.adapter,'journal_ready',side_effect=RenewalRejected('Existing credit journal cannot safely hold the full operation receipt')):
            with self.assertRaises(RuntimeError):asyncio.run(start())
        report=json.loads(path.read_text());self.assertEqual('panel-transaction-journal-check',report['stage']);self.assertIn('credit journal',report['guard'])
        self.assertEqual(self.key,(self.state/'renewal.key').read_bytes())
    def test_no_auth_or_legacy_portal_or_trial_routes_are_exposed(self):
        for path in ['/create-line','/renew-line','/change-package','/terminate-line','/verify-login','/branded-trials/provision']:
            self.assertEqual(404,self.client.post(path,json={}).status_code)
    def test_invalid_signature_old_timestamp_and_invalid_paid_contract_do_not_renew(self):
        with patch.object(self.module,'AnchoredOperations') as ledger:
            self.assertEqual(403,self.send('renew',{},key='f'*64)[0].status_code)
            self.assertEqual(403,self.send('renew',{},stamp=1)[0].status_code)
            self.assertEqual(422,self.send('renew',{})[0].status_code)
            ledger.assert_not_called()
    def test_owned_catalogue_and_expiry_requests_use_new_service_adapter_only(self):
        with patch.object(self.module.adapter,'catalog',return_value={'reseller_id':'101','plans':[]}) as catalog:
            self.assertEqual(200,self.send('catalog',{'reseller_id':'101'})[0].status_code);catalog.assert_called_once_with('101')
            self.assertEqual(422,self.send('catalog',{'reseller_id':'101','username':'forged'})[0].status_code)
        with patch.object(self.module.adapter,'eligibility',return_value={'state':'expired'}) as eligibility:
            self.assertEqual(200,self.send('eligibility',{'reseller_id':'101','line_id':'999','primary_fingerprint':'d'*64})[0].status_code);eligibility.assert_called_once_with('101','999','d'*64)
    def test_renewal_login_is_authenticated_and_does_not_expose_credentials_or_renew(self):
        target={'reseller_id':'101','line_id':'999','primary_fingerprint':'d'*64}
        with patch.object(self.module.adapter,'identify',return_value=target) as identify,patch.object(self.module,'AnchoredOperations') as ledger:
            self.assertEqual(403,self.send('identify',{'activation_code':'123456','reseller_id':'101'},key='f'*64)[0].status_code)
            identify.assert_not_called()
            response=self.send('identify',{'activation_code':'123456','reseller_id':'101'})[0]
            self.assertEqual(200,response.status_code)
            self.assertEqual(target,json.loads(response.json()['payload']))
            self.assertNotIn('123456',response.text);ledger.assert_not_called()
    def test_missing_or_changed_original_state_is_held_without_automatic_recreation(self):
        key=self.state/'renewal.key';key.unlink()
        self.assertEqual(422,self.send('status')[0].status_code);self.assertFalse(key.exists())
        key.write_bytes(b'x'*32);key.chmod(0o600)
        self.assertEqual(422,self.send('status')[0].status_code);self.assertEqual(b'x'*32,key.read_bytes())
        key.write_bytes(self.key);key.chmod(0o600)
    def test_valid_paid_operation_reconciliation_and_funding_are_not_catalogue_only_blocks(self):
        spec={'operation_id':'a'*32,'brand_id':'b'*32,'reseller_id':'101','line_id':'999','package_id':7,'plan_fingerprint':'d'*64,'merchant_id':'merchant','payment_link_id':'link','primary_fingerprint':'c'*64,'current_package_only':True,'expected_expiry':1000}
        from branded_renewals import RenewalDeferred
        with patch.object(self.module,'AnchoredOperations') as ledger:
            ledger.return_value.request.return_value={'state':'needs_review','operation_id':spec['operation_id']}
            reply=self.send('renew',spec)[0];self.assertEqual(200,reply.status_code);self.assertEqual('reconcile_pending',json.loads(reply.json()['payload'])['state'])
            ledger.return_value.request.side_effect=RenewalDeferred('Synthetic funding pending')
            self.assertEqual(503,self.send('renew',spec)[0].status_code)
    def test_generated_helpers_have_no_legacy_app_or_secret_logging_side_effects(self):
        with ZipFile('/home/studio/Extreme Hub Tivimate Rework/new auth.zip') as z:source=z.read('new auth/main.py')
        code=build(source);tree=ast.parse(code)
        self.assertEqual(FUNCTIONS,{n.name for n in tree.body if isinstance(n,ast.FunctionDef)})
        cursor=next(n for n in tree.body if isinstance(n,ast.FunctionDef) and n.name=='panel_db_cursor');self.assertEqual('contextmanager',cursor.decorator_list[0].id)
        self.assertNotIn('FastAPI',code);self.assertNotIn('install_trial_routes',code);self.assertNotIn('FileHandler',code)
        with patch.dict(sys.modules,{'config':SimpleNamespace(settings=SimpleNamespace())}):
            namespace={'__name__':'private_native_test'};exec(compile(code,'frozen-native-helpers','exec'),namespace)
        record=logging.LogRecord('panel',logging.ERROR,'x',1,'password=%s',('SYNTHETIC-SECRET',),None)
        namespace['_PrivatePanelLog']().filter(record);self.assertNotIn('SYNTHETIC',record.getMessage());self.assertIsNone(record.exc_info)


if __name__=='__main__':unittest.main()
