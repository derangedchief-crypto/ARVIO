#!/usr/bin/env bash
set -Eeuo pipefail
[[ $EUID -eq 0 ]] || { echo 'Run with sudo bash update-admin-control.sh' >&2; exit 1; }
SOURCE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
APP="${EPG_APP_DIR:-/opt/iptv-epg-worker}"
[[ -f "$APP/main.py" && -x "$APP/venv/bin/python" ]] || {
  echo 'Existing EPG worker not found. Set EPG_APP_DIR to its installed directory.' >&2
  exit 1
}
"$APP/venv/bin/python" -m py_compile "$SOURCE/main.py"
BACKUP="$APP/main.py.before-admin-$(date -u +%Y%m%dT%H%M%SZ)"
cp -p "$APP/main.py" "$BACKUP"
if [[ ! -f /etc/extreme-epg-admin.env ]]; then
  umask 077
  "$APP/venv/bin/python" -c 'import secrets; print("EPG_ADMIN_SECRET=" + secrets.token_hex(32))' > /etc/extreme-epg-admin.env
fi
chmod 600 /etc/extreme-epg-admin.env
grep -Eq '^EPG_ADMIN_SECRET=[a-f0-9]{64}$' /etc/extreme-epg-admin.env || {
  echo '/etc/extreme-epg-admin.env must contain EPG_ADMIN_SECRET= followed by 64 random hex characters.' >&2
  exit 1
}
install -d -m 755 /etc/systemd/system/iptv-epg-worker.service.d
cat > /etc/systemd/system/iptv-epg-worker.service.d/admin.conf <<'EOF'
[Service]
EnvironmentFile=/etc/extreme-epg-admin.env
EOF
install -m 640 -o root -g epgworker "$SOURCE/main.py" "$APP/main.py"
systemctl daemon-reload
systemctl restart iptv-epg-worker
echo 'Worker updated. Restart starts a background EPG pull; maintenance is unchanged.'
echo "Previous code retained at: $BACKUP"
echo 'Read /etc/extreme-epg-admin.env privately on this server and copy ONLY the secret value into epg_worker_secret in the aaPanel website config.php.'
echo 'Never paste that secret into chat, browser JavaScript, a URL, or a public archive.'
