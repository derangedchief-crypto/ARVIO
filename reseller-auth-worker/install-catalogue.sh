#!/usr/bin/env bash
# AUTH host only. Add catalogue routes; never install a paid-renewal adapter.
set -euo pipefail
umask 077
[[ $(id -u) == 0 ]] || { echo 'Run on the existing AUTH worker host as root.' >&2; exit 1; }
base="$(cd -- "$(dirname -- "$0")" && pwd)"
site=/opt/iptv-auth-worker
main="$site/main.py"
python="$site/venv/bin/python"
env=/etc/extreme-renewal-catalogue.env
dropin=/etc/systemd/system/iptv-auth-worker.service.d/renewal-catalogue.conf
export_file=/root/brand-renewal-website-connection.json
test -x "$python";test -f "$main";test ! -L "$main"
[[ "$(systemctl show iptv-auth-worker.service -p User --value)" == resellerworker && "$(systemctl show iptv-auth-worker.service -p WorkingDirectory --value)" == "$site" ]] || { echo 'Auth-worker service identity differs; nothing changed.' >&2; exit 1; }
systemctl is-active --quiet iptv-auth-worker.service || { echo 'Existing auth worker is stopped; refusing to start or replace it.' >&2; exit 1; }
for path in "$env" "$dropin" "$export_file" "$site/branded_renewal_catalogue.py" "$site/renewal_packages.py"; do test ! -L "$path" || { echo 'Unsafe catalogue setup path; nothing changed.' >&2; exit 1; };done
"$python" - "$base" "$main" <<'PY'
import ast,sys
from pathlib import Path
import fastapi
import starlette.concurrency
sys.path.insert(0,sys.argv[1])
from catalogue_source import transform
transform(Path(sys.argv[2]).read_bytes())
for name in ['branded_renewal_catalogue.py','renewal_packages.py']:
 ast.parse((Path(sys.argv[1])/name).read_bytes())
 existing=Path(sys.argv[2]).parent/name
 if existing.exists() and existing.read_bytes()!=(Path(sys.argv[1])/name).read_bytes():raise SystemExit('Unknown existing catalogue module; preserved for review.')
PY
if [[ -e "$dropin" ]]; then
 [[ "$(cat "$dropin")" == $'[Service]\nEnvironmentFile=/etc/extreme-renewal-catalogue.env' ]] || { echo 'Unknown existing service override; preserved.' >&2; exit 1; }
fi
install -d -m 0700 -o root -g root /root/extreme-renewal-catalogue-backups
backup="$(mktemp -d /root/extreme-renewal-catalogue-backups/install-XXXXXX)"
cp --preserve=all -- "$main" "$backup/main.py"
for name in branded_renewal_catalogue.py renewal_packages.py; do
 if [[ -e "$site/$name" ]]; then cp --preserve=all -- "$site/$name" "$backup/$name";fi
done
if [[ -e "$dropin" ]]; then cp --preserve=all -- "$dropin" "$backup/renewal-catalogue.conf";fi
"$python" - "$env" "$export_file" <<'PY'
import json,os,re,secrets,stat,sys
from pathlib import Path
env=Path(sys.argv[1]);target=Path(sys.argv[2])
if not env.exists():
 with open(env,'x',opener=lambda p,f:os.open(p,f,0o600)) as out:out.write('BRANDED_RENEWAL_SHARED_SECRET='+secrets.token_hex(32)+'\nBRANDED_RENEWAL_WRITE_ENABLED=false\n')
if env.stat().st_uid!=0 or stat.S_IMODE(env.stat().st_mode)!=0o600:raise SystemExit('Catalogue environment must be root0600; no keys replaced.')
lines=[line for line in env.read_text().splitlines() if line and not line.startswith('#')]
values=dict(line.split('=',1) for line in lines)
if len(lines)!=2 or set(values)!={'BRANDED_RENEWAL_SHARED_SECRET','BRANDED_RENEWAL_WRITE_ENABLED'} or not re.fullmatch('[a-f0-9]{64}',values.get('BRANDED_RENEWAL_SHARED_SECRET','')) or values.get('BRANDED_RENEWAL_WRITE_ENABLED')!='false':raise SystemExit('Existing catalogue policy differs; nothing enabled or rotated.')
document={'base_url':'https://resellerworker.extremeiptv.net','shared_secret':values['BRANDED_RENEWAL_SHARED_SECRET'],'mode':'catalogue_only'}
if target.exists():
 if target.stat().st_uid!=0 or stat.S_IMODE(target.stat().st_mode)!=0o600 or json.loads(target.read_text())!=document:raise SystemExit('Existing private export differs; it was not replaced.')
else:
 with open(target,'x',opener=lambda p,f:os.open(p,f,0o600)) as out:json.dump(document,out)
PY
temp="$(mktemp "$site/.catalogue-main-XXXXXX.py")"
modified=false
rollback() {
 status=$?
 trap - EXIT
 rm -f -- "$temp"
 if [[ "$status" != 0 && "$modified" == true ]]; then
  cp --preserve=all -- "$backup/main.py" "$main"
  for name in branded_renewal_catalogue.py renewal_packages.py; do
   if [[ -e "$backup/$name" ]]; then cp --preserve=all -- "$backup/$name" "$site/$name";else rm -f -- "$site/$name";fi
  done
  if [[ -e "$backup/renewal-catalogue.conf" ]]; then cp --preserve=all -- "$backup/renewal-catalogue.conf" "$dropin";else rm -f -- "$dropin";fi
  systemctl daemon-reload
  systemctl restart iptv-auth-worker.service || echo 'Original worker restored; service needs owner attention.' >&2
  echo 'Catalogue readiness failed; original worker files/override restored. Trial state and credentials were never modified.' >&2
 fi
 exit "$status"
}
trap rollback EXIT
cp --preserve=all -- "$main" "$temp"
"$python" - "$base" "$temp" <<'PY'
import sys
from pathlib import Path
sys.path.insert(0,sys.argv[1])
from catalogue_source import transform
p=Path(sys.argv[2]);p.write_bytes(transform(p.read_bytes()))
PY
cmp -s "$main" "$backup/main.py" || { echo 'Worker source changed concurrently; nothing replaced.' >&2; exit 1; }
modified=true
install -m 0644 -o root -g root "$base/branded_renewal_catalogue.py" "$site/branded_renewal_catalogue.py"
install -m 0644 -o root -g root "$base/renewal_packages.py" "$site/renewal_packages.py"
mv -T -- "$temp" "$main"
if [[ ! -d /etc/systemd/system/iptv-auth-worker.service.d ]]; then install -d -m 0755 /etc/systemd/system/iptv-auth-worker.service.d;fi
printf '[Service]\nEnvironmentFile=/etc/extreme-renewal-catalogue.env\n' > "$dropin"
systemctl daemon-reload
systemctl restart iptv-auth-worker.service
systemctl is-active --quiet iptv-auth-worker.service
"$python" - "$env" "$main" <<'PY'
import hashlib,hmac,json,secrets,sys,time,urllib.error,urllib.request
from pathlib import Path
class NoRedirect(urllib.request.HTTPRedirectHandler):
 def redirect_request(self,*args,**kwargs):return None
opener=urllib.request.build_opener(NoRedirect)
key=dict(line.split('=',1) for line in Path(sys.argv[1]).read_text().splitlines() if line and not line.startswith('#'))['BRANDED_RENEWAL_SHARED_SECRET']
def denied(path,expected):
 try:opener.open(urllib.request.Request('http://127.0.0.1:9091'+path,data=b'{}',headers={'Content-Type':'application/json'}),timeout=3)
 except urllib.error.HTTPError as error:
  if error.code==expected:return
 raise RuntimeError('Unexpected restricted route response')
for attempt in range(20):
 try:
  with opener.open('http://127.0.0.1:9091/health',timeout=3) as response:
   if response.status!=200:raise RuntimeError()
  denied('/branded-renewals/catalog',403);denied('/branded-renewals/renew',503)
  if b'EXTREME_BRANDED_TRIALS_V1' in Path(sys.argv[2]).read_bytes():denied('/branded-trials/plans',401)
  stamp=str(int(time.time()));nonce=secrets.token_hex(16);raw=b'{}'
  sig=hmac.new(key.encode(),b'branded-renewal-request-v1\n'+stamp.encode()+b'\n'+nonce.encode()+b'\n'+raw,hashlib.sha256).hexdigest()
  request=urllib.request.Request('http://127.0.0.1:9091/branded-renewals/status',data=raw,headers={'Content-Type':'application/json','X-Renewal-Timestamp':stamp,'X-Renewal-Nonce':nonce,'X-Renewal-Signature':sig})
  with opener.open(request,timeout=3) as response:envelope=json.load(response)
  expected=hmac.new(key.encode(),('branded-renewal-result-v1\n'+stamp+'\n'+nonce+'\n'+envelope['payload']).encode(),hashlib.sha256).hexdigest()
  if not hmac.compare_digest(expected,envelope['signature']):raise RuntimeError()
  value=json.loads(envelope['payload'])
  if value.get('catalogue_only') is not True or value.get('writes_enabled') is not False or value.get('configured') is not True:raise RuntimeError()
  break
 except Exception:
  if attempt==19:raise SystemExit('Catalogue/auth/trial-route readiness failed; restoring original worker.') from None
  time.sleep(1)
PY
modified=false
echo 'Catalogue-only bridge installed. New renewal/checkout routes are disabled regardless of environment flags.'
echo 'Existing portal/trial behavior, secrets and ledgers unchanged. Only iptv-auth-worker.service restarted.'
echo "Backup: $backup"
echo "Private connection file: $export_file"
echo 'Transfer that file directly to /root/ on the WEBSITE host. Never paste its contents or put it under public/.'
