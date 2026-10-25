#!/usr/bin/env bash
# Explicitly owner-authorized normal BRAND releases; never a generic customer APK.
set -euo pipefail
umask 077
[[ $(id -u) == 0 && "${1:-}" =~ ^[a-f0-9]{40}$ ]] || { echo 'Run on WEBSITE as root: bash enable-production-brands.sh APPROVED_MAIN_SHA' >&2; exit 1; }
site=/www/wwwroot/control.fecxbrfzku.net
php=/www/server/php/84/bin/php
config="$site/config.php"
for p in "$config" "$site/storage/application.key"; do test -f "$p" && test ! -L "$p";done
test -f /var/lib/extreme-brand-bridge/.managed
"$php" -d opcache.enable_cli=0 "$site/cli/console.php" migrate
key_before="$(sha256sum "$site/storage/application.key")"
config_before="$(sha256sum "$config")"
install -d -m 0700 -o root -g root /root/extreme-site-config-backups
backup="/root/extreme-site-config-backups/config-before-production-$(date +%Y%m%d%H%M%S)-$$.php"
cp -- "$config" "$backup";chown root:root "$backup";chmod 0600 "$backup"
temp="$(mktemp "$site/.config-production-XXXXXX.php")";trap 'rm -f -- "$temp"' EXIT
cp --preserve=all -- "$config" "$temp"
"$php" -d display_errors=stderr -d opcache.enable_cli=0 /dev/stdin "$site" "$temp" "$1" <<'PHP'
<?php
declare(strict_types=1);
try {
    $site=$argv[1];require $site.'/src/bootstrap.php';$c=require $site.'/config.php';
    if(!is_array($c)||($c['origin']??null)!=='https://control.fecxbrfzku.net'||($c['brand_builder']['enabled']??null)!==true||($c['brand_builder']['publication_enabled']??null)!==true||($c['brand_dispatch']['enabled']??null)!==true||($c['brand_downloads']['enabled']??null)!==true)throw new RuntimeException('Existing working builder/queue/site delivery configuration required.');
    $old=new ControlCenter\Application(new ControlCenter\Config($site,$c));
    if($old->db->one("SELECT id FROM app_brand_builds WHERE state IN ('building','publishing','publication_review') LIMIT 1"))throw new RuntimeException('An active or uncertain build must finish/reconcile before production cutover. No build was cancelled.');
    $history=$c['brand_builder']['reconciliation_sources']??[];if(!is_array($history))throw new RuntimeException('Existing recovery policy needs review.');foreach($c['brand_builder']['sources']??[] as $sha)if(is_string($sha)&&preg_match('/^[a-f0-9]{40}$/D',$sha))$history[]=$sha;
    $c['brand_builder']['reconciliation_sources']=array_values(array_unique($history));
    $c['brand_builder']['sources']=['2.0.116'=>$argv[3]];
    $c['brand_builder']['channels']=['production'];
    $c['brand_builder']['automatic_testing_publication']=false;
    $c['brand_builder']['automatic_production_publication']=true;
    $c['brand_main_updates']['testing_enabled']=false;
    $c['brand_main_updates']['production_enabled']=true;
    $c['brand_release']['default_channel']='production';
    // Switch the offer's artifact binding, never its enabled preference, plan,
    // global pause, history or exhausted pilot slot. Re-review may be required.
    $c['trial_channel']='production';
    $app=new ControlCenter\Application(new ControlCenter\Config($site,$c));$total=0;
    for($i=0;$i<1000;$i++){$result=$app->brandBuilder->fanout(['source_sha'=>$argv[3],'workflow_ref'=>'brand-source-2.0.116-'.$argv[3],'app_version'=>'2.0.116','version_code'=>432,'channel'=>'production']);$total+=$result['queued'];if($result['complete'])break;}
    if(!$result['complete'])throw new RuntimeException('Fanout cursor retained; resume setup deliberately.');
    $out="<?php\ndeclare(strict_types=1);\nreturn ".var_export($c,true).";\n";if(file_put_contents($argv[2],$out)!==strlen($out))throw new RuntimeException('Production settings could not be staged.');
    echo json_encode(['normal_brand_releases'=>'production','platform'=>'2.0.116','source_sha'=>$argv[3],'queued'=>$total,'branding'=>'published snapshot preserved','signing_key'=>'GitHub only','trial_history'=>'unchanged','generic_customer_release'=>'untouched'],JSON_PRETTY_PRINT|JSON_THROW_ON_ERROR)."\n";
}catch(Throwable $error){fwrite(STDERR,$error instanceof PDOException?"Database operation failed; review private server logs.\n":$error->getMessage()."\n");exit(1);}
PHP
"$php" -d opcache.enable_cli=0 -l "$temp" >/dev/null
[[ "$config_before" == "$(sha256sum "$config")" && "$key_before" == "$(sha256sum "$site/storage/application.key")" ]] || { echo 'Original private files changed concurrently; staged config not installed.' >&2; exit 1; }
mv -T -- "$temp" "$config"
setfacl -m u:brandbridge:r-- "$config" "$site/storage/application.key"
setfacl -m u:brandbridge:--x "$site/storage"
setfacl -m u:brandbridge:rwx "$site/storage/brand-apks"
runuser -u brandbridge -- test -w "$site/storage/brand-apks"
/etc/init.d/php-fpm-84 restart
echo 'Normal owner/reseller releases enabled. Upgrade the dispatcher with the SAME approved source SHA.'
echo 'No customer APK, trial account, branding identity or existing test installation was changed.'
