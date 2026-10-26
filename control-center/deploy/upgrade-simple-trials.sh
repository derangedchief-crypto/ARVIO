#!/usr/bin/env bash
# WEBSITE update only. No credentials, existing policies or account history reset.
set -euo pipefail
umask 077
ulimit -c 0
[[ $(id -u) == 0 ]] || { echo 'Run as root in the WEBSITE aaPanel terminal.' >&2; exit 1; }
site=/www/wwwroot/control.fecxbrfzku.net
php=/www/server/php/84/bin/php
for p in "$site/config.php" "$site/storage/application.key"; do test -f "$p";test ! -L "$p";done
config="$(sha256sum "$site/config.php")";key="$(sha256sum "$site/storage/application.key")"
"$php" -d opcache.enable_cli=0 -d opcache.jit=0 -d opcache.jit_buffer_size=0 "$site/cli/console.php" migrate
[[ "$config" == "$(sha256sum "$site/config.php")" && "$key" == "$(sha256sum "$site/storage/application.key")" ]] || { echo 'Private files changed concurrently; stop for review. No private file was replaced by this helper.' >&2; exit 1; }
/etc/init.d/php-fpm-84 restart
echo 'Website trial controls upgraded. Hard-refresh the website.'
echo 'Admin: Allow trials + packages + daily limit -> Save trial access.'
echo 'Reseller: package + app switch -> Save trial settings.'
echo 'Existing reseller permissions are NOT automatically enabled. Owner Unlimited and trial history are preserved.'
echo 'No APK, worker update, trial account or customer release was created.'
