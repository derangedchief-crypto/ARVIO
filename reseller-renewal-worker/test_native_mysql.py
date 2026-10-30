"""REAL isolated MariaDB checks. Never imports old main/config, tunnels, or HTTP.
Only selected reviewed native functions execute against a fresh socket-only DB.
"""
import ast
import calendar
import concurrent.futures
import contextlib
import datetime
from decimal import Decimal
import hashlib
import logging
import os
from pathlib import Path
import re
import secrets
import sys
import tempfile
import time
import unittest
from zipfile import ZipFile
import mysql.connector

ROOT=Path(__file__).resolve().parent.parent
sys.path.insert(0,str(ROOT/'reseller-auth-worker'))
from adapter import RenewalAdapter
from build_native import build
from branded_renewals import RenewalOperations,RenewalRejected
from renewal_packages import package_document

SOCKET=os.environ.get('RENEWAL_TEST_SOCKET','')
FUNCTIONS={'_utcnow','_from_ts','_add_months','_add_duration','_renewal_window','_log_credit_change','_credit_trigger_active','_set_credits','_lock_reseller_row','_lock_line_row','_parse_id_list','_reseller_package_scope','_package_allowed'}


@unittest.skipUnless(SOCKET,'Use run-tests.py for the isolated real-SQL suite')
class NativeMysqlTests(unittest.TestCase):
    def setUp(self):
        self.assertTrue(SOCKET.startswith('/tmp/opencode/'));self.assertTrue(Path(SOCKET).is_socket())
        self.db='renewal_test_'+secrets.token_hex(6);self.user=os.environ.get('USER','studio')
        with self.server() as conn:
            cur=conn.cursor();cur.execute('CREATE DATABASE `'+self.db+'` CHARACTER SET utf8mb4');cur.close()
        self.tmp=tempfile.TemporaryDirectory(prefix='native-renewal-',dir='/tmp/opencode');self.state=Path(self.tmp.name)/'state'
        self.refreshes=0;self.ready=True
        with ZipFile('/home/studio/Extreme Hub Tivimate Rework/new auth.zip') as z:code=build(z.read('new auth/main.py'))
        nodes=[n for n in ast.parse(code).body if isinstance(n,ast.FunctionDef) and n.name in FUNCTIONS]
        scope={'datetime':datetime,'calendar':calendar,'Decimal':Decimal,'json':__import__('json'),'time':time,'log':logging.getLogger('native-test'),'_trigger_state':{'at':0.0,'active':None}}
        exec(compile(ast.Module(body=nodes,type_ignores=[]),'reviewed-native-test','exec'),scope)
        scope['panel_db_cursor']=self.cursor;scope['_overcast_refresh_customer']=self.refresh
        self.native=scope;self.adapter=RenewalAdapter(scope)
        self.schema()
    def server(self):return mysql.connector.connect(unix_socket=SOCKET,user=self.user,autocommit=True,use_pure=True)
    @contextlib.contextmanager
    def cursor(self,autocommit=True):
        conn=mysql.connector.connect(unix_socket=SOCKET,user=self.user,database=self.db,autocommit=autocommit,use_pure=True)
        cur=conn.cursor(dictionary=True,buffered=True)
        try:yield cur,conn
        finally:
            if not autocommit:conn.rollback()
            cur.close();conn.close()
    def sql(self,query,args=()):
        with self.cursor() as (cur,conn):
            cur.execute(query,args)
            return cur.fetchall() if cur.with_rows else []
    def one(self,query,args=()):return self.sql(query,args)[0]
    def refresh(self,line):self.refreshes+=1;return self.ready
    def schema(self):
        statements=[
            'CREATE TABLE users (id INT PRIMARY KEY,username VARCHAR(150),credits DOUBLE,status INT,owner_id INT,member_group_id INT,override_packages TEXT) ENGINE=InnoDB',
            'CREATE TABLE users_packages (id INT PRIMARY KEY,package_name VARCHAR(80),credits DOUBLE,duration INT,duration_period VARCHAR(12),duration_value INT,bouquets TEXT,max_connections INT,`groups` TEXT,type INT,enabled INT,activated INT) ENGINE=InnoDB',
            'CREATE TABLE customers (id BIGINT PRIMARY KEY,member_id INT,username VARCHAR(100),password VARCHAR(100),activation_code VARCHAR(6),exp_date BIGINT,enabled INT,activated INT,package_id INT,duration INT,duration_period VARCHAR(12),duration_value INT,max_connections INT,is_trial INT,bouquet TEXT,allowed_outputs TEXT,parent_pin VARCHAR(20),reseller_notes TEXT,admin_notes TEXT,updated DATETIME) ENGINE=InnoDB',
            'CREATE TABLE users_credits_log (id BIGINT AUTO_INCREMENT PRIMARY KEY,user_id INT,amount DOUBLE,balance_before DOUBLE,balance_after DOUBLE,reason VARCHAR(50),actor_id INT,ref_type VARCHAR(50),ref_id BIGINT,note VARCHAR(255),created_at BIGINT) ENGINE=InnoDB',
        ]
        for statement in statements:self.sql(statement)
        self.sql("INSERT INTO users VALUES (101,'reseller',10,1,0,2,NULL),(102,'other',10,1,0,2,NULL)")
        self.sql("INSERT INTO users_packages VALUES (7,'Monthly',2,1,'months',0,'[10]',1,'[2]',0,1,1),(8,'Yearly',5,1,'years',0,'[11]',1,'[2]',0,1,1)")
        expired=int(time.time())-3600
        self.sql("INSERT INTO customers VALUES (999,101,'user','pass','123456',%s,1,1,7,1,'months',0,3,0,'[10]','[1,2,3,4]','2468','KEEP CONTACT NOTES','KEEP ADMIN',NOW()),(1000,102,'user2','pass2','654321',%s,1,1,7,1,'months',0,1,0,'[10]','[1]','1234','OTHER NOTES','OTHER ADMIN',NOW())",(expired,expired))
        self.before=self.one('SELECT * FROM customers WHERE id=999')
    def tearDown(self):
        if hasattr(self,'db'):
            self.assertRegex(self.db,r'^renewal_test_[a-f0-9]{12}$')
            with self.server() as conn:cur=conn.cursor();cur.execute('DROP DATABASE `'+self.db+'`');cur.close()
        if hasattr(self,'tmp'):self.tmp.cleanup()
    def spec(self,**extra):
        p=package_document(self.one('SELECT * FROM users_packages WHERE id=7'))
        return dict({'operation_id':'a'*32,'brand_id':'b'*32,'reseller_id':'101','line_id':'999','package_id':7,'plan_fingerprint':p['fingerprint'],'merchant_id':'merchant','payment_link_id':'link','primary_fingerprint':hashlib.sha256(b'user\npass').hexdigest(),'current_package_only':True,'expected_expiry':self.before['exp_date']},**extra)
    def run_operation(self,spec=None,adapter=None):return RenewalOperations(self.state,adapter or self.adapter).request(spec or self.spec())
    def preserved(self,activated=None):
        row=self.one('SELECT * FROM customers WHERE id=999')
        for field in ['username','password','activation_code','reseller_notes','admin_notes','bouquet','allowed_outputs','max_connections','parent_pin','package_id','enabled','activated']:
            self.assertEqual(activated if field=='activated' and activated is not None else self.before[field],row[field],field)
        return row
    def untouched(self):
        self.assertEqual(self.before['exp_date'],self.preserved()['exp_date']);self.assertEqual(10,self.one('SELECT credits FROM users WHERE id=101')['credits']);self.assertEqual(0,self.one('SELECT COUNT(*) AS n FROM users_credits_log')['n'])
    def trigger(self,drop_note=False):
        note='NULL' if drop_note else '@oc_credit_note'
        self.sql("CREATE TRIGGER credit_audit AFTER UPDATE ON users FOR EACH ROW BEGIN IF NEW.credits<>OLD.credits THEN INSERT INTO users_credits_log (user_id,amount,balance_before,balance_after,reason,actor_id,ref_type,ref_id,note,created_at) VALUES (NEW.id,NEW.credits-OLD.credits,OLD.credits,NEW.credits,COALESCE(@oc_credit_reason,'adjustment'),@oc_credit_actor,@oc_credit_ref_type,@oc_credit_ref_id,"+note+",UNIX_TIMESTAMP()); END IF; END")
    def test_current_line_extends_once_preserving_credentials_notes_and_connection_override(self):
        first=self.run_operation();self.assertEqual('fulfilled',first['state']);self.assertEqual(first,self.run_operation())
        row=self.preserved();self.assertEqual(first['expires_at'],row['exp_date']);self.assertGreater(row['exp_date'],time.time());self.assertEqual(8,self.one('SELECT credits FROM users WHERE id=101')['credits'])
        logs=self.sql('SELECT * FROM users_credits_log');self.assertEqual(1,len(logs));self.assertTrue(logs[0]['note'].startswith('BRN1|'));self.assertEqual(-2,logs[0]['amount']);self.assertEqual(1,self.refreshes)
    def test_reported_empty_bouquet_offers_do_not_block_catalogue(self):
        for ident,duration,credits,connections in [(15,1,1,2),(16,1,3,4),(80,12,44,8)]:
            self.sql("INSERT INTO users_packages VALUES (%s,'Existing package',%s,%s,'months',%s,'[]',%s,'[2]',0,1,1)",(ident,credits,duration,duration,connections))
        plans=self.adapter.catalog('101')['plans']
        self.assertEqual([7,8,15,16,80],[p['id'] for p in plans])
        self.assertEqual([[],[],[]],[p['bouquets'] for p in plans if p['id'] in [15,16,80]])
        self.untouched()
    def test_expired_code_and_saved_credentials_identify_only_checkout_target(self):
        expected={'reseller_id':'101','line_id':'999','primary_fingerprint':hashlib.sha256(b'user\npass').hexdigest()}
        self.assertEqual(expected,self.adapter.identify({'reseller_id':'101','activation_code':'123456'}))
        self.assertEqual(expected,self.adapter.identify({'reseller_id':'101','username':'user','password':'pass'}))
        self.assertNotIn('username',expected);self.assertNotIn('password',expected)
        self.untouched();self.assertEqual(0,self.refreshes)
    def test_renewal_login_rejects_unknown_wrong_owner_password_duplicate_and_restricted_lines(self):
        for identity in [
            {'reseller_id':'101','activation_code':'654321'},
            {'reseller_id':'102','activation_code':'123456'},
            {'reseller_id':'101','activation_code':'000000'},
            {'reseller_id':'101','activation_code':123456},
            {'reseller_id':'101','activation_code':'123456','line_id':'1000'},
            {'reseller_id':'101','username':'user','password':'wrong'},
        ]:
            with self.assertRaises(RenewalRejected):self.adapter.identify(identity)
            self.untouched()
        for field,value in [('exp_date',int(time.time())+3600),('exp_date',0),('enabled',0),('activated',2)]:
            self.sql('UPDATE customers SET '+field+'=%s WHERE id=999',(value,))
            with self.assertRaises(RenewalRejected):self.adapter.identify({'reseller_id':'101','activation_code':'123456'})
            self.sql('UPDATE customers SET '+field+'=%s WHERE id=999',(self.before[field],))
        self.sql("UPDATE customers SET activation_code='123456' WHERE id=1000")
        with self.assertRaises(RenewalRejected):self.adapter.identify({'reseller_id':'101','activation_code':'123456'})
        self.untouched()
    def test_empty_package_bouquets_renew_once_without_clearing_customer_access(self):
        self.sql("UPDATE users_packages SET bouquets='[]' WHERE id=7")
        offers=self.adapter.catalog('101')['plans']
        self.assertEqual([],offers[0]['bouquets'])
        eligible=self.adapter.eligibility('101','999',hashlib.sha256(b'user\npass').hexdigest())
        self.assertEqual([7],[p['id'] for p in eligible['plans']])
        first=self.run_operation();self.assertEqual('fulfilled',first['state'])
        self.assertEqual(first,self.run_operation())
        self.assertEqual('[10]',self.preserved()['bouquet'])
        self.assertEqual('[]',self.one('SELECT bouquets FROM users_packages WHERE id=7')['bouquets'])
        self.assertEqual(8,self.one('SELECT credits FROM users WHERE id=101')['credits'])
        self.assertEqual(1,self.one('SELECT COUNT(*) AS n FROM users_credits_log')['n'])
    def test_empty_bouquet_support_does_not_accept_malformed_bouquets_or_package_changes(self):
        for raw in ['null','{}','[0]','[-1]','["x"]','[true]','[2147483648]','not-json']:
            self.sql('UPDATE users_packages SET bouquets=%s WHERE id=7',(raw,))
            with self.assertRaises(RenewalRejected):self.adapter.catalog('101')
            self.untouched()
        self.sql("UPDATE users_packages SET bouquets='[]' WHERE id=7")
        spec=self.spec()
        self.sql("UPDATE users_packages SET bouquets='[12]' WHERE id=7")
        with self.assertRaises(RenewalRejected):self.run_operation(spec)
        self.untouched()
    def test_real_panel_trigger_records_one_atomic_marker_not_a_second_adjustment(self):
        self.trigger();self.assertEqual('fulfilled',self.run_operation()['state']);self.assertEqual(1,self.one('SELECT COUNT(*) AS n FROM users_credits_log')['n']);self.assertEqual('line_renew',self.one('SELECT reason FROM users_credits_log')['reason']);self.preserved()
    def test_missing_marker_in_existing_credit_trigger_rolls_back_every_change(self):
        self.trigger(drop_note=True)
        with self.assertRaises(RenewalRejected):self.run_operation()
        self.untouched();self.assertEqual(0,self.refreshes)
    def test_restricted_and_unlimited_lines_never_renew(self):
        for field,value in [('enabled',0),('activated',2),('exp_date',0)]:
            self.sql('UPDATE customers SET '+field+'=%s WHERE id=999',(value,))
            with self.assertRaises(RenewalRejected):self.run_operation()
            self.sql('UPDATE customers SET '+field+'=%s WHERE id=999',(self.before[field],))
            self.untouched()
    def expired_inactive(self):
        self.sql('UPDATE customers SET activated=0 WHERE id=999')
        self.before=self.one('SELECT * FROM customers WHERE id=999')
    def test_enabled_expired_inactive_line_can_quote_without_writes_then_reactivates_once_on_payment(self):
        self.expired_inactive()
        for identity in [{'activation_code':'123456'},{'username':'user','password':'pass'}]:
            result=self.adapter.identify({'reseller_id':'101',**identity})
            self.assertEqual('999',result['line_id'])
        result=self.adapter.eligibility('101','999',hashlib.sha256(b'user\npass').hexdigest())
        self.assertEqual('expired',result['state']);self.assertEqual([7],[p['id'] for p in result['plans']])
        self.untouched();self.assertEqual(0,self.refreshes)
        first=self.run_operation();self.assertEqual('fulfilled',first['state'])
        self.assertEqual(first,self.run_operation())
        self.assertEqual(first['expires_at'],self.preserved(activated=1)['exp_date'])
        self.assertEqual(8,self.one('SELECT credits FROM users WHERE id=101')['credits'])
        self.assertEqual(1,self.one('SELECT COUNT(*) AS n FROM users_credits_log')['n'])
    def test_disabled_never_started_future_and_unknown_activation_states_still_cannot_reactivate(self):
        self.expired_inactive()
        for field,value in [('enabled',0),('exp_date',None),('exp_date',0),('exp_date',int(time.time())+3600),('activated',2)]:
            self.sql('UPDATE customers SET '+field+'=%s WHERE id=999',(value,))
            with self.assertRaises(RenewalRejected):self.adapter.identify({'reseller_id':'101','activation_code':'123456'})
            with self.assertRaises(RenewalRejected):self.run_operation()
            self.sql('UPDATE customers SET '+field+'=%s WHERE id=999',(self.before[field],))
            self.untouched()
        self.assertEqual(0,self.refreshes)
    def test_reactivation_rolls_back_with_a_failed_credit_journal_and_retry_uses_same_order(self):
        self.expired_inactive();self.trigger(drop_note=True)
        with self.assertRaises(RenewalRejected):self.run_operation()
        self.untouched();self.assertEqual(0,self.refreshes)
        self.sql('DROP TRIGGER credit_audit');self.trigger()
        self.assertEqual('fulfilled',self.run_operation()['state'])
        self.preserved(activated=1)
        self.assertEqual(1,self.one('SELECT COUNT(*) AS n FROM users_credits_log')['n'])
    def test_committed_reactivation_lost_reply_and_refresh_repair_never_debit_or_extend_twice(self):
        self.expired_inactive()
        class LostReply(RenewalAdapter):
            def renew_once(self,*args):super().renew_once(*args);raise ConnectionError('Synthetic lost reply')
        self.ready=False
        self.assertEqual('needs_review',self.run_operation(adapter=LostReply(self.native))['state'])
        expiry=self.preserved(activated=1)['exp_date']
        self.assertEqual('refresh_pending',self.run_operation(adapter=RenewalAdapter(self.native))['state'])
        self.ready=True
        self.assertEqual('fulfilled',self.run_operation(adapter=RenewalAdapter(self.native))['state'])
        self.assertEqual(expiry,self.preserved(activated=1)['exp_date'])
        self.assertEqual(8,self.one('SELECT credits FROM users WHERE id=101')['credits'])
        self.assertEqual(1,self.one('SELECT COUNT(*) AS n FROM users_credits_log')['n'])
    def test_concurrent_payment_retries_reactivate_expired_inactive_line_once(self):
        self.expired_inactive();self.trigger()
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
            results=list(pool.map(lambda _:self.run_operation(adapter=RenewalAdapter(self.native)),range(8)))
        self.assertEqual({'fulfilled'},{r['state'] for r in results})
        self.assertEqual(1,len({r['expires_at'] for r in results}))
        self.preserved(activated=1)
        self.assertEqual(8,self.one('SELECT credits FROM users WHERE id=101')['credits'])
        self.assertEqual(1,self.one('SELECT COUNT(*) AS n FROM users_credits_log')['n'])
    def test_current_package_and_funding_and_package_scope_are_authoritative(self):
        for statement,restore in [
            ('UPDATE customers SET package_id=8 WHERE id=999','UPDATE customers SET package_id=7 WHERE id=999'),
            ('UPDATE users SET credits=1 WHERE id=101','UPDATE users SET credits=10 WHERE id=101'),
            ("UPDATE users_packages SET `groups`='[3]' WHERE id=7","UPDATE users_packages SET `groups`='[2]' WHERE id=7"),
            ('UPDATE users SET status=0 WHERE id=101','UPDATE users SET status=1 WHERE id=101'),
        ]:
            self.sql(statement)
            with self.assertRaises(RenewalRejected):self.run_operation()
            self.sql(restore);self.untouched()
    def test_commit_then_lost_reply_reconciles_without_second_period_or_credit_debit(self):
        class LostReply(RenewalAdapter):
            def renew_once(self,*args):super().renew_once(*args);raise ConnectionError('Synthetic lost reply')
        self.assertEqual('needs_review',self.run_operation(adapter=LostReply(self.native))['state']);expiry=self.one('SELECT exp_date FROM customers WHERE id=999')['exp_date']
        self.assertEqual('fulfilled',self.run_operation(adapter=RenewalAdapter(self.native))['state']);self.assertEqual(expiry,self.preserved()['exp_date']);self.assertEqual(8,self.one('SELECT credits FROM users WHERE id=101')['credits']);self.assertEqual(1,self.one('SELECT COUNT(*) AS n FROM users_credits_log')['n'])
    def test_refresh_failure_retries_refresh_only_after_restart(self):
        self.ready=False;self.assertEqual('refresh_pending',self.run_operation()['state']);expiry=self.one('SELECT exp_date FROM customers WHERE id=999')['exp_date']
        self.ready=True;self.assertEqual('fulfilled',self.run_operation(adapter=RenewalAdapter(self.native))['state']);self.assertEqual(expiry,self.preserved()['exp_date']);self.assertEqual(8,self.one('SELECT credits FROM users WHERE id=101')['credits']);self.assertEqual(1,self.one('SELECT COUNT(*) AS n FROM users_credits_log')['n']);self.assertEqual(2,self.refreshes)
    def test_concurrent_same_order_is_one_native_commit(self):
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:results=list(pool.map(lambda _:self.run_operation(adapter=RenewalAdapter(self.native)),range(8)))
        self.assertEqual({'fulfilled'},{r['state'] for r in results});self.assertEqual(1,len({r['expires_at'] for r in results}));self.assertEqual(8,self.one('SELECT credits FROM users WHERE id=101')['credits']);self.assertEqual(1,self.one('SELECT COUNT(*) AS n FROM users_credits_log')['n'])
    def test_one_provider_payment_cannot_be_reused_by_another_reseller(self):
        self.assertEqual('fulfilled',self.run_operation()['state'])
        second=self.spec(operation_id='c'*32,reseller_id='102',line_id='1000',primary_fingerprint=hashlib.sha256(b'user2\npass2').hexdigest())
        with self.assertRaises(RenewalRejected):self.run_operation(second)
        self.assertEqual(10,self.one('SELECT credits FROM users WHERE id=102')['credits']);self.assertEqual(1,self.one('SELECT COUNT(*) AS n FROM users_credits_log')['n'])
    def test_zero_credit_package_has_one_marker_even_with_no_balance_trigger_event(self):
        self.trigger();self.sql('UPDATE users_packages SET credits=0 WHERE id=7');self.assertEqual('fulfilled',self.run_operation()['state']);self.assertEqual(10,self.one('SELECT credits FROM users WHERE id=101')['credits']);self.assertEqual(1,self.one('SELECT COUNT(*) AS n FROM users_credits_log')['n'])
    def test_failure_after_credit_journal_rolls_back_then_same_order_can_reconcile_not_applied(self):
        class Rollback(RenewalAdapter):
            def journal(self,*args):super().journal(*args);raise RuntimeError('Synthetic rollback')
        self.assertEqual('needs_review',self.run_operation(adapter=Rollback(self.native))['state']);self.untouched()
        self.assertEqual('fulfilled',self.run_operation()['state']);self.assertEqual(8,self.one('SELECT credits FROM users WHERE id=101')['credits']);self.assertEqual(1,self.one('SELECT COUNT(*) AS n FROM users_credits_log')['n'])
    def test_package_change_at_transaction_boundary_is_not_switched_back(self):
        fixture=self
        class Changed(RenewalAdapter):
            def renew_once(self,*args):fixture.sql('UPDATE customers SET package_id=8 WHERE id=999');return super().renew_once(*args)
        with self.assertRaises(RenewalRejected):self.run_operation(adapter=Changed(self.native))
        self.assertEqual(8,self.one('SELECT package_id FROM customers WHERE id=999')['package_id']);self.assertEqual(10,self.one('SELECT credits FROM users WHERE id=101')['credits']);self.assertEqual(0,self.one('SELECT COUNT(*) AS n FROM users_credits_log')['n'])
    def test_nontransactional_credit_log_is_refused_before_writes(self):
        self.sql('ALTER TABLE users_credits_log ENGINE=MyISAM')
        with self.assertRaises(RenewalRejected):self.run_operation()
        self.untouched()
    def test_funding_race_defers_same_order_then_automatically_retries_without_second_charge(self):
        fixture=self
        class FundingRace(RenewalAdapter):
            def renew_once(self,*args):fixture.sql('UPDATE users SET credits=1 WHERE id=101');return super().renew_once(*args)
        from branded_renewals import RenewalDeferred
        with self.assertRaises(RenewalDeferred):self.run_operation(adapter=FundingRace(self.native))
        self.assertEqual(self.before['exp_date'],self.one('SELECT exp_date FROM customers WHERE id=999')['exp_date']);self.assertEqual(0,self.one('SELECT COUNT(*) AS n FROM users_credits_log')['n'])
        self.sql('UPDATE users SET credits=10 WHERE id=101');self.assertEqual('fulfilled',self.run_operation()['state']);self.assertEqual(8,self.one('SELECT credits FROM users WHERE id=101')['credits']);self.assertEqual(1,self.one('SELECT COUNT(*) AS n FROM users_credits_log')['n'])
    def test_marker_uses_its_actual_length_not_an_arbitrary_larger_note_capacity(self):
        self.sql('ALTER TABLE users_credits_log MODIFY note VARCHAR(178)');self.assertEqual('fulfilled',self.run_operation()['state']);self.assertLessEqual(len(self.one('SELECT note FROM users_credits_log')['note']),178)
    def test_a_second_invoice_for_the_same_expired_revision_never_double_renews(self):
        self.assertEqual('fulfilled',self.run_operation()['state']);expiry=self.one('SELECT exp_date FROM customers WHERE id=999')['exp_date']
        with self.assertRaises(RenewalRejected):self.run_operation(self.spec(operation_id='f'*32,payment_link_id='different-real-invoice'))
        self.assertEqual(expiry,self.preserved()['exp_date']);self.assertEqual(8,self.one('SELECT credits FROM users WHERE id=101')['credits']);self.assertEqual(1,self.one('SELECT COUNT(*) AS n FROM users_credits_log')['n'])
    def test_unlimited_offer_does_not_block_valid_renewal_packages_and_native_missing_period_means_days(self):
        self.sql("INSERT INTO users_packages VALUES (9,'Unlimited',0,0,NULL,0,'[10]',1,'[2]',0,1,1)")
        self.assertEqual([7,8],[p['id'] for p in self.adapter.catalog('101')['plans']])
        self.sql('UPDATE users_packages SET duration=30,duration_period=NULL WHERE id=7')
        self.assertEqual('days',package_document(self.one('SELECT * FROM users_packages WHERE id=7'))['period'])
        self.assertEqual('fulfilled',self.run_operation()['state']);self.assertEqual('days',self.one('SELECT duration_period FROM customers WHERE id=999')['duration_period'])


if __name__=='__main__':unittest.main()
