#!/usr/bin/env bash
# NEW service only. The running iptv-auth-worker, its source/config/env/trials stay.
set -euo pipefail
umask 077
export PYTHONDONTWRITEBYTECODE=1
[[ $(id -u) == 0 ]] || { echo 'Run on the worker host as root.' >&2; exit 1; }
base="$(cd -- "$(dirname -- "$0")" && pwd)"
auth=/opt/iptv-auth-worker
site=/opt/iptv-renewal-worker
bootstrap=/usr/bin/python3
python="$site/venv/bin/python"
state=/var/lib/iptv-renewal-worker
environment=/etc/iptv-renewal-worker.env
unit=/etc/systemd/system/iptv-renewal-worker.service
export_file=/root/renewal-worker-website-connection.json
test -x "$bootstrap"
[[ "$(systemctl show iptv-auth-worker.service -p User --value)" == resellerworker && "$(systemctl show iptv-auth-worker.service -p WorkingDirectory --value)" == "$auth" ]] || { echo 'Existing auth-worker identity differs; original worker untouched.' >&2; exit 1; }
auth_was_active=false
if systemctl is-active --quiet iptv-auth-worker.service; then auth_was_active=true;fi
for path in "$auth/config.py" "$auth/.env"; do test -f "$path" && test ! -L "$path" || { echo 'Existing private config.py/.env missing or symlinked; send paths only, never values.' >&2; exit 1; };done
for path in "$site" "$state" "$environment" "$unit" "$export_file"; do test ! -L "$path" || { echo 'Unsafe new-worker setup path.' >&2; exit 1; };done
if [[ -d "$site" && -n "$(find "$site" -mindepth 1 -maxdepth 1 -print -quit)" ]]; then
 [[ -f "$site/.managed" && "$(cat "$site/.managed")" == iptv-renewal-worker-v1 ]] || { echo 'Existing non-managed renewal folder preserved; nothing replaced.' >&2; exit 1; }
fi
if [[ -e "$unit" ]]; then [[ -f "$site/.managed" ]] || { echo 'An existing unmanaged renewal service was preserved.' >&2; exit 1; };fi
"$bootstrap" -I -S - "$base" <<'PY'
import ast,sys
from pathlib import Path
for p in Path(sys.argv[1]).glob('*.py'):ast.parse(p.read_bytes())
PY
auth_env_before="$(sha256sum "$auth/.env")"
auth_config_before="$(sha256sum "$auth/config.py")"
auth_main_before="$(sha256sum "$auth/main.py")"
install -d -m 0700 -o root -g root /root/iptv-renewal-worker-backups
backup="$(mktemp -d /root/iptv-renewal-worker-backups/install-XXXXXX)"
was_active=false
if systemctl is-active --quiet iptv-renewal-worker.service; then was_active=true;fi
if id iptvrenewal >/dev/null 2>&1; then
 [[ -f "$site/.managed" && "$(getent passwd iptvrenewal | cut -d: -f6)" == "$state" ]] || { echo 'Existing unrelated system account preserved.' >&2; exit 1; }
else
 getent group iptvrenewal >/dev/null && { echo 'Existing unrelated system group preserved.' >&2; exit 1; }
 useradd --system --user-group --home-dir "$state" --no-create-home --shell /usr/sbin/nologin iptvrenewal
fi
install -d -m 0750 -o root -g iptvrenewal "$site"
printf 'iptv-renewal-worker-v1\n' > "$site/.managed"
test ! -L "$site/venv"
# A separate environment, with the working auth environment's package versions
# as read-only constraints. Nothing is installed into/changed in the auth venv.
if [[ ! -f "$site/.dependencies-ready" ]]; then
 [[ "$was_active" == false ]] || { echo 'Existing active renewal dependency setup needs review.' >&2; exit 1; }
 "$bootstrap" -I -S - "$auth/config.py" "$backup" <<'PY'
import ast,importlib.metadata as m,re,sys
from pathlib import Path
out=Path(sys.argv[2]);auth=Path(sys.argv[1]).parent
paths=[str(p) for p in (auth/'venv/lib').glob('python*/site-packages')]
if not paths:raise SystemExit('Working auth dependency metadata is missing; original environment unchanged')
installed=list(m.distributions(path=paths));mapping={}
for d in installed:
 name=d.metadata.get('Name','')
 if not re.fullmatch('[A-Za-z0-9_.-]+',name):continue
 tops=(d.read_text('top_level.txt') or '').splitlines()
 if not tops:tops={str(p).split('/')[0].removesuffix('.py') for p in (d.files or [])}
 for top in tops:
  if re.fullmatch('[A-Za-z_][A-Za-z0-9_]*',top):mapping.setdefault(top,[]).append(name)
required={'fastapi','uvicorn','mysql-connector-python','sshtunnel','paramiko','pydantic'}
try:tree=ast.parse(Path(sys.argv[1]).read_text())
except Exception:raise SystemExit('Private config syntax could not be inspected; values omitted') from None
for n in ast.walk(tree):
 names=[a.name.split('.')[0] for a in n.names] if isinstance(n,ast.Import) else [n.module.split('.')[0]] if isinstance(n,ast.ImportFrom) and n.module else []
 for name in names:required.update(mapping.get(name,[]))
constraints=[];pinned=set()
for d in installed:
 name=d.metadata.get('Name','');version=d.version
 if re.fullmatch('[A-Za-z0-9_.-]+',name) and re.fullmatch('[A-Za-z0-9.!+_-]+',version):constraints.append(name+'=='+version);pinned.add(re.sub('[-_.]+','-',name).lower())
if any(re.sub('[-_.]+','-',name).lower() not in pinned for name in required):raise SystemExit('Working dependency version metadata is incomplete; original environment unchanged')
(out/'constraints.txt').write_text('\n'.join(sorted(set(constraints)))+'\n')
(out/'requirements.txt').write_text('\n'.join(sorted(required))+'\n')
PY
 if [[ ! -x "$python" ]]; then
  if ! "$bootstrap" -I -m venv "$site/venv" > "$backup/venv.log" 2>&1; then echo 'Independent venv bootstrap needs the matching OS python3-venv package. Auth files/services unchanged.' >&2;exit 1;fi
 fi
 build_home="$(mktemp -d "$site/.dependency-build-XXXXXX")"
 chown iptvrenewal:iptvrenewal "$build_home"
 for name in constraints.txt requirements.txt; do install -m 0600 -o iptvrenewal -g iptvrenewal "$backup/$name" "$build_home/$name";done
 find "$site/venv" -type d -exec chown iptvrenewal:iptvrenewal {} + -exec chmod 0750 {} +
 find "$site/venv" -type f -exec chown iptvrenewal:iptvrenewal {} +
 if ! (cd "$build_home";runuser -u iptvrenewal -- env -u PIP_INDEX_URL -u PIP_EXTRA_INDEX_URL -u PIP_TRUSTED_HOST HOME="$build_home" PIP_CONFIG_FILE=/dev/null "$python" -m pip install --index-url https://pypi.org/simple --no-cache-dir --disable-pip-version-check --no-input --constraint "$build_home/constraints.txt" --requirement "$build_home/requirements.txt") > "$backup/dependencies.log" 2>&1; then
  echo "New private dependency setup failed; auth environment unchanged. Review $backup/dependencies.log locally (do not paste private pip settings)." >&2;exit 1
 fi
 find "$site/venv" -type d -exec chown root:iptvrenewal {} + -exec chmod 0750 {} +
 find "$site/venv" -type f -exec chown root:iptvrenewal {} + -exec chmod g+rX,o-rwx {} +
 (cd "$site";runuser -u iptvrenewal -- "$python" -c 'import fastapi,uvicorn,mysql.connector,sshtunnel,paramiko,pydantic')
 printf 'ready\n' > "$site/.dependencies-ready"
fi
runuser -u iptvrenewal -- test -x "$python"
files=(main.py adapter.py native_panel.py branded_renewals.py branded_renewals_runtime.py renewal_packages.py configure_proxy.py nginx-location.conf README.md preflight.py)
for name in "${files[@]}"; do
 test ! -L "$site/$name" || { echo 'Unsafe managed source path.' >&2; exit 1; }
 if [[ -f "$site/$name" ]]; then cp --preserve=all -- "$site/$name" "$backup/$name";fi
done
if [[ -f "$unit" ]]; then cp --preserve=all -- "$unit" "$backup/unit";fi
# Existing new-worker credentials are retained on updates. Initial setup copies
# values locally with private permissions; no auth-worker ACL/content changes.
for name in config.py .env; do
 test ! -L "$site/$name" || { echo 'Unsafe private copy path.' >&2; exit 1; }
 if [[ ! -e "$site/$name" ]]; then install -m 0640 -o root -g iptvrenewal "$auth/$name" "$site/$name";fi
done
"$bootstrap" -I -S "$base/bootstrap_state.py" "$environment" "$state" "$export_file" iptvrenewal
modified=false
rollback() {
 code=$?
 trap - EXIT
 if [[ "$code" != 0 && "$modified" == true ]]; then
  systemctl stop iptv-renewal-worker.service || true
  for name in "${files[@]}"; do
   if [[ -f "$backup/$name" ]]; then cp --preserve=all -- "$backup/$name" "$site/$name";else rm -f -- "$site/$name";fi
  done
  if [[ -f "$backup/unit" ]]; then cp --preserve=all -- "$backup/unit" "$unit";else rm -f -- "$unit";fi
  systemctl daemon-reload
  if [[ "$was_active" == true ]]; then systemctl start iptv-renewal-worker.service || true;fi
  echo 'New renewal-worker readiness failed; its prior source/unit restored. Auth worker untouched; renewal state/key retained.' >&2
 fi
 exit "$code"
}
trap rollback EXIT
modified=true
if [[ "$was_active" == true ]]; then systemctl stop iptv-renewal-worker.service;fi
for name in "${files[@]}"; do install -m 0644 -o root -g iptvrenewal "$base/$name" "$site/$name";done
install -m 0644 -o root -g root "$base/iptv-renewal-worker.service" "$unit"
"$bootstrap" -I -S - "$environment" "$site" "$state" <<'PY'
import json,os,subprocess,sys
from pathlib import Path
env=dict(os.environ)
env.update(dict(line.split('=',1) for line in Path(sys.argv[1]).read_text().splitlines() if line and not line.startswith('#')))
site=Path(sys.argv[2]);started=__import__('time').time()
try:
 result=subprocess.run(['runuser','-u','iptvrenewal','--',str(site/'venv/bin/python'),str(site/'preflight.py')],cwd=site,env=env,capture_output=True,text=True,timeout=180)
except subprocess.TimeoutExpired:
 raise SystemExit('Read-only startup exceeded 180 seconds; check SSH/database reachability. Original state retained.') from None
try:
 report=json.loads(result.stdout)
 print(json.dumps(report,indent=2))
except Exception:print('Read-only startup could not produce its safe report.')
if result.returncode:
 try:
  evidence=json.loads((Path(sys.argv[3])/'startup-status.json').read_text())
  if evidence.get('at',0)>=int(started):print(json.dumps(evidence,indent=2))
 except Exception:pass
 raise SystemExit('Read-only startup failed BEFORE service launch; evidence above. No renewal was attempted.')
PY
systemctl daemon-reload
systemctl start iptv-renewal-worker.service
"$bootstrap" -I -S - "$environment" <<'PY'
import hashlib,hmac,http.client,json,secrets,sys,time
from pathlib import Path
key=dict(line.split('=',1) for line in Path(sys.argv[1]).read_text().splitlines() if line and not line.startswith('#'))['RENEWAL_BRIDGE_SECRET']
deadline=time.monotonic()+180
last={}
while True:
 connection=None
 try:
  stamp=str(int(time.time()));nonce=secrets.token_hex(16);raw=b'{}'
  sig=hmac.new(key.encode(),b'branded-renewal-request-v1\n'+stamp.encode()+b'\n'+nonce.encode()+b'\n'+raw,hashlib.sha256).hexdigest()
  # A loopback health check must NEVER inherit HTTP_PROXY/ALL_PROXY settings.
  connection=http.client.HTTPConnection('127.0.0.1',9093,timeout=3)
  connection.request('POST','/branded-renewals/status',body=raw,headers={'Content-Type':'application/json','X-Renewal-Timestamp':stamp,'X-Renewal-Nonce':nonce,'X-Renewal-Signature':sig})
  response=connection.getresponse();last={'http_status':response.status}
  if response.status!=200:raise RuntimeError('http-status')
  e=json.loads(response.read(65537))
  last['phase']='response-signature'
  expected=hmac.new(key.encode(),('branded-renewal-result-v1\n'+stamp+'\n'+nonce+'\n'+e['payload']).encode(),hashlib.sha256).hexdigest()
  if not hmac.compare_digest(expected,e['signature']):raise RuntimeError()
  value=json.loads(e['payload'])
  last['phase']='response-capabilities'
  if value.get('mode')!='renewals' or value.get('writes_enabled') is not True or value.get('atomic_renewal')!=1 or value.get('current_package_only') is not True:raise RuntimeError()
  break
 except Exception as error:
  last['error_type']=type(error).__name__
  if isinstance(error,OSError):last['errno']=error.errno
  if time.monotonic()>=deadline or 'http_status' in last:
   print(json.dumps({'loopback_readiness':last}))
   import subprocess
   for property in ['ActiveState','SubState','MainPID','ExecMainStatus']:
    result=subprocess.run(['systemctl','show','iptv-renewal-worker.service','-p',property,'--value'],capture_output=True,text=True)
    text=result.stdout.strip()
    if text and all(c.isalnum() or c in '-_' for c in text):print(property+'='+text)
   try:print(Path('/var/lib/iptv-renewal-worker/startup-status.json').read_text())
   except OSError:pass
   raise SystemExit('New renewal service failed readiness; private values omitted') from None
  time.sleep(1)
 finally:
  if connection is not None:connection.close()
PY
[[ "$auth_env_before" == "$(sha256sum "$auth/.env")" && "$auth_config_before" == "$(sha256sum "$auth/config.py")" && "$auth_main_before" == "$(sha256sum "$auth/main.py")" ]] || { echo 'Auth files changed concurrently; investigate before proceeding.' >&2; exit 1; }
if [[ "$auth_was_active" == true ]]; then systemctl is-active --quiet iptv-auth-worker.service;fi
systemctl enable iptv-renewal-worker.service
modified=false
echo 'Separate renewal worker ready on 127.0.0.1:9093; auth worker was NOT modified or restarted.'
if ! "$bootstrap" -I -S "$base/configure_proxy.py"; then echo 'Local worker is ready; public renewal routing needs the actual proxy config reviewed. Existing auth/trial routing was not replaced. Connection transfer must wait.' >&2;exit 1;fi
echo "New-worker backup: $backup"
echo "Private website connection file: $export_file (never paste/upload publicly)"
echo 'After public routing succeeds, transfer the private connection file to the website and run its full-worker import.'
