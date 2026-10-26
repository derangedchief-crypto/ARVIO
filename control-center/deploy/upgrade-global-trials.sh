#!/usr/bin/env bash
set -euo pipefail
umask 077
ulimit -c 0
[[ $(id -u) == 0 ]] || { echo 'Run in the WEBSITE aaPanel terminal as root.' >&2; exit 1; }
site=/www/wwwroot/control.fecxbrfzku.net
php=/www/server/php/84/bin/php
for p in "$site/config.php" "$site/storage/application.key"; do test -f "$p";test ! -L "$p";done
config="$(sha256sum "$site/config.php")";key="$(sha256sum "$site/storage/application.key")"
"$php" -d opcache.enable_cli=0 -d opcache.jit=0 -d opcache.jit_buffer_size=0 "$site/cli/console.php" migrate
"$php" -d opcache.enable_cli=0 -d opcache.jit=0 -d opcache.jit_buffer_size=0 /dev/stdin "$site" <<'PHP'
<?php
declare(strict_types=1);
try {
 $site=$argv[1];require $site.'/src/bootstrap.php';$app=new ControlCenter\Application(new ControlCenter\Config($site));
 $result=$app->db->transaction(function()use($app):array {
  $global=$app->db->one('SELECT * FROM trial_global_packages WHERE id=1 FOR UPDATE');if(!$global)throw new RuntimeException('Apply the global-trials migration first.');
  $owner=$app->db->one("SELECT id,role,tenant_id FROM users WHERE role='owner' AND active=1 ORDER BY id LIMIT 1");if(!$owner)throw new RuntimeException('An active owner is required.');
  if(!$global['enabled']){
   $approved=$app->db->one("SELECT g.allowed_plans FROM trial_plan_grants g JOIN tenants t ON t.id=g.tenant_id JOIN panel_resellers r ON r.tenant_id=t.id WHERE t.kind='owner' AND t.status='active' AND g.enabled=1 ORDER BY t.id LIMIT 1");
   $terms=$approved?json_decode($approved['allowed_plans'],true,8,JSON_THROW_ON_ERROR):[];
   $app->db->query('UPDATE trial_global_packages SET enabled=1,allowed_plans=?,updated_by=? WHERE id=1',[json_encode((object)$terms,JSON_THROW_ON_ERROR),$owner['id']]);
   $app->auth->audit($owner,'trial.global_self_service_enabled',null,null,['imported_owner_packages'=>count($terms)]);
  }else $terms=json_decode($global['allowed_plans'],true,8,JSON_THROW_ON_ERROR);
  return ['model'=>'Global approved packages; reseller-owned switch and daily limit','approved_packages'=>count($terms),'per_reseller_admin_enable'=>'removed','owner_unlimited'=>'preserved','trial_history'=>'preserved','new_apps_auto_enabled'=>false,'accounts_created'=>false];
 });echo json_encode($result,JSON_PRETTY_PRINT|JSON_THROW_ON_ERROR),"\n";
}catch(Throwable $e){fwrite(STDERR,$e instanceof PDOException?"Database operation failed; check private server logs.\n":$e->getMessage()."\n");exit(1);}
PHP
[[ "$config" == "$(sha256sum "$site/config.php")" && "$key" == "$(sha256sum "$site/storage/application.key")" ]] || { echo 'Private files changed concurrently; stop for review. This helper did not replace them.' >&2; exit 1; }
/etc/init.d/php-fpm-84 restart
echo 'Hard-refresh the website. Admin: global switch + global approved packages. Resellers: package + daily limit + app switch.'
echo 'No APK/worker update or trial account was created.'
