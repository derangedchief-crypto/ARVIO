#!/usr/bin/env bash
# Installs the preconfigured activation worker from this extracted directory.
set -Eeuo pipefail

if [[ "${EUID}" -ne 0 ]]; then
  echo "Run with: sudo bash install.sh" >&2
  exit 1
fi

worker_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
service_user="activationworker"
log_dir="/var/log/iptv-activation-worker"

if ! id -u "$service_user" >/dev/null 2>&1; then
  useradd --system --no-create-home --shell /usr/sbin/nologin "$service_user"
fi

apt-get update
apt-get install -y python3-venv
install -d -o "$service_user" -g "$service_user" "$log_dir"

chown -R root:"$service_user" "$worker_dir"
chmod 750 "$worker_dir"
chmod 640 "$worker_dir/.env"

# The directory may contain a virtual environment created by root or by an
# earlier deployment. It is generated state, so recreate it for the service
# account instead of inheriting incompatible ownership.
rm -rf "$worker_dir/venv"
sudo -u "$service_user" python3 -m venv "$worker_dir/venv"
sudo -u "$service_user" "$worker_dir/venv/bin/pip" install --upgrade pip
sudo -u "$service_user" "$worker_dir/venv/bin/pip" install -r "$worker_dir/requirements.txt"

install -m 644 "$worker_dir/iptv-activation-worker.service" /etc/systemd/system/iptv-activation-worker.service
systemctl daemon-reload
systemctl enable --now iptv-activation-worker
systemctl restart iptv-activation-worker

sleep 2
curl --fail --silent --show-error http://127.0.0.1:9092/health
echo
echo "Activation worker installed and running."
