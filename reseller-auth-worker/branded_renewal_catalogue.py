"""Catalogue-only extension. NO renewal imports, ledgers, debit/save calls or IO
at import time. A write-enabled environment flag CANNOT enable a write route.
"""
import decimal
import hashlib
import hmac
import json
import os
import re
import time
from renewal_packages import package_document


class Catalogue:
    def __init__(self,worker):self.worker=worker
    def read(self,reseller):
        with self.worker['panel_db_cursor'](autocommit=False) as (cur,conn):
            try:
                # Transaction-level, NOT session-level: pool users never inherit
                # read-only state after this request's mandatory rollback.
                cur.execute('START TRANSACTION READ ONLY')
                cur.execute('SELECT id,username,status,credits FROM users WHERE id=%s',(reseller,));owner=cur.fetchone()
                if not owner or str(owner.get('id'))!=reseller or str(owner.get('status'))!='1':raise ValueError('Reseller unavailable')
                group,overrides=self.worker['_reseller_package_scope'](cur,owner['username'])
                cur.execute('SELECT * FROM users_packages WHERE enabled=1 AND activated=1 AND type<>1 ORDER BY id LIMIT 201');rows=cur.fetchall()
                if len(rows)>200:raise ValueError('Too many offered packages')
                plans=[];balance=decimal.Decimal(str(owner.get('credits') or '0'))
                if not balance.is_finite():raise ValueError('Invalid balance')
                for row in rows:
                    if not self.worker['_package_allowed'](row,group,overrides):continue
                    p=package_document(row);p['funded']=balance>=decimal.Decimal(p['credits']);plans.append(p)
                return {'reseller_id':reseller,'plans':plans,'catalogue_only':True,'writes_enabled':False}
            finally:conn.rollback()


def install_catalogue_routes(app,worker):
    from fastapi import HTTPException,Request
    from starlette.concurrency import run_in_threadpool
    globals()['Request']=Request
    secret=os.environ.get('BRANDED_RENEWAL_SHARED_SECRET','')
    configured=bool(re.fullmatch('[a-f0-9]{64}',secret))
    catalogue=Catalogue(worker)
    @app.get('/branded-renewals/health')
    def health():return {'schema_version':1,'configured':configured,'catalogue_only':True,'writes_enabled':False,'atomic_renewal':0}
    @app.post('/branded-renewals/{action}')
    async def endpoint(action:str,request:Request):
        # Deny BEFORE authentication/DB access, even if someone sets a write flag.
        if action in ('renew','eligibility'):raise HTTPException(503,'Catalogue-only bridge; renewal writes and checkout are disabled')
        if action not in ('catalog','status'):raise HTTPException(404,'Unknown catalogue action')
        if not configured:raise HTTPException(503,'Catalogue connection is not configured')
        raw=bytearray()
        async for chunk in request.stream():
            raw.extend(chunk)
            if len(raw)>8192:raise HTTPException(413,'Request too large')
        stamp=request.headers.get('x-renewal-timestamp','');nonce=request.headers.get('x-renewal-nonce','');sig=request.headers.get('x-renewal-signature','')
        if not re.fullmatch('[0-9]{10,12}',stamp) or abs(int(stamp)-time.time())>120 or not re.fullmatch('[a-f0-9]{32}',nonce) or not hmac.compare_digest(hmac.new(secret.encode(),b'branded-renewal-request-v1\n'+stamp.encode()+b'\n'+nonce.encode()+b'\n'+raw,hashlib.sha256).hexdigest(),sig):raise HTTPException(403,'Invalid catalogue authentication')
        try:
            value=json.loads(raw)
            if action=='status':
                if value!={}:raise ValueError()
                result=health()
            else:
                if not isinstance(value,dict) or set(value)!={'reseller_id'} or not isinstance(value['reseller_id'],str) or not re.fullmatch('[1-9][0-9]{0,18}',value['reseller_id']):raise ValueError()
                result=await run_in_threadpool(catalogue.read,value['reseller_id'])
        except Exception:raise HTTPException(422,'Offered package catalogue unavailable; no changes made') from None
        payload=json.dumps(result,separators=(',',':'))
        if len(payload.encode())>120000:raise HTTPException(503,'Catalogue response exceeds supported limits')
        signature=hmac.new(secret.encode(),b'branded-renewal-result-v1\n'+stamp.encode()+b'\n'+nonce.encode()+b'\n'+payload.encode(),hashlib.sha256).hexdigest()
        return {'payload':payload,'signature':signature}
