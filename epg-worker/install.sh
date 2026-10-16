#!/usr/bin/env bash
set -Eeuo pipefail
[[ $EUID -eq 0 ]] || { echo 'Run with sudo bash install.sh' >&2; exit 1; }
dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
id -u epgworker >/dev/null 2>&1 || useradd --system --no-create-home --shell /usr/sbin/nologin epgworker
apt-get update && apt-get install -y python3-venv
install -d -o epgworker -g epgworker /var/lib/iptv-epg-worker
chown -R root:epgworker "$dir" && chmod 750 "$dir"
rm -rf "$dir/venv"
chmod 770 "$dir"
sudo -u epgworker python3 -m venv "$dir/venv"
sudo -u epgworker "$dir/venv/bin/pip" install -r "$dir/requirements.txt"
chmod 750 "$dir"
install -m 644 "$dir/iptv-epg-worker.service" /etc/systemd/system/iptv-epg-worker.service
systemctl daemon-reload
systemctl enable --now iptv-epg-worker
site=/etc/nginx/sites-available/iptv-activation-worker
if ! grep -q 'proxy_pass http://127.0.0.1:9093' "$site"; then
  sed -i '/^[[:space:]]*location \/ {/i\    location ^~ /epg/ {\n        proxy_pass http://127.0.0.1:9093;\n        proxy_http_version 1.1;\n        proxy_set_header Host $host;\n        proxy_set_header X-Real-IP $remote_addr;\n    }\n' "$site"
  nginx -t
  systemctl reload nginx
fi
