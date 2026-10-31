#!/usr/bin/env bash
# WEBSITE host only. Separate catalogue key; no paid worker or Rampex enablement.
set -euo pipefail
umask 077
[[ $(id -u) == 0 ]] || { echo 'Run on the WEBSITE host as root.' >&2; exit 1; }
site=/www/wwwroot/control.fecxbrfzku.net
php=/www/server/php/84/bin/php
connection=/root/brand-renewal-website-connection.json
config="$site/config.php"
for path in "$connection" "$config" "$site/storage/application.key"; do test -f "$path" && test ! -L "$path" || { echo 'Original private file missing/unsafe; nothing imported.' >&2; exit 1; };done
[[ "$(stat -c %u "$connection")" == 0 && "$(stat -c %a "$connection")" == 600 ]] || { echo 'Private connection file must be root0600. Never put it under public/.' >&2; exit 1; }
test -f "$site/cli/connect-renewal-catalogue.php"
"$php" -d opcache.enable_cli=0 "$site/cli/console.php" migrate
key_before="$(sha256sum "$site/storage/application.key")"
install -d -m 0700 -o root -g root /root/extreme-site-config-backups
backup="$(mktemp /root/extreme-site-config-backups/config-before-catalogue-XXXXXX.php)"
cp -- "$config" "$backup";chmod 0600 "$backup"
temp="$(mktemp "$site/.config-catalogue-XXXXXX.php")";trap 'rm -f -- "$temp"' EXIT
cp --preserve=all -- "$config" "$temp"
report="$backup.result.json"
# Signed status + real offered-package read must both pass BEFORE config install.
"$php" -d display_errors=stderr -d opcache.enable_cli=0 "$site/cli/connect-renewal-catalogue.php" "$temp" "$connection" > "$report"
"$php" -d opcache.enable_cli=0 -l "$temp" >/dev/null
cmp -s "$config" "$backup" || { echo 'Configuration changed concurrently; original retained.' >&2; exit 1; }
[[ "$key_before" == "$(sha256sum "$site/storage/application.key")" ]] || { echo 'Original application key changed concurrently; staged config not installed.' >&2; exit 1; }
mv -T -- "$temp" "$config"
cat "$report"
echo 'Catalogue-only connection imported. Payments, live Rampex calls and new renewal writes remain OFF.'
echo 'Existing trial connection, builder settings, signing/storage keys and all other config values preserved.'
echo "Private original-config backup: $backup"
echo 'Hard-refresh Customer renewals. If PHP bytecode remains stale, reload only this website PHP handler in aaPanel.'
