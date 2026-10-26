#!/usr/bin/env bash
# Explicit owner-only transition, preserving pilot history and reseller restrictions.
set -euo pipefail
umask 077
ulimit -c 0
[[ $(id -u) == 0 ]] || { echo 'Run as root on the WEBSITE server.' >&2; exit 1; }
site=/www/wwwroot/control.fecxbrfzku.net
php=/www/server/php/84/bin/php
config="$site/config.php"
test -f "$config";test ! -L "$config"
test -s "$site/storage/application.key";test ! -L "$site/storage/application.key"
before="$(sha256sum "$config")";key="$(sha256sum "$site/storage/application.key")"
install -d -m 0700 -o root -g root /root/extreme-site-config-backups
backup="/root/extreme-site-config-backups/config-before-owner-unlimited-$(date +%Y%m%d%H%M%S)-$$.php"
cp -- "$config" "$backup";chmod 0600 "$backup"
temp="$(mktemp "$site/.config-owner-unlimited-XXXXXX.php")";trap 'rm -f -- "$temp"' EXIT
cp --preserve=all -- "$config" "$temp"
"$php" -d opcache.enable_cli=0 -d opcache.jit=0 -d opcache.jit_buffer_size=0 /dev/stdin "$site" "$temp" <<'PHP'
<?php
declare(strict_types=1);
try {
 $site=$argv[1];require $site.'/src/bootstrap.php';$c=require $site.'/config.php';$brandId='46ae4715dbfe4e57dfc7498cfbd6fbfb';
 if(($c['origin']??null)!=='https://control.fecxbrfzku.net'||($c['trial_channel']??null)!=='production'||($c['trial_public_signup_enabled']??false)!==true)throw new RuntimeException('Existing production owner-trial deployment required.');
 foreach(['trial_pilot_brand_id','trial_owner_unlimited_brand_id'] as $field)if(isset($c[$field])&&$c[$field]!==$brandId)throw new RuntimeException('Another brand policy is configured; no identity or limit was replaced.');
 $c['trial_owner_unlimited_brand_id']=$brandId;$app=new ControlCenter\Application(new ControlCenter\Config($site,$c));
 $result=$app->db->transaction(function()use($app,$brandId):array {
  $brand=$app->db->one("SELECT b.id,b.tenant_id,t.kind,r.panel_id FROM app_brands b JOIN tenants t ON t.id=b.tenant_id JOIN panel_resellers r ON r.tenant_id=t.id WHERE b.id=? AND b.enabled=1 AND t.kind='owner' AND t.status='active'",[$brandId]);
  if(!$brand||(string)$brand['panel_id']!=='252')throw new RuntimeException('Expected existing owner workspace / MarcPanel link required.');
  $app->db->one('SELECT id FROM tenants WHERE id=? FOR UPDATE',[$brand['tenant_id']]);
  $owner=$app->db->one("SELECT id,role,tenant_id FROM users WHERE role='owner' AND active=1 ORDER BY id LIMIT 1");if(!$owner)throw new RuntimeException('An active owner is required.');
  $history=[];foreach(['trial_signup_operations','trial_contacts'] as $table)$history[$table]=$app->db->all('SELECT * FROM '.$table.' WHERE tenant_id=? ORDER BY id',[$brand['tenant_id']]);
  $budgets=$app->db->all('SELECT * FROM trial_signup_budgets WHERE panel_reseller_id=? ORDER BY operation_id',[(string)$brand['panel_id']]);
  $state=$app->trialOffers->managerState($owner,['tenant_id'=>$brand['tenant_id']]);if(!$state['owner_unlimited']||!$state['can_publish'])throw new RuntimeException($state['reason']?:'Review current owner plan/build prerequisites before applying unlimited trials.');
  if($state['publication']&&(bool)$state['publication']['enabled'])$app->trialOffers->publish($owner,['tenant_id'=>$brand['tenant_id'],'expected_revision'=>(int)$state['publication']['revision'],'enabled'=>true,'confirmed_public_mode'=>true]);
  foreach($history as $table=>$rows)if($rows!==$app->db->all('SELECT * FROM '.$table.' WHERE tenant_id=? ORDER BY id',[$brand['tenant_id']]))throw new RuntimeException('Trial history changed concurrently; original state retained for review.');
  if($budgets!==$app->db->all('SELECT * FROM trial_signup_budgets WHERE panel_reseller_id=? ORDER BY operation_id',[(string)$brand['panel_id']]))throw new RuntimeException('Budget history changed concurrently; stop for review.');
  $state=$app->trialOffers->managerState($owner,['tenant_id'=>$brand['tenant_id']]);
  return ['owner_brand_id'=>$brandId,'owner_daily_trials'=>'unlimited','offer_active'=>$state['active'],'pilot_history'=>'preserved','customer_device_repeat_rule'=>'unchanged','anti_abuse_limits'=>'retained','reseller_configuration'=>'unchanged','accounts_created'=>false];
 });
 $text="<?php\ndeclare(strict_types=1);\nreturn ".var_export($c,true).";\n";if(file_put_contents($argv[2],$text)!==strlen($text))throw new RuntimeException('Could not stage owner-only configuration.');
 echo json_encode($result,JSON_PRETTY_PRINT|JSON_THROW_ON_ERROR),"\n";
}catch(Throwable $e){fwrite(STDERR,$e instanceof PDOException?"Database operation failed; check private server logs.\n":$e->getMessage()."\n");exit(1);}
PHP
"$php" -d opcache.enable_cli=0 -d opcache.jit=0 -d opcache.jit_buffer_size=0 -l "$temp" >/dev/null
[[ "$before" == "$(sha256sum "$config")" && "$key" == "$(sha256sum "$site/storage/application.key")" ]] || { echo 'Private configuration/key changed concurrently; staged config not installed.' >&2; exit 1; }
mv -T -- "$temp" "$config"
/etc/init.d/php-fpm-84 restart
echo 'Owner unlimited-trial policy installed. Hard-refresh the website. No APK or worker update required.'
