"""Separate authenticated renewal service. No legacy auth/create/delete routes."""
import contextlib
import hashlib
import hmac
import json
import logging
import os
from pathlib import Path
import re
import sqlite3
import time
from fastapi import FastAPI,HTTPException,Request
from starlette.concurrency import run_in_threadpool
from mysql.connector import Error as PanelDatabaseError
from sshtunnel import BaseSSHTunnelForwarderError
import native_panel
from adapter import RenewalAdapter
from branded_renewals import RenewalOperations,RenewalDeferred,RenewalRejected,validate

log=logging.getLogger('iptv-renewal-worker.http')
adapter=RenewalAdapter(vars(native_panel))
secret=os.environ.get('RENEWAL_BRIDGE_SECRET','')
directory=Path(os.environ.get('RENEWAL_STATE_DIR','/var/lib/iptv-renewal-worker'))
expected_key=os.environ.get('RENEWAL_LEDGER_KEY_SHA256','')


def startup_report(stage,error=None):
    """Private on-host evidence survives installer rollback; never store values."""
    value={'stage':stage,'at':int(time.time()),'pid':os.getpid()}
    if error is not None:
        value['error_type']=type(error).__name__
        # RenewalRejected is our own fixed-text schema/transaction guard, not
        # a DB/SSH/validation exception that may contain credentials or input.
        if isinstance(error,RenewalRejected):value['guard']=str(error)
        import traceback
        value['frames']=[{'file':Path(f.filename).name,'line':f.lineno} for f in traceback.extract_tb(error.__traceback__)[-8:]]
    try:
        temporary=directory/('startup-'+str(os.getpid())+'.tmp')
        fd=os.open(temporary,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600)
        with os.fdopen(fd,'w') as stream:json.dump(value,stream)
        os.replace(temporary,directory/'startup-status.json')
    except OSError:pass


def state_ready():
    if not directory.is_absolute() or not re.fullmatch('[a-f0-9]{64}',expected_key):raise RuntimeError('Original renewal state anchor is required')
    key=directory/'renewal.key';ledger=directory/'renewals.sqlite'
    if directory.is_symlink() or not directory.is_dir() or directory.stat().st_mode&0o077 or key.is_symlink() or ledger.is_symlink() or not key.is_file() or not ledger.is_file():raise RuntimeError('Restore original renewal state; it is never reset automatically')
    if key.stat().st_mode&0o077 or ledger.stat().st_mode&0o077 or len(key.read_bytes())!=32 or hashlib.sha256(key.read_bytes()).hexdigest()!=expected_key:raise RuntimeError('Original renewal ledger key does not match its anchor or private permissions')


class AnchoredOperations(RenewalOperations):
    def validate_request(self,value):return validate(value,allow_trial_conversion=True)
    @contextlib.contextmanager
    def storage(self,operation):
        state_ready()
        with super().storage(operation) as (db,key):
            if hashlib.sha256(key).hexdigest()!=expected_key:raise RuntimeError('Renewal key changed; original state must be restored')
            yield db,key


@contextlib.asynccontextmanager
async def lifespan(app):
    stage='state-validation'
    try:
        startup_report(stage)
        if not re.fullmatch('[a-f0-9]{64}',secret):raise RuntimeError('Separate renewal bridge key is required')
        state_ready()
        stage='ssh-tunnel-and-database-pool';startup_report(stage)
        await run_in_threadpool(native_panel._start_tunnel_and_pool)
        stage='panel-transaction-journal-check';startup_report(stage)
        with native_panel.panel_db_cursor(autocommit=False) as (cur,conn):
            try:adapter.journal_ready(cur)
            finally:conn.rollback()
        stage='ready';startup_report(stage)
        yield
    except Exception as error:
        startup_report(stage,error)
        log.error('Renewal service startup/runtime unavailable: error_type=%s',type(error).__name__)
        raise RuntimeError('Renewal panel connection/readiness failed; private values omitted') from None
    finally:await run_in_threadpool(native_panel._stop_tunnel)


app=FastAPI(title='IPTV Renewal Worker',lifespan=lifespan)


def status():
    state_ready()
    return {'schema_version':1,'service':'iptv-renewal-worker','service_pid':os.getpid(),'configured':True,'mode':'renewals','catalogue_only':False,'writes_enabled':True,'atomic_renewal':1,'unique_payment_reference':1,'current_package_only':True,'renewal_login':1,'expired_line_reactivation':1,'trial_conversion':1}


@app.get('/health')
def health():return {'status':'ok',**status()}


@app.get('/branded-renewals/health')
def bridge_health():return status()


@app.post('/branded-renewals/{action}')
async def request(action:str,request:Request):
    if action not in ['status','catalog','eligibility','renew','identify']:raise HTTPException(404,'Unknown renewal action')
    raw=bytearray()
    async for chunk in request.stream():
        raw.extend(chunk)
        if len(raw)>8192:raise HTTPException(413,'Request too large')
    stamp=request.headers.get('x-renewal-timestamp','');nonce=request.headers.get('x-renewal-nonce','');signature=request.headers.get('x-renewal-signature','')
    if not re.fullmatch('[0-9]{10,12}',stamp) or abs(int(stamp)-time.time())>120 or not re.fullmatch('[a-f0-9]{32}',nonce) or not hmac.compare_digest(hmac.new(secret.encode(),b'branded-renewal-request-v1\n'+stamp.encode()+b'\n'+nonce.encode()+b'\n'+raw,hashlib.sha256).hexdigest(),signature):raise HTTPException(403,'Invalid renewal authentication')
    try:
        value=json.loads(raw);state_ready()
        if action=='status':
            if value!={}:raise ValueError()
            result=status()
        elif action=='identify':
            result=await run_in_threadpool(adapter.identify,value)
        elif action=='catalog':
            if not isinstance(value,dict) or set(value)!={'reseller_id'} or not isinstance(value['reseller_id'],str) or not re.fullmatch('[1-9][0-9]{0,18}',value['reseller_id']):raise ValueError()
            result=await run_in_threadpool(adapter.catalog,value['reseller_id'])
        elif action=='eligibility':
            if not isinstance(value,dict) or set(value)!={'reseller_id','line_id','primary_fingerprint'} or any(not isinstance(value[k],str) or not re.fullmatch('[1-9][0-9]{0,18}',value[k]) for k in ['reseller_id','line_id']) or not isinstance(value['primary_fingerprint'],str) or not re.fullmatch('[a-f0-9]{64}',value['primary_fingerprint']):raise ValueError()
            result=await run_in_threadpool(adapter.eligibility,value['reseller_id'],value['line_id'],value['primary_fingerprint'])
        else:
            spec=validate(value,allow_trial_conversion=True)
            result=await run_in_threadpool(AnchoredOperations(directory,adapter).request,spec)
            if result.get('state')=='needs_review':result={**result,'state':'reconcile_pending'}
        payload=json.dumps(result,separators=(',',':'))
        if len(payload.encode())>2000000:raise ValueError()
        return {'payload':payload,'signature':hmac.new(secret.encode(),('branded-renewal-result-v1\n'+stamp+'\n'+nonce+'\n'+payload).encode(),hashlib.sha256).hexdigest()}
    except RenewalDeferred:
        raise HTTPException(503,'Funding pending; the same paid order will retry without another customer charge') from None
    except (PanelDatabaseError,BaseSSHTunnelForwarderError,sqlite3.OperationalError,OSError,TimeoutError):
        raise HTTPException(503,'Temporary renewal connection/storage issue; retry the same paid operation, never a new charge') from None
    except Exception as error:
        log.warning('Renewal request unavailable: action=%s error_type=%s',action,type(error).__name__)
        raise HTTPException(422,'Renewal requires review; retain the same operation and never charge again') from None
