#!/usr/bin/env bash
set -euo pipefail
umask 077
[[ $(id -u) == 0 ]] || exit 1
site=/opt/iptv-activation-worker
base="$(cd -- "$(dirname -- "$0")" && pwd)"
test -f "$site/main.py";test ! -L "$site/main.py"
systemctl is-active --quiet iptv-activation-worker.service || { echo 'Existing activation worker is not active; no stopped service was started.' >&2; exit 1; }
[[ "$(systemctl show iptv-activation-worker.service -p WorkingDirectory --value)" == "$site" ]] || { echo 'Activation service path differs; no changes made.' >&2; exit 1; }
backup="/root/extreme-activation-package-backups/$(date +%Y%m%d%H%M%S)-$$"
install -d -m 0700 -o root -g root "$backup"
cp --preserve=all "$site/main.py" "$backup/main.py"
test -f "$site/management_enrollment.py";test ! -L "$site/management_enrollment.py"
cp --preserve=all "$site/management_enrollment.py" "$backup/management_enrollment.py"
rollback(){ cp --preserve=all "$backup/main.py" "$site/main.py";cp --preserve=all "$backup/management_enrollment.py" "$site/management_enrollment.py";systemctl restart iptv-activation-worker.service || true; }
trap rollback ERR
before="$(sha256sum "$site/main.py" "$site/management_enrollment.py")"
"$site/venv/bin/python" "$base/patch-branded-package.py" "$site/main.py" "$site/management_enrollment.py"
if [[ "$before" == "$(sha256sum "$site/main.py" "$site/management_enrollment.py")" ]]; then trap - ERR;echo 'Activation proof package format already current; no restart needed.';exit 0;fi
systemctl restart iptv-activation-worker.service
"$site/venv/bin/python" - <<'PY'
import time,urllib.request
for attempt in range(20):
 try:
  with urllib.request.urlopen('http://127.0.0.1:9092/management/health',timeout=2) as response:
   if response.status==200:break
 except Exception:pass
 if attempt==19:raise SystemExit('Activation worker readiness failed; original source will be restored.')
 time.sleep(1)
PY
trap - ERR
echo 'Activation proof format fixed; .env/config, credentials, subscriptions, routes and signing secret preserved.'
