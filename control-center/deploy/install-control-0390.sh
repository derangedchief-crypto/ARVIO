#!/usr/bin/env bash
# Reseller workspace redesign: fixed-site, frontend-only release.
set -euo pipefail
umask 077
site=/www/wwwroot/control.fecxbrfzku.net
[[ $(id -u) == 0 ]] || { echo 'Run as root on the website host.' >&2; exit 1; }
cd "$site"
[[ $(pwd -P) == "$site" && -f config.php && ! -L config.php ]] || exit 1
case "$(cat VERSION)" in 0.38.0|0.38.1|0.39.0) ;; *) echo 'Install 0.38.0 or 0.38.1 first.' >&2; exit 1 ;; esac
backup=$(mktemp -d /root/control-0390-backup-XXXXXX)
python3 - "$site" "${1:?Pass the verified 0.39.0 update ZIP}" "$backup" <<'PY'
import hashlib,json,os,stat,sys,tempfile
from pathlib import Path
from zipfile import ZipFile,ZIP_DEFLATED
site,archive,backup=map(Path,sys.argv[1:])
version='0.39.0';release=site/('public/assets/portal-'+version)
assets=['app.js','workspace.css','compact.css','renewals-settings.js','trial-workspace.js','trial-setup.js','trial-self-service.js','app-branding.js','workspace-library.js','renewal-checkout.js','renewal-checkout.css','device-workspace.js']
names=['public/index.html','public/renew.html','VERSION']
def digest(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def inside(p):
    if p.is_symlink() or not p.resolve().is_relative_to(site):raise RuntimeError('Unexpected symlink/path; stopped')
for p in [release,*[site/n for n in names]]:inside(p)
protected=[site/'config.php',site/'storage/application.key',*[p for folder in ['src','public','cli'] for p in (site/folder).rglob('*.php')]]
before={str(p.relative_to(site)):digest(p) for p in protected}
marker=release/'.managed-control-update'
if release.exists() and (not marker.is_file() or marker.is_symlink() or marker.read_text()!='Extreme Control website '+version+'\n'):raise RuntimeError('Unrelated release directory preserved')
with ZipFile(archive) as z:
    if z.read('VERSION').decode().strip()!=version:raise RuntimeError('Wrong ZIP version')
    for line in z.read('MANIFEST.sha256').decode().splitlines():
        expected,name=line.split('  ',1)
        if hashlib.sha256(z.read(name)).hexdigest()!=expected:raise RuntimeError('ZIP manifest mismatch')
    content={n:z.read('public/assets/'+n) for n in assets}
    pointers={n:z.read(n) for n in names}
for n in ['public/index.html','public/renew.html']:
    text=pointers[n].decode()
    for a in assets:text=text.replace('/assets/'+a,'/assets/portal-'+version+'/'+a)
    pointers[n]=text.encode()
if release.exists():
    for n,data in content.items():
        p=release/n;inside(p)
        if p.exists() and digest(p)!=hashlib.sha256(data).hexdigest():raise RuntimeError('Modified release asset preserved')
with ZipFile(backup/'website-before.zip','w',ZIP_DEFLATED) as z:
    for n in names:z.write(site/n,n)
(backup/'preserved-hashes.json').write_text(json.dumps(before,indent=2))
def atomic(p,data):
    inside(p);owner=p.stat() if p.exists() else p.parent.stat()
    fd,tmp=tempfile.mkstemp(prefix='.control-update-',dir=p.parent)
    try:
        with os.fdopen(fd,'wb') as f:
            f.write(data);f.flush();os.fsync(f.fileno());os.fchmod(f.fileno(),stat.S_IMODE(owner.st_mode) if p.exists() else 0o644);os.fchown(f.fileno(),owner.st_uid,owner.st_gid)
        os.replace(tmp,p)
    finally:
        if os.path.exists(tmp):os.unlink(tmp)
release.mkdir(mode=0o755,exist_ok=True);os.chmod(release,0o755)
atomic(marker,('Extreme Control website '+version+'\n').encode())
for n,data in content.items():atomic(release/n,data)
installed={}
try:
    for n,data in pointers.items():atomic(site/n,data);installed[n]=hashlib.sha256(data).hexdigest()
    if any(not (site/n).is_file() or digest(site/n)!=v for n,v in before.items()):raise RuntimeError('Protected files changed concurrently; preserved')
except BaseException:
    with ZipFile(backup/'website-before.zip') as z:
        for n,value in installed.items():
            if (site/n).is_file() and digest(site/n)==value:atomic(site/n,z.read(n))
    raise
print('Website 0.39.0 frontend installed; backend, database, credentials, APKs and workers preserved.')
print('Rollback backup: '+str(backup/'website-before.zip'))
PY
