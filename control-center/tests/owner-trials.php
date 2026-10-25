<?php
declare(strict_types=1);
function ownerTrialCases(array &$tests,ControlCenter\Application $app,ControlCenter\Config $config,array $owner,array $reseller):void {
    $space=$app->db->one("SELECT id FROM tenants WHERE kind='owner'");$calls=[];$panel='88000001';
    $plan=['id'=>17,'name'=>'TEST owner trial','duration'=>24,'period'=>'hours','max_connections'=>1,'credits'=>'0','bouquets'=>[10],'fingerprint'=>str_repeat('4',64)];
    $c=new ControlCenter\Config($config->root,array_replace($config->values,['trial_worker'=>['shared_secret'=>str_repeat('3',64),'base_url'=>'https://worker.example.test']]));
    $worker=new ControlCenter\TrialWorker($c,function($path,$body)use(&$calls,$plan):string{$calls[]=$path;same('/branded-trials/plans',$path);$input=json_decode($body,true);return json_encode(['reseller_id'=>$input['reseller_id'],'plans'=>[$plan]],JSON_THROW_ON_ERROR);});
    $policies=new ControlCenter\TrialPolicies($app->db,$app->management,$app->auth,$worker);
    $tests['owner branding alone grants no panel trial access and resellers cannot configure owner trials']=function()use($app,$owner,$reseller,$space,$policies,&$calls):void {
        same(null,$app->db->one('SELECT panel_id FROM panel_resellers WHERE tenant_id=?',[$space['id']]));same(false,$policies->get($owner,[])['configuration_ready']);problem(409,fn()=> $policies->refresh($owner,[]));same([],$calls);
        problem(404,fn()=> $policies->get($reseller,['tenant_id'=>$space['id']]));problem(403,fn()=> $policies->refresh($reseller,['tenant_id'=>$space['id']]));
    };
    $tests['owner trial panel link requires signed exact identity and refuses permanent-link transfers']=function()use($app,$config,$owner,$reseller,$space,$panel):void {
        $proofs=new ControlCenter\EnrollmentProof($config,new ControlCenter\Secrets($config));
        $bridge=function(bool $tamper=false,?string $identity=null)use($proofs,$panel):ControlCenter\PanelBridge{$identity??=$panel;return new ControlCenter\PanelBridge($proofs,function($body,$headers)use($proofs,$identity,$tamper):string{$h=[];foreach($headers as $header){[$key,$value]=explode(': ',$header,2);$h[$key]=$value;}$input=json_decode($body,true);$payload=json_encode(['id'=>$identity,'username'=>$input['username']],JSON_THROW_ON_ERROR);return json_encode(['payload'=>$payload,'signature'=>$tamper?str_repeat('0',64):ControlCenter\PanelBridge::signature($proofs->bridgeSecret(),'reseller-result-v1',$h['X-Management-Timestamp'],$h['X-Management-Nonce'],$payload)],JSON_THROW_ON_ERROR);});};
        problem(403,fn()=> $app->enrollment->linkPanelReseller($reseller,$space['id'],['username'=>'TEST-owner-panel'],$bridge()));problem(503,fn()=> $app->enrollment->linkPanelReseller($owner,$space['id'],['username'=>'TEST-owner-panel'],$bridge(true)));same(null,$app->db->one('SELECT panel_id FROM panel_resellers WHERE tenant_id=?',[$space['id']]));
        $link=$app->enrollment->linkPanelReseller($owner,$space['id'],['username'=>'TEST-owner-panel'],$bridge());same($panel,$link['panel_id']);same($panel,$app->enrollment->linkPanelReseller($owner,$space['id'],['username'=>'TEST-owner-panel'],$bridge())['panel_id']);
        problem(409,fn()=> $app->enrollment->linkPanelReseller($owner,$space['id'],['username'=>'TEST-different-owner'],$bridge(false,'88000002')));
    };
    $tests['verified owner can review actual catalogue and defaults without enabling offers or provisioning accounts']=function()use($app,$owner,$reseller,$space,$policies,$c,$plan,&$calls):void {
        $before=$app->db->all('SELECT id FROM customers ORDER BY id');$catalog=$policies->refresh($owner,[]);same(false,$catalog['accounts_created']);same([$plan],$policies->get($owner,[])['catalog']['plans']);same(false,$policies->get($owner,[])['configuration_ready']);
        $grant=$policies->grant($owner,['tenant_id'=>$space['id'],'expected_version'=>0,'catalog_id'=>$catalog['catalog_id'],'allowed_plan_ids'=>[17],'enabled'=>true]);$policies->setDefault($owner,['tenant_id'=>$space['id'],'expected_revision'=>0,'grant_version'=>$grant['version'],'plan_id'=>17,'enabled'=>true]);same(true,$policies->get($owner,[])['configuration_ready']);same(false,$policies->get($owner,[])['live_offer_enabled']);
        problem(404,fn()=> $policies->setDefault($reseller,['tenant_id'=>$space['id'],'expected_revision'=>1,'grant_version'=>1,'plan_id'=>17,'enabled'=>false]));
        $offers=new ControlCenter\TrialOffers($app->db,$c,$app->management,$app->auth,new ControlCenter\Secrets($c),$policies,new ControlCenter\BrandArtifacts($app->db,new ControlCenter\Secrets($c),$c));same(['available'=>false],$offers->publicInfo($app->appBrands->get($owner,[])['brand']['id']));same(null,$app->db->one('SELECT tenant_id FROM trial_offer_publications WHERE tenant_id=?',[$space['id']]));same($before,$app->db->all('SELECT id FROM customers ORDER BY id'));same(['/branded-trials/plans'],$calls);
        $contacts=new ControlCenter\TrialContacts($app->db,$app->management,$app->auth,new ControlCenter\Secrets($c),$c,new ControlCenter\TrialWorker($c));
        $context=['brand_id'=>$app->appBrands->get($owner,[])['brand']['id'],'operation_id'=>ControlCenter\Database::id(),'device_ref'=>str_repeat('5',64),'first_name'=>'TEST Owner','phone'=>'+15551234599','plan_id'=>17,'plan_fingerprint'=>$plan['fingerprint']];
        $receipt=['state'=>'prepared','operation_id'=>$context['operation_id'],'reseller_id'=>'88000001','line_id'=>'88000002','plan_id'=>17,'plan_fingerprint'=>$plan['fingerprint'],'notes_recorded'=>true,'expires_at'=>time()+3600];
        problem(409,fn()=> $contacts->recordPrepared($context,array_replace($receipt,['reseller_id'=>'999999'])));$contact=$contacts->recordPrepared($context,$receipt);same('TEST Owner',$contacts->detail($owner,$contact['id'])['first_name']);problem(404,fn()=> $contacts->detail($reseller,$contact['id']));problem(404,fn()=> $contacts->listing($reseller,['tenant_id'=>$space['id']]));
        // Removing the verified panel link immediately invalidates prepared readiness.
        $app->db->query('DELETE FROM panel_resellers WHERE tenant_id=?',[$space['id']]);same(false,$policies->get($owner,[])['configuration_ready']);
    };
}
