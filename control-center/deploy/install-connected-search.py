#!/usr/bin/env python3
"""Connected-idle counting and live IPTV-note search for the panel-notes backend."""
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
    target=site/'src/Management.php'
    if (site/'VERSION').read_text().strip()!='0.42.0':raise RuntimeError('Expected workspace 0.42.0')
    if target.is_symlink() or not target.resolve().is_relative_to(site.resolve()):raise RuntimeError('Unexpected source path')
    before=target.read_bytes()
    with ZipFile(archive) as z:
        manifest=json.loads(z.read('hashes.json'));data=z.read('Management.php')
    if hashlib.sha256(data).hexdigest()!=manifest['after']:raise RuntimeError('Damaged update')
    if before==data:print('Connected devices and notes search already installed');return
    if hashlib.sha256(before).hexdigest() not in manifest['before']:raise RuntimeError('Install the IPTV Panel Notes control update first; unknown Management.php was not replaced')
    backup.mkdir(mode=0o700,parents=True,exist_ok=True)
    candidate=backup/'Management.new.php';candidate.write_bytes(data)
    subprocess.run([*php,'-n','-l',str(candidate)],check=True)
    shutil.copy2(target,backup/'Management.php')
    if target.read_bytes()!=before:raise RuntimeError('Source changed during preparation')
    owner=target.stat();fd,tmp=tempfile.mkstemp(dir=target.parent,prefix='.connected-search-')
    try:
        with os.fdopen(fd,'wb') as f:
            f.write(data);f.flush();os.fsync(f.fileno());os.fchmod(f.fileno(),owner.st_mode & 0o777)
            if os.geteuid()==0:os.fchown(f.fileno(),owner.st_uid,owner.st_gid)
        os.replace(tmp,target)
    finally:
        if os.path.exists(tmp):os.unlink(tmp)
    print('Connected idle devices and IPTV notes search installed. Backup: '+str(backup))


if __name__=='__main__':
    if os.geteuid()!=0 or len(sys.argv)!=2:sys.exit('Run as root: python3 install-connected-search.py UPDATE.zip')
    install('/www/wwwroot/control.fecxbrfzku.net',sys.argv[1],tempfile.mkdtemp(prefix='connected-search-before-',dir='/root'),['/www/server/php/84/bin/php'])
