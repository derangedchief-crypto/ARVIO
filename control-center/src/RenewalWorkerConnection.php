<?php
declare(strict_types=1);
namespace ControlCenter;

/** Root CLI import for the NEW standalone worker, not the old auth service. */
final class RenewalWorkerConnection {
    public static function configuration(array $current,array $connection):array {
        if(($current['origin']??null)!=='https://control.fecxbrfzku.net')throw new Problem(409,'Unexpected website origin.');
        if(count($connection)!==3||array_diff(array_keys($connection),['base_url','shared_secret','mode'])||($connection['base_url']??null)!=='https://resellerworker.extremeiptv.net'||($connection['mode']??null)!=='renewals'||!is_string($connection['shared_secret']??null)||!preg_match('/^[a-f0-9]{64}$/D',$connection['shared_secret']))throw new Problem(422,'Invalid private standalone renewal-worker connection.');
        $prior=$current['renewal_worker']??[];$payments=$current['renewal_payments']??[];
        if(!is_array($prior)||!is_array($payments))throw new Problem(409,'Existing private connection needs review.');
        if(isset($prior['mode'])&&!in_array($prior['mode'],['catalogue_only','renewals'],true))throw new Problem(409,'Unknown prior worker connection; original retained.');
        if(($prior['mode']??null)!=='catalogue_only'&&!empty($prior['shared_secret'])&&(!is_string($prior['shared_secret'])||!hash_equals($prior['shared_secret'],$connection['shared_secret'])))throw new Problem(409,'Existing financial worker key differs; it was not rotated.');
        // Explicit user-requested upgrade from catalogue-only to the NEW service.
        // Trial/portal keys, builder policies, storage and every other value stay.
        $current['renewal_worker']=array_replace($prior,$connection);
        $current['renewal_payments']=array_replace($payments,['allow_live_api'=>true,'simulation_enabled'=>false]);
        return $current;
    }
    public static function probe(Application $app,Config $config,?RenewalWorker $worker=null):array {
        if(($config->get('renewal_worker',[])['mode']??null)!=='renewals')throw new Problem(409,'Configure the separate renewal service.');
        $owner=$app->db->one("SELECT * FROM users WHERE role='owner' AND active=1 ORDER BY id LIMIT 1")??throw new Problem(409,'Active owner required.');
        $space=$app->renewalSettings->workspace($owner);$link=$app->db->one('SELECT panel_id FROM panel_resellers WHERE tenant_id=?',[$space['id']]);
        $worker??=new RenewalWorker($config);$status=$worker->productionStatus();$packages=$worker->catalog($link['panel_id']);
        return $status+['package_count'=>count($packages),'payments_enabled'=>$app->renewalSettings->policy()['enabled'],'rampex_account_setup_enabled'=>true,'packages'=>array_map(static fn($p)=>array_intersect_key($p,array_flip(['id','name','credits','duration','period','max_connections'])),$packages)];
    }
}
