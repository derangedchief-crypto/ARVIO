#!/usr/bin/env bash
set -euo pipefail
root="${CONTROL_TOOLS_ROOT:-/tmp/opencode/control-center-tools/root}"
state="${CONTROL_DEV_DIR:-/tmp/opencode/control-center-dev}"
umask 077
mkdir -p "$state/data"
if [[ ! -d "$state/data/mysql" ]]; then
  "$root/usr/bin/mariadb-install-db" --no-defaults --basedir="$root/usr" \
    --datadir="$state/data" --auth-root-authentication-method=socket \
    --auth-root-socket-user="$(id -un)" --skip-test-db
fi
exec "$root/usr/sbin/mariadbd" --no-defaults --basedir="$root/usr" \
  --datadir="$state/data" --socket="$state/database.sock" --pid-file="$state/database.pid" \
  --plugin-dir="$root/usr/lib/mysql/plugin" --skip-networking --skip-log-bin \
  --log-error="$state/database.log"
