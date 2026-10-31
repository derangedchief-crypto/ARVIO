#!/usr/bin/env bash
# WEBSITE host. Connect the independent full worker and its automatic job timer.
set -euo pipefail
umask 077
[[ $(id -u) == 0 ]] || { echo 'Run on the website host as root.' >&2; exit 1; }
site=/www/wwwroot/control.fecxbrfzku.net
php=/www/server/php/84/bin/php
connection=/root/renewal-worker-website-connection.json
config="$site/config.php"
for path in "$connection" "$config" "$site/storage/application.key"; do test -f "$path" && test ! -L "$path" || { echo 'Original private file missing/unsafe.' >&2; exit 1; };done
[[ "$(stat -c %u "$connection")" == 0 && "$(stat -c %a "$connection")" == 600 ]] || { echo 'Private worker connection must be root0600.' >&2; exit 1; }
id www >/dev/null
runuser -u www -- test -r "$config"
for name in renewal-jobs.service renewal-jobs.timer; do
 path="/etc/systemd/system/$name";test ! -L "$path"
 if [[ -e "$path" ]]; then cmp -s "$path" "$site/deploy/$name" || { echo 'Existing job service differs; preserved for review.' >&2; exit 1; };fi
done
"$php" -d opcache.enable_cli=0 "$site/cli/console.php" migrate
key_before="$(sha256sum "$site/storage/application.key")"
install -d -m 0700 -o root -g root /root/extreme-site-config-backups
backup="$(mktemp /root/extreme-site-config-backups/config-before-full-renewal-XXXXXX.php)"
cp -- "$config" "$backup";chmod 0600 "$backup"
temp="$(mktemp "$site/.config-renewal-worker-XXXXXX.php")";trap 'rm -f -- "$temp"' EXIT
cp --preserve=all -- "$config" "$temp"
"$php" -d display_errors=stderr -d opcache.enable_cli=0 "$site/cli/connect-renewal-worker.php" "$temp" "$connection" > "$backup.result.json"
"$php" -d opcache.enable_cli=0 -l "$temp" >/dev/null
cmp -s "$config" "$backup" || { echo 'Private config changed concurrently; original retained.' >&2; exit 1; }
[[ "$key_before" == "$(sha256sum "$site/storage/application.key")" ]] || { echo 'Application key changed concurrently; staged config not installed.' >&2; exit 1; }
mv -T -- "$temp" "$config"
for name in renewal-jobs.service renewal-jobs.timer; do install -m 0644 -o root -g root "$site/deploy/$name" "/etc/systemd/system/$name";done
systemctl daemon-reload
if bash "$site/deploy/fix-renewal-jobs-socket.sh"; then
 :
else
 status=$?
 [[ "$status" == 2 ]] || exit "$status" # TCP databases need no socket mount.
fi
systemctl enable --now renewal-jobs.timer
systemctl is-active --quiet renewal-jobs.timer
cat "$backup.result.json"
echo "Separate full renewal worker connected. Config backup: $backup"
echo 'Rampex account connection is enabled. Enter your own API key + webhook secret in Customer renewals, set the displayed webhook URL in Rampex, set package money prices, then use the normal owner payment rollout control.'
echo 'Setup creates no customer charge. The timer can now fulfil queued verified paid invoices automatically.'
