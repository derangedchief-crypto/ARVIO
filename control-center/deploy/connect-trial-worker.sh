#!/usr/bin/env bash
# WEBSITE host only. Imports a private server-to-server connection, not an offer.
set -euo pipefail
umask 077
[[ $(id -u) == 0 ]] || { echo 'Run on the WEBSITE host as root.' >&2; exit 1; }
site=/www/wwwroot/control.fecxbrfzku.net
php=/www/server/php/84/bin/php
connection=/root/brand-trial-website-connection.json
config="$site/config.php"
for path in "$connection" "$config" "$site/storage/application.key"; do test -f "$path" && test ! -L "$path" || { echo 'Original private configuration needs review.' >&2; exit 1; };done
[[ "$(stat -c %u "$connection")" == 0 && "$(stat -c %a "$connection")" == 600 ]] || { echo 'Private connection must be root0600.' >&2; exit 1; }
key_before="$(sha256sum "$site/storage/application.key")"
install -d -m 0700 -o root -g root /root/extreme-site-config-backups
backup="/root/extreme-site-config-backups/config-before-trial-$(date +%Y%m%d%H%M%S)-$$.php"
cp -- "$config" "$backup";chown root:root "$backup";chmod 0600 "$backup"
temp="$(mktemp "$site/.config-trial-XXXXXX.php")";trap 'rm -f -- "$temp"' EXIT
cp --preserve=all -- "$config" "$temp"
"$php" -d display_errors=stderr -d opcache.enable_cli=0 -r '
  $c=require $argv[1];$v=json_decode(file_get_contents($argv[3]),true,8,JSON_THROW_ON_ERROR);
  if(!is_array($c)||($c["origin"]??null)!=="https://control.fecxbrfzku.net"||!is_array($v)||array_keys($v)!==["base_url","shared_secret"]||$v["base_url"]!=="https://resellerworker.extremeiptv.net"||!is_string($v["shared_secret"])||!preg_match("/^[a-f0-9]{64}$/D",$v["shared_secret"]))exit(1);
  $prior=$c["trial_worker"]??[];
  if(!is_array($prior)||(!empty($prior["shared_secret"])&&!hash_equals($prior["shared_secret"],$v["shared_secret"])))exit(1);
  // Never silently replace a pre-existing trial secret, enable offers or change policy.
  $c["trial_worker"]=array_replace($prior,$v);
  $out="<?php\ndeclare(strict_types=1);\nreturn ".var_export($c,true).";\n";
  if(file_put_contents($argv[2],$out)!==strlen($out))exit(1);
' "$config" "$temp" "$connection" || { echo 'Trial connection differs; original private config was not replaced.' >&2; exit 1; }
"$php" -d opcache.enable_cli=0 -l "$temp" >/dev/null
mv -T -- "$temp" "$config"
[[ "$key_before" == "$(sha256sum "$site/storage/application.key")" ]] || { echo 'Original key changed unexpectedly; stop for owner review.' >&2; exit 1; }
/etc/init.d/php-fpm-84 restart
echo 'Separate trial-worker connection imported. Original key, other secrets and all offer/policy settings preserved.'
echo 'This does not enable public trials, choose a plan, or create a panel account.'
