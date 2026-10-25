#!/usr/bin/env bash
# Owner-authorized ONE-operation, testing-brand-only pilot. No panel account creation.
set -euo pipefail
umask 077
[[ $(id -u) == 0 && $# == 3 ]] || { echo 'Run on WEBSITE as root: bash enable-owner-trial-pilot.sh EXACT_PANEL_USERNAME PLAN_ID APPROVED_FINGERPRINT' >&2; exit 1; }
site=/www/wwwroot/control.fecxbrfzku.net
php=/www/server/php/84/bin/php
config="$site/config.php"
for path in "$config" "$site/storage/application.key"; do test -f "$path" && test ! -L "$path" || { echo 'Original private configuration/key needs review.' >&2; exit 1; };done
test -x "$php";test -f "$site/src/TrialOffers.php"
key_before="$(sha256sum "$site/storage/application.key")"
config_before="$(sha256sum "$config")"
install -d -m 0700 -o root -g root /root/extreme-site-config-backups
backup="/root/extreme-site-config-backups/config-before-trial-pilot-$(date +%Y%m%d%H%M%S)-$$.php"
cp -- "$config" "$backup";chown root:root "$backup";chmod 0600 "$backup"
temp="$(mktemp "$site/.config-trial-pilot-XXXXXX.php")";trap 'rm -f -- "$temp"' EXIT
cp --preserve=all -- "$config" "$temp"
"$php" -d display_errors=stderr -d opcache.enable_cli=0 /dev/stdin "$site" "$temp" "$1" "$2" "$3" <<'PHP'
<?php
declare(strict_types=1);
try {
    $site=$argv[1];require $site.'/src/bootstrap.php';$values=require $site.'/config.php';
    if(!is_array($values)||($values['origin']??null)!=='https://control.fecxbrfzku.net'||!preg_match('/^[1-9][0-9]{0,9}$/D',$argv[4])||(int)$argv[4]>2147483647||!preg_match('/^[a-f0-9]{64}$/D',$argv[5]))throw new RuntimeException('Invalid website or reviewed plan identity.');
    $normal=new ControlCenter\Application(new ControlCenter\Config($site,$values));
    $owner=$normal->db->one("SELECT * FROM users WHERE role='owner' AND active=1 ORDER BY id LIMIT 1");$space=$normal->db->one("SELECT * FROM tenants WHERE kind='owner' AND status='active' ORDER BY id LIMIT 1");if(!$owner||!$space)throw new RuntimeException('Active owner workspace required.');
    $brand=$normal->db->one('SELECT * FROM app_brands WHERE tenant_id=? AND enabled=1',[$space['id']]);if(!$brand)throw new RuntimeException('Existing enabled owner brand required.');
    if(isset($values['trial_pilot_brand_id'])&&$values['trial_pilot_brand_id']!==$brand['id'])throw new RuntimeException('A different pilot is configured; it was not replaced.');
    if(($values['trial_public_signup_enabled']??false)===true&&($values['trial_pilot_brand_id']??null)!==$brand['id'])throw new RuntimeException('A broader rollout is already configured; stop for owner review.');
    if($normal->db->one('SELECT id FROM trial_signup_operations WHERE brand_id=? LIMIT 1',[$brand['id']]))throw new RuntimeException('This brand already has a trial operation. Recover/review that operation; the pilot cannot be reset.');
    if($normal->db->one('SELECT tenant_id FROM trial_offer_publications WHERE enabled=1 AND brand_id<>? LIMIT 1',[$brand['id']]))throw new RuntimeException('Other published offers exist; stop for owner review.');
    $normal->enrollment->linkPanelReseller($owner,$space['id'],['username'=>$argv[3]]);
    $normal->trialPolicies->refresh($owner,['tenant_id'=>$space['id']]);$policy=$normal->trialPolicies->get($owner,['tenant_id'=>$space['id']]);$plan=null;
    foreach($policy['catalog']['plans']??[] as $candidate)if($candidate['id']===(int)$argv[4])$plan=$candidate;
    if(!$plan||!hash_equals($argv[5],$plan['fingerprint']))throw new RuntimeException('The actual plan terms changed or are unavailable. Reconfirm the catalogue before enabling anything.');
    $secrets=new ControlCenter\Secrets($normal->config);$artifacts=new ControlCenter\BrandArtifacts($normal->db,$secrets,$normal->config);$artifact=$artifacts->approved($brand['id'],'testing');
    if(!$artifact||!$artifacts->resellerInfo($artifact)['download_ready'])throw new RuntimeException('A verified site-delivered testing APK is required; no production rollout is permitted.');
    $updated=array_replace($values,['trial_public_signup_enabled'=>true,'trial_channel'=>'testing','trial_reseller_daily_limit'=>1,'trial_pilot_brand_id'=>$brand['id']]);
    $app=new ControlCenter\Application(new ControlCenter\Config($site,$updated));
    $app->db->transaction(function()use($app,$space,$brand,$owner,$plan,$argv,$updated):void {
        $app->db->one('SELECT id FROM tenants WHERE id=? FOR UPDATE',[$space['id']]);$current=$app->db->one('SELECT * FROM app_brands WHERE id=? FOR UPDATE',[$brand['id']]);
        if(!$current||!$current['enabled']||(int)$current['version']!==(int)$brand['version'])throw new RuntimeException('Owner branding changed; review before publication.');
        $app->trialOffers->admitNewOperation($brand['id']);
        if($app->db->one('SELECT tenant_id FROM trial_offer_publications WHERE enabled=1 AND brand_id<>? LIMIT 1',[$brand['id']]))throw new RuntimeException('Another offer was published; stop for review.');
        $p=$app->trialPolicies->get($owner,['tenant_id'=>$space['id']]);
        if(!$p['grant']||!$p['grant']['enabled']||$p['grant']['allowed_plan_ids']!==[$plan['id']]||count($p['plans'])!==1||$p['plans'][0]['fingerprint']!==$plan['fingerprint']){
            $app->trialPolicies->grant($owner,['tenant_id'=>$space['id'],'expected_version'=>$p['grant']['version']??0,'catalog_id'=>$p['catalog']['catalog_id'],'allowed_plan_ids'=>[$plan['id']],'enabled'=>true]);$p=$app->trialPolicies->get($owner,['tenant_id'=>$space['id']]);
        }
        if(!$p['default']||!$p['default']['enabled']||(int)$p['default']['grant_version']!==$p['grant']['version']||(int)$p['default']['plan_id']!==$plan['id']||$p['default']['plan_fingerprint']!==$plan['fingerprint'])$app->trialPolicies->setDefault($owner,['tenant_id'=>$space['id'],'expected_revision'=>(int)($p['default']['revision']??0),'grant_version'=>$p['grant']['version'],'plan_id'=>$plan['id'],'enabled'=>true]);
        $state=$app->trialOffers->managerState($owner,['tenant_id'=>$space['id']]);if(!$state['can_publish'])throw new RuntimeException($state['reason']?:'Pilot prerequisites are not ready.');
        if(!$state['active']||(int)($state['publication']['daily_limit']??0)!==1)$app->trialOffers->publish($owner,['tenant_id'=>$space['id'],'expected_revision'=>(int)($state['publication']['revision']??0),'enabled'=>true,'daily_limit'=>1,'confirmed_public_mode'=>true]);
        $out="<?php\ndeclare(strict_types=1);\nreturn ".var_export($updated,true).";\n";if(file_put_contents($argv[2],$out)!==strlen($out))throw new RuntimeException('Private pilot configuration could not be fully staged.');
    });
    echo json_encode(['workspace'=>'owner','panel_username'=>$argv[3],'plan_id'=>$plan['id'],'plan_name'=>$plan['name'],'duration'=>$plan['duration'],'period'=>$plan['period'],'max_connections'=>$plan['max_connections'],'credits'=>$plan['credits'],'channel'=>'testing','brand_id'=>$brand['id'],'daily_limit'=>1,'lifetime_pilot_operation_limit'=>1,'accounts_created'=>false],JSON_PRETTY_PRINT|JSON_UNESCAPED_UNICODE|JSON_THROW_ON_ERROR)."\n";
} catch(Throwable $error) {
    fwrite(STDERR,$error instanceof PDOException?"Database operation failed; review private server logs.\n":$error->getMessage()."\n");exit(1);
}
PHP
"$php" -d opcache.enable_cli=0 -l "$temp" >/dev/null
# Load the new broker guard BEFORE installing a config that exposes the offer.
# Then restart again after the atomic config replacement to refresh cached config.
/etc/init.d/php-fpm-84 restart
[[ "$key_before" == "$(sha256sum "$site/storage/application.key")" ]] || { echo 'Original key changed unexpectedly; stop for owner review.' >&2; exit 1; }
[[ "$config_before" == "$(sha256sum "$config")" ]] || { echo 'Private configuration changed concurrently; staged changes were not installed.' >&2; exit 1; }
mv -T -- "$temp" "$config"
/etc/init.d/php-fpm-84 restart
echo 'Owner TESTING pilot configured: ONE operation total, at most one per day. Other brands are excluded.'
echo 'No account was created. Original uncertain/failed requests retain the slot; never reset history to retry.'
