#!/usr/bin/env python3
"""Supply the client identity required by the IPTV playlist endpoint."""
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile


def install(site,backup,php):
    path=Path(site)/'src/MasterLayoutCatalogue.php';backup=Path(backup)
    if path.is_symlink() or not path.resolve().is_relative_to(Path(site).resolve()):raise RuntimeError('Unexpected source path')
    before=path.read_text();old='curl_setopt_array($curl,[CURLOPT_FOLLOWLOCATION=>false,';new="curl_setopt_array($curl,[CURLOPT_USERAGENT=>'MyHub-Catalogue/1.0',CURLOPT_FOLLOWLOCATION=>false,"
    if new in before:print('Master playlist reader already fixed');return
    if before.count(old)!=1:raise RuntimeError('Unknown master reader source; stopped')
    after=before.replace(old,new);backup.mkdir(mode=0o700,parents=True,exist_ok=True);shutil.copy2(path,backup/path.name)
    candidate=backup/'MasterLayoutCatalogue.new.php';candidate.write_text(after);subprocess.run([*php,'-n','-l',str(candidate)],check=True)
    owner=path.stat();fd,tmp=tempfile.mkstemp(dir=path.parent,prefix='.master-reader-')
    try:
        with os.fdopen(fd,'w') as f:
            f.write(after);f.flush();os.fsync(f.fileno());os.fchmod(f.fileno(),0o644)
            if os.geteuid()==0:os.fchown(f.fileno(),owner.st_uid,owner.st_gid)
        os.replace(tmp,path)
    finally:
        if os.path.exists(tmp):os.unlink(tmp)
    print('Master playlist reader fixed. Backup: '+str(backup))


if __name__=='__main__':
    if os.geteuid()!=0:sys.exit('Run as root on the control website host')
    install('/www/wwwroot/control.fecxbrfzku.net',tempfile.mkdtemp(prefix='master-reader-before-',dir='/root'),['/www/server/php/84/bin/php'])
