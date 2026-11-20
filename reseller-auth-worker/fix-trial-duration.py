#!/usr/bin/env python3
"""Accept panel duration_value packages for both catalogue and provisioning."""
import ast
import os
from pathlib import Path
import shutil
import tempfile

PATCHES=[
    ("        duration=bounded_integer(plan.get('duration'),0);connections=bounded_integer(plan.get('max_connections') or 1,1);legacy_duration=bounded_integer(plan.get('duration_value'),0)",
     "        duration=bounded_integer(plan.get('duration'),0);connections=bounded_integer(plan.get('max_connections') or 1,1);legacy_duration=bounded_integer(plan.get('duration_value'),0)\n        # Some panel packages store the displayed length in duration_value.\n        # Preserve established positive-duration packages and their fingerprints.\n        if duration==0:duration=legacy_duration"),
    ("        duration = int(plan.get('duration') or 0)","        document = plan_document(plan)\n        duration = document['duration']"),
    ("        if not hmac.compare_digest(plan_document(plan)['fingerprint'],spec['plan_fingerprint']):raise TrialRejected('Trial plan changed; review its current limits and price')\n        return owner, plan, price",
     "        if not hmac.compare_digest(document['fingerprint'],spec['plan_fingerprint']):raise TrialRejected('Trial plan changed; review its current limits and price')\n        return owner, dict(plan,duration=duration), price")
]

def install(path,backup_root):
    path=Path(path)
    if path.is_symlink() or not path.is_file():raise RuntimeError('Expected regular branded_trials.py')
    original=path.read_text()
    if all(new in original for _,new in PATCHES):print('Duration compatibility already installed');return
    updated=original
    for old,new in PATCHES:
        if updated.count(old)!=1 or new in updated:raise RuntimeError('Unknown worker source; no files changed')
        updated=updated.replace(old,new)
    ast.parse(updated)
    backup=Path(tempfile.mkdtemp(prefix='trial-duration-before-',dir=backup_root));shutil.copy2(path,backup/path.name)
    owner=path.stat();fd,tmp=tempfile.mkstemp(prefix='.trial-duration-',dir=path.parent)
    try:
        with os.fdopen(fd,'w') as f:
            f.write(updated);f.flush();os.fsync(f.fileno());os.fchmod(f.fileno(),owner.st_mode&0o777)
            if os.geteuid()==0:os.fchown(f.fileno(),owner.st_uid,owner.st_gid)
        os.replace(tmp,path)
    finally:
        if os.path.exists(tmp):os.unlink(tmp)
    print('Trial duration compatibility installed. Backup: '+str(backup))

if __name__=='__main__':
    if os.geteuid()!=0:raise SystemExit('Run as root on auth-worker host')
    install('/opt/iptv-auth-worker/branded_trials.py','/root')
