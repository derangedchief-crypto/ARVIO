#!/usr/bin/env python3
"""Update only the reseller panel's My Hub download presentation/handler."""
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile

HANDLER = '''
if (isset($_GET['download'])) {
    if (!in_array($_SERVER['REQUEST_METHOD'] ?? '',['GET','HEAD'],true)) { http_response_code(405);exit; }
    if (!$ready || $error!==null || $state!=='active') { http_response_code(403);exit('An active My Hub license is required.'); }
    try {
        require_once __DIR__.'/MyHubRelease.php';
        $release=\\AmAccount\\MyHubRelease::latest($config);
        header('Location: '.$release['url'],true,302);exit;
    } catch (Throwable $e) { http_response_code(503);exit('The latest My Hub download is temporarily unavailable. Please retry shortly.'); }
}
'''


def install(site,bundle,backup,php):
    panel=Path(site)/'am-app';bundle=Path(bundle);backup=Path(backup)
    controller=panel/'my-hub.php';showcase=panel/'my-hub-showcase.php';module=panel/'MyHubRelease.php'
    for path in [controller,showcase,module]:
        if path.is_symlink() or not path.resolve().is_relative_to(Path(site).resolve()):raise RuntimeError('Unexpected panel path')
    original=controller.read_text();page=showcase.read_text();source=(bundle/'MyHubRelease.php').read_text()
    if HANDLER in original and module.exists() and module.read_text()==source and 'my-hub.php?download=1' in page:
        print('Latest My Hub download already installed');return
    anchor="require __DIR__.'/my-hub-showcase.php';"
    if original.count(anchor)!=1 or "Licenses::state($row)" not in original or module.exists():raise RuntimeError('Unknown My Hub controller; stopped')
    pattern=r'https://github\.com/derangedchief-crypto/ARVIO/releases/download/[^"\s]+/My-Hub-[0-9.]+-testing\.apk'
    if len(re.findall(pattern,page))!=1:raise RuntimeError('Expected one versioned My Hub download')
    page=re.sub(pattern,"<?= e(url('am-app/my-hub.php?download=1')) ?>",page)
    page,count=re.subn(r'Testing build · [0-9.]+ · Android','Latest published testing build · Android',page)
    if count!=1:raise RuntimeError('Expected testing build caption')
    data={module:source,controller:original.replace(anchor,HANDLER+anchor),showcase:page}
    backup.mkdir(mode=0o700,parents=True,exist_ok=True)
    for path,text in data.items():
        candidate=backup/(path.name+'.new.php');candidate.write_text(text);subprocess.run([*php,'-n','-l',str(candidate)],check=True)
    previous={p:p.read_bytes() if p.exists() else None for p in data}
    for p,content in previous.items():
        if content is not None:shutil.copy2(p,backup/p.name)
    def write(path,content):
        owner=path.stat() if path.exists() else controller.stat();fd,tmp=tempfile.mkstemp(dir=panel,prefix='.my-hub-download-')
        try:
            with os.fdopen(fd,'wb') as f:
                f.write(content);f.flush();os.fsync(f.fileno());os.fchmod(f.fileno(),0o644)
                if os.geteuid()==0:os.fchown(f.fileno(),owner.st_uid,owner.st_gid)
            os.replace(tmp,path)
        finally:
            if os.path.exists(tmp):os.unlink(tmp)
    try:
        for path,text in data.items():write(path,text.encode())
    except BaseException:
        for path,content in previous.items():
            if content is not None:write(path,content)
            elif path.exists():path.unlink()
        raise
    print('Latest published My Hub download installed. Backup: '+str(backup))


if __name__=='__main__':
    if os.geteuid()!=0:sys.exit('Run as root on the reseller-panel website host')
    install(sys.argv[1] if len(sys.argv)>1 else '/www/wwwroot/fecxbrfzku.net',Path(__file__).resolve().parent,tempfile.mkdtemp(prefix='my-hub-download-before-',dir='/root'),['/www/server/php/84/bin/php'])
