#!/usr/bin/env python3
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

ROUTE="        if(in_array($method,['GET','HEAD'],true)&&preg_match('~^/downloads/latest/([a-f0-9]{32})/(testing|production)/App\\.apk$~D',$path,$match))$this->brandDownloads->serveLatest($match[1],$match[2],$method==='HEAD');"

def install(site,archive,backup_root,php):
    site=Path(site);index=site/'public/index.html';application=site/'src/Application.php';downloads=site/'src/BrandDownloads.php'
    html=index.read_bytes();matches=re.findall(rb'/assets/portal-0\.42\.0/app-branding\.js',html)
    if len(matches)!=1:raise RuntimeError('Unknown branding asset link or repair already installed')
    source=site/('public'+matches[0].decode());target=source.with_name('app-branding-downloads.js')
    for p in [index,application,downloads,source,target]:
        if p.is_symlink() or not p.resolve().is_relative_to(site.resolve()):raise RuntimeError('Unexpected site path')
    if target.exists():raise RuntimeError('Permanent download update already present')
    original={p:p.read_bytes() for p in [index,application,downloads]}
    with ZipFile(archive) as z:manifest=json.loads(z.read('manifest.json'));js=z.read('app-branding.js');code=z.read('BrandDownloads.php')
    digest=lambda b:hashlib.sha256(b).hexdigest()
    if digest(source.read_bytes())!=manifest['before_js'] or digest(original[downloads])!=manifest['before_php']:raise RuntimeError('Unknown source; nothing changed')
    if digest(js)!=manifest['after_js'] or digest(code)!=manifest['after_php']:raise RuntimeError('Damaged archive')
    app=original[application].decode();lines=[line for line in app.splitlines() if "~^/downloads/apps/" in line and 'brandDownloads->serve(' in line]
    if len(lines)!=1 or '/downloads/latest/' in app:raise RuntimeError('Unknown download routes; nothing changed')
    app=app.replace(lines[0],lines[0]+'\n'+ROUTE).encode()
    backup=Path(tempfile.mkdtemp(prefix='stable-downloads-before-',dir=backup_root))
    for p in [index,application,downloads,source]:shutil.copy2(p,backup/p.name)
    for name,body in [('Application.new.php',app),('BrandDownloads.new.php',code)]:
        candidate=backup/name;candidate.write_bytes(body);subprocess.run([*php,'-n','-l',str(candidate)],check=True)
    def write(path,data):
        owner=(path if path.exists() else source).stat();fd,tmp=tempfile.mkstemp(dir=path.parent,prefix='.stable-download-')
        try:
            with os.fdopen(fd,'wb') as f:
                f.write(data);f.flush();os.fsync(f.fileno());os.fchmod(f.fileno(),owner.st_mode&0o777)
                if os.geteuid()==0:os.fchown(f.fileno(),owner.st_uid,owner.st_gid)
            os.replace(tmp,path)
        finally:
            if os.path.exists(tmp):os.unlink(tmp)
    try:
        write(downloads,code);write(application,app);write(target,js);write(index,html.replace(matches[0],b'/assets/portal-0.42.0/app-branding-downloads.js'))
    except BaseException:
        for p,data in original.items():write(p,data)
        if target.exists():target.unlink()
        raise
    print('Permanent APK links installed. Backup: '+str(backup))

if __name__=='__main__':
    if os.geteuid()!=0 or len(sys.argv)!=2:sys.exit('Run as root on website host: python3 install-stable-downloads.py UPDATE.zip')
    install('/www/wwwroot/control.fecxbrfzku.net',sys.argv[1],'/root',['/www/server/php/84/bin/php'])
