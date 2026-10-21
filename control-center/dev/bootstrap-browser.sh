#!/usr/bin/env bash
# Optional headless UI tooling, isolated in a user-owned directory. No sudo.
set -euo pipefail
base="${CONTROL_BROWSER_TOOLS_DIR:-/tmp/opencode/control-center-browser-tools}"
mkdir -p "$base/packages" "$base/root"
npm install --prefix "$base" playwright@1.64.1 --ignore-scripts
node "$base/node_modules/playwright/cli.js" install chromium
cd "$base/packages"
apt-get download libatk1.0-0t64 libatk-bridge2.0-0t64 libatspi2.0-0t64 \
  libxcomposite1 libxdamage1 libxfixes3 libxrandr2 libgbm1 libasound2t64 libdrm2 libxrender1 libxi6
for package in ./*.deb; do dpkg-deb -x "$package" "$base/root"; done
echo "Run optional browser checks with LD_LIBRARY_PATH=$base/root/usr/lib/x86_64-linux-gnu"
