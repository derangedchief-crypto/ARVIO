#!/usr/bin/env bash
# Explicit operator action on the ACTIVATION WORKER host, not the website host.
set -euo pipefail
[[ $EUID -eq 0 ]] || { echo 'Run as root on the activation worker host.' >&2; exit 1; }
app="${1:-/opt/iptv-activation-worker}"
base="$(cd "$(dirname "$0")" && pwd)"
test -x "$app/venv/bin/python"
backup="$("$app/venv/bin/python" "$base/patch-primary-status.py" "$app" "$base")"
if [[ "$backup" == UNCHANGED ]]; then echo 'Primary status route already current; no restart needed.';exit 0;fi
if ! systemctl restart iptv-activation-worker.service || ! "$app/venv/bin/python" "$base/patch-primary-status.py" --health "${PRIMARY_HEALTH_URL:-http://127.0.0.1:9092/management/primary-line-health}"; then
  "$app/venv/bin/python" "$base/patch-primary-status.py" --restore "$backup"
  systemctl restart iptv-activation-worker.service || true
  echo 'Worker restart failed; previous source restored.' >&2
  exit 1
fi
echo "Primary status route installed. Source backup: $backup"
echo 'Existing config, environment, keys and trial storage were not modified.'
echo 'Verify /management/primary-line-health before enabling licence enforcement.'
