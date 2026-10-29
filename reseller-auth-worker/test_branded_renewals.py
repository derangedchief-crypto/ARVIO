import concurrent.futures
import json
import os
from pathlib import Path
import sqlite3
import tempfile
import threading
import unittest
from branded_renewals import RenewalOperations,RenewalRejected


def spec(**changes):
    return dict({'operation_id':'a'*32,'brand_id':'b'*32,'reseller_id':'101','line_id':'999','package_id':7,
        'plan_fingerprint':'c'*64,'merchant_id':'merchant-A','payment_link_id':'fixture-link'},**changes)


class FakePanel:
    capabilities={'atomic_renewal':1,'unique_payment_reference':1}
    def __init__(self):self.calls=0;self.commits={};self.refreshes=0;self.fail_after_commit=False;self.ready=True;self.uncertain=False;self.lock=threading.Lock()
    def validate(self,value):pass
    def renew_once(self,value,fingerprint,payment_hash):
        with self.lock:
            self.calls+=1
            if value['operation_id'] not in self.commits:
                self.commits[value['operation_id']]={**{k:value[k] for k in ['operation_id','reseller_id','line_id','package_id']},'expires_at':2000000000}
            if self.fail_after_commit:raise TimeoutError('Synthetic lost reply')
            return self.commits[value['operation_id']]
    def reconcile(self,value,*_):return None if self.uncertain else self.commits.get(value['operation_id'],{'state':'not_applied'})
    def refresh_and_verify(self,value,receipt):self.refreshes+=1;return self.ready


class RenewalOperationTests(unittest.TestCase):
    def test_duplicate_callback_and_process_restart_do_not_add_another_period(self):
        with tempfile.TemporaryDirectory() as d:
            panel=FakePanel();first=RenewalOperations(d,panel).request(spec())
            self.assertEqual(first,RenewalOperations(d,panel).request(spec()));self.assertEqual(1,panel.calls);self.assertEqual(1,panel.refreshes)
    def test_lost_commit_reply_reconciles_without_another_credit_charge(self):
        with tempfile.TemporaryDirectory() as d:
            panel=FakePanel();panel.fail_after_commit=True
            self.assertEqual('needs_review',RenewalOperations(d,panel).request(spec())['state'])
            self.assertEqual('fulfilled',RenewalOperations(d,panel).request(spec())['state']);self.assertEqual(1,panel.calls)
    def test_native_refresh_failure_retries_only_refresh(self):
        with tempfile.TemporaryDirectory() as d:
            panel=FakePanel();panel.ready=False;service=RenewalOperations(d,panel)
            self.assertEqual('refresh_pending',service.request(spec())['state']);panel.ready=True
            self.assertEqual('fulfilled',service.request(spec())['state']);self.assertEqual(1,panel.calls);self.assertEqual(2,panel.refreshes)
    def test_uncertain_reconciliation_never_blindly_retries_renewal(self):
        with tempfile.TemporaryDirectory() as d:
            panel=FakePanel();panel.fail_after_commit=True;panel.uncertain=True;service=RenewalOperations(d,panel)
            service.request(spec());self.assertEqual('needs_review',service.request(spec())['state']);self.assertEqual(1,panel.calls)
    def test_same_verified_payment_cannot_fund_another_order(self):
        with tempfile.TemporaryDirectory() as d:
            panel=FakePanel();service=RenewalOperations(d,panel);service.request(spec())
            with self.assertRaises(RenewalRejected):service.request(spec(operation_id='d'*32))
            self.assertEqual(1,panel.calls)
    def test_same_order_cannot_change_its_line_package_reseller_or_merchant(self):
        with tempfile.TemporaryDirectory() as d:
            service=RenewalOperations(d,FakePanel());service.request(spec())
            for change in [{'line_id':'1000'},{'package_id':8},{'reseller_id':'102'},{'merchant_id':'merchant-B'}]:
                with self.assertRaises(RenewalRejected):service.request(spec(**change))
    def test_parallel_requests_for_one_order_execute_one_renewal(self):
        with tempfile.TemporaryDirectory() as d:
            panel=FakePanel()
            with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
                results=list(pool.map(lambda _:RenewalOperations(d,panel).request(spec()),range(2)))
            self.assertEqual(results[0],results[1]);self.assertEqual(1,panel.calls)
    def test_legacy_worker_without_atomic_marker_capability_is_never_called(self):
        with tempfile.TemporaryDirectory() as d:
            panel=FakePanel();panel.capabilities={}
            with self.assertRaises(RenewalRejected):RenewalOperations(d,panel).request(spec())
            self.assertEqual(0,panel.calls);self.assertEqual([],os.listdir(d))
    def test_missing_original_key_refuses_to_reset_operation_history(self):
        with tempfile.TemporaryDirectory() as d:
            service=RenewalOperations(d,FakePanel());service.request(spec());Path(d,'renewal.key').unlink()
            with self.assertRaises(RenewalRejected):service.request(spec())
            self.assertTrue(Path(d,'renewals.sqlite').exists())
    def test_corrupt_committed_receipt_cannot_refresh_another_line(self):
        with tempfile.TemporaryDirectory() as d:
            panel=FakePanel();service=RenewalOperations(d,panel);service.request(spec())
            with sqlite3.connect(Path(d,'renewals.sqlite')) as db:
                receipt=dict(panel.commits['a'*32],line_id='1000')
                db.execute('UPDATE operations SET receipt=?',(json.dumps(receipt),))
            with self.assertRaises(RenewalRejected):service.request(spec())
            self.assertEqual(1,panel.calls);self.assertEqual(1,panel.refreshes)

if __name__=='__main__':unittest.main()
