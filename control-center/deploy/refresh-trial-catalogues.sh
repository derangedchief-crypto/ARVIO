#!/usr/bin/env bash
# Website host: expire cached package metadata so the next view fetches it again.
set -euo pipefail
cd /www/wwwroot/control.fecxbrfzku.net
PHP=/www/server/php/84/bin/php
EXT="$("$PHP" -n -r 'echo ini_get("extension_dir");')"
ARGS=(-n)
for module in mysqlnd pdo openssl curl mbstring pdo_mysql; do
  if ! "$PHP" -n -r "exit(extension_loaded('$module') ? 0 : 1);"; then
    test -f "$EXT/$module.so" || { echo "Missing extension: $module"; exit 1; }
    ARGS+=(-d "extension=$EXT/$module.so")
  fi
done
"$PHP" "${ARGS[@]}" <<'PHP'
<?php
require 'src/bootstrap.php';
try {
    $db=new ControlCenter\Database(new ControlCenter\Config(getcwd()));
    $db->transaction(function()use($db):void {
        $db->query('UPDATE trial_plan_catalogs SET expires_at=LEAST(expires_at,UTC_TIMESTAMP())');
        $db->query('UPDATE trial_catalog_refreshes SET retry_after=LEAST(retry_after,UTC_TIMESTAMP())');
    });
    echo "Package cache expired. Reload Trials & contacts to fetch current packages.\n";
} catch (Throwable $e) {
    fwrite(STDERR,'Cache refresh failed: '.get_class($e).PHP_EOL);
    exit(1);
}
PHP
