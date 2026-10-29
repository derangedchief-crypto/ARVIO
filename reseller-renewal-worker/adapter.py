"""The new service's paid adapter. Existing auth worker is never called/patched.
Uses its reviewed calendar, locking, credit journal and native refresh helpers.
"""
from decimal import Decimal
from types import SimpleNamespace
import threading
from branded_renewals import RenewalOperations,RenewalRejected,RenewalDeferred,RenewalDefinitive
from branded_renewals_runtime import NativeRenewalAdapter,_operation,primary_matches,plan_document


class RenewalAdapter(NativeRenewalAdapter):
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
    def validate(self,spec):
        with self.worker['panel_db_cursor'](autocommit=False) as (cur,conn):
            try:
                owner=self.owner(cur,spec['reseller_id']);self.journal_ready(cur)
                row=self.worker['_lock_line_row'](cur,spec['line_id'],owner['id'])
                if not row or not primary_matches(row,spec['primary_fingerprint']) or str(row.get('package_id'))!=str(spec['package_id']) or str(row.get('enabled'))!='1' or str(row.get('activated'))!='1' or not row.get('exp_date'):raise RenewalRejected('Current primary/package is unavailable or restricted')
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
                    if int(fresh.get('exp_date') or 0)!=spec['expected_expiry']:raise RenewalRejected('Line was already renewed or its invoice revision changed')
                    base,expiry=self.worker['_renewal_window'](fresh,package,None)
                    if not 1<=int(expiry.timestamp())<=4102444800:raise RenewalRejected('Invalid renewal expiry')
                    # Extend the SAME line/current package. Preserve credentials,
                    # activation code, notes, bouquets and connection overrides.
                    cur.execute('UPDATE customers SET exp_date=%s,duration=%s,duration_period=%s,duration_value=%s,is_trial=0,updated=NOW() WHERE id=%s AND member_id=%s AND package_id=%s',
                        (int(expiry.timestamp()),package.get('duration') or 0,package.get('duration_period') or 'days',package.get('duration_value') or 0,spec['line_id'],owner['id'],spec['package_id']))
                    after=self.worker['_lock_line_row'](cur,spec['line_id'],owner['id'])
                    retained=['username','password','activation_code','reseller_notes','admin_notes','bouquet','allowed_outputs','max_connections','parent_pin','enabled','activated','package_id']
                    if not after or int(after.get('exp_date') or 0)!=int(expiry.timestamp()) or any(after.get(k)!=fresh.get(k) for k in retained):raise RenewalRejected('Panel did not preserve the original line during renewal; transaction rolled back')
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
