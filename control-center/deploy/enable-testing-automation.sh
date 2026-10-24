#!/usr/bin/env bash
# Owner-approved WEBSITE-only finalisation. No raw credentials or key values printed.
set -euo pipefail
umask 077
[[ $(id -u) == 0 && "${1:-}" =~ ^[a-f0-9]{40}$ ]] || { echo 'Run as root: bash enable-testing-automation.sh FULL_REVIEWED_SOURCE_SHA' >&2; exit 1; }
sha="$1"
site=/www/wwwroot/control.fecxbrfzku.net
php=/www/server/php/84/bin/php
config="$site/config.php"
directory="$site/storage/brand-apks"
for command in setfacl runuser sha256sum; do command -v "$command" >/dev/null; done
id brandbridge >/dev/null;id www >/dev/null
test -f /var/lib/extreme-brand-bridge/.managed
test -x "$php";test -x /etc/init.d/php-fpm-84
for path in "$config" "$site/storage/application.key" "$directory"; do
  test -e "$path" && test ! -L "$path" || { echo 'Original private files or APK directory need owner review.' >&2; exit 1; }
done
"$php" -d opcache.enable_cli=0 -r 'exit(version_compare(trim(file_get_contents($argv[1])),"0.14.3",">=")?0:1);' "$site/VERSION" || { echo 'Extract website0.14.3 UPDATE first.' >&2; exit 1; }
key_before="$(sha256sum "$site/storage/application.key")"
install -d -m 0700 -o root -g root /root/extreme-site-config-backups
backup="/root/extreme-site-config-backups/config-before-auto-$(date +%Y%m%d%H%M%S)-$$.php"
cp -- "$config" "$backup";chown root:root "$backup";chmod 0600 "$backup"
temp="$(mktemp "$site/.config-automatic-XXXXXX.php")"
trap 'rm -f -- "$temp"' EXIT
# Atomic replacement retains the original owner, mode and narrow bridge read ACL.
cp --preserve=all -- "$config" "$temp"
"$php" -d display_errors=stderr -d opcache.enable_cli=0 -r '
  $c=require $argv[1];
  if(!is_array($c)||($c["origin"]??null)!=="https://control.fecxbrfzku.net"||($c["brand_builder"]["enabled"]??null)!==true||($c["brand_builder"]["publication_enabled"]??null)!==true||($c["brand_builder"]["channels"]??null)!==["testing"]||($c["brand_dispatch"]["enabled"]??null)!==true||!preg_match("/^[a-f0-9]{64}$/D",$c["brand_dispatch"]["token_sha256"]??"")||($c["brand_downloads"]["enabled"]??null)!==true)exit(1);
  $prior=$c["brand_builder"]["sources"]["2.0.115"]??null;
  $historical=$c["brand_builder"]["reconciliation_sources"]??[];
  if(!is_array($historical))exit(1);
  if(is_string($prior)&&preg_match("/^[a-f0-9]{40}$/D",$prior)&&$prior!==$argv[3])$historical[]=$prior;
  $c["brand_builder"]["reconciliation_sources"]=array_values(array_unique($historical));
  $c["brand_builder"]["sources"]["2.0.115"]=$argv[3];
  $c["brand_builder"]["automatic_testing_publication"]=true;
  $out="<?php\ndeclare(strict_types=1);\nreturn ".var_export($c,true).";\n";
  if(file_put_contents($argv[2],$out)!==strlen($out))exit(1);
' "$config" "$temp" "$sha" || { echo 'Existing testing-only settings need review; config was not replaced.' >&2; exit 1; }
"$php" -d opcache.enable_cli=0 -l "$temp" >/dev/null
mv -T -- "$temp" "$config"
setfacl -m u:brandbridge:r-- "$config" "$site/storage/application.key"
setfacl -m u:brandbridge:--x "$site/storage"
chgrp "$(id -gn www)" "$directory";chmod g+s "$directory"
setfacl -m u:brandbridge:rwx,g::r-x "$directory"
runuser -u brandbridge -- test -w "$directory"
runuser -u brandbridge -- test ! -w "$config"
runuser -u brandbridge -- test ! -w "$site/storage/application.key"
runuser -u www -- test -r "$config"
runuser -u brandbridge -- "$php" -d display_errors=stderr -d opcache.enable_cli=0 -r 'require $argv[1]."/src/bootstrap.php";(new \ControlCenter\Config($argv[1]))->secret("application.key",false);' "$site"
[[ "$key_before" == "$(sha256sum "$site/storage/application.key")" ]] || { echo 'Original key content changed unexpectedly; stop for owner review.' >&2; exit 1; }
/etc/init.d/php-fpm-84 restart
echo 'Website testing automation enabled; source pin updated and APK storage ACL repaired.'
echo 'Existing database settings, queue token hash and original encryption key preserved.'
echo 'Only PHP8.4 restarted. Production channels, trial settings and other workers unchanged.'
echo 'Private original-config backup: '"$backup"
