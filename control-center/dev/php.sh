#!/usr/bin/env bash
set -euo pipefail
root="${CONTROL_TOOLS_ROOT:-/tmp/opencode/control-center-tools/root}"
exec "$root/usr/bin/php8.3" -n \
  -d "extension_dir=$root/usr/lib/php/20230831" \
  -d extension=ctype -d extension=fileinfo -d extension=mbstring \
  -d extension=pdo -d extension=mysqlnd -d extension=pdo_mysql -d extension=curl \
  -d date.timezone=UTC -d expose_php=0 "$@"
