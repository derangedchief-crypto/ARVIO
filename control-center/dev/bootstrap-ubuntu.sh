#!/usr/bin/env bash
# User-space tooling only: no sudo, system package installation or system services.
set -euo pipefail
base="${CONTROL_TOOLS_DIR:-/tmp/opencode/control-center-tools}"
mkdir -p "$base/packages" "$base/root"
cd "$base/packages"
apt-get download php8.3-cli php8.3-common php8.3-mysql php8.3-mbstring php8.3-curl \
  mariadb-server-core mariadb-client-core mariadb-client mariadb-server mariadb-common libmariadb3
for package in ./*.deb; do dpkg-deb -x "$package" "$base/root"; done
sha256sum ./*.deb > "$base/package-checksums.txt"
echo "PHP/MariaDB extracted under $base/root; no system services changed."
