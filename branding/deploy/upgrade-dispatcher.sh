#!/usr/bin/env bash
# Existing dispatcher only. Preserve credentials/state and the prior timer state.
set -euo pipefail
umask 077
[[ $(id -u) == 0 && "${1:-}" =~ ^[a-f0-9]{40}$ ]] || { echo 'Run as root: bash upgrade.sh REVIEWED_FULL_SHA' >&2; exit 1; }
base="$(cd -- "$(dirname -- "$0")" && pwd)"
target=/opt/brand-dispatch/branding/dispatch_github.py
env=/etc/brand-dispatch.env
lock=/var/lib/brand-dispatch/.dispatch.lock
for path in "$target" "$env" "$lock"; do test -f "$path" && test ! -L "$path" || { echo 'Existing dispatcher files need review; no new service installed.' >&2; exit 1; };done
test -f "$base/branding/dispatch_github.py";test -f "$base/configure-poll.py"
python3 - "$base/branding/dispatch_github.py" <<'PY'
import ast,sys
from pathlib import Path
ast.parse(Path(sys.argv[1]).read_bytes())
PY
active=false
if systemctl is-active --quiet brand-dispatch.timer; then active=true;fi
backup="/root/extreme-brand-dispatch-backups/upgrade-$(date +%Y%m%d%H%M%S)-$$"
install -d -m 0700 -o root -g root "$backup"
cp --preserve=all -- "$target" "$backup/dispatch_github.py"
cp --preserve=all -- "$env" "$backup/dispatcher.env"
temp="$(mktemp /opt/brand-dispatch/branding/.dispatch-XXXXXX.py)"
modified=false
cleanup() {
  status=$?
  trap - EXIT
  rm -f -- "$temp"
  if [[ "$status" != 0 && "$modified" == true ]]; then
    cp --preserve=all -- "$backup/dispatch_github.py" "$target"
    cp --preserve=all -- "$backup/dispatcher.env" "$env"
    echo 'Dispatcher upgrade failed; original code/private environment restored.' >&2
  fi
  if [[ "$active" == true ]]; then systemctl start brand-dispatch.timer;fi
  exit "$status"
}
trap cleanup EXIT
systemctl stop brand-dispatch.timer
# Wait for the current short dispatch, never terminate a possibly accepted API call.
exec 9>>"$lock"
flock --exclusive --wait 90 9
modified=true
python3 "$base/configure-poll.py" "$1"
install -m 0644 -o root -g root "$base/branding/dispatch_github.py" "$temp"
mv -T -- "$temp" "$target"
modified=false
echo 'Existing dispatcher upgraded; tokens, state, testing opt-in and unit files preserved.'
echo 'The timer returns to its previous state. Payment/auth/activation services unchanged.'
