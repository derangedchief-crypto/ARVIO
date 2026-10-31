#!/usr/bin/env bash
# Frontend-only installer for the one explicitly authorized website.
set -euo pipefail
umask 077
[[ $(id -u) == 0 ]] || { echo 'Run on the WEBSITE host as root.' >&2; exit 1; }
site=/www/wwwroot/control.fecxbrfzku.net
php=/www/server/php/84/bin/php
archive=${1:?Usage: bash install-ui-cleanup.sh VERIFIED_UPDATE_ZIP}
cd "$site"
[[ $(pwd -P) == "$site" && -f config.php && ! -L config.php ]] || { echo 'Unexpected site directory/configuration; stopped.' >&2; exit 1; }
[[ -f storage/application.key && ! -L storage/application.key ]] || { echo 'Original application key missing/unsafe; stopped without creating one.' >&2; exit 1; }
case "$(cat VERSION)" in 0.34.0|0.35.0) ;; *) echo 'Expected website 0.34.0 or 0.35.0; stopped.' >&2; exit 1 ;; esac
"$php" -d opcache.enable_cli=0 -d display_errors=stderr -r 'require "src/bootstrap.php";try{$c=new ControlCenter\Config(getcwd());if(!$c->configured()||$c->origin()!=="https://control.fecxbrfzku.net")throw new RuntimeException();$c->secret("application.key",false);$db=new ControlCenter\Database($c);$db->query("SELECT 1");echo "Correct website origin, database and existing key verified (read-only).\n";}catch(Throwable $e){fwrite(STDERR,"Website origin/database/key validation failed; stopped.\n");exit(1);}'
backup=$(mktemp -d /root/control-ui-0350-backup-XXXXXX)
/usr/bin/python3 - "$site" "$archive" "$backup" "$php" <<'PY'
import hashlib,json,os,stat,subprocess,sys,tempfile
from pathlib import Path
from zipfile import ZipFile,ZIP_DEFLATED
site,archive,backup,php=Path(sys.argv[1]),Path(sys.argv[2]),Path(sys.argv[3]),sys.argv[4]
version='0.35.0';release=site/'public/assets/portal-0.35.0'
assets=['app.js','workspace.css','compact.css','renewals-settings.js','trial-workspace.js','trial-setup.js','trial-self-service.js','app-branding.js','workspace-library.js','renewal-checkout.js','renewal-checkout.css']
protected=[site/'config.php',site/'storage/application.key',*sorted((site/'src').rglob('*.php'))]
def digest(path):return hashlib.sha256(path.read_bytes()).hexdigest()
before={str(p.relative_to(site)):digest(p) for p in protected}
def inside(path):
    if path.is_symlink() or not path.resolve().is_relative_to(site):raise RuntimeError('Unexpected frontend symlink/path; no other site may be changed')
for p in [release,*[site/n for n in ['public/index.html','public/renew.html','VERSION']]]:inside(p)
marker=release/'.managed-ui-cleanup'
if release.exists() and (not marker.is_file() or marker.is_symlink() or marker.read_text()!='Extreme Control frontend '+version+'\n'):raise RuntimeError('Existing unrelated asset directory preserved')
with ZipFile(archive) as z:
    if z.read('VERSION').decode().strip()!=version:raise RuntimeError('Wrong release archive')
    for line in z.read('MANIFEST.sha256').decode().splitlines():
        expected,name=line.split('  ',1)
        if hashlib.sha256(z.read(name)).hexdigest()!=expected:raise RuntimeError('Archive manifest verification failed')
    content={name:z.read('public/assets/'+name) for name in assets}
    pointers={name:z.read(name) for name in ['public/index.html','public/renew.html','VERSION']}
for name in ['public/index.html','public/renew.html']:
    text=pointers[name].decode()
    for asset in assets:text=text.replace('/assets/'+asset,'/assets/portal-0.35.0/'+asset)
    pointers[name]=text.encode()
if release.exists():
    for name,data in content.items():
        p=release/name
        if p.exists() and digest(p)!=hashlib.sha256(data).hexdigest():raise RuntimeError('Existing release asset differs; preserved for review')
with ZipFile(backup/'frontend-before.zip','w',ZIP_DEFLATED) as z:
    for name in pointers:
        path=site/name;z.write(path,name)
(backup/'preserved-hashes.json').write_text(json.dumps(before,indent=2))
def atomic(path,data):
    inside(path);owner=path.stat() if path.exists() else path.parent.stat()
    fd,tmp=tempfile.mkstemp(prefix='.ui-install-',dir=path.parent)
    try:
        with os.fdopen(fd,'wb') as out:
            out.write(data);out.flush();os.fsync(out.fileno());os.fchmod(out.fileno(),stat.S_IMODE(owner.st_mode) if path.exists() else 0o644);os.fchown(out.fileno(),owner.st_uid,owner.st_gid)
        os.replace(tmp,path)
    finally:
        if os.path.exists(tmp):os.unlink(tmp)
release.mkdir(mode=0o755,exist_ok=True)
os.chmod(release,0o755) # The root shell uses a private 0077 umask; assets must be readable.
atomic(marker,('Extreme Control frontend '+version+'\n').encode())
# Versioned assets are completed before either HTML pointer changes. Old asset
# URLs stay intact for old open pages; no half-updated script/helper combinations.
for name,data in content.items():atomic(release/name,data)
installed={}
try:
    for name,data in pointers.items():
        atomic(site/name,data);installed[name]=hashlib.sha256(data).hexdigest()
    if any(not (site/name).is_file() or digest(site/name)!=value for name,value in before.items()):raise RuntimeError('Private/backend files changed concurrently; not overwritten')
    check='require "src/bootstrap.php";try{$c=new ControlCenter\\Config(getcwd());if(!$c->configured()||$c->origin()!=="https://control.fecxbrfzku.net")throw new RuntimeException();$c->secret("application.key",false);$db=new ControlCenter\\Database($c);$db->query("SELECT 1");echo "Read-only website check passed.\\n";}catch(Throwable $e){fwrite(STDERR,"Read-only website check failed.\\n");exit(1);}'
    subprocess.run([php,'-d','opcache.enable_cli=0','-r',check],cwd=site,check=True)
    if any(not (site/name).is_file() or digest(site/name)!=value for name,value in before.items()):raise RuntimeError('Private/backend files changed concurrently; not overwritten')
except BaseException:
    # Roll back our HTML/version pointers only, never a database, credential,
    # backend file, another site's content or a concurrently edited pointer.
    with ZipFile(backup/'frontend-before.zip') as z:
        for name,value in installed.items():
            if (site/name).is_file() and digest(site/name)==value:atomic(site/name,z.read(name))
    raise
print('Frontend 0.35.0 installed for https://control.fecxbrfzku.net only.')
print('Backend PHP, private configuration, application key, databases and workers were not modified.')
print('Existing asset URLs, APKs, customer data and the automatic-renewal socket fix are retained.')
print('Frontend rollback backup: '+str(backup/'frontend-before.zip'))
PY
