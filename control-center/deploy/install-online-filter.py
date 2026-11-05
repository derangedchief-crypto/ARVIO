#!/usr/bin/env python3
"""Fixed-scope online-card hotfix for the published 0.42.0 workspace."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
from zipfile import ZipFile


def install(site, archive, backup, php):
    site, backup = Path(site), Path(backup)
    targets = {'src/Management.php':'src/Management.php',
               'app.js':'public/assets/portal-0.42.0/app.js',
               'dashboard.js':'public/assets/portal-0.42.0/dashboard.js'}
    with ZipFile(archive) as z:
        manifest = json.loads(z.read('hashes.json'))
        data = {name:z.read(name) for name in targets}
    if (site/'VERSION').read_text().strip() != '0.42.0':
        raise RuntimeError('Install the 0.42.0 workspace first.')
    for name, target in targets.items():
        path = site/target
        if path.is_symlink() or not path.resolve().is_relative_to(site.resolve()):
            raise RuntimeError('Unexpected site path: '+target)
        if hashlib.sha256(path.read_bytes()).hexdigest() != manifest[name]['before']:
            raise RuntimeError('Unexpected installed file; stopped: '+target)
        if hashlib.sha256(data[name]).hexdigest() != manifest[name]['after']:
            raise RuntimeError('Archive checksum mismatch: '+name)
    index = site/'public/index.html'
    if index.is_symlink():
        raise RuntimeError('Unexpected index symlink')
    previous = index.read_bytes()
    updated = previous.decode()
    for name in ['app','dashboard']:
        old = '/assets/portal-0.42.0/'+name+'.js'
        if old not in updated:
            raise RuntimeError('Expected 0.42.0 asset link missing')
        updated = updated.replace(old, '/assets/portal-0.42.0/'+name+'-online.js')
    backup.mkdir(mode=0o700,parents=True,exist_ok=True)
    candidate = backup/'Management.new.php'
    candidate.write_bytes(data['src/Management.php'])
    subprocess.run([*php,'-l',str(candidate)],check=True)
    shutil.copy2(site/'src/Management.php',backup/'Management.php')
    shutil.copy2(index,backup/'index.html')

    def atomic(path, content):
        owner = path.stat() if path.exists() else path.parent.stat()
        fd, tmp = tempfile.mkstemp(dir=path.parent,prefix='.online-filter-')
        try:
            with os.fdopen(fd,'wb') as f:
                f.write(content); f.flush(); os.fsync(f.fileno()); os.fchmod(f.fileno(),0o644)
                if os.geteuid()==0: os.fchown(f.fileno(),owner.st_uid,owner.st_gid)
            os.replace(tmp,path)
        finally:
            if os.path.exists(tmp): os.unlink(tmp)
    for name in ['app','dashboard']:
        path=site/('public/assets/portal-0.42.0/'+name+'-online.js')
        if path.exists() or path.is_symlink(): raise RuntimeError('Hotfix asset already exists; stopped')
    for name in ['app','dashboard']:
        atomic(site/('public/assets/portal-0.42.0/'+name+'-online.js'),data[name+'.js'])
    try:
        atomic(site/'src/Management.php',data['src/Management.php'])
        atomic(index,updated.encode())
    except BaseException:
        atomic(site/'src/Management.php',(backup/'Management.php').read_bytes())
        atomic(index,previous)
        raise
    print('Online devices card filter installed. Backup: '+str(backup))


if __name__=='__main__':
    if os.geteuid()!=0 or len(sys.argv)!=2: sys.exit('Run as root: python3 install-online-filter.py UPDATE.zip')
    install('/www/wwwroot/control.fecxbrfzku.net',sys.argv[1],
            tempfile.mkdtemp(prefix='online-filter-before-',dir='/root'),['/www/server/php/84/bin/php'])
