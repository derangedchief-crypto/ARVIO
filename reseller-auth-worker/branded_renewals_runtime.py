"""Additive paid-renewal bridge for the EXISTING reseller worker.
No import-time startup/IO. Routes stay unavailable without a separate secret;
renewal writes additionally default OFF. Original /renew-line remains compatible.
"""
import asyncio
import contextvars
import datetime
import decimal
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import time
from branded_renewals import RenewalOperations,RenewalRejected,canonical,validate

_operation=contextvars.ContextVar('branded_renewal_operation',default=None)

def plan_document(plan):
    try:
        credit=decimal.Decimal(str(plan.get('credits') or '0'));duration=int(plan.get('duration') or 0);connections=int(plan.get('max_connections') or 1)
        groups=json.loads(plan.get('bouquets') or '[]');name=plan['package_name'];period=str(plan.get('duration_period') or '').lower();legacy=int(plan.get('duration_value') or 0)
        if not credit.is_finite() or credit<0 or credit>1000000000 or credit.normalize().as_tuple().exponent<-8 or not 1<=duration<=1000000 or not 1<=connections<=100 or not 0<=legacy<=1000000:raise ValueError()
        if not isinstance(name,str) or not 1<=len(name)<=80 or any(ord(c)<32 or ord(c)==127 for c in name):raise ValueError()
        if period not in {'hour','hours','day','days','week','weeks','month','months','year','years'} or not isinstance(groups,list) or not groups or len(groups)>10000:raise ValueError()
        bouquets=[]
        for v in groups:
            if type(v) is not int and not (isinstance(v,str) and re.fullmatch('[0-9]{1,10}',v)):raise ValueError()
            n=int(v)
            if not 1<=n<=2147483647:raise ValueError()
            bouquets.append(n)
        doc={'id':int(plan['id']),'name':name,'duration':duration,'duration_value':legacy,'period':period,'max_connections':connections,'credits':format(credit.normalize(),'f'),'bouquets':bouquets}
        if not 1<=doc['id']<=2147483647:raise ValueError()
    except (ValueError,TypeError,KeyError,decimal.InvalidOperation):raise RenewalRejected('Invalid paid renewal package') from None
    doc['fingerprint']=hashlib.sha256(json.dumps(doc,sort_keys=True,separators=(',',':'),ensure_ascii=False).encode()).hexdigest()
    return doc

def primary_matches(row,fingerprint):
    value=hashlib.sha256((str(row['username'])+'\n'+str(row['password'])).encode()).hexdigest()
    return hmac.compare_digest(value,fingerprint)

def marker(meta,expiry):
    return 'BRN1|'+meta['spec']['operation_id']+'|'+meta['fingerprint']+'|'+meta['payment_hash']+'|'+str(expiry)

def lookup_receipt(cur,spec,fingerprint,payment_hash):
    cur.execute("SELECT user_id,ref_id,note FROM users_credits_log WHERE user_id=%s AND reason='line_renew' AND ref_type='customer' AND (note LIKE %s OR note LIKE %s) FOR UPDATE",(spec['reseller_id'],'BRN1|'+spec['operation_id']+'|%','BRN1|%|'+payment_hash+'|%'))
    rows=cur.fetchall()
    if not rows:return None
    if len(rows)!=1:raise RenewalRejected('Renewal journal needs review')
    row=rows[0];m=re.fullmatch(r'BRN1\|([a-f0-9]{32})\|([a-f0-9]{64})\|([a-f0-9]{64})\|([0-9]{1,10})',str(row.get('note') or ''))
    if not m or str(row['user_id'])!=spec['reseller_id'] or str(row['ref_id'])!=spec['line_id'] or m[1]!=spec['operation_id'] or not hmac.compare_digest(m[2],fingerprint) or not hmac.compare_digest(m[3],payment_hash):raise RenewalRejected('Payment/order journal conflict')
    expiry=int(m[4])
    if not 1<=expiry<=4102444800:raise RenewalRejected('Invalid committed expiry')
    return {k:spec[k] for k in ['operation_id','reseller_id','line_id','package_id']}|{'expires_at':expiry}

class NativeRenewalAdapter:
    def __init__(self,worker,atomic_guard_version):
        self.worker=worker;self.refreshed={}
        function=worker.get('renew_line');names=set(getattr(getattr(function,'__code__',None),'co_names',()))
        fields=getattr(worker.get('RenewLineRequest'),'model_fields',{})
        guarded=atomic_guard_version==1 and {'_branded_renewal_prepare','_branded_renewal_journal'}.issubset(names) and 'renewal_operation' in fields
        self.capabilities={'atomic_renewal':1,'unique_payment_reference':1} if guarded else {}
    def owner(self,cur,reseller):
        cur.execute('SELECT id,username,status,credits FROM users WHERE id=%s FOR UPDATE',(reseller,));owner=cur.fetchone()
        if not owner or str(owner.get('status'))!='1':raise RenewalRejected('Reseller is unavailable')
        return owner
    @staticmethod
    def journal_ready(cur):
        cur.execute("SELECT TABLE_NAME AS table_name,ENGINE AS engine FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('users','customers','users_credits_log','users_packages')")
        tables={row['table_name']:str(row['engine']).lower() for row in cur.fetchall()}
        if tables!={name:'innodb' for name in ['users','customers','users_credits_log','users_packages']}:raise RenewalRejected('Renewal and credit journal tables must support the same atomic transaction')
        cur.execute("SELECT CHARACTER_MAXIMUM_LENGTH AS note_capacity FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='users_credits_log' AND COLUMN_NAME='note'")
        row=cur.fetchone()
        if not row or int(row.get('note_capacity') or 0)<200:raise RenewalRejected('Existing credit journal cannot safely hold operation receipts')
    def plans(self,cur,owner):
        group,overrides=self.worker['_reseller_package_scope'](cur,owner['username'])
        cur.execute('SELECT * FROM users_packages WHERE enabled=1 AND activated=1 AND type<>1 ORDER BY id LIMIT 201');rows=cur.fetchall()
        if len(rows)>200:raise RenewalRejected('Too many paid renewal packages')
        output=[]
        for p in rows:
            if not self.worker['_package_allowed'](p,group,overrides):continue
            doc=plan_document(p);doc['funded']=decimal.Decimal(str(owner.get('credits') or 0))>=decimal.Decimal(doc['credits']);output.append(doc)
        return output
    def catalog(self,reseller):
        with self.worker['panel_db_cursor'](autocommit=False) as (cur,conn):
            try:owner=self.owner(cur,reseller);self.journal_ready(cur);return {'reseller_id':reseller,'plans':self.plans(cur,owner)}
            finally:conn.rollback()
    def eligibility(self,reseller,line,fingerprint):
        with self.worker['panel_db_cursor'](autocommit=False) as (cur,conn):
            try:
                owner=self.owner(cur,reseller);self.journal_ready(cur);row=self.worker['_lock_line_row'](cur,line,owner['id'])
                matched=bool(row and primary_matches(row,fingerprint));expiry=int(row.get('exp_date') or 0) if row else 0
                state='unavailable'
                if matched:
                    if str(row.get('enabled'))!='1' or str(row.get('activated'))!='1':state='restricted'
                    elif not expiry:state='unlimited'
                    else:state='expired' if expiry<=time.time() else 'active'
                current=int(row.get('package_id') or 0) if matched else 0
                if current<1:state='unavailable'
                plans=[p for p in self.plans(cur,owner) if p['id']==current] if state=='expired' else []
                return {'reseller_id':reseller,'line_id':line,'primary_match':matched,'state':state,'expires_at':expiry or None,'checked_at':int(time.time()),'current_package_id':current or None,'plans':plans}
            finally:conn.rollback()
    def validate(self,spec):
        with self.worker['panel_db_cursor'](autocommit=False) as (cur,conn):
            try:
                owner=self.owner(cur,spec['reseller_id']);self.journal_ready(cur);row=self.worker['_lock_line_row'](cur,spec['line_id'],owner['id'])
                if not row or not primary_matches(row,spec['primary_fingerprint']) or str(row.get('enabled'))!='1' or str(row.get('activated'))!='1' or not row.get('exp_date'):raise RenewalRejected('Original primary is unavailable/restricted')
                if str(row.get('package_id'))!=str(spec['package_id']):raise RenewalRejected('Current package changed; never switch packages during a paid renewal')
                if not any(p['id']==spec['package_id'] and p['fingerprint']==spec['plan_fingerprint'] and p['funded'] for p in self.plans(cur,owner)):raise RenewalRejected('Paid plan changed or reseller funding unavailable')
            finally:conn.rollback()
    def prepare(self,cur,reseller,body):
        meta=body.renewal_operation;authorized=_operation.get()
        if authorized is None or not hmac.compare_digest(canonical(meta),canonical(authorized)):raise RenewalRejected('Payment operation is not authorized on this route')
        spec=validate(meta['spec'])
        if str(reseller['id'])!=spec['reseller_id'] or str(body.line_id)!=spec['line_id'] or body.package_id!=spec['package_id'] or str(reseller.get('status'))!='1':raise RenewalRejected('Renewal owner/plan mismatch')
        row=self.worker['_lock_line_row'](cur,body.line_id,reseller['id'])
        if not row or not primary_matches(row,spec['primary_fingerprint']):raise RenewalRejected('Primary identity changed')
        self.journal_ready(cur);receipt=lookup_receipt(cur,spec,meta['fingerprint'],meta['payment_hash'])
        if receipt:return {'receipt':receipt}
        if str(row.get('package_id'))!=str(spec['package_id']):raise RenewalRejected('Current package changed; keep this paid order for review')
        if str(row.get('enabled'))!='1' or str(row.get('activated'))!='1' or not row.get('exp_date'):raise RenewalRejected('Restricted/unlimited primary cannot be auto-renewed')
        cur.execute('SELECT * FROM users_packages WHERE id=%s AND enabled=1 AND activated=1 FOR UPDATE',(spec['package_id'],));package=cur.fetchone()
        if not package or str(package.get('type'))=='1':raise RenewalRejected('Select a currently enabled paid package')
        group,overrides=self.worker['_reseller_package_scope'](cur,body.username)
        if not self.worker['_package_allowed'](package,group,overrides) or not hmac.compare_digest(plan_document(package)['fingerprint'],spec['plan_fingerprint']):raise RenewalRejected('Renewal package changed')
        return {'receipt':None,'package':package,'bouquets':plan_document(package)['bouquets']}
    def note(self,meta,expiry):
        if _operation.get() is None or not hmac.compare_digest(canonical(meta),canonical(_operation.get())):raise RenewalRejected('No authorized payment operation')
        return marker(meta,int(expiry.timestamp()))
    def journal(self,cur,reseller,body,note,price,before,after):
        meta=body.renewal_operation;spec=meta['spec'];found=lookup_receipt(cur,spec,meta['fingerprint'],meta['payment_hash'])
        if found is None and price==0:
            self.worker['_log_credit_change'](cur,reseller['id'],-price,before,after,'line_renew',reseller['id'],'customer',body.line_id,note)
            found=lookup_receipt(cur,spec,meta['fingerprint'],meta['payment_hash'])
        if found is None:raise RenewalRejected('Atomic credit receipt was not recorded; transaction must roll back')
    def replay(self,body,receipt,response_type):
        ready=self.refresh_and_verify(body.renewal_operation['spec'],receipt)
        return response_type(ok=ready,expire_at=datetime.datetime.fromtimestamp(receipt['expires_at'],datetime.timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ'),credits_charged='0',error=None if ready else 'Renewal already applied; refresh pending. Do not renew again.')
    def reconcile(self,spec,fingerprint,payment_hash):
        with self.worker['panel_db_cursor'](autocommit=False) as (cur,conn):
            try:
                owner=self.owner(cur,spec['reseller_id']);row=self.worker['_lock_line_row'](cur,spec['line_id'],owner['id'])
                if not row or not primary_matches(row,spec['primary_fingerprint']):raise RenewalRejected('Original renewal ownership changed')
                return lookup_receipt(cur,spec,fingerprint,payment_hash) or {'state':'not_applied'}
            finally:conn.rollback()
    def renew_once(self,spec,fingerprint,payment_hash):
        if not self.capabilities:raise RenewalRejected('Legacy renewal route is not atomically guarded')
        meta={'spec':spec,'fingerprint':fingerprint,'payment_hash':payment_hash}
        with self.worker['panel_db_cursor'](autocommit=False) as (cur,conn):
            try:username=self.owner(cur,spec['reseller_id'])['username']
            finally:conn.rollback()
        raw=json.dumps({'username':username,'line_id':spec['line_id'],'package_id':spec['package_id'],'sync_bouquets':False,'renewal_operation':meta},separators=(',',':')).encode()
        class Request:
            async def body(self):return raw
        stamp=str(int(time.time()));signature=hmac.new(self.worker['settings'].shared_secret.encode(),stamp.encode()+b'.'+raw,hashlib.sha256).hexdigest()
        token=_operation.set(meta)
        try:result=asyncio.run(self.worker['renew_line'](Request(),x_portal_signature=signature,x_portal_timestamp=stamp))
        finally:_operation.reset(token)
        receipt=self.reconcile(spec,fingerprint,payment_hash)
        if receipt=={'state':'not_applied'}:raise RenewalRejected('No committed renewal receipt; keep the same order')
        self.refreshed[spec['operation_id']]=result.ok is True
        return receipt
    def current(self,spec,receipt):
        with self.worker['panel_db_cursor'](autocommit=False) as (cur,conn):
            try:
                owner=self.owner(cur,spec['reseller_id']);row=self.worker['_lock_line_row'](cur,spec['line_id'],owner['id'])
                return bool(row and primary_matches(row,spec['primary_fingerprint']) and str(row.get('package_id'))==str(spec['package_id']) and str(row.get('enabled'))=='1' and str(row.get('activated'))=='1' and int(row.get('exp_date') or 0)>=receipt['expires_at'] and int(row.get('exp_date') or 0)>time.time())
            finally:conn.rollback()
    def refresh_and_verify(self,spec,receipt):
        if not self.current(spec,receipt):return False
        if not self.refreshed.pop(spec['operation_id'],False) and not self.worker['_overcast_refresh_customer'](int(spec['line_id'])):return False
        return self.current(spec,receipt)

def install_renewal_routes(app,worker,atomic_guard_version=0):
    from fastapi import HTTPException,Request
    from starlette.concurrency import run_in_threadpool
    # FastAPI resolves postponed/local annotations from module globals.
    globals()['Request']=Request
    panel=NativeRenewalAdapter(worker,atomic_guard_version)
    worker['_branded_renewal_prepare']=panel.prepare;worker['_branded_renewal_note']=panel.note;worker['_branded_renewal_journal']=panel.journal;worker['_branded_renewal_replay']=panel.replay
    secret=os.environ.get('BRANDED_RENEWAL_SHARED_SECRET','');directory=os.environ.get('BRANDED_RENEWAL_STATE_DIR','');writes=os.environ.get('BRANDED_RENEWAL_WRITE_ENABLED','false').lower()=='true'
    def configured():return bool(re.fullmatch('[a-f0-9]{64}',secret) and directory and Path(directory).is_absolute() and panel.capabilities)
    @app.get('/branded-renewals/health')
    def health():return {'schema_version':1,'configured':configured(),'atomic_renewal':1 if panel.capabilities else 0,'writes_enabled':bool(configured() and writes)}
    @app.post('/branded-renewals/{action}')
    async def endpoint(action:str,request:Request):
        if not configured():raise HTTPException(503,'Renewal bridge is not configured')
        if action not in ('catalog','eligibility','renew'):raise HTTPException(404,'Unknown renewal action')
        raw=bytearray()
        async for chunk in request.stream():
            raw.extend(chunk)
            if len(raw)>8192:raise HTTPException(413,'Request too large')
        stamp=request.headers.get('x-renewal-timestamp','');nonce=request.headers.get('x-renewal-nonce','');sig=request.headers.get('x-renewal-signature','')
        if not re.fullmatch('[0-9]{10,12}',stamp) or abs(int(stamp)-time.time())>120 or not re.fullmatch('[a-f0-9]{32}',nonce) or not hmac.compare_digest(hmac.new(secret.encode(),b'branded-renewal-request-v1\n'+stamp.encode()+b'\n'+nonce.encode()+b'\n'+raw,hashlib.sha256).hexdigest(),sig):raise HTTPException(403,'Invalid renewal authentication')
        try:
            value=json.loads(raw)
            if action=='catalog':
                if set(value)!={'reseller_id'} or not re.fullmatch('[1-9][0-9]{0,18}',value['reseller_id']):raise RenewalRejected('Invalid reseller')
                result=await run_in_threadpool(panel.catalog,value['reseller_id'])
            elif action=='eligibility':
                if set(value)!={'reseller_id','line_id','primary_fingerprint'} or any(not isinstance(value[k],str) or not re.fullmatch('[1-9][0-9]{0,18}',value[k]) for k in ['reseller_id','line_id']) or not re.fullmatch('[a-f0-9]{64}',value['primary_fingerprint']):raise RenewalRejected('Invalid renewal target')
                result=await run_in_threadpool(panel.eligibility,value['reseller_id'],value['line_id'],value['primary_fingerprint'])
            else:
                if not writes:raise HTTPException(503,'Renewal writes are disabled')
                result=await run_in_threadpool(RenewalOperations(directory,panel).request,validate(value))
        except HTTPException:raise
        except Exception:raise HTTPException(422,'Renewal operation unavailable; keep the original order') from None
        payload=json.dumps(result,separators=(',',':'))
        signature=hmac.new(secret.encode(),b'branded-renewal-result-v1\n'+stamp.encode()+b'\n'+nonce.encode()+b'\n'+payload.encode(),hashlib.sha256).hexdigest()
        return {'payload':payload,'signature':signature}
