#!/usr/bin/env bash
# Additive installer for the exact owner-confirmed auth worker. Never replace config.
set -euo pipefail
umask 077
[[ $(id -u) == 0 ]] || { echo 'Run on the AUTH worker host as root.' >&2; exit 1; }
base="$(cd -- "$(dirname -- "$0")" && pwd)"
site=/opt/iptv-auth-worker
main="$site/main.py"
python="$site/venv/bin/python"
state=/var/lib/extreme-branded-trials
env=/etc/extreme-branded-trials.env
dropin=/etc/systemd/system/iptv-auth-worker.service.d/branded-trials.conf
test -x "$python";test -f "$main";test ! -L "$main"
[[ "$(systemctl show iptv-auth-worker.service -p User --value)" == resellerworker && "$(systemctl show iptv-auth-worker.service -p WorkingDirectory --value)" == "$site" ]] || { echo 'Auth-worker service identity differs; no changes made.' >&2; exit 1; }
systemctl is-active --quiet iptv-auth-worker.service || { echo 'The named auth-worker service is not active. Refusing to start a stopped or different service; send its status for review.' >&2; exit 1; }
"$python" - "$base/branded_trials.py" <<'PY'
import ast,sys
import fastapi
import fastapi.concurrency
from pathlib import Path
ast.parse(Path(sys.argv[1]).read_bytes())
PY
"$python" - "$main" <<'PY'
import hashlib,sys
from pathlib import Path
p=Path(sys.argv[1]);data=p.read_bytes()
hook=b'\n# EXTREME_BRANDED_TRIALS_V1\nfrom branded_trials import install_trial_routes\ninstall_trial_routes(app, globals())\n'
baseline='c5a93d1a4d98f214a4427e8ac0ca7dbdcb03fb5d59632ad4212740a188a7e8d1'
original=data[:-len(hook)] if data.endswith(hook) else data
if hashlib.sha256(original).hexdigest()!=baseline:raise SystemExit('Auth-worker source changed; installer refused without overwriting it.')
PY
for path in "$env" "$state" "$site/branded_trials.py" "$dropin"; do test ! -L "$path" || { echo 'Setup symlink needs owner review.' >&2; exit 1; };done
if [[ -e "$site/branded_trials.py" ]]; then
  prior="$(sha256sum "$site/branded_trials.py" | cut -d ' ' -f1)"
  payload="$(sha256sum "$base/branded_trials.py" | cut -d ' ' -f1)"
  [[ "$prior" == "$payload" || "$prior" == 819ac5e13d6b10815909bb27067e7dc2cd5cd3e0f16dee3ed3b846ea55201788 || "$prior" == 9ec850d9f790c898421e2da0b15eaf0ee36c5dd492128df71272ce969934ab9e ]] || { echo 'Existing trial module differs; preserve it for owner review.' >&2; exit 1; }
fi
if [[ -e "$dropin" ]]; then
  [[ "$(cat "$dropin")" == $'[Service]\nEnvironmentFile=/etc/extreme-branded-trials.env\nReadWritePaths=/var/lib/extreme-branded-trials' ]] || { echo 'Existing service override differs; no changes made.' >&2; exit 1; }
fi
install -d -m 0700 -o root -g root /root/extreme-trial-bridge-backups
backup="/root/extreme-trial-bridge-backups/install-$(date +%Y%m%d%H%M%S)-$$"
install -d -m 0700 "$backup"
cp --preserve=all -- "$main" "$backup/main.py"
if [[ -e "$site/branded_trials.py" ]]; then cp --preserve=all -- "$site/branded_trials.py" "$backup/branded_trials.py";fi
if [[ -e "$dropin" ]]; then cp --preserve=all -- "$dropin" "$backup/branded-trials.conf";fi
if [[ ! -e "$env" ]]; then
  "$python" - "$env" <<'PY'
import os,secrets,sys
with open(sys.argv[1],'x',opener=lambda p,f:os.open(p,f,0o600)) as stream:
 stream.write('BRANDED_TRIAL_SHARED_SECRET='+secrets.token_hex(32)+'\nBRANDED_TRIAL_STATE_DIR=/var/lib/extreme-branded-trials\nBRANDED_TRIAL_PROVISION_ENABLED=false\n')
PY
fi
[[ "$(stat -c %u "$env")" == 0 && "$(stat -c %a "$env")" == 600 ]] || { echo 'Original trial environment must remain root0600; stop for review.' >&2; exit 1; }
"$python" - "$env" <<'PY'
import re,sys
from pathlib import Path
lines=Path(sys.argv[1]).read_text().splitlines()
values=dict(line.split('=',1) for line in lines if line and not line.startswith('#'))
if not re.fullmatch('[a-f0-9]{64}',values.get('BRANDED_TRIAL_SHARED_SECRET','')) or values.get('BRANDED_TRIAL_STATE_DIR')!='/var/lib/extreme-branded-trials' or values.get('BRANDED_TRIAL_PROVISION_ENABLED') not in ('true','false'):
 raise SystemExit('Existing private trial environment differs; it was not replaced.')
PY
# Preserve any existing state/identity.key: never truncate or regenerate history.
if [[ -d "$state" ]]; then
  [[ "$(stat -c %U "$state")" == resellerworker && "$(stat -c %a "$state")" == 700 ]] || { echo 'Existing trial-state ownership needs review.' >&2; exit 1; }
else install -d -m 0700 -o resellerworker -g resellerworker "$state";fi
temp="$(mktemp "$site/.trial-main-XXXXXX.py")"
modified=false
rollback() {
  status=$?
  trap - EXIT
  rm -f -- "$temp"
  if [[ "$status" != 0 && "$modified" == true ]]; then
    cp --preserve=all -- "$backup/main.py" "$main"
    if [[ -e "$backup/branded_trials.py" ]]; then cp --preserve=all -- "$backup/branded_trials.py" "$site/branded_trials.py";else rm -f -- "$site/branded_trials.py";fi
    if [[ -e "$backup/branded-trials.conf" ]]; then cp --preserve=all -- "$backup/branded-trials.conf" "$dropin";else rm -f -- "$dropin";fi
    systemctl daemon-reload
    systemctl restart iptv-auth-worker.service || echo 'Original files restored, but original service needs owner attention.' >&2
    echo 'Trial installation failed; original worker files/service override restored. Private trial identity/history preserved.' >&2
  fi
  exit "$status"
}
trap rollback EXIT
cp --preserve=all -- "$main" "$temp"
"$python" - "$temp" <<'PY'
import ast,sys
from pathlib import Path
p=Path(sys.argv[1]);data=p.read_bytes();hook=b'\n# EXTREME_BRANDED_TRIALS_V1\nfrom branded_trials import install_trial_routes\ninstall_trial_routes(app, globals())\n'
if not data.endswith(hook):data+=hook
ast.parse(data);p.write_bytes(data)
PY
modified=true
install -m 0644 -o root -g root "$base/branded_trials.py" "$site/branded_trials.py"
mv -T -- "$temp" "$main"
install -d -m 0755 /etc/systemd/system/iptv-auth-worker.service.d
printf '[Service]\nEnvironmentFile=/etc/extreme-branded-trials.env\nReadWritePaths=/var/lib/extreme-branded-trials\n' > "$dropin"
"$python" - "$env" <<'PY'
import json,os,re,sys
from pathlib import Path
values=dict(line.split('=',1) for line in Path(sys.argv[1]).read_text().splitlines() if line and not line.startswith('#'))
secret=values.get('BRANDED_TRIAL_SHARED_SECRET','')
if not re.fullmatch('[a-f0-9]{64}',secret):raise SystemExit('Existing trial connection secret needs private owner review; it was not replaced.')
path=Path('/root/brand-trial-website-connection.json')
if path.is_symlink():raise SystemExit('Unsafe private export path.')
with open(path,'w',opener=lambda p,f:os.open(p,f,0o600)) as stream:json.dump({'base_url':'https://resellerworker.extremeiptv.net','shared_secret':secret},stream)
path.chmod(0o600)
PY
systemctl daemon-reload
systemctl restart iptv-auth-worker.service
systemctl is-active --quiet iptv-auth-worker.service
"$python" - <<'PY'
import time,urllib.request,urllib.error
for attempt in range(15):
 try:
  with urllib.request.urlopen('http://127.0.0.1:9091/health',timeout=2) as response:
   if response.status!=200:raise RuntimeError('Worker health unavailable')
  request=urllib.request.Request('http://127.0.0.1:9091/branded-trials/plans',data=b'{}',headers={'Content-Type':'application/json'})
  try:urllib.request.urlopen(request,timeout=2)
  except urllib.error.HTTPError as error:
   if error.code==401:break
  raise RuntimeError('Restricted trial route not ready')
 except Exception:
  if attempt==14:raise SystemExit('Auth-worker readiness failed; restoring original service.') from None
  time.sleep(1)
PY
modified=false
echo 'Trial bridge installed on the existing auth worker; original portal config/operations preserved.'
echo 'New installations default to read-only policy setup; trial creation is OFF until explicitly enabled.'
echo 'Private website connection file: /root/brand-trial-website-connection.json'
echo 'Transfer that file directly to /root/ on the website server. Never upload it under public/ or paste it in chat.'
