#!/usr/bin/env bash
# Run from the extracted, hash-verified live-connection update directory.
set -euo pipefail
umask 077
site=/www/wwwroot/control.fecxbrfzku.net
php=/www/server/php/84/bin/php
nginx=/www/server/nginx/sbin/nginx
conf=/www/server/panel/vhost/nginx/control.fecxbrfzku.net.conf
bundle=$(cd -- "$(dirname -- "$0")/.." && pwd -P)
[[ $(id -u) == 0 ]] || { echo 'Run as root on the control-panel website server.' >&2; exit 1; }
[[ -f "$site/config.php" && -f "$site/storage/application.key" && -f "$conf" && ! -L "$conf" ]] || { echo 'Expected control-panel installation not found.' >&2; exit 1; }
[[ $(cd "$site" && pwd -P) == "$site" && ! -L "$site/src" && ! -L "$site/storage" && ! -e "$site/src/LiveConnection.php" ]] || { echo 'Unexpected site path or existing live backend; stopped.' >&2; exit 1; }
[[ $(cat "$site/VERSION") == 0.40.0 ]] || { echo 'This update requires website 0.40.0. Other versions need a matching package.' >&2; exit 1; }
[[ ! -e /etc/systemd/system/extreme-live-connection.service && ! -e /etc/extreme-live-connection.env && ! -e "$site/storage/live-connection.key" && ! -e /opt/extreme-live-connection ]] || { echo 'Existing live-service installation found; stopped without replacing it.' >&2; exit 1; }
python3 -c 'import sys; assert sys.version_info >= (3,10), "Python 3.10+ required"'
"$php" -d opcache.enable_cli=0 -r 'exit(extension_loaded("curl")?0:1);'
(cd "$bundle" && sha256sum -c MANIFEST.sha256)
(cd "$site" && sha256sum -c "$bundle/BASELINE.sha256")
for file in Auth Application Database DeviceProfiles LiveConnection; do
    "$php" -d opcache.enable_cli=0 -l "$bundle/src/$file.php"
    [[ ! -L "$site/src/$file.php" ]]
done
"$nginx" -t
backup=$(mktemp -d /root/extreme-live-before-XXXXXX)
cp -p "$conf" "$backup/nginx.conf"
tar -C "$site" -czf "$backup/backend.tgz" src/Auth.php src/Application.php src/Database.php src/DeviceProfiles.php VERSION
python3 - "$conf" "$bundle/live-connection/nginx-location.conf" "$backup/nginx.new" <<'PY'
import re,sys
from pathlib import Path
text=Path(sys.argv[1]).read_text()
if '/api/device/live' in text or len(re.findall(r'(?m)^\s*server\s*\{',text))!=1:
    raise SystemExit('Nginx layout needs manual review; no website changes made.')
text=re.sub(r'(?m)^(\s*server\s*\{)',lambda m:m[1]+'\n'+Path(sys.argv[2]).read_text(),text,count=1)
Path(sys.argv[3]).write_text(text)
PY
echo "Preparing dependencies; backup: $backup"
install -d -m 0755 /opt/extreme-live-connection
python3 -m venv /opt/extreme-live-connection/venv
/opt/extreme-live-connection/venv/bin/pip install --disable-pip-version-check -r "$bundle/live-connection/requirements.txt"
id extremelive >/dev/null 2>&1 || useradd --system --no-create-home --shell /usr/sbin/nologin extremelive
install -m 0644 "$bundle/live-connection/service.py" /opt/extreme-live-connection/service.py
# The root-only install umask must not prevent the service user reading its venv.
# This directory contains code/dependencies only; the secret stays in /etc.
chmod -R a+rX /opt/extreme-live-connection
changed=0
rollback() {
    code=$?
    if (( code != 0 && changed == 1 )); then
        set +e
        systemctl disable --now extreme-live-connection.service || true
        tar -C "$site" -xzf "$backup/backend.tgz"
        cp -p "$backup/nginx.conf" "$conf"
        rm -f "$site/storage/live-connection.key" "$site/src/LiveConnection.php" /etc/extreme-live-connection.env /etc/systemd/system/extreme-live-connection.service
        systemctl daemon-reload
        "$nginx" -t && "$nginx" -s reload || true
        if [[ -x /etc/init.d/php-fpm-84 ]]; then /etc/init.d/php-fpm-84 reload || true; fi
        echo "Installation rolled back. Dependency directory retained; backup: $backup" >&2
    fi
    exit "$code"
}
trap rollback EXIT
changed=1
python3 - "$site" <<'PY'
import os,secrets,sys
from pathlib import Path
site=Path(sys.argv[1]);key=secrets.token_hex(32);owner=(site/'storage/application.key').stat()
path=site/'storage/live-connection.key'
with path.open('x') as f:f.write(key+'\n')
os.chown(path,owner.st_uid,owner.st_gid);os.chmod(path,0o600)
with open('/etc/extreme-live-connection.env','x') as f:f.write('EXTREME_LIVE_KEY='+key+'\n')
os.chmod('/etc/extreme-live-connection.env',0o600)
PY
for file in Auth Application Database DeviceProfiles LiveConnection; do
    install -m 0644 "$bundle/src/$file.php" "$site/src/$file.php"
done
install -m 0644 "$bundle/live-connection/extreme-live-connection.service" /etc/systemd/system/extreme-live-connection.service
systemctl daemon-reload
systemctl enable --now extreme-live-connection.service
curl --fail --retry 5 --retry-connrefused --retry-delay 1 --max-time 3 http://127.0.0.1:19095/health
cp "$backup/nginx.new" "$conf"
"$nginx" -t
"$nginx" -s reload
printf '0.41.0\n' > "$site/VERSION"
if [[ -x /etc/init.d/php-fpm-84 ]]; then /etc/init.d/php-fpm-84 reload; fi
trap - EXIT
echo
echo "Live connection backend installed. Backup: $backup"
echo 'Existing apps still need the separately signed app update. No APK was published.'
