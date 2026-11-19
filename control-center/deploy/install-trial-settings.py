#!/usr/bin/env python3
import hashlib
import json
import os
from pathlib import Path
import shutil
import tempfile
from zipfile import ZipFile
import sys


def install(site,archive,backup_root):
    site=Path(site);index=site/'public/index.html';source=site/'public/assets/portal-0.42.0/trial-self-service.js';target=source.with_name('trial-self-service-settings.js')
    for p in [index,source,target]:
        if p.is_symlink() or not p.resolve().is_relative_to(site.resolve()):raise RuntimeError('Unexpected site path')
    before=index.read_bytes();old=b'/assets/portal-0.42.0/trial-self-service.js';new=b'/assets/portal-0.42.0/trial-self-service-settings.js'
    if before.count(old)!=1 or target.exists():raise RuntimeError('Unknown asset links or update already installed')
    with ZipFile(archive) as z:manifest=json.loads(z.read('manifest.json'));code=z.read('trial-self-service.js')
    if hashlib.sha256(source.read_bytes()).hexdigest()!=manifest['before'] or hashlib.sha256(code).hexdigest()!=manifest['after']:raise RuntimeError('Unknown source or damaged update')
    backup=Path(tempfile.mkdtemp(prefix='trial-settings-before-',dir=backup_root));shutil.copy2(index,backup/index.name);shutil.copy2(source,backup/source.name)
    def write(path,data):
        owner=(path if path.exists() else source).stat();fd,tmp=tempfile.mkstemp(prefix='.trial-settings-',dir=path.parent)
        try:
            with os.fdopen(fd,'wb') as f:
                f.write(data);f.flush();os.fsync(f.fileno());os.fchmod(f.fileno(),owner.st_mode&0o777)
                if os.geteuid()==0:os.fchown(f.fileno(),owner.st_uid,owner.st_gid)
            os.replace(tmp,path)
        finally:
            if os.path.exists(tmp):os.unlink(tmp)
    try:write(target,code);write(index,before.replace(old,new))
    except BaseException:
        if target.exists():target.unlink()
        raise
    print('Trial settings navigation installed. Backup: '+str(backup))


if __name__=='__main__':
    if os.geteuid()!=0 or len(sys.argv)!=2:sys.exit('Run as root on the website host: python3 install-trial-settings.py UPDATE.zip')
    install('/www/wwwroot/control.fecxbrfzku.net',sys.argv[1],'/root')
