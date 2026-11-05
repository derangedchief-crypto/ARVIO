#!/usr/bin/env python3
"""Verified 0.42.0 update for actual IPTV-panel notes."""
import hashlib
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
    site, backup = Path(site), Path(backup)
    if (site/'VERSION').read_text().strip()!='0.42.0': raise RuntimeError('Install the 0.42.0 workspace first')
    index=site/'public/index.html'; original=index.read_bytes(); html=original.decode()
    targets={'src/Management.php':'src/Management.php','src/PanelBridge.php':'src/PanelBridge.php'}
    for name, pattern in [('app',r'app(?:-online|-customer-notes|-device-notes)?'),('dashboard',r'dashboard(?:-online)?'),('studio',r'studio(?:-device-notes)?')]:
        ext='css' if name=='studio' else 'js'
        matches=re.findall(r'/assets/portal-0\.42\.0/'+pattern+r'\.'+ext,html)
        if len(matches)!=1: raise RuntimeError('Unknown asset links: '+name)
        targets[name+'.'+ext]='public'+matches[0]
        html=html.replace(matches[0],'/assets/portal-0.42.0/'+name+'-panel-notes.'+ext)
    with ZipFile(archive) as z:
        manifest=json.loads(z.read('hashes.json')); data={name:z.read(name) for name in targets}
    for name,target in targets.items():
        path=site/target
        if path.is_symlink() or not path.resolve().is_relative_to(site.resolve()): raise RuntimeError('Unexpected path: '+target)
        if hashlib.sha256(path.read_bytes()).hexdigest() not in manifest[name]['before']: raise RuntimeError('Unknown installed source: '+target)
        if hashlib.sha256(data[name]).hexdigest()!=manifest[name]['after']: raise RuntimeError('Damaged update: '+name)
    if index.is_symlink(): raise RuntimeError('Unexpected index symlink')
    backup.mkdir(mode=0o700,parents=True,exist_ok=True)
    writes={}
    for name,target in targets.items():
        if name.startswith('src/'):
            candidate=backup/(Path(name).name+'.new.php'); candidate.write_bytes(data[name]); subprocess.run([*php,'-l',str(candidate)],check=True)
            writes[site/target]=data[name]
        else:
            stem,ext=name.rsplit('.',1); dest=site/('public/assets/portal-0.42.0/'+stem+'-panel-notes.'+ext)
            if dest.exists() or dest.is_symlink(): raise RuntimeError('Panel notes assets already exist')
            writes[dest]=data[name]
    writes[index]=html.encode()
    previous={path:path.read_bytes() if path.exists() else None for path in writes}
    for path,content in previous.items():
        if content is not None: shutil.copy2(path,backup/path.name)
    def atomic(path,content):
        owner=path.stat() if path.exists() else index.stat(); fd,tmp=tempfile.mkstemp(dir=path.parent,prefix='.panel-notes-')
        try:
            with os.fdopen(fd,'wb') as f:
                f.write(content);f.flush();os.fsync(f.fileno());os.fchmod(f.fileno(),0o644)
                if os.geteuid()==0: os.fchown(f.fileno(),owner.st_uid,owner.st_gid)
            os.replace(tmp,path)
        finally:
            if os.path.exists(tmp): os.unlink(tmp)
    try:
        for path,content in writes.items(): atomic(path,content)
    except BaseException:
        for path,content in previous.items():
            if content is not None: atomic(path,content)
            elif path.exists(): path.unlink()
        raise
    print('Actual IPTV panel Notes columns installed. Backup: '+str(backup))


if __name__=='__main__':
    if os.geteuid()!=0 or len(sys.argv)!=2: sys.exit('Run as root: python3 install-panel-notes.py UPDATE.zip')
    install('/www/wwwroot/control.fecxbrfzku.net',sys.argv[1],tempfile.mkdtemp(prefix='panel-notes-before-',dir='/root'),['/www/server/php/84/bin/php'])
