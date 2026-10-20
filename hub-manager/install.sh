#!/usr/bin/env bash
set -euo pipefail
if [ "$(id -u)" -ne 0 ]; then
  echo "Run this installer as root on the aaPanel server."
  exit 1
fi
SOURCE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
APP=/opt/extreme-hub-manager
DATA=/var/lib/extreme-hub-manager
if ! id extreme-hub-manager >/dev/null 2>&1; then
  useradd --system --home-dir "$DATA" --shell /usr/sbin/nologin extreme-hub-manager
fi
install -d -m 755 "$APP"
install -d -m 700 -o extreme-hub-manager -g extreme-hub-manager "$DATA"
for file in service.py admin.html admin.js requirements.txt; do
  install -m 644 "$SOURCE/$file" "$APP/$file"
done
if [ ! -x "$APP/.venv/bin/python" ]; then
  python3 -m venv "$APP/.venv" || {
    echo "Install Python 3.10+ with venv support first (Debian/Ubuntu: apt install python3-venv)."
    exit 1
  }
fi
"$APP/.venv/bin/python" -m pip install -r "$APP/requirements.txt"
install -m 644 "$SOURCE/extreme-hub-manager.service" /etc/systemd/system/extreme-hub-manager.service
systemctl daemon-reload
systemctl enable extreme-hub-manager
systemctl restart extreme-hub-manager
echo "Service installed on 127.0.0.1:8766. Follow README.md to configure HTTPS and create both admins."
echo "No default passwords exist. No maintenance event was enabled."
