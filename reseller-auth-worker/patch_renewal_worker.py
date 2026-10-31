#!/usr/bin/env python3
"""Offline source transformer. Never starts a worker, reads config or deploys.
Recognizes only the reviewed original source plus its known additive trial hook.
"""
import argparse
import ast
import hashlib
import os
from pathlib import Path

BASE_SHA='c5a93d1a4d98f214a4427e8ac0ca7dbdcb03fb5d59632ad4212740a188a7e8d1'
TRIAL_HOOK='from branded_trials import install_trial_routes\ninstall_trial_routes(app, globals())'
HOOK='\n\n# BRANDED_RENEWAL_GUARD_V1\nfrom branded_renewals_runtime import install_renewal_routes\ninstall_renewal_routes(app, globals(), atomic_guard_version=1)\n'

def changes():
    return [
        ('    sync_bouquets: bool = False\n\n\nclass RenewLineResponse',
         '    sync_bouquets: bool = False\n    renewal_operation: dict | None = None\n\n\nclass RenewLineResponse'),
        ('            current_credits = Decimal(str(reseller["credits"] or 0))\n            if current_credits < price:',
         '            if body.renewal_operation is not None:\n                guarded = _branded_renewal_prepare(cur, reseller, body)\n                if guarded["receipt"] is not None:\n                    conn.rollback()\n                    return _branded_renewal_replay(body, guarded["receipt"], RenewLineResponse)\n                package = guarded["package"]\n                price = Decimal(str(package.get("credits") or "0"))\n                pkg_bouquets = guarded["bouquets"]\n            current_credits = Decimal(str(reseller["credits"] or 0))\n            if current_credits < price:'),
        ('            _set_credits(cur, reseller["id"], new_balance, -price, current_credits, "line_renew", reseller["id"], "customer", body.line_id)\n            conn.commit()',
         '            renewal_note = _branded_renewal_note(body.renewal_operation, new_expire_dt) if body.renewal_operation is not None else None\n            _set_credits(cur, reseller["id"], new_balance, -price, current_credits, "line_renew", reseller["id"], "customer", body.line_id, note=renewal_note)\n            if body.renewal_operation is not None:\n                _branded_renewal_journal(cur, reseller, body, renewal_note, price, current_credits, new_balance)\n            conn.commit()')
    ]

def transform(source):
    if '# BRANDED_RENEWAL_GUARD_V1' in source:
        if not source.endswith(HOOK):raise ValueError('Unknown renewal hook; no source changed')
        restored=source[:-len(HOOK)]
        tree=ast.parse(restored);route=next(n for n in tree.body if isinstance(n,ast.AsyncFunctionDef) and n.name=='renew_line')
        lines=restored.splitlines(keepends=True);prefix=''.join(lines[:route.lineno-1]);body=''.join(lines[route.lineno-1:route.end_lineno]);suffix=''.join(lines[route.end_lineno:])
        old,new=changes()[0]
        if prefix.count(new)!=1:raise ValueError('Unknown guarded request model')
        prefix=prefix.replace(new,old)
        for old,new in changes()[1:]:
            if body.count(new)!=1:raise ValueError('Unknown guarded renewal body')
            body=body.replace(new,old)
        restored=prefix+body+suffix
        if transform(restored)!=source:raise ValueError('Guarded source differs from reviewed transform')
        return source
    # The prior installer appends only this hook. Do not accept arbitrary code.
    candidates=[source]
    marker=source.find('\nfrom branded_trials import install_trial_routes')
    if marker!=-1 and source[marker:].strip()==TRIAL_HOOK:
        # Preserve the exact original newline count in the verified prefix.
        candidates += [source[:marker].rstrip('\r\n')+'\n'*i for i in range(9)]
    if not any(hashlib.sha256(s.encode()).hexdigest()==BASE_SHA for s in candidates):raise ValueError('Unreviewed worker source; no patch authorized')
    tree=ast.parse(source);route=next(n for n in tree.body if isinstance(n,ast.AsyncFunctionDef) and n.name=='renew_line')
    lines=source.splitlines(keepends=True);prefix=''.join(lines[:route.lineno-1]);body=''.join(lines[route.lineno-1:route.end_lineno]);suffix=''.join(lines[route.end_lineno:])
    old,new=changes()[0]
    if prefix.count(old)!=1:raise ValueError('Renewal model anchor mismatch')
    prefix=prefix.replace(old,new)
    for old,new in changes()[1:]:
        if body.count(old)!=1:raise ValueError('Renewal transaction anchor mismatch')
        body=body.replace(old,new)
    result=prefix+body+suffix+HOOK;ast.parse(result);return result

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('input');p.add_argument('output');args=p.parse_args()
    output=transform(Path(args.input).read_text())
    with open(args.output,'x',opener=lambda path,flags:os.open(path,flags,0o600)) as f:f.write(output)
    print('Guarded worker candidate written; no service/config/database changed.')
