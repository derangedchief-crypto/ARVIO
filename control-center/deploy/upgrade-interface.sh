#!/usr/bin/env bash
# Website presentation only. Never reset configuration or enable trial accounts.
set -euo pipefail
umask 077
ulimit -c 0
[[ $(id -u) == 0 ]] || { echo 'Run as root in the WEBSITE aaPanel terminal.' >&2; exit 1; }
site=/www/wwwroot/control.fecxbrfzku.net
php=/www/server/php/84/bin/php
for p in "$site/config.php" "$site/storage/application.key"; do test -f "$p";test ! -L "$p";done
config="$(sha256sum "$site/config.php")";key="$(sha256sum "$site/storage/application.key")"
"$php" -d opcache.enable_cli=0 -d opcache.jit=0 -d opcache.jit_buffer_size=0 "$site/cli/console.php" migrate
[[ "$config" == "$(sha256sum "$site/config.php")" && "$key" == "$(sha256sum "$site/storage/application.key")" ]] || { echo 'Private files changed concurrently. This helper did not replace them; stop for review.' >&2; exit 1; }
/etc/init.d/php-fpm-84 restart
echo 'Compact website interface installed. Hard-refresh the website.'
echo 'No trial permissions, limits, account history, device settings or app builds were changed.'
