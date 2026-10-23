import concurrent.futures
import contextlib
import datetime
import importlib.util
import logging
from pathlib import Path
import tempfile
import time
import unittest
from unittest.mock import Mock

spec = importlib.util.spec_from_file_location('branded_trials',Path(__file__).with_name('branded_trials.py'))
module = importlib.util.module_from_spec(spec);spec.loader.exec_module(module)


def request(**changes):
    value={'operation_id':'a'*32,'brand_id':'b'*32,'reseller_id':'101','device_ref':'c'*64,
        'package_id':7,'first_name':'Test Customer','phone':'+15551234567'}
    value.update(changes);return value


class Panel:
    def __init__(self):
        self.rows={};self.creates=0;self.refreshes=0;self.ready=True;self.mode='ok';self.allowed=True
    def check(self,spec):
        if not self.allowed:raise module.TrialRejected('Unavailable')
    def create(self,spec):
        self.creates+=1
        if self.mode=='definite_failure':raise module.TrialRejected('Insufficient credits')
        if self.mode=='unknown_before_commit':raise OSError('Disconnected')
        line=str(self.creates);self.rows[spec['operation_id']]=line
        if self.mode=='lost_response':raise OSError('Lost commit acknowledgment')
        return line
    def find(self,spec):return self.rows.get(spec['operation_id'])
    def refresh(self,line):self.refreshes+=1;return self.ready
    def result(self,spec,line):return {'activation_code':'SYNTHETIC','username':'test','password':'fixture'}


class TrialLedgerTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(dir='/tmp/opencode');self.root=Path(self.temp.name)/'private'
        self.panel=Panel();self.service=module.TrialService(self.root,self.panel)
    def tearDown(self):self.temp.cleanup()
    def test_same_request_creates_once_and_does_not_replay_panel_save(self):
        a=self.service.request(request());b=self.service.request(request())
        self.assertEqual(a,b);self.assertEqual('prepared',a['state']);self.assertEqual(1,self.panel.creates);self.assertEqual(1,self.panel.refreshes)
    def test_customer_device_rule_is_reseller_scoped_not_brand_scoped(self):
        self.service.request(request())
        with self.assertRaises(module.TrialRejected):self.service.request(request(operation_id='d'*32,brand_id='e'*32))
        self.assertEqual('prepared',self.service.request(request(operation_id='f'*32,reseller_id='102'))['state'])
        self.assertEqual('prepared',self.service.request(request(operation_id='1'*32,phone='+15557654321'))['state'])
        self.assertEqual('prepared',self.service.request(request(operation_id='2'*32,device_ref='9'*64))['state'])
    def test_registration_failure_repairs_same_line(self):
        self.panel.ready=False;first=self.service.request(request());self.assertEqual('registration_pending',first['state'])
        self.panel.ready=True;second=self.service.request(request());self.assertEqual(first['line_id'],second['line_id']);self.assertEqual(1,self.panel.creates)
    def test_lost_commit_response_is_reconciled_not_recreated(self):
        self.panel.mode='lost_response';self.assertEqual('needs_review',self.service.request(request())['state'])
        self.assertEqual('prepared',self.service.request(request())['state']);self.assertEqual(1,self.panel.creates)
    def test_unknown_absent_result_never_blindly_retries_creation(self):
        self.panel.mode='unknown_before_commit'
        for _ in range(3):self.assertEqual('needs_review',self.service.request(request())['state'])
        self.assertEqual(1,self.panel.creates)
        with self.assertRaises(module.TrialRejected):self.service.request(request(operation_id='d'*32))
    def test_definite_precommit_failure_releases_unused_eligibility(self):
        self.panel.mode='definite_failure'
        with self.assertRaises(module.TrialRejected):self.service.request(request())
        self.panel.mode='ok';self.assertEqual('prepared',self.service.request(request(operation_id='d'*32))['state'])
        self.assertEqual('failed',self.service.request(request())['state'])
    def test_changed_request_under_same_key_is_rejected(self):
        self.service.request(request())
        with self.assertRaises(module.TrialRejected):self.service.request(request(first_name='Different'))
    def test_parallel_process_style_service_instances_share_one_reservation(self):
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
            results=list(pool.map(lambda _:module.TrialService(self.root,self.panel).request(request()),range(4)))
        self.assertTrue(all(r['line_id']==results[0]['line_id'] for r in results));self.assertEqual(1,self.panel.creates)
    def test_contact_is_in_plain_panel_notes_not_worker_ledger(self):
        notes=module.ExistingPanelAdapter.notes(request());self.assertIn('Test Customer',notes);self.assertIn('+15551234567',notes)
        self.service.request(request());raw=(self.root/'trials.sqlite').read_bytes();self.assertNotIn(b'+15551234567',raw);self.assertNotIn(b'Test Customer',raw)
        self.assertEqual(0o600,(self.root/'identity.key').stat().st_mode&0o777)
    def test_unverified_phone_is_validated_without_otp_and_markup_is_rejected(self):
        self.assertEqual('+15551234567',module.validate(request())['phone'])
        for changes in [{'first_name':'Name\nInjected notes'}, {'phone':'555-123'}, {'reseller_id':'101 OR 1=1'},{'package_id':True},{'update_url':'https://bad.test'}]:
            with self.assertRaises(module.TrialRejected):module.validate(request(**changes))
    def test_disabled_reseller_policy_stops_repeat_result_delivery(self):
        self.service.request(request());self.panel.allowed=False
        with self.assertRaises(module.TrialRejected):self.service.request(request())
        self.assertEqual(1,self.panel.creates)
    def test_missing_original_identity_key_does_not_silently_reset_trial_history(self):
        self.service.request(request());(self.root/'identity.key').unlink()
        with self.assertRaises(module.TrialRejected):self.service.request(request(operation_id='d'*32))
        self.assertEqual(1,self.panel.creates)


class PlanValidationTest(unittest.TestCase):
    def test_native_panel_response_logging_is_redacted_for_trials_only(self):
        logger=logging.getLogger('synthetic-trial-log');logger.setLevel(logging.INFO);records=[]
        class Capture(logging.Handler):
            def emit(self,record):records.append(record.getMessage())
        handler=Capture();logger.addHandler(handler)
        def refresh(line):logger.warning('Panel response Phone: +15551234567');return True
        try:
            adapter=module.ExistingPanelAdapter({'log':logger,'_overcast_refresh_customer':refresh});adapter.refresh('999')
            logger.info('Ordinary portal log')
            self.assertNotIn('+15551234567',' '.join(records));self.assertEqual('Ordinary portal log',records[-1])
        finally:logger.removeHandler(handler)
    def test_legacy_and_new_native_refresh_share_a_process_lock(self):
        active=0;peak=0
        def refresh(line):
            nonlocal active,peak
            active+=1;peak=max(peak,active);time.sleep(0.01);active-=1;return True
        worker={'_overcast_refresh_customer':refresh};module.serialize_native_refresh(worker)
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:list(pool.map(worker['_overcast_refresh_customer'],range(8)))
        self.assertEqual(1,peak)
    def adapter(self):
        cur,conn=Mock(),Mock();cur.lastrowid=999
        @contextlib.contextmanager
        def cursor(**kwargs):yield cur,conn
        owner={'id':101,'username':'TEST','credits':'5','status':1}
        plan={'id':7,'type':1,'credits':'0','duration':24,'duration_period':'hours','bouquets':'[10,20]','max_connections':1}
        cur.fetchone.side_effect=[owner,plan,None];cur.fetchall.return_value=[]
        credit=Mock();worker={'panel_db_cursor':cursor,'_reseller_package_scope':lambda c,n:(1,None),'_package_allowed':lambda *a:True,
            '_utcnow':lambda:datetime.datetime.now(datetime.timezone.utc),'_add_duration':lambda now,value,period:now+datetime.timedelta(hours=value),'_set_credits':credit}
        return module.ExistingPanelAdapter(worker),cur,conn,credit
    def test_actual_adapter_insert_uses_locked_owner_and_contact_notes_with_one_credit_commit(self):
        adapter,cur,conn,credit=self.adapter();self.assertEqual('999',adapter.create(request()))
        insert=next(call.args for call in cur.execute.call_args_list if call.args[0].startswith('INSERT INTO customers'))
        self.assertEqual(insert[0].count('%s'),len(insert[1]));self.assertEqual(101,insert[1][0]);self.assertEqual(16,len(insert[1][1]));self.assertEqual(24,len(insert[1][2]))
        self.assertIn('Phone: +15551234567',insert[1][7]);self.assertIn('Test Customer',insert[1][7]);credit.assert_called_once();conn.commit.assert_called_once()
    def test_adapter_commit_error_remains_uncertain_not_a_definitive_rejection(self):
        adapter,cur,conn,credit=self.adapter();conn.commit.side_effect=OSError('Lost connection')
        with self.assertRaises(OSError):adapter.create(request())
        conn.commit.assert_called_once()
    def test_single_line_result_rejects_wrong_owner_even_if_a_driver_returns_it(self):
        adapter,cur,conn,credit=self.adapter();cur.fetchone.side_effect=None;cur.fetchone.return_value={'id':999,'member_id':102,'package_id':7,'is_trial':1,'enabled':1,'reseller_notes':adapter.notes(request())}
        with self.assertRaises(module.TrialRejected):adapter.result(request(),'999')
    def test_adapter_requires_active_reseller_and_enabled_trial_plan_with_nonnegative_price(self):
        worker={'_reseller_package_scope':lambda cur,name:(1,None),'_package_allowed':lambda plan,group,overrides:True}
        adapter=module.ExistingPanelAdapter(worker)
        owner={'id':101,'username':'TEST','credits':'5','status':1}
        plan={'id':7,'type':1,'credits':'0','duration':24,'duration_period':'hours'}
        for changed_owner,changed_plan in [(dict(owner,status=0),plan),(owner,None),(owner,dict(plan,type=0)),(owner,dict(plan,credits='-1')),(owner,dict(plan,duration=0))]:
            cur=Mock();cur.fetchone.side_effect=[changed_owner,changed_plan]
            with self.assertRaises(module.TrialRejected):adapter.owner_plan(cur,request())
        cur=Mock();cur.fetchone.side_effect=[owner,plan];self.assertEqual(0,adapter.owner_plan(cur,request())[2])
    def test_bridge_signature_is_domain_separated_and_covers_body_and_timestamp(self):
        secret='a'*64;raw=b'{"operation_id":"test"}'
        self.assertNotEqual(module.signature(secret,'123',raw),module.signature(secret,'124',raw))
        self.assertNotEqual(module.signature(secret,'123',raw),module.signature(secret,'123',raw+b' '))


if __name__=='__main__':unittest.main()
