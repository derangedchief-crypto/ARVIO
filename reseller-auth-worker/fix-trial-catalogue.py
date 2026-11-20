#!/usr/bin/env python3
"""Isolate invalid trial package metadata without changing provisioning rules."""
import ast
import os
from pathlib import Path
import shutil
import tempfile

OLD="                plans=[plan_document(p) for p in rows if self.worker['_package_allowed'](p,group,overrides)]"
NEW="""                plans=[]
                for p in rows:
                    if not self.worker['_package_allowed'](p,group,overrides):continue
                    try:plans.append(plan_document(p))
                    except TrialRejected:
                        logging.getLogger(__name__).warning('Omitting invalid trial package id=%s from catalogue',p['id'])"""


def install(path,backup_root):
    path=Path(path)
    if path.is_symlink() or not path.is_file():raise RuntimeError('Expected a regular branded_trials.py file')
    original=path.read_text()
    if NEW in original:
        print('Catalogue repair already installed');return
    if original.count(OLD)!=1 or '\nimport logging\n' not in original:raise RuntimeError('Unknown worker source; no files changed')
    updated=original.replace(OLD,NEW);ast.parse(updated)
    backup=Path(tempfile.mkdtemp(prefix='trial-catalogue-before-',dir=backup_root))
    shutil.copy2(path,backup/path.name)
    owner=path.stat();fd,tmp=tempfile.mkstemp(prefix='.trial-catalogue-',dir=path.parent)
    try:
        with os.fdopen(fd,'w') as stream:
            stream.write(updated);stream.flush();os.fsync(stream.fileno());os.fchmod(stream.fileno(),owner.st_mode & 0o777)
            if os.geteuid()==0:os.fchown(stream.fileno(),owner.st_uid,owner.st_gid)
        os.replace(tmp,path)
    finally:
        if os.path.exists(tmp):os.unlink(tmp)
    print('Trial catalogue repaired. Backup: '+str(backup))


if __name__=='__main__':
    if os.geteuid()!=0:raise SystemExit('Run as root on the auth-worker host')
    install('/opt/iptv-auth-worker/branded_trials.py','/root')
