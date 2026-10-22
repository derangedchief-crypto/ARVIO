#!/usr/bin/env bash
# Backward-compatible source update. Existing DB/SSH configuration and venv stay intact.
set -euo pipefail
app=/opt/iptv-activation-worker
source_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
settings_file="${1:-}"
[[ -f "$app/main.py" && -f "$app/config.py" && -f "$app/.env" ]] || { echo 'Existing activation-worker installation not found.' >&2; exit 1; }
backup="$app/backups/management-$(date -u +%Y%m%d-%H%M%S)-$$"
mkdir -m 700 -p "$backup"
for name in main.py config.py management_enrollment.py .env; do
  if [[ -f "$app/$name" ]]; then cp -p "$app/$name" "$backup/$name"; fi
done
restore() {
  echo 'Update failed; restoring the previous worker files.' >&2
  for name in main.py config.py management_enrollment.py .env; do
    if [[ -f "$backup/$name" ]]; then cp -p "$backup/$name" "$app/$name"; fi
  done
  systemctl restart iptv-activation-worker || true
}
trap restore ERR
for name in main.py config.py management_enrollment.py; do
  install -m 644 "$source_dir/$name" "$app/$name"
done
if [[ -n "$settings_file" ]]; then
  "$app/venv/bin/python" - "$app/.env" "$settings_file" <<'PY'
import os, pathlib, re, stat, sys, tempfile
from urllib.parse import urlsplit
target, incoming = map(pathlib.Path,sys.argv[1:])
values = {}
for line in incoming.read_text().splitlines():
    if not line.strip() or line.lstrip().startswith('#'): continue
    key, separator, value = line.partition('=')
    if not separator or key not in {'MANAGEMENT_CONTROL_ORIGIN','MANAGEMENT_ENROLLMENT_SECRET'} or key in values:
        raise SystemExit('Invalid bridge settings file')
    values[key] = value.strip()
if set(values) != {'MANAGEMENT_CONTROL_ORIGIN','MANAGEMENT_ENROLLMENT_SECRET'}:
    raise SystemExit('Bridge settings file must contain exactly the two management fields')
url = urlsplit(values['MANAGEMENT_CONTROL_ORIGIN'])
if url.scheme != 'https' or not url.hostname or url.username or url.password or url.path not in ('','/') or url.query or url.fragment:
    raise SystemExit('Invalid control-center HTTPS origin')
if not re.fullmatch('[a-f0-9]{64}',values['MANAGEMENT_ENROLLMENT_SECRET']):
    raise SystemExit('Invalid bridge key format')
original = target.stat()
lines = [line for line in target.read_text().splitlines() if not any(re.match(r'^\s*(?:export\s+)?'+key+r'\s*=',line) for key in values)]
text = '\n'.join(lines).rstrip() + '\n' + ''.join(key+'='+value+'\n' for key,value in values.items())
fd, temporary = tempfile.mkstemp(prefix='.management-env-',dir=target.parent)
try:
    with os.fdopen(fd,'w') as out:
        out.write(text); out.flush(); os.fsync(out.fileno())
    os.chmod(temporary,stat.S_IMODE(original.st_mode)); os.chown(temporary,original.st_uid,original.st_gid)
    os.replace(temporary,target)
finally:
    if os.path.exists(temporary): os.unlink(temporary)
PY
fi
"$app/venv/bin/python" -m py_compile "$app/main.py" "$app/config.py" "$app/management_enrollment.py"
systemctl restart iptv-activation-worker
ready=0
for attempt in $(seq 1 45); do
  if curl --fail --silent --max-time 2 http://127.0.0.1:9092/health >/dev/null; then ready=1; break; fi
  sleep 1
done
[[ "$ready" == 1 ]] || { echo 'Worker did not become healthy after restart.' >&2; false; }
trap - ERR
echo 'Activation worker updated; its existing configuration and activation route were preserved.'
curl --fail --silent --max-time 3 http://127.0.0.1:9092/management/health
echo
