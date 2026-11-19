#!/usr/bin/env python3
"""Install owner workspace trial editing, with verified sources and rollback."""
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


def install(site,archive,backup_root,php):
    site=Path(site);index=site/'public/index.html';backend=site/'src/TrialSelfService.php'
    html=index.read_bytes();matches=re.findall(rb'/assets/portal-0\.42\.0/trial-self-service(?:-settings)?\.js',html)
    if len(matches)!=1:raise RuntimeError('Unknown trial script links or update already installed')
    source=site/('public'+matches[0].decode());target=source.with_name('trial-self-service-owner.js')
    for path in [index,backend,source,target]:
        if path.is_symlink() or not path.resolve().is_relative_to(site.resolve()):raise RuntimeError('Unexpected site path')
    if target.exists():raise RuntimeError('Owner trial controls already present')
    with ZipFile(archive) as z:
        manifest=json.loads(z.read('manifest.json'));js=z.read('trial-self-service.js');code=z.read('TrialSelfService.php')
    digest=lambda data:hashlib.sha256(data).hexdigest()
    original=backend.read_bytes()
    if digest(source.read_bytes()) not in manifest['before_js'] or digest(original)!=manifest['before_php']:raise RuntimeError('Unknown installed source; no files changed')
    if digest(js)!=manifest['after_js'] or digest(code)!=manifest['after_php']:raise RuntimeError('Damaged archive')
    backup=Path(tempfile.mkdtemp(prefix='owner-trials-before-',dir=backup_root))
    for path in [index,backend,source]:shutil.copy2(path,backup/path.name)
    candidate=backup/'TrialSelfService.new.php';candidate.write_bytes(code)
    subprocess.run([*php,'-n','-l',str(candidate)],check=True)
    def write(path,data):
        owner=(path if path.exists() else source).stat();fd,tmp=tempfile.mkstemp(prefix='.owner-trials-',dir=path.parent)
        try:
            with os.fdopen(fd,'wb') as f:
                f.write(data);f.flush();os.fsync(f.fileno());os.fchmod(f.fileno(),owner.st_mode&0o777)
                if os.geteuid()==0:os.fchown(f.fileno(),owner.st_uid,owner.st_gid)
            os.replace(tmp,path)
        finally:
            if os.path.exists(tmp):os.unlink(tmp)
    try:
        write(backend,code);write(target,js);write(index,html.replace(matches[0],b'/assets/portal-0.42.0/trial-self-service-owner.js'))
    except BaseException:
        write(backend,original);write(index,html)
        if target.exists():target.unlink()
        raise
    print('Owner trial workspace controls installed. Backup: '+str(backup))


if __name__=='__main__':
    if os.geteuid()!=0 or len(sys.argv)!=2:sys.exit('Run as root on website host: python3 install-owner-trial-controls.py UPDATE.zip')
    install('/www/wwwroot/control.fecxbrfzku.net',sys.argv[1],'/root',['/www/server/php/84/bin/php'])
