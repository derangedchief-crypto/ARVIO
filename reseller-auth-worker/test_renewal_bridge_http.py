import hashlib
import hmac
import json
import os
import tempfile
import time
import unittest
from types import SimpleNamespace
from unittest.mock import patch
from fastapi import FastAPI
from fastapi.testclient import TestClient
from branded_renewals_runtime import install_renewal_routes,NativeRenewalAdapter


def guarded_route():
    # Capability inspection only; this fake is never invoked as a renewal.
    _branded_renewal_prepare()
    _branded_renewal_journal()


class RenewalBridgeHttpTests(unittest.TestCase):
    def test_missing_guard_or_config_keeps_paid_routes_unavailable(self):
        with patch.dict(os.environ,{},clear=True):
            app=FastAPI();install_renewal_routes(app,{})
            with TestClient(app) as client:
                self.assertFalse(client.get('/branded-renewals/health').json()['writes_enabled'])
                self.assertEqual(503,client.post('/branded-renewals/renew',json={}).status_code)

    def test_signed_catalog_is_nonce_bound_and_paid_writes_default_off(self):
        with tempfile.TemporaryDirectory(prefix='renewal-http-') as directory:
            key='a'*64
            with patch.dict(os.environ,{'BRANDED_RENEWAL_SHARED_SECRET':key,'BRANDED_RENEWAL_STATE_DIR':directory,'BRANDED_RENEWAL_WRITE_ENABLED':'false'}):
                app=FastAPI();install_renewal_routes(app,{'renew_line':guarded_route,'RenewLineRequest':SimpleNamespace(model_fields={'renewal_operation':None})},1)
            stamp=str(int(time.time()));nonce='b'*32;raw=b'{"reseller_id":"101"}'
            signature=hmac.new(key.encode(),b'branded-renewal-request-v1\n'+stamp.encode()+b'\n'+nonce.encode()+b'\n'+raw,hashlib.sha256).hexdigest()
            headers={'X-Renewal-Timestamp':stamp,'X-Renewal-Nonce':nonce,'X-Renewal-Signature':signature}
            with TestClient(app) as client,patch.object(NativeRenewalAdapter,'catalog',return_value={'reseller_id':'101','plans':[]}) as catalog:
                self.assertTrue(client.get('/branded-renewals/health').json()['configured'])
                self.assertEqual(403,client.post('/branded-renewals/catalog',content=raw).status_code)
                reply=client.post('/branded-renewals/catalog',content=raw,headers=headers)
                self.assertEqual(200,reply.status_code);catalog.assert_called_once_with('101')
                envelope=reply.json()
                expected=hmac.new(key.encode(),('branded-renewal-result-v1\n'+stamp+'\n'+nonce+'\n'+envelope['payload']).encode(),hashlib.sha256).hexdigest()
                self.assertEqual(expected,envelope['signature'])
                self.assertEqual(503,client.post('/branded-renewals/renew',content=raw,headers=headers).status_code)
                self.assertEqual(403,client.post('/branded-renewals/catalog',content=raw+b' ',headers=headers).status_code)


if __name__=='__main__':unittest.main()
