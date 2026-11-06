#!/usr/bin/env python3
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
from zipfile import ZipFile


def install(site,archive,backup,php):
    site,backup=Path(site),Path(backup)
    if (site/'VERSION').read_text().strip()!='0.42.0':raise RuntimeError('Expected workspace 0.42.0')
    app=site/'src/Application.php';index=site/'public/index.html';oldjs=site/'public/assets/portal-0.42.0/workspace-library.js';newjs=oldjs.with_name('workspace-library-master.js');module=site/'src/MasterLayoutCatalogue.php'
    for path in [app,index,oldjs,newjs,module]:
        if path.is_symlink() or not path.resolve().is_relative_to(site.resolve()):raise RuntimeError('Unexpected site path')
    if newjs.exists() or module.exists():raise RuntimeError('Master catalogue update already present; stopped')
    with ZipFile(archive) as z:
        manifest=json.loads(z.read('manifest.json'));js=z.read('workspace-library.js');source=z.read('MasterLayoutCatalogue.php')
    if hashlib.sha256(oldjs.read_bytes()).hexdigest()!=manifest['library_before']:raise RuntimeError('Unknown installed library; stopped')
    text=app.read_text();html=index.read_text();anchor=manifest['anchor'];link='/assets/portal-0.42.0/workspace-library.js'
    if text.count(anchor)!=1 or html.count(link)!=1:raise RuntimeError('Unknown installed routes/assets; stopped')
    data={module:source,app:text.replace(anchor,manifest['routes']+anchor).encode(),newjs:js,index:html.replace(link,'/assets/portal-0.42.0/workspace-library-master.js').encode()}
    backup.mkdir(mode=0o700,parents=True,exist_ok=True)
    for path,content in data.items():
        if path.suffix=='.php':
            candidate=backup/(path.name+'.new.php');candidate.write_bytes(content);subprocess.run([*php,'-n','-l',str(candidate)],check=True)
    previous={p:p.read_bytes() if p.exists() else None for p in data}
    for path in [app,index,oldjs]:shutil.copy2(path,backup/path.name)
    def write(path,content):
        owner=path.stat() if path.exists() else (app if path.suffix=='.php' else oldjs).stat();fd,tmp=tempfile.mkstemp(dir=path.parent,prefix='.master-layout-')
        try:
            with os.fdopen(fd,'wb') as f:
                f.write(content);f.flush();os.fsync(f.fileno());os.fchmod(f.fileno(),0o644)
                if os.geteuid()==0:os.fchown(f.fileno(),owner.st_uid,owner.st_gid)
            os.replace(tmp,path)
        finally:
            if os.path.exists(tmp):os.unlink(tmp)
    try:
        for path,content in data.items():write(path,content)
    except BaseException:
        for path,content in previous.items():
            if content is not None:write(path,content)
            elif path.exists():path.unlink()
        raise
    print('Master category catalogue installed. Backup: '+str(backup))
    print('Sign in as owner: Saved setups > Channel layouts > Master catalogue · owner only. Paste the private playlist URL and Save & refresh.')


if __name__=='__main__':
    if os.geteuid()!=0 or len(sys.argv)!=2:sys.exit('Run as root: python3 install-master-layout.py UPDATE.zip')
    install('/www/wwwroot/control.fecxbrfzku.net',sys.argv[1],tempfile.mkdtemp(prefix='master-layout-before-',dir='/root'),['/www/server/php/84/bin/php'])
