#!/usr/bin/env python3
"""Compact checkboxes and multi-selection layout ordering; assets only."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import sys
import tempfile
from zipfile import ZipFile


def install(site,archive,backup):
    site,backup=Path(site),Path(backup);index=site/'public/index.html'
    if (site/'VERSION').read_text().strip()!='0.42.0':raise RuntimeError('Expected workspace 0.42.0')
    original=index.read_text();matches=re.findall(r'/assets/portal-0\.42\.0/workspace-library(?:-master|-drag)?\.js',original)
    if len(matches)!=1 or original.count('</head>')!=1:raise RuntimeError('Unknown workspace asset links; stopped')
    source=site/('public'+matches[0]);js=source.with_name('workspace-library-pointer.js');css=source.with_name('layout-template-pointer.css')
    for path in [index,source,js,css]:
        if path.is_symlink() or not path.resolve().is_relative_to(site.resolve()):raise RuntimeError('Unexpected site path')
    if js.exists() or css.exists():raise RuntimeError('Layout drag update already present')
    with ZipFile(archive) as z:manifest=json.loads(z.read('manifest.json'));code=z.read('workspace-library.js');style=z.read('layout-template-controls.css')
    if hashlib.sha256(source.read_bytes()).hexdigest() not in manifest['before']:raise RuntimeError('Install the master catalogue update first; unknown library was not replaced')
    if hashlib.sha256(code).hexdigest()!=manifest['js'] or hashlib.sha256(style).hexdigest()!=manifest['css']:raise RuntimeError('Damaged update')
    html=original.replace(matches[0],'/assets/portal-0.42.0/workspace-library-pointer.js').replace('</head>','<link rel="stylesheet" href="/assets/portal-0.42.0/layout-template-pointer.css">\n</head>')
    backup.mkdir(mode=0o700,parents=True,exist_ok=True);shutil.copy2(index,backup/'index.html');shutil.copy2(source,backup/source.name)
    def write(path,data):
        owner=source.stat();fd,tmp=tempfile.mkstemp(dir=path.parent,prefix='.layout-drag-')
        try:
            with os.fdopen(fd,'wb') as f:
                f.write(data);f.flush();os.fsync(f.fileno());os.fchmod(f.fileno(),0o644)
                if os.geteuid()==0:os.fchown(f.fileno(),owner.st_uid,owner.st_gid)
            os.replace(tmp,path)
        finally:
            if os.path.exists(tmp):os.unlink(tmp)
    try:write(js,code);write(css,style);write(index,html.encode())
    except BaseException:
        write(index,original.encode())
        for path in [js,css]:
            if path.exists():path.unlink()
        raise
    print('Compact layout controls and multi-select drag ordering installed. Backup: '+str(backup))


if __name__=='__main__':
    if os.geteuid()!=0 or len(sys.argv)!=2:sys.exit('Run as root: python3 install-layout-drag.py UPDATE.zip')
    install('/www/wwwroot/control.fecxbrfzku.net',sys.argv[1],tempfile.mkdtemp(prefix='layout-drag-before-',dir='/root'))
