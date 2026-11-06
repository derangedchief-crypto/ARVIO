#!/usr/bin/env python3
"""Targeted website IPTV-login update; preserves installed backend hotfixes."""
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
from zipfile import ZipFile


def install(site, archive, backup, php):
    site,backup=Path(site),Path(backup)
    if (site/'VERSION').read_text().strip()!='0.42.0':raise RuntimeError('Expected workspace 0.42.0')
    index=site/'public/index.html';original=index.read_text()
    links=re.findall(r'/assets/portal-0\.42\.0/app(?:-online|-customer-notes|-device-notes|-panel-notes)?\.js',original)
    if len(links)!=1:raise RuntimeError('Expected one installed workspace app asset')
    app=site/('public'+links[0]);newapp=app.parent/'app-reseller-login.js'
    if newapp.exists() or newapp.is_symlink():raise RuntimeError('Reseller login asset already exists')
    with ZipFile(archive) as z:patches=json.loads(z.read('patches.json'))
    targets={'Auth.php':site/'src/Auth.php','Application.php':site/'src/Application.php','app.js':app}
    updated={}
    for name,path in targets.items():
        if path.is_symlink() or not path.resolve().is_relative_to(site.resolve()):raise RuntimeError('Unexpected path')
        text=path.read_text();before=patches[name]['before']
        if text.count(before)!=1:raise RuntimeError('Unexpected installed source: '+name)
        updated[newapp if name=='app.js' else path]=text.replace(before,patches[name]['after'])
    if index.is_symlink():raise RuntimeError('Unexpected index symlink')
    updated[index]=original.replace(links[0],'/assets/portal-0.42.0/app-reseller-login.js')
    backup.mkdir(mode=0o700,parents=True,exist_ok=True)
    for path,text in updated.items():
        if path.suffix=='.php':
            candidate=backup/(path.name+'.new.php');candidate.write_text(text);subprocess.run([*php,'-n','-l',str(candidate)],check=True)
    previous={path:path.read_bytes() if path.exists() else None for path in updated}
    for path in [*targets.values(),index]:shutil.copy2(path,backup/path.name)
    def write(path,data):
        owner=path.stat() if path.exists() else app.stat();fd,tmp=tempfile.mkstemp(dir=path.parent,prefix='.reseller-login-')
        try:
            with os.fdopen(fd,'wb') as f:
                f.write(data);f.flush();os.fsync(f.fileno());os.fchmod(f.fileno(),0o644)
                if os.geteuid()==0:os.fchown(f.fileno(),owner.st_uid,owner.st_gid)
            os.replace(tmp,path)
        finally:
            if os.path.exists(tmp):os.unlink(tmp)
    try:
        for path,text in updated.items():write(path,text.encode())
    except BaseException:
        for path,data in previous.items():
            if data is not None:write(path,data)
            elif path.exists():path.unlink()
        raise
    print('IPTV reseller website login installed. Backup: '+str(backup))


if __name__=='__main__':
    if os.geteuid()!=0 or len(sys.argv)!=2:sys.exit('Run as root: python3 install-reseller-login.py UPDATE.zip')
    install('/www/wwwroot/control.fecxbrfzku.net',sys.argv[1],tempfile.mkdtemp(prefix='reseller-login-before-',dir='/root'),['/www/server/php/84/bin/php'])
