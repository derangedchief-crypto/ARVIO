"""Restricted server-to-server trial extension for the supplied iptv-auth-worker.

No import-time service startup, panel connection, SQL schema mutation or app secret.
Install from the END of the supplied main.py with install_trial_routes(app, globals()).
Disabled unless a separate BRANDED_TRIAL_SHARED_SECRET and private state directory exist.
"""
import contextlib
import decimal
import datetime
import fcntl
import hashlib
import hmac
import json
import logging
import os
from pathlib import Path
import re
import secrets
import sqlite3
import string
import threading
import time
import unicodedata


class TrialRejected(Exception):
    pass


class _TrialLogFilter(logging.Filter):
    def __init__(self):super().__init__();self.local=threading.local()
    def filter(self, record):
        if getattr(self.local,'active',False):
            # The legacy helper can log arbitrary native panel response text.
            # Do not let a panel echo place trial contact details into logs.
            record.msg='Branded trial panel registration status (private response omitted)'
            record.args=()
        return True


def serialize_native_refresh(worker):
    original=worker.get('_overcast_refresh_customer')
    if callable(original) and not getattr(original,'_branded_serialized',False):
        lock=threading.RLock()
        def wrapped(line_id):
            with lock:return original(line_id)
        wrapped._branded_serialized=True
        worker['_overcast_refresh_customer']=wrapped


def validate(value):
    fields = {'operation_id', 'brand_id', 'reseller_id', 'device_ref', 'package_id', 'plan_fingerprint', 'first_name', 'phone'}
    if not isinstance(value, dict) or set(value) != fields:
        raise TrialRejected('Invalid trial fields')
    value = dict(value)
    for key, size in [('operation_id', 32), ('brand_id', 32), ('device_ref', 64), ('plan_fingerprint',64)]:
        if not isinstance(value[key], str) or not re.fullmatch('[a-f0-9]{%d}' % size, value[key]):
            raise TrialRejected('Invalid trial identity')
    if not isinstance(value['reseller_id'], str) or not re.fullmatch('[1-9][0-9]{0,18}', value['reseller_id']):
        raise TrialRejected('Invalid reseller identity')
    if type(value['package_id']) is not int or not 1 <= value['package_id'] <= 2**31-1:
        raise TrialRejected('Invalid trial plan')
    name = value['first_name']
    if not isinstance(name,str):raise TrialRejected('Enter a first name')
    name=unicodedata.normalize('NFC',name)
    if not 1 <= len(name) <= 60 or not all(c.isalpha() or c in " '-’" for c in name) or not name.strip():
        raise TrialRejected('Enter a first name without control characters')
    value['first_name'] = ' '.join(name.split())
    # Country choice/normalization belongs to the public form. No SMS verification.
    if not isinstance(value['phone'], str) or not re.fullmatch(r'\+[1-9][0-9]{7,14}', value['phone']):
        raise TrialRejected('Use an international phone number including country code')
    return value


def signature(secret, timestamp, raw, domain='branded-trial-v1'):
    return hmac.new(secret.encode(), domain.encode()+b'\n'+str(timestamp).encode()+b'\n'+raw, hashlib.sha256).hexdigest()


def plan_document(plan):
    """Canonical revision binds grants to actual price/duration/groups, not just an ID."""
    try:
        price=decimal.Decimal(str(plan.get('credits') or '0'))
        def bounded_integer(raw,default):
            n=decimal.Decimal(str(raw if raw not in (None,'') else default))
            if not n.is_finite() or n<0 or n>1000000 or n!=n.to_integral_value():raise ValueError()
            return int(n)
        duration=bounded_integer(plan.get('duration'),0);connections=bounded_integer(plan.get('max_connections') or 1,1);legacy_duration=bounded_integer(plan.get('duration_value'),0)
        groups=group_ids(plan.get('bouquets') or '[]')
        period=str(plan.get('duration_period') or '').lower()
        name=str(plan.get('package_name') or '')
        if not price.is_finite() or not 0<=price<=decimal.Decimal('1000000000') or duration<1 or duration>1000000 or not 1<=connections<=100 or not 0<=legacy_duration<=1000000:
            raise ValueError()
        if price.normalize().as_tuple().exponent < -8:raise ValueError()
        if period not in {'hour','hours','day','days','week','weeks','month','months','year','years'}:raise ValueError()
        if not 1<=len(name)<=80 or any(ord(c)<32 or ord(c)==127 for c in name):raise ValueError()
        doc={'id':int(plan['id']),'name':name,'duration':duration,'duration_value':legacy_duration,'period':period,'max_connections':connections,
             'credits':format(price.normalize(),'f'),'bouquets':groups}
        if not 1<=doc['id']<=2**31-1:raise ValueError()
    except (ValueError,TypeError,decimal.InvalidOperation,KeyError):raise TrialRejected('Invalid trial package metadata') from None
    doc['fingerprint']=hashlib.sha256(json.dumps(doc,sort_keys=True,separators=(',',':'),ensure_ascii=False).encode()).hexdigest()
    return doc


def group_ids(raw):
    try:groups=json.loads(raw)
    except (ValueError,TypeError):raise TrialRejected('Invalid trial groups') from None
    if not isinstance(groups,list) or not groups or len(groups)>10000:raise TrialRejected('Invalid trial groups')
    result=[]
    for group in groups:
        if type(group) is int:value=group
        elif isinstance(group,str) and re.fullmatch('[0-9]{1,10}',group):value=int(group)
        else:raise TrialRejected('Invalid trial groups')
        if not 1<=value<=2**31-1:raise TrialRejected('Invalid trial groups')
        result.append(value)
    return result


class TrialService:
    def __init__(self, directory, panel):
        self.directory = Path(directory)
        self.panel = panel

    @contextlib.contextmanager
    def locked(self):
        self.directory.mkdir(mode=0o700, parents=True, exist_ok=True)
        if self.directory.is_symlink() or self.directory.stat().st_mode & 0o077:
            raise TrialRejected('Private trial storage permissions require attention')
        fd = os.open(self.directory/'trial.lock', os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
        with os.fdopen(fd, 'a') as lock:
            deadline = time.monotonic()+10
            while True:
                try: fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB);break
                except BlockingIOError:
                    if time.monotonic()>=deadline: raise TrialRejected('Trial service busy; retry the same request') from None
                    time.sleep(0.05)
            key_file = self.directory/'identity.key'
            if not key_file.exists():
                if (self.directory/'trials.sqlite').exists():
                    raise TrialRejected('Restore the original trial identity key before accepting requests')
                key_fd = os.open(key_file, os.O_CREAT | os.O_EXCL | os.O_WRONLY | os.O_NOFOLLOW, 0o600)
                with os.fdopen(key_fd, 'wb') as out: out.write(secrets.token_bytes(32));out.flush();os.fsync(out.fileno())
            if key_file.is_symlink() or key_file.stat().st_mode & 0o077:
                raise TrialRejected('Private trial key permissions require attention')
            key = key_file.read_bytes()
            if len(key) != 32: raise TrialRejected('Restore the original trial identity key')
            path = self.directory/'trials.sqlite'
            if path.is_symlink(): raise TrialRejected('Invalid trial storage path')
            if path.exists() and path.stat().st_mode & 0o077: raise TrialRejected('Private trial ledger permissions require attention')
            if not path.exists():
                ledger_fd=os.open(path,os.O_CREAT | os.O_EXCL | os.O_RDWR | os.O_NOFOLLOW,0o600)
                os.close(ledger_fd)
            db = sqlite3.connect(path, timeout=30)
            try:
                db.row_factory = sqlite3.Row
                db.execute('PRAGMA synchronous=FULL')
                db.execute('CREATE TABLE IF NOT EXISTS trials (operation_id TEXT PRIMARY KEY, reseller_id TEXT NOT NULL, customer_ref TEXT, device_ref TEXT, fingerprint TEXT NOT NULL, state TEXT NOT NULL, line_id TEXT, created_at INTEGER NOT NULL, UNIQUE(reseller_id,customer_ref,device_ref))')
                db.commit()
                yield db, key
            finally: db.close()

    def request(self, value):
        spec = validate(value)
        # Entire operation is serialized across service processes, including post-commit repair.
        # A process crash releases the OS lock but NOT the durable sending reservation.
        with self.locked() as (db, key):
            operation = spec['operation_id']
            customer = hmac.new(key, b'customer-phone-v1\n'+spec['reseller_id'].encode()+b'\n'+spec['phone'].encode(), hashlib.sha256).hexdigest()
            digest = hmac.new(key, json.dumps(spec, sort_keys=True, separators=(',', ':')).encode(), hashlib.sha256).hexdigest()
            row = db.execute('SELECT * FROM trials WHERE operation_id=?', (operation,)).fetchone()
            if row and row['fingerprint'] != digest: raise TrialRejected('Request key already belongs to another trial request')
            if not row:
                # Validate current active owner/trial policy before reserving eligibility.
                self.panel.check(spec)
                try:
                    db.execute('INSERT INTO trials VALUES (?,?,?,?,?,?,NULL,?)', (operation, spec['reseller_id'], customer, spec['device_ref'], digest, 'reserved', int(time.time())))
                    db.commit()
                except sqlite3.IntegrityError:
                    db.rollback()
                    raise TrialRejected('This customer/device already used or requested a trial with this reseller') from None
                row = db.execute('SELECT * FROM trials WHERE operation_id=?', (operation,)).fetchone()
            state, line_id = row['state'], row['line_id']
            if state == 'failed': return {'state': 'failed', 'operation_id': operation}
            if state in {'sending', 'needs_review'}:
                # Never repeat an uncertain create. Authoritative lookup takes the same
                # reseller lock as creation, so it waits for any outstanding DB transaction.
                line_id = self.panel.find(spec)
                if not line_id:
                    db.execute("UPDATE trials SET state='needs_review' WHERE operation_id=?", (operation,));db.commit()
                    return {'state': 'needs_review', 'operation_id': operation}
            if state == 'reserved':
                db.execute("UPDATE trials SET state='sending' WHERE operation_id=?", (operation,));db.commit()
                try: line_id = self.panel.create(spec)
                except TrialRejected:
                    # Adapter raises this only after definitive validation/rollback, never
                    # a commit/network error. Preserve receipt but release the unused scope.
                    db.execute("UPDATE trials SET state='failed',customer_ref=NULL,device_ref=NULL WHERE operation_id=?", (operation,));db.commit()
                    raise
                except Exception:
                    return {'state': 'needs_review', 'operation_id': operation}
            if not line_id: return {'state': 'needs_review', 'operation_id': operation}
            if state != 'prepared':
                db.execute("UPDATE trials SET state='created',line_id=? WHERE operation_id=?", (str(line_id),operation));db.commit()
            # Recheck eligibility before delivering or repairing any stored result.
            self.panel.check(spec)
            if state != 'prepared' and not self.panel.refresh(line_id):
                return {'state': 'registration_pending', 'operation_id': operation, 'line_id': str(line_id)}
            result = self.panel.result(spec, line_id)
            db.execute("UPDATE trials SET state='prepared' WHERE operation_id=?", (operation,));db.commit()
            return {'state': 'prepared', 'operation_id': operation, 'reseller_id':spec['reseller_id'],'line_id': str(line_id),
                'plan_id':spec['package_id'],'plan_fingerprint':spec['plan_fingerprint'],'notes_recorded':True,'account': result}

    def status(self, value):
        if not isinstance(value,dict) or set(value)!={'operation_id','reseller_id'} or not isinstance(value['operation_id'],str) or not re.fullmatch('[a-f0-9]{32}',value['operation_id']) or not isinstance(value['reseller_id'],str) or not re.fullmatch('[1-9][0-9]{0,18}',value['reseller_id']):raise TrialRejected('Invalid trial status identity')
        with self.locked() as (db,_):
            row=db.execute('SELECT * FROM trials WHERE operation_id=? AND reseller_id=?',(value['operation_id'],value['reseller_id'])).fetchone()
            if not row or not row['line_id']:raise TrialRejected('No confirmed trial line for this operation')
            observed=time.time_ns()//1000;data=self.panel.line_status(row['line_id'],row['reseller_id'])
            return dict(data,operation_id=value['operation_id'],reseller_id=value['reseller_id'],line_id=row['line_id'],checked_at_us=observed)


class ExistingPanelAdapter:
    """Reuses the UPLOADED worker's pool, credit ledger and native panel refresh.
    Does not call its non-idempotent public create-line endpoint or alter panel schema.
    """
    def __init__(self, worker):
        self.worker = worker
        logger=worker.get('log');self.log_filter=None
        if isinstance(logger,logging.Logger):
            self.log_filter=next((f for f in logger.filters if isinstance(f,_TrialLogFilter)),None)
            if self.log_filter is None:self.log_filter=_TrialLogFilter();logger.addFilter(self.log_filter)

    def owner_plan(self, cur, spec):
        cur.execute('SELECT id,username,credits,status FROM users WHERE id=%s FOR UPDATE', (spec['reseller_id'],))
        owner = cur.fetchone()
        if not owner or str(owner.get('status')) != '1': raise TrialRejected('Reseller is unavailable')
        cur.execute('SELECT * FROM users_packages WHERE id=%s AND enabled=1 AND activated=1 FOR UPDATE', (spec['package_id'],))
        plan = cur.fetchone()
        if not plan or str(plan.get('type')) != '1': raise TrialRejected('Select an enabled trial plan')
        group, overrides = self.worker['_reseller_package_scope'](cur, owner['username'])
        if not self.worker['_package_allowed'](plan, group, overrides): raise TrialRejected('Trial plan is unavailable for this reseller')
        duration = int(plan.get('duration') or 0)
        period = str(plan.get('duration_period') or '').lower()
        if duration <= 0 or period not in {'hour','hours','day','days','week','weeks','month','months','year','years'}:
            raise TrialRejected('Invalid trial duration')
        try: price = decimal.Decimal(str(plan.get('credits') or '0'))
        except decimal.InvalidOperation: raise TrialRejected('Invalid trial price') from None
        if not price.is_finite() or price < 0: raise TrialRejected('Invalid trial price')
        if not hmac.compare_digest(plan_document(plan)['fingerprint'],spec['plan_fingerprint']):raise TrialRejected('Trial plan changed; review its current limits and price')
        return owner, plan, price

    def catalog(self, reseller_id):
        if not isinstance(reseller_id,str) or not re.fullmatch('[1-9][0-9]{0,18}',reseller_id):raise TrialRejected('Invalid reseller identity')
        with self.worker['panel_db_cursor'](autocommit=False) as (cur,conn):
            try:
                cur.execute('SELECT id,username,status FROM users WHERE id=%s FOR UPDATE',(reseller_id,));owner=cur.fetchone()
                if not owner or str(owner.get('status'))!='1':raise TrialRejected('Reseller is unavailable')
                group,overrides=self.worker['_reseller_package_scope'](cur,owner['username'])
                cur.execute('SELECT id,package_name,credits,duration,duration_value,duration_period,bouquets,max_connections,type,groups FROM users_packages WHERE enabled=1 AND activated=1 AND type=1 ORDER BY id LIMIT 201')
                rows=cur.fetchall()
                if len(rows)>200:raise TrialRejected('Too many trial plans; narrow the panel catalog first')
                plans=[plan_document(p) for p in rows if self.worker['_package_allowed'](p,group,overrides)]
                return {'reseller_id':reseller_id,'plans':plans}
            finally:conn.rollback()

    def check(self, spec):
        with self.worker['panel_db_cursor'](autocommit=False) as (cur, conn):
            try: self.owner_plan(cur, spec)
            finally: conn.rollback()

    @staticmethod
    def notes(spec):
        return f"Trial contact: {spec['first_name']} | Phone: {spec['phone']} | Trial request: {spec['operation_id']}"

    def find_locked(self, cur, spec):
        # Exact generated note and owner; no user-controlled wildcard correlation.
        cur.execute('SELECT id FROM customers WHERE member_id=%s AND reseller_notes=%s', (spec['reseller_id'], self.notes(spec)))
        rows = cur.fetchall()
        if len(rows) > 1: raise TrialRejected('Trial result needs owner review')
        return str(rows[0]['id']) if rows else None

    def find(self, spec):
        with self.worker['panel_db_cursor'](autocommit=False) as (cur, conn):
            try:
                self.owner_plan(cur, spec)
                return self.find_locked(cur, spec)
            finally: conn.rollback()

    def create(self, spec):
        with self.worker['panel_db_cursor'](autocommit=False) as (cur, conn):
            try:
                owner, plan, price = self.owner_plan(cur, spec)
                existing = self.find_locked(cur, spec)
                if existing: conn.rollback();return existing
                balance = decimal.Decimal(str(owner.get('credits') or '0'))
                if balance < price: raise TrialRejected('Insufficient reseller credits')
                alphabet = string.ascii_lowercase + string.digits
                username = None
                for _ in range(10):
                    candidate = ''.join(secrets.choice(alphabet) for _ in range(16))
                    cur.execute('SELECT id FROM customers WHERE username=%s LIMIT 1', (candidate,))
                    if not cur.fetchone(): username=candidate;break
                if not username: raise TrialRejected('Could not allocate a trial account')
                password = ''.join(secrets.choice(alphabet) for _ in range(24))
                try:activation_code=self.worker['_new_activation_code'](cur)
                except RuntimeError:raise TrialRejected('Could not allocate a trial activation code') from None
                if not isinstance(activation_code,str) or not re.fullmatch('[0-9]{6}',activation_code):raise TrialRejected('Invalid trial activation code allocation')
                bouquets = group_ids(plan.get('bouquets') or '[]')
                connections = int(plan.get('max_connections') or 1)
                if connections<1 or connections>100: raise TrialRejected('Trial connection limit is invalid')
                expiry = self.worker['_add_duration'](self.worker['_utcnow'](), plan['duration'], plan['duration_period'])
                if expiry.tzinfo is None: expiry=expiry.replace(tzinfo=datetime.timezone.utc)
                # Only this NEW requested trial is started. Never update an existing
                # paid line or infer success from the panel's form-action spelling.
                cur.execute("INSERT INTO customers (member_id,username,password,exp_date,enabled,activated,duration,duration_period,duration_value,admin_notes,reseller_notes,bouquet,allowed_outputs,max_connections,is_restreamer,is_trial,allowed_ips,allowed_ua,created_at,package_id,activation_code,updated) VALUES (%s,%s,%s,%s,1,1,%s,%s,%s,'',%s,%s,'[1,2,3,4]',%s,0,1,'[]','[]',%s,%s,%s,NOW())",
                    (owner['id'],username,password,int(expiry.timestamp()),plan['duration'],plan['duration_period'],plan.get('duration_value') or 0,self.notes(spec),json.dumps(bouquets),connections,int(time.time()),plan['id'],activation_code))
                line_id = cur.lastrowid
                self.worker['_set_credits'](cur,owner['id'],balance-price,-price,balance,'line_create',owner['id'],'customer',line_id)
            except TrialRejected:
                conn.rollback();raise
            except Exception:
                conn.rollback();raise
            # If commit throws, the caller treats the outcome as uncertain and
            # retains the reservation. Never report a definite no-line failure.
            conn.commit()
            return str(line_id)

    def refresh(self, line_id):
        prior=getattr(self.log_filter.local,'active',False) if self.log_filter else False
        if self.log_filter:self.log_filter.local.active=True
        try:return self.worker['_overcast_refresh_customer'](int(line_id))
        finally:
            if self.log_filter:self.log_filter.local.active=prior

    def result(self, spec, line_id):
        with self.worker['panel_db_cursor']() as (cur, _):
            cur.execute('SELECT id,member_id,package_id,is_trial,enabled,activated,username,password,activation_code,exp_date,reseller_notes,bouquet,max_connections,duration,duration_period,duration_value FROM customers WHERE id=%s AND member_id=%s', (line_id,spec['reseller_id']))
            row = cur.fetchone()
        if not row or str(row['id']) != str(line_id) or str(row['member_id']) != spec['reseller_id'] or str(row['package_id']) != str(spec['package_id']) or str(row['is_trial']) != '1' or str(row['enabled']) != '1' or row['reseller_notes'] != self.notes(spec):
            raise TrialRejected('Created trial needs owner review')
        if row['exp_date'] not in (None,0,'0','') and int(row['exp_date'])<=time.time():raise TrialRejected('Trial has expired; no new line is created by retrying')
        if str(row.get('activated'))!='1':raise TrialRejected('Panel has not confirmed trial activation')
        with self.worker['panel_db_cursor']() as (cur,_):
            cur.execute('SELECT * FROM users_packages WHERE id=%s AND enabled=1 AND activated=1 AND type=1',(spec['package_id'],));plan=cur.fetchone()
        if not plan:raise TrialRejected('Trial package is unavailable')
        doc=plan_document(plan)
        try:matches=doc['fingerprint']==spec['plan_fingerprint'] and int(row['max_connections'])==doc['max_connections'] and sorted(group_ids(row['bouquet']))==sorted(doc['bouquets']) and int(row['duration'])==doc['duration'] and int(row.get('duration_value') or 0)==doc['duration_value'] and str(row['duration_period']).lower().rstrip('s')==doc['period'].rstrip('s')
        except (ValueError,TypeError,KeyError):matches=False
        if not matches:raise TrialRejected('Created trial limits require owner review')
        code=str(row.get('activation_code') or '')
        if not re.fullmatch('[0-9]{6}',code):raise TrialRejected('Panel has not confirmed a usable activation code')
        with self.worker['panel_db_cursor']() as (cur,_):
            cur.execute('SELECT id FROM customers WHERE activation_code=%s AND enabled=1 AND activated=1 AND (exp_date IS NULL OR exp_date=0 OR exp_date>%s) LIMIT 2',(code,int(time.time())));codes=cur.fetchall()
        if len(codes)!=1 or str(codes[0]['id'])!=str(line_id):raise TrialRejected('Trial activation code requires owner review')
        # This is a privileged server response for ONE operation, never a fleet list.
        return {key:row[key] for key in ['username','password','activation_code','exp_date']}

    def line_status(self,line_id,reseller_id):
        with self.worker['panel_db_cursor']() as (cur,_):
            cur.execute('SELECT id,member_id,is_trial,enabled,activated,exp_date FROM customers WHERE id=%s AND member_id=%s',(line_id,reseller_id));row=cur.fetchone()
        if not row or str(row['id'])!=str(line_id) or str(row['member_id'])!=str(reseller_id):return {'state':'unavailable','expires_at':None}
        expiry=int(row['exp_date']) if row['exp_date'] not in (None,0,'0','') else None
        if expiry is not None and not 1<=expiry<=4102444800:raise TrialRejected('Invalid panel expiry')
        state='converted' if str(row['is_trial'])=='0' else 'restricted' if str(row['enabled'])!='1' else 'pending_activation' if str(row.get('activated'))!='1' or expiry is None else 'expired' if expiry<=time.time() else 'active'
        return {'state':state,'expires_at':expiry}


def install_trial_routes(app, worker):
    from fastapi import HTTPException, Request
    from fastapi.concurrency import run_in_threadpool
    secret = os.environ.get('BRANDED_TRIAL_SHARED_SECRET', '')
    directory = os.environ.get('BRANDED_TRIAL_STATE_DIR', '')
    # New trial work runs in the threadpool. Protect the legacy shared cookie jar
    # for BOTH old portal callers and new trial callers in this worker process.
    enabled=bool(directory and re.fullmatch('[a-f0-9]{64}',secret))
    provision_enabled=os.environ.get('BRANDED_TRIAL_PROVISION_ENABLED','false')=='true'
    if enabled:serialize_native_refresh(worker)
    service = TrialService(directory, ExistingPanelAdapter(worker)) if enabled else None

    @app.post('/branded-trials/provision')
    async def provision(request: Request):
        raw=await authenticated_body(request,'branded-trial-v1')
        if not provision_enabled:raise HTTPException(503,'Trial creation is disabled by the owner; policy catalog remains read-only')
        try: return await run_in_threadpool(service.request,json.loads(raw))
        except (TrialRejected, ValueError, TypeError): raise HTTPException(409,'Trial unavailable or already requested; review this operation before retrying') from None
        except Exception: raise HTTPException(503,'Trial outcome requires review; retry only with the same operation ID') from None

    @app.post('/branded-trials/plans')
    async def plans(request: Request):
        raw=await authenticated_body(request,'branded-trial-plans-v1')
        try:
            body=json.loads(raw)
            if not isinstance(body,dict) or set(body)!={'reseller_id'}:raise TrialRejected('Invalid catalog request')
            return await run_in_threadpool(ExistingPanelAdapter(worker).catalog,body['reseller_id'])
        except (TrialRejected, ValueError, TypeError):raise HTTPException(409,'Trial catalog unavailable for this reseller') from None
        except Exception:raise HTTPException(503,'Trial catalog temporarily unavailable') from None

    @app.post('/branded-trials/status')
    async def status(request:Request):
        raw=await authenticated_body(request,'branded-trial-status-v1')
        try:return await run_in_threadpool(service.status,json.loads(raw))
        except (TrialRejected,ValueError,TypeError):raise HTTPException(409,'Trial status is unavailable for this operation') from None
        except Exception:raise HTTPException(503,'Trial status temporarily unavailable') from None

    async def authenticated_body(request,domain):
        if not service or not re.fullmatch('[a-f0-9]{64}',secret): raise HTTPException(503,'Branded trials are disabled')
        # Bound before decoding (do not read an unbounded body into memory).
        raw = bytearray()
        async for chunk in request.stream():
            if len(raw)+len(chunk)>8192: raise HTTPException(413,'Trial request too large')
            raw.extend(chunk)
        raw = bytes(raw)
        stamp = request.headers.get('x-trial-timestamp','')
        supplied = request.headers.get('x-trial-signature','')
        if not re.fullmatch('[0-9]{1,12}',stamp) or not re.fullmatch('[a-f0-9]{64}',supplied) or abs(time.time()-int(stamp))>120 or not hmac.compare_digest(signature(secret,stamp,raw,domain),supplied):
            raise HTTPException(401,'Invalid trial bridge signature')
        return raw
