<?php
declare(strict_types=1);
namespace ControlCenter;

/** Privileged website CLI only. Never exports private configuration or enables
 * payments. Pure configuration merge is separated for offline preservation tests. */
final class RenewalCatalogueConnection {
    public static function configuration(array $current,array $connection):array {
        if(($current['origin']??null)!=='https://control.fecxbrfzku.net')throw new Problem(409,'Unexpected website origin; original configuration retained.');
        if(count($connection)!==3||array_diff(array_keys($connection),['base_url','shared_secret','mode'])||($connection['base_url']??null)!=='https://resellerworker.extremeiptv.net'||($connection['mode']??null)!=='catalogue_only'||!is_string($connection['shared_secret']??null)||!preg_match('/^[a-f0-9]{64}$/D',$connection['shared_secret']))throw new Problem(422,'Invalid private catalogue-only connection file.');
        $prior=$current['renewal_worker']??[];$payments=$current['renewal_payments']??[];
        if(!is_array($prior)||!is_array($payments))throw new Problem(409,'Existing renewal configuration needs private review.');
        if((isset($prior['shared_secret'])&&$prior['shared_secret']!==''&&(!is_string($prior['shared_secret'])||!hash_equals($prior['shared_secret'],$connection['shared_secret'])))||(isset($prior['base_url'])&&$prior['base_url']!==$connection['base_url'])||(isset($prior['mode'])&&$prior['mode']!=='catalogue_only'))throw new Problem(409,'Existing renewal connection differs; no credential was replaced.');
        $current['renewal_worker']=array_replace($prior,$connection);
        $current['renewal_payments']=array_replace($payments,['allow_live_api'=>false,'simulation_enabled'=>false]);
        return $current;
    }
    public static function probe(Application $app,Config $config,?RenewalWorker $worker=null):array {
        if(($config->get('renewal_worker',[])['mode']??null)!=='catalogue_only'||($config->get('renewal_payments',[])['allow_live_api']??false)!==false||($config->get('renewal_payments',[])['simulation_enabled']??false)!==false)throw new Problem(409,'Catalogue check requires live payments and simulations to be disabled.');
        if($app->renewalSettings->policy()['enabled'])throw new Problem(409,'Disable the renewal payment rollout before checking the catalogue.');
        $owner=$app->db->one("SELECT * FROM users WHERE role='owner' AND active=1 ORDER BY id LIMIT 1")??throw new Problem(409,'Active owner login required.');
        $workspace=$app->renewalSettings->workspace($owner);
        $link=$app->db->one('SELECT panel_id FROM panel_resellers WHERE tenant_id=?',[$workspace['id']]);
        $worker??=new RenewalWorker($config);$status=$worker->connectionStatus();$packages=$worker->catalog($link['panel_id']);
        return $status+['payments_enabled'=>false,'live_rampex_api_enabled'=>false,'package_count'=>count($packages),'packages'=>array_map(static fn($p)=>array_intersect_key($p,array_flip(['id','name','credits','duration','period','max_connections'])),$packages)];
    }
}
