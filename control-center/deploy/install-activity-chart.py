#!/usr/bin/env python3
"""Make the Device Activity plot fill its card on 0.42.0 and its hotfixes."""
import os
from pathlib import Path
import shutil
import sys
import tempfile

STYLE = '''.dashboard-page .activity-chart>svg{display:block;width:100%;height:auto;max-height:none;aspect-ratio:620/250}
@media(max-width:600px){.dashboard-page .activity-chart>svg text{font-size:24px}}
'''
LINK = '<link rel="stylesheet" href="/assets/portal-0.42.0/activity-chart-fit.css?v=1">'


def install(site, backup):
    site, backup = Path(site), Path(backup)
    if (site/'VERSION').read_text().strip() != '0.42.0': raise RuntimeError('Expected workspace 0.42.0')
    index=site/'public/index.html'; asset=site/'public/assets/portal-0.42.0/activity-chart-fit.css'
    for path in [index,asset]:
        if path.is_symlink() or not path.resolve().is_relative_to(site.resolve()): raise RuntimeError('Unexpected site path')
    before=index.read_text()
    if LINK in before and asset.exists() and asset.read_text()==STYLE:
        print('Activity chart already updated');return
    if before.count('</head>')!=1 or '/assets/portal-0.42.0/' not in before or asset.exists(): raise RuntimeError('Unexpected installed source')
    backup.mkdir(mode=0o700,parents=True,exist_ok=True);shutil.copy2(index,backup/'index.html')
    def atomic(path,text):
        owner=index.stat();fd,tmp=tempfile.mkstemp(dir=path.parent,prefix='.chart-fit-')
        try:
            with os.fdopen(fd,'w') as f:
                f.write(text);f.flush();os.fsync(f.fileno());os.fchmod(f.fileno(),0o644)
                if os.geteuid()==0:os.fchown(f.fileno(),owner.st_uid,owner.st_gid)
            os.replace(tmp,path)
        finally:
            if os.path.exists(tmp):os.unlink(tmp)
    try:
        atomic(asset,STYLE);atomic(index,before.replace('</head>',LINK+'\n</head>'))
    except BaseException:
        atomic(index,before)
        if asset.exists():asset.unlink()
        raise
    print('Activity chart enlarged. Backup: '+str(backup))


if __name__=='__main__':
    if os.geteuid()!=0:sys.exit('Run as root on the website host')
    install('/www/wwwroot/control.fecxbrfzku.net',tempfile.mkdtemp(prefix='activity-chart-before-',dir='/root'))
