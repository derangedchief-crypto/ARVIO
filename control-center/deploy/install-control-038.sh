#!/usr/bin/env bash
# Fixed-site enrollment/recovery update. No migrations, config edits or APK work.
set -euo pipefail
umask 077
[[ $(id -u) == 0 ]] || { echo 'Run on the WEBSITE host as root.' >&2; exit 1; }
site=/www/wwwroot/control.fecxbrfzku.net
php=/www/server/php/84/bin/php
archive=${1:?Usage: bash install-control-038.sh VERIFIED_038_UPDATE_ZIP}
cd "$site"
[[ $(pwd -P) == "$site" && -f config.php && ! -L config.php ]] || { echo 'Unexpected website directory/configuration.' >&2; exit 1; }
[[ -f storage/application.key && ! -L storage/application.key ]] || { echo 'Original application key missing/unsafe.' >&2; exit 1; }
case "$(cat VERSION)" in 0.37.0|0.38.0) ;; *) echo 'Expected website 0.37.0 or 0.38.0.' >&2; exit 1 ;; esac
"$php" -d opcache.enable_cli=0 -d opcache.jit=0 -d opcache.jit_buffer_size=0 -r 'require "src/bootstrap.php";try{$c=new ControlCenter\Config(getcwd());if(!$c->configured()||$c->origin()!=="https://control.fecxbrfzku.net")throw new RuntimeException();$c->secret("application.key",false);$db=new ControlCenter\Database($c);foreach(["025_device_recovery.sql","033_trial_conversion_defaults.sql"] as $m)if(!$db->one("SELECT name FROM schema_migrations WHERE name=?",[$m]))throw new RuntimeException();echo "Existing website/key/schema verified.\n";}catch(Throwable $e){fwrite(STDERR,"Website preflight failed; no changes made.\n");exit(1);}'
backup=$(mktemp -d /root/control-0380-backup-XXXXXX)
/usr/bin/python3 - "$site" "$archive" "$backup" "$php" <<'PY'
import hashlib,json,os,stat,subprocess,sys,tempfile
from pathlib import Path
from zipfile import ZipFile,ZIP_DEFLATED
site,archive,backup,php=Path(sys.argv[1]),Path(sys.argv[2]),Path(sys.argv[3]),sys.argv[4]
version='0.38.0';release=site/'public/assets/portal-0.38.0'
assets=['app.js','workspace.css','compact.css','renewals-settings.js','trial-workspace.js','trial-setup.js','trial-self-service.js','app-branding.js','workspace-library.js','renewal-checkout.js','renewal-checkout.css','device-workspace.js']
backend=['src/DeviceRecovery.php','src/RecoveryHolds.php','src/Enrollment.php','src/Application.php']
names=backend+['public/index.html','public/renew.html','VERSION']
def digest(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def inside(p):
    if p.is_symlink() or not p.resolve().is_relative_to(site):raise RuntimeError('Unexpected path/symlink; other sites preserved')
protected=[site/'config.php',site/'storage/application.key',*[p for folder in ['src','public','cli'] for p in sorted((site/folder).rglob('*.php')) if str(p.relative_to(site)) not in backend],*[p for p in sorted((site/'public/assets').iterdir()) if p.is_file()]]
before={str(p.relative_to(site)):digest(p) for p in protected}
for p in [release,*[site/n for n in names]]:inside(p)
marker=release/'.managed-control-update'
if release.exists() and (not marker.is_file() or marker.is_symlink() or marker.read_text()!='Extreme Control website '+version+'\n'):raise RuntimeError('Unrelated release directory preserved')
with ZipFile(archive) as z:
    if z.read('VERSION').decode().strip()!=version:raise RuntimeError('Wrong archive version')
    for line in z.read('MANIFEST.sha256').decode().splitlines():
        expected,name=line.split('  ',1)
        if hashlib.sha256(z.read(name)).hexdigest()!=expected:raise RuntimeError('Archive manifest mismatch')
    content={name:z.read('public/assets/'+name) for name in assets}
    pointers={name:z.read(name) for name in names}
for name in ['public/index.html','public/renew.html']:
    text=pointers[name].decode()
    for asset in assets:text=text.replace('/assets/'+asset,'/assets/portal-0.38.0/'+asset)
    pointers[name]=text.encode()
if release.exists():
    for name,data in content.items():
        p=release/name;inside(p)
        if p.exists() and digest(p)!=hashlib.sha256(data).hexdigest():raise RuntimeError('Modified versioned asset preserved')
php_args=[php,'-d','opcache.enable_cli=0','-d','opcache.jit=0','-d','opcache.jit_buffer_size=0']
for name in backend:
    fd,path=tempfile.mkstemp(suffix='.php',dir=backup)
    with os.fdopen(fd,'wb') as out:out.write(pointers[name])
    subprocess.run(php_args+['-l',path],check=True)
missing=[name for name in names if not (site/name).exists()]
with ZipFile(backup/'website-before.zip','w',ZIP_DEFLATED) as z:
    for name in names:
        if name not in missing:z.write(site/name,name)
(backup/'previously-absent.json').write_text(json.dumps(missing))
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
    if any(not (site/n).is_file() or digest(site/n)!=v for n,v in before.items()):raise RuntimeError('Protected files changed concurrently; not overwritten')
    check='require "src/bootstrap.php";$c=new ControlCenter\\Config(getcwd());$c->secret("application.key",false);$db=new ControlCenter\\Database($c);$db->query("SELECT 1");if(!class_exists("ControlCenter\\\\RecoveryHolds"))throw new RuntimeException();echo "Read-only post-install check passed.\\n";'
    subprocess.run(php_args+['-r',check],cwd=site,check=True)
except BaseException:
    with ZipFile(backup/'website-before.zip') as z:
        for name,value in installed.items():
            p=site/name
            if p.is_file() and digest(p)==value:
                if name in missing:p.unlink()
                else:atomic(p,z.read(name))
    raise
print('Website 0.38.0 installed for https://control.fecxbrfzku.net only.')
print('Existing APKs, credentials, private configuration, worker services and other sites preserved.')
print('Rollback backup: '+str(backup/'website-before.zip'))
PY
