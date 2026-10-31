"""The new service's paid adapter. Existing auth worker is never called/patched.
Uses its reviewed calendar, locking, credit journal and native refresh helpers.
"""
from decimal import Decimal
import hashlib
import hmac
import json
import re
import time
from types import SimpleNamespace
import threading
from branded_renewals import RenewalOperations,RenewalRejected,RenewalDeferred,RenewalDefinitive,validate,canonical
from branded_renewals_runtime import NativeRenewalAdapter,_operation,primary_matches,plan_document,lookup_receipt


class RenewalAdapter(NativeRenewalAdapter):
    def line_permitted(self,row):
        if not row or str(row.get('enabled'))!='1':return False
        if str(row.get('activated'))=='1':return True
        # Overcast can clear activation on expiry. This is renewable, not an
        # administrative disable. Never admit a never-started/future line.
        return str(row.get('activated'))=='0' and 0<int(row.get('exp_date') or 0)<=time.time()
    def identify(self,value):
        """Checkout-only identity; never returns credentials or grants app access."""
        if not isinstance(value,dict) or not isinstance(value.get('reseller_id'),str) or not re.fullmatch('[1-9][0-9]{0,18}',value['reseller_id']):raise RenewalRejected('Invalid renewal login')
        code=set(value)=={'reseller_id','activation_code'}
        if code:
            if not isinstance(value['activation_code'],str) or not re.fullmatch('[0-9]{6}',value['activation_code']):raise RenewalRejected('Invalid renewal login')
            column='activation_code';argument=value['activation_code']
        else:
            if set(value)!={'reseller_id','username','password'} or any(not isinstance(value.get(k),str) or not 1<=len(value[k])<=limit for k,limit in [('username',256),('password',512)]):raise RenewalRejected('Invalid renewal login')
            column='username';argument=value['username']
        with self.worker['panel_db_cursor'](autocommit=False) as (cur,conn):
            try:
                cur.execute('SELECT id,member_id,username,password,enabled,activated,exp_date FROM customers WHERE '+column+'=%s LIMIT 2',(argument,));rows=cur.fetchall()
                row=rows[0] if len(rows)==1 else None
                password=str(row.get('password') or '') if row else 'unavailable-account-placeholder'
                matched=code or hmac.compare_digest(password.encode(),value['password'].encode())
                if not row or not matched or not row.get('username') or not row.get('password') or str(row['member_id'])!=value['reseller_id'] or not self.line_permitted(row):raise RenewalRejected('Renewal login is unavailable')
                primary=hashlib.sha256((str(row['username'])+'\n'+str(row['password'])).encode()).hexdigest();line=str(row['id'])
            finally:conn.rollback()
        result=self.eligibility(value['reseller_id'],line,primary)
        if result['state']!='expired' or result['primary_match'] is not True:raise RenewalRejected('Renewal login is unavailable')
        return {'reseller_id':value['reseller_id'],'line_id':line,'primary_fingerprint':primary}
    def __init__(self,native):
        self.worker=native;self.refreshed={};self.refresh_lock=threading.RLock()
        # Unlike the old public /renew-line route, this owned implementation has
        # its journal guard IN the transaction, exercised by real SQL tests.
        self.capabilities={'atomic_renewal':1,'unique_payment_reference':1}
    def plans(self,cur,owner):
        group,overrides=self.worker['_reseller_package_scope'](cur,owner['username'])
        cur.execute('SELECT * FROM users_packages WHERE enabled=1 AND activated=1 AND type<>1 ORDER BY id')
        plans=[]
        for p in cur.fetchall():
            if not self.worker['_package_allowed'](p,group,overrides):continue
            # An offered unlimited package is not a renewal product. Its
            # presence must not block every other valid package in the list.
            if Decimal(str(p.get('duration') or 0))<=0:continue
            doc=plan_document(p);doc['funded']=Decimal(str(owner.get('credits') or 0))>=Decimal(doc['credits']);plans.append(doc)
        return plans
    def eligibility(self,reseller,line,fingerprint):
        with self.worker['panel_db_cursor'](autocommit=False) as (cur,conn):
            try:
                owner=self.owner(cur,reseller);self.journal_ready(cur);row=self.worker['_lock_line_row'](cur,line,owner['id'])
                matched=bool(row and primary_matches(row,fingerprint));expiry=int(row.get('exp_date') or 0) if row else 0
                current=int(row.get('package_id') or 0) if matched else 0;trial=bool(matched and str(row.get('is_trial'))=='1');state='unavailable'
                if matched and current>0 and str(row.get('is_trial')) in ('0','1'):
                    state='restricted' if not self.line_permitted(row) else 'unlimited' if not expiry else 'expired' if expiry<=time.time() else 'active'
                plans=self.plans(cur,owner) if state=='expired' else []
                if not trial:plans=[p for p in plans if p['id']==current]
                return {'reseller_id':reseller,'line_id':line,'primary_match':matched,'state':state,'expires_at':expiry or None,'checked_at':int(time.time()),'current_package_id':current or None,'is_trial':trial,'trial_conversion_supported':1,'plans':plans}
            finally:conn.rollback()
    @staticmethod
    def source_matches(row,spec):
        converting=spec.get('trial_conversion') is True
        return bool(row and str(row.get('package_id'))==str(spec.get('source_package_id') if converting else spec['package_id']) and str(row.get('is_trial'))==('1' if converting else '0'))
    def prepare(self,cur,reseller,body):
        meta=body.renewal_operation;spec=meta['spec']
        if spec.get('trial_conversion') is not True:
            result=super().prepare(cur,reseller,body)
            if result['receipt'] is None and not self.source_matches(self.worker['_lock_line_row'](cur,body.line_id,reseller['id']),spec):raise RenewalRejected('A trial requires its configured paid conversion plan')
            return result
        authorized=_operation.get()
        if authorized is None or not hmac.compare_digest(canonical(meta),canonical(authorized)):raise RenewalRejected('Payment operation is not authorized')
        spec=validate(spec,allow_trial_conversion=True)
        if str(reseller['id'])!=spec['reseller_id'] or str(body.line_id)!=spec['line_id'] or body.package_id!=spec['package_id'] or str(reseller.get('status'))!='1':raise RenewalRejected('Conversion owner/plan mismatch')
        row=self.worker['_lock_line_row'](cur,body.line_id,reseller['id'])
        if not row or not primary_matches(row,spec['primary_fingerprint']):raise RenewalRejected('Primary identity changed')
        self.journal_ready(cur);receipt=lookup_receipt(cur,spec,meta['fingerprint'],meta['payment_hash'])
        if receipt:return {'receipt':receipt}
        if not self.source_matches(row,spec) or not self.line_permitted(row) or int(row.get('exp_date') or 0)!=spec['expected_expiry']:raise RenewalRejected('Original expired trial changed or was already converted')
        cur.execute('SELECT * FROM users_packages WHERE id=%s AND enabled=1 AND activated=1 FOR UPDATE',(spec['package_id'],));package=cur.fetchone()
        group,overrides=self.worker['_reseller_package_scope'](cur,body.username)
        if not package or str(package.get('type'))=='1' or not self.worker['_package_allowed'](package,group,overrides) or plan_document(package)['fingerprint']!=spec['plan_fingerprint']:raise RenewalRejected('Paid conversion package changed or is unavailable')
        return {'receipt':None,'package':package,'bouquets':plan_document(package)['bouquets']}
    def validate(self,spec):
        with self.worker['panel_db_cursor'](autocommit=False) as (cur,conn):
            try:
                owner=self.owner(cur,spec['reseller_id']);self.journal_ready(cur)
                row=self.worker['_lock_line_row'](cur,spec['line_id'],owner['id'])
                if not row or not primary_matches(row,spec['primary_fingerprint']) or not self.source_matches(row,spec) or not self.line_permitted(row) or not row.get('exp_date'):raise RenewalRejected('Current primary/package or original trial is unavailable or restricted')
                if int(row['exp_date'])!=spec['expected_expiry']:raise RenewalRejected('Line expiry changed or was renewed elsewhere; retain the paid invoice for review')
                cur.execute('SELECT * FROM users_packages WHERE id=%s AND enabled=1 AND activated=1 FOR UPDATE',(spec['package_id'],));p=cur.fetchone()
                group,overrides=self.worker['_reseller_package_scope'](cur,owner['username'])
                if not p or str(p.get('type'))=='1' or not self.worker['_package_allowed'](p,group,overrides) or plan_document(p)['fingerprint']!=spec['plan_fingerprint']:raise RenewalRejected('Current package changed or is no longer offered')
                if Decimal(str(owner.get('credits') or 0))<Decimal(str(p.get('credits') or 0)):raise RenewalDeferred('Reseller funding pending; retain the same paid order')
            finally:conn.rollback()
    def renew_once(self,spec,fingerprint,payment_hash):
        meta={'spec':spec,'fingerprint':fingerprint,'payment_hash':payment_hash};token=_operation.set(meta)
        try:
            with self.worker['panel_db_cursor'](autocommit=False) as (cur,conn):
                try:
                    owner=self.owner(cur,spec['reseller_id'])
                    body=SimpleNamespace(username=owner['username'],line_id=spec['line_id'],package_id=spec['package_id'],renewal_operation=meta)
                    guarded=self.prepare(cur,owner,body)
                    if guarded['receipt'] is not None:conn.rollback();return guarded['receipt']
                    package=guarded['package'];price=Decimal(str(package.get('credits') or 0));balance=Decimal(str(owner.get('credits') or 0))
                    if balance<price:raise RenewalDeferred('Reseller funding pending; no second customer charge')
                    fresh=self.worker['_lock_line_row'](cur,spec['line_id'],owner['id'])
                    if not self.source_matches(fresh,spec) or int(fresh.get('exp_date') or 0)!=spec['expected_expiry']:raise RenewalRejected('Line was already renewed/converted or its invoice revision changed')
                    base,expiry=self.worker['_renewal_window'](fresh,package,None)
                    if not 1<=int(expiry.timestamp())<=4102444800:raise RenewalRejected('Invalid renewal expiry')
                    # Keep the SAME account identity. Only a verified trial
                    # conversion may apply a different paid package/entitlements;
                    # normal renewal retains its package and access overrides.
                    converting=spec.get('trial_conversion') is True
                    if converting:
                        doc=plan_document(package);bouquet=json.dumps(doc['bouquets'],separators=(',',':')) if doc['bouquets'] else fresh['bouquet']
                        cur.execute('UPDATE customers SET exp_date=%s,duration=%s,duration_period=%s,duration_value=%s,is_trial=0,activated=1,package_id=%s,max_connections=%s,bouquet=%s,updated=NOW() WHERE id=%s AND member_id=%s AND package_id=%s AND is_trial=1',
                            (int(expiry.timestamp()),package.get('duration') or 0,package.get('duration_period') or 'days',package.get('duration_value') or 0,spec['package_id'],doc['max_connections'],bouquet,spec['line_id'],owner['id'],spec['source_package_id']))
                    else:
                        cur.execute('UPDATE customers SET exp_date=%s,duration=%s,duration_period=%s,duration_value=%s,is_trial=0,activated=1,updated=NOW() WHERE id=%s AND member_id=%s AND package_id=%s',
                            (int(expiry.timestamp()),package.get('duration') or 0,package.get('duration_period') or 'days',package.get('duration_value') or 0,spec['line_id'],owner['id'],spec['package_id']))
                    after=self.worker['_lock_line_row'](cur,spec['line_id'],owner['id'])
                    retained=['username','password','activation_code','reseller_notes','admin_notes','bouquet','allowed_outputs','max_connections','parent_pin','enabled','package_id']
                    if converting:
                        retained=[k for k in retained if k not in ['bouquet','max_connections','package_id']]
                        if not after or str(after.get('is_trial'))!='0' or int(after.get('package_id') or 0)!=spec['package_id'] or int(after.get('max_connections') or 0)!=doc['max_connections'] or after.get('bouquet')!=bouquet:raise RenewalRejected('Panel did not apply the paid conversion entitlements')
                    if not after or str(after.get('activated'))!='1' or int(after.get('exp_date') or 0)!=int(expiry.timestamp()) or any(after.get(k)!=fresh.get(k) for k in retained):raise RenewalRejected('Panel did not preserve the original line during renewal; transaction rolled back')
                    note=self.note(meta,expiry)
                    self.worker['_set_credits'](cur,owner['id'],balance-price,-price,balance,'line_renew',owner['id'],'customer',spec['line_id'],note=note)
                    self.journal(cur,owner,body,note,price,balance,balance-price)
                    receipt={key:spec[key] for key in ['operation_id','reseller_id','line_id','package_id']}|{'expires_at':int(expiry.timestamp())}
                except RenewalDeferred:conn.rollback();raise
                except RenewalRejected as error:
                    conn.rollback()
                    raise RenewalDefinitive(str(error)) from None
                except Exception:conn.rollback();raise
                conn.commit() # A lost reply is reconciled against that SAME marker.
                return receipt
        finally:_operation.reset(token)
    def refresh_and_verify(self,spec,receipt):
        # Independent process/cookie jar from auth worker; serialize our own jar.
        with self.refresh_lock:return super().refresh_and_verify(spec,receipt)
    def current(self,spec,receipt):
        if not super().current(spec,receipt):return False
        if spec.get('trial_conversion') is not True:return True
        with self.worker['panel_db_cursor'](autocommit=False) as (cur,conn):
            try:
                row=self.worker['_lock_line_row'](cur,spec['line_id'],spec['reseller_id'])
                return bool(row and str(row.get('is_trial'))=='0' and str(row.get('package_id'))==str(spec['package_id']))
            finally:conn.rollback()
