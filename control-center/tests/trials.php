<?php
declare(strict_types=1);
function trialCases(array &$tests,ControlCenter\Application $app,ControlCenter\Config $config,array $owner,array $alpha,array $beta,array $tenantA,array $tenantB,HttpFixture $http,string $password):void {
    $plans=[['id'=>7,'name'=>'TEST 24-hour trial','duration'=>24,'period'=>'hours','max_connections'=>1,'credits'=>'0','bouquets'=>[10,20],'fingerprint'=>str_repeat('1',64)],['id'=>8,'name'=>'TEST 48-hour trial','duration'=>48,'period'=>'hours','max_connections'=>1,'credits'=>'1','bouquets'=>[10,20],'fingerprint'=>str_repeat('2',64)]];
    $replyState='active';$replyExpiry=time()+3600;$replyLine='999001';$paths=[];$secret=str_repeat('c',64);
    $trialConfig=new ControlCenter\Config($config->root,array_replace($config->values,['trial_worker'=>['shared_secret'=>$secret,'base_url'=>'https://resellerworker.example.test']]));
    $worker=new ControlCenter\TrialWorker($trialConfig,function($path,$raw,$headers)use(&$plans,&$replyState,&$replyExpiry,&$replyLine,&$paths,$secret):string {
        $paths[]=$path;check(in_array($path,['/branded-trials/plans','/branded-trials/status'],true),'Policy/contact actions must never call provisioning');$body=json_decode($raw,true);$h=[];foreach($headers as $header){$pair=explode(': ',$header,2);$h[$pair[0]]=$pair[1]??'';}
        $domain=$path==='/branded-trials/plans'?'branded-trial-plans-v1':'branded-trial-status-v1';same(hash_hmac('sha256',$domain."\n".$h['X-Trial-Timestamp']."\n".$raw,$secret),$h['X-Trial-Signature']);
        return json_encode($path==='/branded-trials/plans'?['reseller_id'=>$body['reseller_id'],'plans'=>$plans]:['operation_id'=>$body['operation_id'],'reseller_id'=>$body['reseller_id'],'line_id'=>$replyLine,'state'=>$replyState,'expires_at'=>$replyExpiry,'checked_at_us'=>(int)(microtime(true)*1000000)],JSON_THROW_ON_ERROR);
    });
    $policies=new ControlCenter\TrialPolicies($app->db,$app->management,$app->auth,$worker);$contacts=new ControlCenter\TrialContacts($app->db,$app->management,$app->auth,new ControlCenter\Secrets($trialConfig),$trialConfig,$worker);
    $context=function(array $changes=[])use($app,$alpha):array {$brand=$app->appBrands->get($alpha,[])['brand'];return array_replace(['brand_id'=>$brand['id'],'operation_id'=>ControlCenter\Database::id(),'device_ref'=>str_repeat('d',64),'first_name'=>'TEST Contact','phone'=>'+1 (555) 123-4567','plan_id'=>7,'plan_fingerprint'=>str_repeat('1',64)],$changes);};
    $receipt=function(array $ctx,string $line='999001')use($app,$tenantA):array {$link=$app->db->one('SELECT panel_id FROM panel_resellers WHERE tenant_id=?',[$tenantA['id']]);return ['state'=>'prepared','operation_id'=>$ctx['operation_id'],'reseller_id'=>(string)$link['panel_id'],'line_id'=>$line,'plan_id'=>7,'plan_fingerprint'=>$ctx['plan_fingerprint'],'notes_recorded'=>true,'expires_at'=>time()+3600];};
    $savedId=null;$savedContext=null;$savedReceipt=null;
    $tests['only owner grants actual worker trial plans; reseller chooses only approved default and no offers start']=function()use($app,$policies,$owner,$alpha,$beta,$tenantA,&$paths):void {
        $before=(int)$app->db->query('SELECT COUNT(*) FROM customers')->fetchColumn();problem(403,fn()=> $policies->refresh($alpha,[]));$catalog=$policies->refresh($owner,['tenant_id'=>$tenantA['id']]);
        problem(403,fn()=> $policies->grant($alpha,[]));problem(422,fn()=> $policies->grant($owner,['tenant_id'=>$tenantA['id'],'catalog_id'=>$catalog['catalog_id'],'expected_version'=>0,'allowed_plan_ids'=>[999],'enabled'=>true]));
        $g=$policies->grant($owner,['tenant_id'=>$tenantA['id'],'catalog_id'=>$catalog['catalog_id'],'expected_version'=>0,'allowed_plan_ids'=>[7],'enabled'=>true]);same(1,$g['version']);
        $p=$policies->get($alpha,[]);same(null,$p['catalog']);same([7],array_column($p['plans'],'id'));same(false,$p['live_offer_enabled']);
        problem(409,fn()=> $policies->setDefault($alpha,['expected_revision'=>0,'grant_version'=>1,'plan_id'=>8,'enabled'=>true]));
        $policies->setDefault($alpha,['expected_revision'=>0,'grant_version'=>1,'plan_id'=>7,'enabled'=>true]);same(true,$policies->get($alpha,[])['configuration_ready']);
        problem(404,fn()=> $policies->get($beta,['tenant_id'=>$tenantA['id']]));same($before,(int)$app->db->query('SELECT COUNT(*) FROM customers')->fetchColumn());same(['/branded-trials/plans'],$paths);
    };
    $tests['plan price revisions revoke prepared readiness until both admin and reseller explicitly review']=function()use($app,$policies,$owner,$alpha,$tenantA,&$plans):void {
        $plans[0]['credits']='2';$plans[0]['fingerprint']=str_repeat('3',64);$catalog=$policies->refresh($owner,['tenant_id'=>$tenantA['id']]);$old=$policies->get($alpha,[]);same(false,$old['configuration_ready']);same([],$old['plans']);same(7,(int)$old['default']['plan_id']);
        problem(409,fn()=> $policies->setDefault($alpha,['expected_revision'=>1,'grant_version'=>1,'plan_id'=>7,'enabled'=>true]));
        $policies->grant($owner,['tenant_id'=>$tenantA['id'],'catalog_id'=>$catalog['catalog_id'],'expected_version'=>1,'allowed_plan_ids'=>[7,8],'enabled'=>true]);same(false,$policies->get($alpha,[])['configuration_ready']);
        $policies->setDefault($alpha,['expected_revision'=>1,'grant_version'=>2,'plan_id'=>7,'enabled'=>true]);same(true,$policies->get($alpha,[])['configuration_ready']);
        $app->db->query('UPDATE trial_plan_catalogs SET expires_at=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 SECOND) WHERE tenant_id=?',[$tenantA['id']]);same(false,$policies->get($alpha,[])['configuration_ready']);
        $policies->grant($owner,['tenant_id'=>$tenantA['id'],'expected_version'=>2,'allowed_plan_ids'=>[7,8],'enabled'=>false]);$policies->setDefault($alpha,['expected_revision'=>2,'enabled'=>false]);same(false,$policies->get($alpha,[])['live_offer_enabled']);
    };
    $tests['trial worker bridge rejects wrong ownership invalid metadata oversized responses and unsafe connection URLs']=function()use($trialConfig,$config):void {
        foreach(['http://worker.test','https://user@worker.test','https://worker.test/path','https://worker.test/?secret=x'] as $url){$c=new ControlCenter\Config($config->root,array_replace($config->values,['trial_worker'=>['shared_secret'=>str_repeat('c',64),'base_url'=>$url]]));same(false,(new ControlCenter\TrialWorker($c))->configured());}
        $wrong=new ControlCenter\TrialWorker($trialConfig,fn()=>json_encode(['reseller_id'=>'999','plans'=>[]]));problem(503,fn()=> $wrong->catalog('9001'));
        $large=new ControlCenter\TrialWorker($trialConfig,fn()=>str_repeat('x',131073));problem(503,fn()=> $large->catalog('9001'));
        $bad=new ControlCenter\TrialWorker($trialConfig,fn()=>json_encode(['reseller_id'=>'9001','plans'=>[['id'=>7,'password'=>'MUST-NOT-LEAK']]]));problem(503,fn()=> $bad->catalog('9001'));
    };
    $tests['only confirmed owned worker receipts create contacts and later offer suspension does not drop a created lead']=function()use($app,$contacts,$context,$receipt):void {
        $ctx=$context(['device_ref'=>str_repeat('f',64)]);$r=$receipt($ctx,'999010');$count=(int)$app->db->query('SELECT COUNT(*) FROM trial_contacts')->fetchColumn();
        problem(409,fn()=> $contacts->recordPrepared($ctx,array_replace($r,['notes_recorded'=>false])));problem(409,fn()=> $contacts->recordPrepared($ctx,array_replace($r,['reseller_id'=>'999999'])));same($count,(int)$app->db->query('SELECT COUNT(*) FROM trial_contacts')->fetchColumn());
        problem(422,fn()=> ControlCenter\TrialContacts::contact(['first_name'=>"Name\nInjected note",'phone'=>'+15551234567']));problem(422,fn()=> ControlCenter\TrialContacts::contact(['first_name'=>'Name','phone'=>'javascript:alert(1)']));
        $app->db->query('UPDATE app_brands SET enabled=0 WHERE id=?',[$ctx['brand_id']]);try{check(isset($contacts->recordPrepared($ctx,$r)['id']));}finally{$app->db->query('UPDATE app_brands SET enabled=1 WHERE id=?',[$ctx['brand_id']]);}
    };
    $tests['trial contacts are encrypted scoped masked and preserve first name phone after expiry']=function()use($app,$contacts,$alpha,$beta,$context,$receipt,&$savedId,&$savedContext,&$savedReceipt):void {
        $ctx=$context();$r=$receipt($ctx);$savedContext=$ctx;$savedReceipt=$r;$savedId=$contacts->recordPrepared($ctx,$r)['id'];same(['id'=>$savedId],$contacts->recordPrepared($ctx,$r));
        $stored=$app->db->one('SELECT * FROM trial_contacts WHERE id=?',[$savedId]);check(!str_contains($stored['contact_cipher'],'15551234567'));check(!str_contains($stored['contact_cipher'],'TEST Contact'));
        $app->db->query('UPDATE trial_contacts SET expires_at=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 HOUR),trial_expires_at=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 HOUR) WHERE id=?',[$savedId]);
        $list=array_column($contacts->listing($alpha,[])['items'],null,'id');same('••••4567',$list[$savedId]['phone']);same('expired',$list[$savedId]['state']);same('TEST Contact',$list[$savedId]['first_name']);same('+15551234567',$contacts->detail($alpha,$savedId)['phone']);same(false,$contacts->detail($alpha,$savedId)['phone_verified']);problem(404,fn()=> $contacts->detail($beta,$savedId));
        foreach($app->db->all('SELECT metadata FROM audit_events WHERE resource_id=?',[$savedId]) as $audit){check(!str_contains($audit['metadata'],'15551234567'));check(!str_contains($audit['metadata'],'TEST Contact'));}
        $another=array_replace($ctx,['operation_id'=>ControlCenter\Database::id()]);problem(409,fn()=> $contacts->recordPrepared($another,$receipt($another,'999002')));
    };
    $tests['confirmed contact status refresh is read only in panel and keeps original trial expiry on conversion']=function()use($app,$contacts,$alpha,$trialConfig,&$savedId,&$replyState,&$replyExpiry,&$replyLine,&$paths):void {
        $before=$contacts->detail($alpha,$savedId);$replyState='converted';$replyExpiry=time()+30*86400;$contacts->refresh($alpha,$savedId,[]);$after=$contacts->detail($alpha,$savedId);same('converted',$after['state']);same($before['trial_expires_at'],$after['trial_expires_at']);same('+15551234567',$after['phone']);
        $replyLine='999777';problem(409,fn()=> $contacts->refresh($alpha,$savedId,[]));same($after['expires_at'],$contacts->detail($alpha,$savedId)['expires_at']);$replyLine='999001';check(!in_array('/branded-trials/provision',$paths,true));
        $stored=$app->db->one('SELECT * FROM trial_contacts WHERE id=?',[$savedId]);$stale=new ControlCenter\TrialWorker($trialConfig,function($path,$body)use($stored):string{$b=json_decode($body,true);return json_encode(['operation_id'=>$b['operation_id'],'reseller_id'=>$b['reseller_id'],'line_id'=>$stored['panel_line_id'],'state'=>'active','expires_at'=>time()+3600,'checked_at_us'=>(int)$stored['panel_status_revision_us']-1000000]);});
        $oldContacts=new ControlCenter\TrialContacts($app->db,$app->management,$app->auth,new ControlCenter\Secrets($trialConfig),$trialConfig,$stale);same('converted',$oldContacts->refresh($alpha,$savedId,[])['state']);same($stored['panel_status_revision_us'],$app->db->one('SELECT panel_status_revision_us FROM trial_contacts WHERE id=?',[$savedId])['panel_status_revision_us']);
    };
    $tests['encrypted contact envelopes are bound to their original record and cannot be swapped']=function()use($app,$contacts,$alpha,$context,$receipt,&$savedId):void {
        $ctx=$context(['device_ref'=>str_repeat('c',64),'phone'=>'+15559876543']);$id=$contacts->recordPrepared($ctx,$receipt($ctx,'999020'))['id'];$original=$app->db->one('SELECT contact_cipher FROM trial_contacts WHERE id=?',[$id])['contact_cipher'];$other=$app->db->one('SELECT contact_cipher FROM trial_contacts WHERE id=?',[$savedId])['contact_cipher'];
        $app->db->query('UPDATE trial_contacts SET contact_cipher=? WHERE id=?',[$other,$id]);try{problem(503,fn()=> $contacts->detail($alpha,$id));}finally{$app->db->query('UPDATE trial_contacts SET contact_cipher=? WHERE id=?',[$original,$id]);}same('+15559876543',$contacts->detail($alpha,$id)['phone']);
    };
    $tests['redacting or expiring contact data never resets eligibility or restores PII on replay']=function()use($app,$contacts,$alpha,$beta,$context,$receipt,&$savedId,&$savedContext,&$savedReceipt):void {
        problem(422,fn()=> $contacts->redact($alpha,$savedId,['confirmed'=>false]));problem(404,fn()=> $contacts->redact($beta,$savedId,['confirmed'=>true]));$result=$contacts->redact($alpha,$savedId,['confirmed'=>true]);same(false,$result['eligibility_reset']);same(false,$result['panel_notes_changed']);same(null,$contacts->detail($alpha,$savedId)['phone']);
        $contacts->recordPrepared($savedContext,$savedReceipt);same(null,$contacts->detail($alpha,$savedId)['phone']);
        $ctx=$context(['device_ref'=>str_repeat('e',64)]);$id=$contacts->recordPrepared($ctx,$receipt($ctx,'999003'))['id'];$app->db->query('UPDATE trial_contacts SET contact_retention_until=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 SECOND) WHERE id=?',[$id]);same(null,$contacts->detail($alpha,$id)['phone']);$contacts->maintain();same(null,$app->db->one('SELECT contact_cipher FROM trial_contacts WHERE id=?',[$id])['contact_cipher']);
        $ctx['operation_id']=ControlCenter\Database::id();problem(409,fn()=> $contacts->recordPrepared($ctx,$receipt($ctx,'999004')));
    };
    $tests['public trial-policy and contact routes require CSRF roles and tenant ownership with no public provisioning']=function()use($app,$http,$password,$tenantA,&$savedId):void {
        $alphaLogin=$http->request('POST','/api/login',['email'=>'alpha@example.test','password'=>$password],[],'trials-alpha');same(200,$alphaLogin['status']);$betaLogin=$http->request('POST','/api/login',['email'=>'beta@example.test','password'=>$password],[],'trials-beta');same(200,$betaLogin['status']);$csrf=$alphaLogin['body']['csrf'];
        same(403,$http->request('POST','/api/trial-policy/default',['enabled'=>false,'expected_revision'=>3],[],'trials-alpha')['status']);same(403,$http->request('POST','/api/trial-policy/grants',(object)[],['X-CSRF-Token'=>$csrf],'trials-alpha')['status']);same(404,$http->request('GET','/api/trial-policy?tenant_id='.$tenantA['id'],null,[],'trials-beta')['status']);same(404,$http->request('GET','/api/trial-contacts/'.$savedId,null,[],'trials-beta')['status']);same(401,$http->request('GET','/api/trial-contacts/'.$savedId,null,[],'trial-anonymous')['status']);
        same(404,$http->request('POST','/api/trial-contacts',['first_name'=>'No public writer'],['X-CSRF-Token'=>$csrf],'trials-alpha')['status']);same(404,$http->request('POST','/api/trials/provision',[],['X-CSRF-Token'=>$csrf],'trials-alpha')['status']);same(false,$http->request('GET','/api/trial-policy',null,[],'trials-alpha')['body']['live_offer_enabled']);
    };
}
