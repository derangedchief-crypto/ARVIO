"""Durable renewal-operation ledger, without import-time routes or IO.

The separate runtime adapter requires the reviewed atomic transaction patch.
The unmodified /renew-line is NOT safe to use as renew_once().
"""
import contextlib
import fcntl
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import secrets
import sqlite3
import time


class RenewalRejected(Exception):
    pass


def validate(spec):
    fields={'operation_id','brand_id','reseller_id','line_id','package_id','plan_fingerprint','merchant_id','payment_link_id','primary_fingerprint','current_package_only'}
    if not isinstance(spec,dict) or set(spec)!=fields:raise RenewalRejected('Invalid renewal operation fields')
    if spec['current_package_only'] is not True:raise RenewalRejected('Current-package-only renewal contract is required')
    for field,size in [('operation_id',32),('brand_id',32),('plan_fingerprint',64),('primary_fingerprint',64)]:
        if not isinstance(spec[field],str) or not re.fullmatch('[a-f0-9]{%d}'%size,spec[field]):raise RenewalRejected('Invalid renewal operation identity')
    for field in ['reseller_id','line_id']:
        if not isinstance(spec[field],str) or not re.fullmatch('[1-9][0-9]{0,18}',spec[field]):raise RenewalRejected('Invalid verified panel identity')
    if type(spec['package_id']) is not int or not 1<=spec['package_id']<=2147483647:raise RenewalRejected('Invalid renewal plan')
    for field in ['merchant_id','payment_link_id']:
        if not isinstance(spec[field],str) or not re.fullmatch('[A-Za-z0-9._:-]{1,200}',spec[field]):raise RenewalRejected('Invalid verified payment reference')
    return dict(spec)


def canonical(value):
    return json.dumps(value,sort_keys=True,separators=(',',':')).encode()


class RenewalOperations:
    def __init__(self,directory,panel):
        self.directory=Path(directory)
        self.panel=panel

    @contextlib.contextmanager
    def storage(self,operation):
        directory=self.directory
        directory.mkdir(mode=0o700,parents=True,exist_ok=True)
        if directory.is_symlink() or directory.stat().st_mode&0o077:raise RenewalRejected('Private renewal storage permissions require attention')
        def private(path,create=False):
            if path.is_symlink() or path.exists() and path.stat().st_mode&0o077:raise RenewalRejected('Unsafe renewal ledger path or permissions')
            if create:
                fd=os.open(path,os.O_CREAT|os.O_EXCL|os.O_WRONLY|os.O_NOFOLLOW,0o600)
                return os.fdopen(fd,'wb')
        # Initialization is serialized separately from per-operation locks.
        fd=os.open(directory/'init.lock',os.O_CREAT|os.O_RDWR|os.O_NOFOLLOW,0o600)
        with os.fdopen(fd,'a') as init:
            fcntl.flock(init,fcntl.LOCK_EX)
            keyfile=directory/'renewal.key';ledger=directory/'renewals.sqlite'
            new=not keyfile.exists()
            if new:
                if ledger.exists():raise RenewalRejected('Restore the original renewal key; ledger retained')
                with private(keyfile,True) as out:out.write(secrets.token_bytes(32));out.flush();os.fsync(out.fileno())
            private(keyfile)
            if not ledger.exists():
                if not new:raise RenewalRejected('Restore the original renewal ledger')
                with private(ledger,True):pass
            private(ledger)
            key=keyfile.read_bytes()
            if len(key)!=32:raise RenewalRejected('Invalid renewal key; restore original state')
            with sqlite3.connect(ledger,timeout=10) as db:
                db.execute('CREATE TABLE IF NOT EXISTS operations (operation_id TEXT PRIMARY KEY,reseller_id TEXT NOT NULL,fingerprint TEXT NOT NULL,payment_hash TEXT NOT NULL UNIQUE,state TEXT NOT NULL,receipt TEXT,updated_at INTEGER NOT NULL)')
        fd=os.open(directory/(operation+'.lock'),os.O_CREAT|os.O_RDWR|os.O_NOFOLLOW,0o600)
        with os.fdopen(fd,'a') as lock:
            deadline=time.monotonic()+10
            while True:
                try:fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB);break
                except BlockingIOError:
                    if time.monotonic()>=deadline:raise RenewalRejected('Renewal operation busy; retry the SAME order') from None
                    time.sleep(.02)
            db=sqlite3.connect(ledger,timeout=10)
            db.row_factory=sqlite3.Row
            db.execute('PRAGMA synchronous=FULL')
            try:yield db,key
            finally:db.close()

    @staticmethod
    def receipt(spec,value):
        if not isinstance(value,dict):raise RenewalRejected('Invalid committed renewal receipt')
        expected={key:spec[key] for key in ['operation_id','reseller_id','line_id','package_id']}
        if any(value.get(key)!=v for key,v in expected.items()) or type(value.get('expires_at')) is not int or not 1<=value['expires_at']<=4102444800:
            raise RenewalRejected('Renewal receipt does not match the original order')
        return {**expected,'expires_at':value['expires_at']}

    def request(self,value):
        spec=validate(value)
        if getattr(self.panel,'capabilities',{})!={'atomic_renewal':1,'unique_payment_reference':1}:
            raise RenewalRejected('Existing worker needs its atomic renewal/payment guard first')
        with self.storage(spec['operation_id']) as (db,key):
            fingerprint=hmac.new(key,b'renewal-request-v1\n'+canonical(spec),hashlib.sha256).hexdigest()
            payment_hash=hmac.new(key,b'renewal-payment-v1\n'+canonical({k:spec[k] for k in ['merchant_id','payment_link_id','reseller_id']}),hashlib.sha256).hexdigest()
            row=db.execute('SELECT * FROM operations WHERE operation_id=?',(spec['operation_id'],)).fetchone()
            if row and not hmac.compare_digest(row['fingerprint'],fingerprint):raise RenewalRejected('Order ID already belongs to another renewal')
            if not row:
                self.panel.validate(spec) # eligibility/plan/funding, not client claims
                try:
                    db.execute('INSERT INTO operations VALUES (?,?,?,?,?,NULL,?)',(spec['operation_id'],spec['reseller_id'],fingerprint,payment_hash,'reserved',int(time.time())))
                    db.commit()
                except sqlite3.IntegrityError:raise RenewalRejected('This verified payment already belongs to an order') from None
                row=db.execute('SELECT * FROM operations WHERE operation_id=?',(spec['operation_id'],)).fetchone()
            state=row['state'];receipt=self.receipt(spec,json.loads(row['receipt'])) if row['receipt'] else None
            if state not in {'reserved','sending','needs_review','committed','refresh_pending','fulfilled'} or state in {'committed','refresh_pending','fulfilled'} and receipt is None:
                raise RenewalRejected('Invalid renewal ledger state; original operation retained')
            def save(next_state,result=None):
                db.execute('UPDATE operations SET state=?,receipt=?,updated_at=? WHERE operation_id=?',(next_state,json.dumps(result) if result is not None else None,int(time.time()),spec['operation_id']));db.commit()
            if state=='fulfilled':return {'state':'fulfilled',**receipt}
            if state in ('sending','needs_review'):
                try:known=self.panel.reconcile(spec,fingerprint,payment_hash)
                except Exception:known=None
                if known=={'state':'not_applied'}:
                    # Must come from the atomic adapter after its authoritative
                    # reseller/line transaction locks, never a failed HTTP read.
                    state='reserved'
                elif known is not None:receipt=self.receipt(spec,known);state='committed';save(state,receipt)
                else:save('needs_review');return {'state':'needs_review','operation_id':spec['operation_id']}
            if state=='reserved':
                self.panel.validate(spec)
                save('sending')
                try:receipt=self.receipt(spec,self.panel.renew_once(spec,fingerprint,payment_hash))
                except Exception:
                    save('needs_review')
                    return {'state':'needs_review','operation_id':spec['operation_id']}
                save('committed',receipt)
            # Native refresh is repeatable; the paid renewal/credit debit is NOT.
            try:ready=self.panel.refresh_and_verify(spec,receipt)
            except Exception:ready=False
            if ready is not True:
                save('refresh_pending',receipt)
                return {'state':'refresh_pending','operation_id':spec['operation_id'],'line_id':spec['line_id']}
            save('fulfilled',receipt)
            # Historical fulfilment, not a lasting playback grant. The app still
            # requires fresh primary verification after this response.
            return {'state':'fulfilled',**receipt}
