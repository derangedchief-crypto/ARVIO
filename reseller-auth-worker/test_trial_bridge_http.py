import json
import os
from pathlib import Path
import tempfile
import time
import unittest
from unittest.mock import patch
from fastapi import FastAPI
from fastapi.testclient import TestClient
from test_branded_trials import module, Panel, request, PLAN


class BridgeHttpTest(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory(dir='/tmp/opencode');self.panel=Panel()
        self.service=module.TrialService(Path(self.tmp.name)/'private',self.panel);self.secret='e'*64
        with patch.dict(os.environ,{'BRANDED_TRIAL_SHARED_SECRET':self.secret,'BRANDED_TRIAL_STATE_DIR':str(Path(self.tmp.name)/'private'),'BRANDED_TRIAL_PROVISION_ENABLED':'true'}),patch.object(module,'TrialService',return_value=self.service):
            app=FastAPI();module.install_trial_routes(app,{})
        self.client=TestClient(app)
    def tearDown(self):self.client.close();self.tmp.cleanup()
    def send(self,body=None,stamp=None,key=None):
        raw=body if body is not None else json.dumps(request()).encode();stamp=str(int(time.time()) if stamp is None else stamp)
        headers={'X-Trial-Timestamp':stamp,'X-Trial-Signature':module.signature(key or self.secret,stamp,raw)}
        return self.client.post('/branded-trials/provision',content=raw,headers=headers)
    def test_server_signature_required_and_repeated_signed_request_is_idempotent(self):
        self.assertEqual(401,self.send(key='f'*64).status_code);self.assertEqual(401,self.send(stamp=1).status_code)
        first=self.send();second=self.send();self.assertEqual(200,first.status_code);self.assertEqual(first.json(),second.json());self.assertEqual(1,self.panel.creates)
    def test_oversized_and_malformed_inputs_cannot_provision(self):
        self.assertEqual(413,self.send(body=b'x'*8193).status_code);self.assertEqual(409,self.send(body=b'{').status_code);self.assertEqual(0,self.panel.creates)
    def test_missing_configuration_fails_closed(self):
        with patch.dict(os.environ,{'BRANDED_TRIAL_SHARED_SECRET':'','BRANDED_TRIAL_STATE_DIR':''}):
            app=FastAPI();module.install_trial_routes(app,{})
        with TestClient(app) as client:self.assertEqual(503,client.post('/branded-trials/provision',json=request()).status_code)
    def test_owner_creation_gate_defaults_off_without_disabling_signed_catalog(self):
        with patch.dict(os.environ,{'BRANDED_TRIAL_SHARED_SECRET':self.secret,'BRANDED_TRIAL_STATE_DIR':str(Path(self.tmp.name)/'private')}),patch.object(module,'TrialService',return_value=self.service):
            os.environ.pop('BRANDED_TRIAL_PROVISION_ENABLED',None)
            app=FastAPI();module.install_trial_routes(app,{})
        stamp=str(int(time.time()));raw=json.dumps(request()).encode()
        with TestClient(app) as client:
            headers={'X-Trial-Timestamp':stamp,'X-Trial-Signature':module.signature(self.secret,stamp,raw)}
            self.assertEqual(503,client.post('/branded-trials/provision',content=raw,headers=headers).status_code)
            self.assertEqual(0,self.panel.creates)
            raw=json.dumps({'reseller_id':'101'}).encode();headers['X-Trial-Signature']=module.signature(self.secret,stamp,raw,'branded-trial-plans-v1')
            with patch.object(module.ExistingPanelAdapter,'catalog',return_value={'reseller_id':'101','plans':[module.plan_document(PLAN)]}):
                self.assertEqual(200,client.post('/branded-trials/plans',content=raw,headers=headers).status_code)
            self.assertEqual(0,self.panel.creates)
    def test_catalog_and_status_use_distinct_read_only_signature_domains(self):
        stamp=str(int(time.time()));body=json.dumps({'reseller_id':'101'}).encode();headers={'X-Trial-Timestamp':stamp,'X-Trial-Signature':module.signature(self.secret,stamp,body,'branded-trial-plans-v1')}
        with patch.object(module.ExistingPanelAdapter,'catalog',return_value={'reseller_id':'101','plans':[module.plan_document(PLAN)]}):
            response=self.client.post('/branded-trials/plans',content=body,headers=headers);self.assertEqual(200,response.status_code)
        self.assertEqual(401,self.client.post('/branded-trials/provision',content=body,headers=headers).status_code);self.assertEqual(0,self.panel.creates)
        self.send();body=json.dumps({'reseller_id':'101','operation_id':'a'*32}).encode();headers['X-Trial-Signature']=module.signature(self.secret,stamp,body,'branded-trial-status-v1')
        response=self.client.post('/branded-trials/status',content=body,headers=headers);self.assertEqual(200,response.status_code);self.assertNotIn('account',response.json());self.assertEqual(1,self.panel.creates)


if __name__=='__main__':unittest.main()
