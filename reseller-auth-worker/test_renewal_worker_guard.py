import ast
import copy
import hashlib
from pathlib import Path
from types import SimpleNamespace
import unittest
from zipfile import ZipFile
from patch_renewal_worker import transform,BASE_SHA,TRIAL_HOOK
from branded_renewals import RenewalRejected
from branded_renewals_runtime import NativeRenewalAdapter,_operation,lookup_receipt,marker,plan_document

UPLOAD=Path('/home/studio/Extreme Hub Tivimate Rework/new auth.zip')

def spec():return {'operation_id':'a'*32,'brand_id':'b'*32,'reseller_id':'101','line_id':'999','package_id':7,'plan_fingerprint':'c'*64,'merchant_id':'merchant-A','payment_link_id':'link-A','primary_fingerprint':hashlib.sha256(b'user\npass').hexdigest()}

class Cursor:
    def __init__(self):
        self.rows=[];self.queries=[];self.next=None
        self.engine_query=False;self.engines={name:'InnoDB' for name in ['users','customers','users_credits_log','users_packages']}
        self.package={'id':7,'package_name':'Monthly','duration':1,'duration_value':0,'duration_period':'months','max_connections':1,'credits':'2','bouquets':'[10]','type':0,'enabled':1,'activated':1}
    def execute(self,sql,args=()):
        self.queries.append((sql,args))
        self.engine_query='information_schema.TABLES' in sql
        if 'note_capacity' in sql:self.next={'note_capacity':255}
        elif 'FROM users_packages' in sql:self.next=self.package
    def fetchall(self):return [{'table_name':name,'engine':engine} for name,engine in self.engines.items()] if self.engine_query else self.rows
    def fetchone(self):return self.next

class RenewalWorkerGuardTests(unittest.TestCase):
    def test_nontransactional_panel_table_is_never_accepted(self):
        cur=Cursor();cur.engines['users_credits_log']='MyISAM'
        with self.assertRaises(RenewalRejected):NativeRenewalAdapter.journal_ready(cur)
    def test_manually_claiming_guard_version_does_not_enable_a_legacy_route(self):
        adapter=NativeRenewalAdapter({},1)
        self.assertEqual({},adapter.capabilities)
        with self.assertRaises(RenewalRejected):adapter.renew_once(spec(),'d'*64,'e'*64)
    def test_transform_is_bounded_to_known_worker_and_idempotent(self):
        with ZipFile(UPLOAD) as z:raw=z.read('new auth/main.py')
        self.assertEqual(BASE_SHA,hashlib.sha256(raw).hexdigest());source=raw.decode()
        for value in [source,source+'\n\n'+TRIAL_HOOK+'\n']:
            patched=transform(value);ast.parse(patched);self.assertEqual(patched,transform(patched))
            route=next(n for n in ast.parse(patched).body if isinstance(n,ast.AsyncFunctionDef) and n.name=='renew_line')
            text='\n'.join(patched.splitlines()[route.lineno-1:route.end_lineno])
            self.assertLess(text.index('_branded_renewal_prepare'),text.index('if current_credits < price'))
            self.assertLess(text.index('_branded_renewal_journal'),text.index('conn.commit()'))
            self.assertIn('note=renewal_note',text)
    def test_unknown_worker_source_is_never_patched(self):
        with self.assertRaises(ValueError):transform('app = None\n')
    def test_legacy_portal_cannot_assert_a_payment_operation(self):
        cur=Cursor();adapter=NativeRenewalAdapter({},1)
        with self.assertRaises(RenewalRejected):adapter.prepare(cur,{'id':101,'status':1},SimpleNamespace(renewal_operation={'spec':spec()},line_id='999',package_id=7))
        self.assertEqual([],cur.queries)
    def test_payment_journal_is_scoped_to_order_payment_and_line(self):
        s=spec();meta={'spec':s,'fingerprint':'d'*64,'payment_hash':'e'*64};cur=Cursor()
        cur.rows=[{'user_id':101,'ref_id':999,'note':marker(meta,2000000000)}]
        self.assertEqual(2000000000,lookup_receipt(cur,s,meta['fingerprint'],meta['payment_hash'])['expires_at'])
        for field in ['line_id','operation_id']:
            wrong=dict(s,**{field:'1000' if field=='line_id' else 'f'*32})
            with self.assertRaises(RenewalRejected):lookup_receipt(cur,wrong,meta['fingerprint'],meta['payment_hash'])
        with self.assertRaises(RenewalRejected):lookup_receipt(cur,s,meta['fingerprint'],'f'*64)
    def test_authorized_guard_rechecks_current_plan_and_restricted_primary(self):
        cur=Cursor();s=spec();s['plan_fingerprint']=plan_document(cur.package)['fingerprint'];meta={'spec':s,'fingerprint':'d'*64,'payment_hash':'e'*64}
        line={'id':999,'member_id':101,'username':'user','password':'pass','enabled':1,'activated':1,'exp_date':1000}
        worker={'_lock_line_row':lambda *_:line,'_reseller_package_scope':lambda *_:(1,None),'_package_allowed':lambda *_:True}
        adapter=NativeRenewalAdapter(worker,1);body=SimpleNamespace(renewal_operation=meta,line_id='999',package_id=7,username='reseller')
        token=_operation.set(meta)
        try:
            self.assertEqual(7,adapter.prepare(cur,{'id':101,'status':1},body)['package']['id'])
            line['enabled']=0
            with self.assertRaises(RenewalRejected):adapter.prepare(cur,{'id':101,'status':1},body)
            line['enabled']=1;cur.package['duration']=3
            with self.assertRaises(RenewalRejected):adapter.prepare(cur,{'id':101,'status':1},body)
        finally:_operation.reset(token)
    def test_missing_atomic_credit_marker_forces_transaction_rollback(self):
        cur=Cursor();adapter=NativeRenewalAdapter({},1);s=spec();body=SimpleNamespace(renewal_operation={'spec':s,'fingerprint':'d'*64,'payment_hash':'e'*64},line_id='999')
        from decimal import Decimal
        with self.assertRaises(RenewalRejected):adapter.journal(cur,{'id':101},body,'note',Decimal(2),Decimal(10),Decimal(8))

if __name__=='__main__':unittest.main()
