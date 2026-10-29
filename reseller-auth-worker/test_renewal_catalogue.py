import ast
import contextlib
import copy
import hashlib
import hmac
import json
import os
import re
from pathlib import Path
import subprocess
import tempfile
import time
import unittest
from unittest.mock import patch
from zipfile import ZipFile
from fastapi import FastAPI,HTTPException
from fastapi.testclient import TestClient
from branded_renewal_catalogue import Catalogue,install_catalogue_routes
from catalogue_source import BASE_SHA,HOOK,TRIAL_HOOK,transform
from renewal_packages import package_document
from branded_renewals_runtime import plan_document

ROOT=Path(__file__).resolve().parent.parent
PLAN={'id':7,'package_name':'Provider monthly','duration':1,'duration_value':0,'duration_period':'months','max_connections':2,'credits':'2.00','bouquets':'[10]','type':0,'enabled':1,'activated':1}


class Panel:
    def __init__(self):
        self.sql=[];self.rollbacks=0;self.owner={'id':101,'username':'reseller','status':1,'credits':'10'};self.rows=[copy.deepcopy(PLAN),dict(PLAN,id=8),dict(PLAN,id=9,type=1)]
        def forbidden(*args,**kwargs):raise AssertionError('A write function was called')
        self.worker={'panel_db_cursor':self.cursor,'_reseller_package_scope':lambda cur,user:(2,None),'_package_allowed':lambda p,g,o:p['id']==7,'renew_line':forbidden,'_set_credits':forbidden,'_overcast_refresh_customer':forbidden}
    @contextlib.contextmanager
    def cursor(self,autocommit=True):
        assert autocommit is False
        panel=self
        class Cursor:
            def execute(self,sql,args=()):
                panel.sql.append((sql,args));self.sql=sql;self.args=args
                assert sql=='START TRANSACTION READ ONLY' or sql.startswith('SELECT ')
            def fetchone(self):return panel.owner if self.args==(str(panel.owner['id']),) else None
            def fetchall(self):return [p for p in panel.rows if p['enabled']==1 and p['activated']==1 and p['type']!=1]
        class Connection:
            def rollback(self):panel.rollbacks+=1
        yield Cursor(),Connection()


class CatalogueTests(unittest.TestCase):
    def setUp(self):
        self.panel=Panel();self.key='a'*64;app=FastAPI()
        @app.get('/health')
        def existing_health():return {'original':True}
        @app.post('/branded-trials/plans')
        def original_trial():raise HTTPException(401,'Existing authentication')
        self.original=dict(self.panel.worker)
        with patch.dict(os.environ,{'BRANDED_RENEWAL_SHARED_SECRET':self.key,'BRANDED_RENEWAL_WRITE_ENABLED':'true'}):install_catalogue_routes(app,self.panel.worker)
        self.client=TestClient(app)
    def tearDown(self):self.client.close()
    def send(self,action='catalog',raw=b'{"reseller_id":"101"}',key=None,stamp=None,nonce=None):
        stamp=str(int(time.time()) if stamp is None else stamp);nonce=nonce or 'b'*32
        sig=hmac.new((key or self.key).encode(),b'branded-renewal-request-v1\n'+stamp.encode()+b'\n'+nonce.encode()+b'\n'+raw,hashlib.sha256).hexdigest()
        headers={'X-Renewal-Timestamp':stamp,'X-Renewal-Nonce':nonce,'X-Renewal-Signature':sig}
        return self.client.post('/branded-renewals/'+action,content=raw,headers=headers),stamp,nonce
    def test_real_scope_credit_cost_and_fingerprint_are_read_in_a_readonly_transaction(self):
        reply,stamp,nonce=self.send();self.assertEqual(200,reply.status_code)
        envelope=reply.json();value=json.loads(envelope['payload'])
        self.assertTrue(value['catalogue_only']);self.assertFalse(value['writes_enabled']);self.assertEqual([7],[p['id'] for p in value['plans']]);self.assertEqual('2',value['plans'][0]['credits'])
        expected=hmac.new(self.key.encode(),('branded-renewal-result-v1\n'+stamp+'\n'+nonce+'\n'+envelope['payload']).encode(),hashlib.sha256).hexdigest()
        self.assertEqual(expected,envelope['signature']);self.assertEqual(1,self.panel.rollbacks);self.assertEqual('START TRANSACTION READ ONLY',self.panel.sql[0][0])
    def test_write_flag_never_enables_checkout_or_renewal_or_calls_database(self):
        self.assertFalse(self.client.get('/branded-renewals/health').json()['writes_enabled'])
        for action in ['renew','eligibility']:
            self.assertEqual(503,self.send(action)[0].status_code)
        self.assertEqual([],self.panel.sql);self.assertEqual(self.original,self.panel.worker)
    def test_signed_status_has_no_database_access_and_original_routes_are_preserved(self):
        result=self.send('status',b'{}')[0];self.assertEqual(200,result.status_code)
        self.assertFalse(json.loads(result.json()['payload'])['writes_enabled']);self.assertEqual([],self.panel.sql)
        self.assertTrue(self.client.get('/health').json()['original']);self.assertEqual(401,self.client.post('/branded-trials/plans',json={}).status_code)
    def test_missing_secret_never_opens_catalogue_or_renewal_routes(self):
        app=FastAPI()
        with patch.dict(os.environ,{'BRANDED_RENEWAL_SHARED_SECRET':''}):install_catalogue_routes(app,self.panel.worker)
        with TestClient(app) as client:
            self.assertFalse(client.get('/branded-renewals/health').json()['configured'])
            self.assertEqual(503,client.post('/branded-renewals/catalog',json={'reseller_id':'101'}).status_code)
            self.assertEqual(503,client.post('/branded-renewals/renew',json={}).status_code)
        self.assertEqual([],self.panel.sql)
    def test_forged_stale_malformed_and_oversized_requests_cannot_read_packages(self):
        self.assertEqual(403,self.send(key='f'*64)[0].status_code);self.assertEqual(403,self.send(stamp=1)[0].status_code)
        for raw in [b'{',b'[]',b'{"reseller_id":"101","line_id":"999"}',b'{"reseller_id":101}']:
            self.assertEqual(422,self.send(raw=raw)[0].status_code)
        self.assertEqual(413,self.send(raw=b'x'*8193)[0].status_code);self.assertEqual([],self.panel.sql)
    def test_missing_or_suspended_reseller_is_not_given_someone_elses_packages(self):
        self.assertEqual(422,self.send(raw=b'{"reseller_id":"102"}')[0].status_code)
        self.panel.owner['status']=0;self.assertEqual(422,self.send()[0].status_code);self.assertEqual(2,self.panel.rollbacks)
    def test_catalogue_failure_always_rolls_back_and_never_uses_mutating_fallbacks(self):
        self.panel.rows[0]['credits']='NaN';self.assertEqual(422,self.send()[0].status_code);self.assertEqual(1,self.panel.rollbacks)
    def test_shared_pure_package_format_preserves_existing_paid_fingerprints(self):
        self.assertEqual(plan_document(PLAN),package_document(PLAN))
    def test_source_transform_accepts_only_reviewed_worker_and_exact_trial_hook(self):
        with ZipFile('/home/studio/Extreme Hub Tivimate Rework/new auth.zip') as z:source=z.read('new auth/main.py')
        self.assertEqual(BASE_SHA,hashlib.sha256(source).hexdigest())
        for value in [source,source+TRIAL_HOOK]:
            candidate=transform(value);self.assertEqual(value,candidate[:-len(HOOK)]);self.assertEqual(candidate,transform(candidate))
        for value in [source+b'\n# owner modification\n',source+TRIAL_HOOK+b'\n# unknown\n']:
            with self.assertRaises(ValueError):transform(value)
    def test_delivery_bundle_has_no_original_config_keys_trial_module_or_paid_adapter(self):
        with tempfile.TemporaryDirectory(dir='/tmp/opencode') as folder:
            target=Path(folder)/'read.zip';subprocess.run(['python3',str(ROOT/'reseller-auth-worker/package-catalogue.py'),str(target)],check=True,capture_output=True)
            with ZipFile(target) as z:
                self.assertEqual({'install-catalogue.sh','catalogue_source.py','branded_renewal_catalogue.py','renewal_packages.py','CATALOGUE-ONLY.md','LICENSE','MANIFEST.sha256'},set(z.namelist()))
                subprocess.run(['bash','-n'],input=z.read('install-catalogue.sh'),check=True)
                for line in z.read('MANIFEST.sha256').decode().splitlines():
                    sha,name=line.split('  ',1);self.assertEqual(sha,hashlib.sha256(z.read(name)).hexdigest())
                module=z.read('branded_renewal_catalogue.py').decode()
                for forbidden in ['branded_renewals','RenewalOperations','_set_credits','_overcast_refresh_customer']:
                    self.assertNotIn(forbidden,module)
                script=z.read('install-catalogue.sh').decode();self.assertIn('trap rollback EXIT',script);self.assertNotIn('php-fpm',script);self.assertNotIn('BRANDED_TRIAL_SHARED_SECRET',script)
                for block in re.findall(r"<<'PY'\n(.*?)\nPY",script,re.S):ast.parse(block)


if __name__=='__main__':unittest.main()
