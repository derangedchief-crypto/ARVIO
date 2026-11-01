#!/usr/bin/env bash
# Fixed-site recovery update. No schema/config changes, worker access or APK publishing.
set -euo pipefail
umask 077
[[ $(id -u) == 0 ]] || { echo 'Run on the WEBSITE host as root.' >&2; exit 1; }
site=/www/wwwroot/control.fecxbrfzku.net
php=/www/server/php/84/bin/php
archive=${1:?Usage: bash install-control-037.sh VERIFIED_037_UPDATE_ZIP}
cd "$site"
[[ $(pwd -P) == "$site" && -f config.php && ! -L config.php ]] || { echo 'Unexpected website directory/configuration; stopped.' >&2; exit 1; }
[[ -f storage/application.key && ! -L storage/application.key ]] || { echo 'Original application key missing/unsafe; stopped without creating one.' >&2; exit 1; }
case "$(cat VERSION)" in 0.36.0|0.37.0) ;; *) echo 'Expected website 0.36.0 or 0.37.0; stopped.' >&2; exit 1 ;; esac
"$php" -d opcache.enable_cli=0 -d display_errors=stderr -r 'require "src/bootstrap.php";try{$c=new ControlCenter\Config(getcwd());if(!$c->configured()||$c->origin()!=="https://control.fecxbrfzku.net")throw new RuntimeException();$c->secret("application.key",false);$db=new ControlCenter\Database($c);$db->query("SELECT 1");foreach(["025_device_recovery.sql","033_trial_conversion_defaults.sql"] as $m)if(!$db->one("SELECT name FROM schema_migrations WHERE name=?",[$m]))throw new RuntimeException();echo "Correct website, existing key and required schema verified (read-only).\n";}catch(Throwable $e){fwrite(STDERR,"Website origin/database/key/schema check failed; stopped without running migrations.\n");exit(1);}'
backup=$(mktemp -d /root/control-0370-backup-XXXXXX)
/usr/bin/python3 - "$site" "$archive" "$backup" "$php" <<'PY'
import hashlib,json,os,stat,subprocess,sys,tempfile
from pathlib import Path
from zipfile import ZipFile,ZIP_DEFLATED
site,archive,backup,php=Path(sys.argv[1]),Path(sys.argv[2]),Path(sys.argv[3]),sys.argv[4]
version='0.37.0';release=site/'public/assets/portal-0.37.0'
assets=['app.js','workspace.css','compact.css','renewals-settings.js','trial-workspace.js','trial-setup.js','trial-self-service.js','app-branding.js','workspace-library.js','renewal-checkout.js','renewal-checkout.css','device-workspace.js']
backend=['src/DeviceRecovery.php','src/Enrollment.php','src/Management.php','src/Application.php']
names=backend+['public/index.html','public/renew.html','VERSION']
protected=[site/'config.php',site/'storage/application.key',*[p for folder in ['src','public','cli'] for p in sorted((site/folder).rglob('*.php')) if str(p.relative_to(site)) not in backend],*[p for p in sorted((site/'public/assets').iterdir()) if p.is_file()]]
def digest(path):return hashlib.sha256(path.read_bytes()).hexdigest()
before={str(p.relative_to(site)):digest(p) for p in protected}
def inside(path):
    if path.is_symlink() or not path.resolve().is_relative_to(site):raise RuntimeError('Unexpected path/symlink; no other site may be changed')
for p in [release,*[site/n for n in names]]:inside(p)
marker=release/'.managed-control-update'
if release.exists() and (not marker.is_file() or marker.is_symlink() or marker.read_text()!='Extreme Control website '+version+'\n'):raise RuntimeError('Existing unrelated asset directory preserved')
with ZipFile(archive) as z:
    if z.read('VERSION').decode().strip()!=version:raise RuntimeError('Wrong release archive')
    for line in z.read('MANIFEST.sha256').decode().splitlines():
        expected,name=line.split('  ',1)
        if hashlib.sha256(z.read(name)).hexdigest()!=expected:raise RuntimeError('Archive manifest verification failed')
    content={name:z.read('public/assets/'+name) for name in assets}
    pointers={name:z.read(name) for name in names}
for name in ['public/index.html','public/renew.html']:
    text=pointers[name].decode()
    for asset in assets:text=text.replace('/assets/'+asset,'/assets/portal-0.37.0/'+asset)
    pointers[name]=text.encode()
if release.exists():
    for name,data in content.items():
        p=release/name;inside(p)
        if p.exists() and digest(p)!=hashlib.sha256(data).hexdigest():raise RuntimeError('Existing release asset differs; preserved for review')
for name in backend:
    fd,path=tempfile.mkstemp(suffix='.php',dir=backup)
    with os.fdopen(fd,'wb') as out:out.write(pointers[name])
    subprocess.run([php,'-d','opcache.enable_cli=0','-l',path],check=True)
with ZipFile(backup/'website-before.zip','w',ZIP_DEFLATED) as z:
    for name in names:z.write(site/name,name)
(backup/'preserved-hashes.json').write_text(json.dumps(before,indent=2))
def atomic(path,data):
    inside(path);owner=path.stat() if path.exists() else path.parent.stat()
    fd,tmp=tempfile.mkstemp(prefix='.control-update-',dir=path.parent)
    try:
        with os.fdopen(fd,'wb') as out:
            out.write(data);out.flush();os.fsync(out.fileno());os.fchmod(out.fileno(),stat.S_IMODE(owner.st_mode) if path.exists() else 0o644);os.fchown(out.fileno(),owner.st_uid,owner.st_gid)
        os.replace(tmp,path)
    finally:
        if os.path.exists(tmp):os.unlink(tmp)
release.mkdir(mode=0o755,exist_ok=True);os.chmod(release,0o755)
atomic(marker,('Extreme Control website '+version+'\n').encode())
for name,data in content.items():atomic(release/name,data)
installed={}
try:
    for name,data in pointers.items():atomic(site/name,data);installed[name]=hashlib.sha256(data).hexdigest()
    if any(not (site/name).is_file() or digest(site/name)!=value for name,value in before.items()):raise RuntimeError('Protected files changed concurrently; not overwritten')
    check='require "src/bootstrap.php";try{$c=new ControlCenter\\Config(getcwd());if(!$c->configured()||$c->origin()!=="https://control.fecxbrfzku.net")throw new RuntimeException();$c->secret("application.key",false);$db=new ControlCenter\\Database($c);$db->query("SELECT 1");echo "Read-only website check passed.\\n";}catch(Throwable $e){fwrite(STDERR,"Read-only website check failed.\\n");exit(1);}'
    subprocess.run([php,'-d','opcache.enable_cli=0','-r',check],cwd=site,check=True)
    if any(not (site/name).is_file() or digest(site/name)!=value for name,value in before.items()):raise RuntimeError('Protected files changed concurrently; not overwritten')
except BaseException:
    with ZipFile(backup/'website-before.zip') as z:
        for name,value in installed.items():
            if (site/name).is_file() and digest(site/name)==value:atomic(site/name,z.read(name))
    raise
print('Website 0.37.0 installed for https://control.fecxbrfzku.net only.')
print('Updated versioned frontend and four recovery/management PHP files only.')
print('Credentials, application key, other backend PHP, databases, existing APKs, updater feeds and workers were not modified.')
print('No app publication, reseller fanout or global recovery-setting change was started.')
print('Rollback backup: '+str(backup/'website-before.zip'))
PY
