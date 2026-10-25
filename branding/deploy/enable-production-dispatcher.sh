#!/usr/bin/env bash
set -euo pipefail
umask 077
[[ $(id -u) == 0 && "${1:-}" =~ ^[a-f0-9]{40}$ ]] || { echo 'Run on WORKER as root: bash production.sh APPROVED_MAIN_SHA' >&2; exit 1; }
base="$(cd -- "$(dirname -- "$0")" && pwd)"
target=/opt/brand-dispatch/branding/dispatch_github.py
env=/etc/brand-dispatch.env
lock=/var/lib/brand-dispatch/.dispatch.lock
drop=/etc/systemd/system/brand-dispatch.service.d/normal-releases.conf
for p in "$target" "$env" "$lock"; do test -f "$p" && test ! -L "$p";done
prior="$(sha256sum "$target" | cut -d ' ' -f1)";payload="$(sha256sum "$base/branding/dispatch_github.py" | cut -d ' ' -f1)"
[[ "$prior" == "$payload" || "$prior" == f90dac344ac8ca5e57ba767a1ca1e11644c9ed8c87de26d10d228b5c4dda0d12 ]] || { echo 'Existing dispatcher differs; preserve it for owner review.' >&2; exit 1; }
test ! -L "$drop"
if [[ -e "$drop" ]]; then grep -q '^# EXTREME_NORMAL_BRAND_RELEASES_V1$' "$drop" || { echo 'Existing production override needs review.' >&2; exit 1; };fi
active=false;if systemctl is-active --quiet brand-dispatch.timer; then active=true;fi
backup="/root/extreme-brand-dispatch-backups/production-$(date +%Y%m%d%H%M%S)-$$"
install -d -m 0700 -o root -g root "$backup"
cp --preserve=all "$target" "$backup/dispatcher.py";cp --preserve=all "$env" "$backup/dispatcher.env"
if [[ -e "$drop" ]]; then cp --preserve=all "$drop" "$backup/override.conf";fi
modified=false
cleanup(){ status=$?;trap - EXIT;if [[ "$status" != 0 && "$modified" == true ]]; then cp --preserve=all "$backup/dispatcher.py" "$target";cp --preserve=all "$backup/dispatcher.env" "$env";if [[ -e "$backup/override.conf" ]]; then cp --preserve=all "$backup/override.conf" "$drop";else rm -f -- "$drop";fi;systemctl daemon-reload;fi;if [[ "$active" == true ]]; then systemctl start brand-dispatch.timer;fi;exit "$status";}
trap cleanup EXIT
systemctl stop brand-dispatch.timer
exec 9>>"$lock";flock --exclusive --wait 90 9
# Optional bundled auth update changes only future credential length. It preserves
# the original secret, flag, key and ledger, and never creates another trial.
if [[ -f "$base/auth/branded_trials.py" ]] && ! cmp -s "$base/auth/branded_trials.py" /opt/iptv-auth-worker/branded_trials.py; then bash "$base/auth/install.sh";fi
if [[ -f "$base/activation/upgrade-branded-package.sh" ]]; then bash "$base/activation/upgrade-branded-package.sh";fi
modified=true
python3 - "$base/configure-production.py" "$env" "$1" <<'PY'
import importlib.util,sys
from pathlib import Path
s=importlib.util.spec_from_file_location('production_config',sys.argv[1]);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);m.configure(Path(sys.argv[2]),sys.argv[3])
PY
install -m 0644 -o root -g root "$base/branding/dispatch_github.py" "$target"
install -d -m 0755 /etc/systemd/system/brand-dispatch.service.d
cat > "$drop" <<'UNIT'
# EXTREME_NORMAL_BRAND_RELEASES_V1
[Service]
ExecStart=
ExecStart=/usr/bin/python3 /opt/brand-dispatch/branding/dispatch_github.py --poll --mode build --channel production
UNIT
systemctl daemon-reload
modified=false
echo 'Normal production dispatcher ready; original tokens/state preserved. Timer restored to its previous state.'
echo 'No generic customer release or existing trial account was changed.'
