<?php
declare(strict_types=1);
function brandBuilderCases(array &$tests,ControlCenter\Application $app,ControlCenter\Config $config,array $owner,HttpFixture $http):void {
    $sha=str_repeat('a',40);$jobId=null;$tenant=null;$brand=null;$context=null;$receipt=null;
    $settings=['enabled'=>true,'publication_enabled'=>true,'channels'=>['testing'],'sources'=>['2.0.115'=>$sha]];
    $c=new ControlCenter\Config($config->root,array_replace($config->values,['brand_builder'=>$settings]));$secrets=new ControlCenter\Secrets($c);$artifacts=new ControlCenter\BrandArtifacts($app->db,$secrets,$c);$builder=new ControlCenter\BrandBuilder($app->db,$c,$app->appBrands,$artifacts,$secrets,$app->auth);
    $call=fn($ctx)=>['build_id'=>$ctx['build_id'],'lease_token'=>$ctx['lease_token']];
    $tests['central builder claims only owner-pinned source channel and verified active workspaces']=function()use($app,$owner,$builder,$sha,$artifacts,&$jobId,&$tenant,&$brand,&$context):void {
        $tenant=$app->management->createReseller($owner,['company'=>'TEST central builds','name'=>'TEST Builder Operator','email'=>'builder@example.test','password'=>'Synthetic-Builder-Password-123!','device_limit'=>5]);$app->db->query('INSERT INTO panel_resellers (panel_id,tenant_id,username,linked_by) VALUES (?,?,?,?)',['800001',$tenant['id'],'SYNTHETIC-builder',$owner['id']]);
        $asset=$app->appBrands->upload($owner,['tenant_id'=>$tenant['id'],'image'=>'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jSuoAAAAASUVORK5CYII=']);
        $brand=$app->appBrands->save($owner,['tenant_id'=>$tenant['id'],'name'=>'TEST Builder TV','loading_asset'=>$asset['id'],'logo_asset'=>$asset['id'],'icon_asset'=>$asset['id']]);$jobId=$app->appBrands->requestBuild($owner,['tenant_id'=>$tenant['id'],'expected_version'=>1])['id'];
        $token=str_repeat('1',64);$pollConfig=new ControlCenter\Config($app->config->root,array_replace($app->config->values,['brand_builder'=>['enabled'=>true,'channels'=>['testing','production'],'sources'=>['2.0.115'=>$sha]],'brand_dispatch'=>['enabled'=>true,'token_sha256'=>hash('sha256',$token)]]));
        $pollSecrets=new ControlCenter\Secrets($pollConfig);$poller=new ControlCenter\BrandBuilder($app->db,$pollConfig,$app->appBrands,new ControlCenter\BrandArtifacts($app->db,$pollSecrets,$pollConfig),$pollSecrets,$app->auth);
        $input=['channel'=>'testing','source_sha'=>$sha];$rows=$app->db->all('SELECT * FROM app_brand_builds ORDER BY id');
        problem(401,fn()=> $poller->poll($input,null));problem(401,fn()=> $poller->poll($input,'Bearer '.str_repeat('2',64)));
        problem(403,fn()=> $poller->poll(array_replace($input,['channel'=>'production']),'Bearer '.$token));
        problem(403,fn()=> $poller->poll($input+['mode'=>'publish'],'Bearer '.$token));
        problem(409,fn()=> $poller->poll(array_replace($input,['source_sha'=>str_repeat('b',40)]),'Bearer '.$token));
        $hint=$poller->poll($input,'Bearer '.$token);same('queued',$hint['state']);same(['state','build_id','channel','source_sha'],array_keys($hint));same($hint,$poller->poll($input,'Bearer '.$token));same($rows,$app->db->all('SELECT * FROM app_brand_builds ORDER BY id'));
        problem(409,fn()=> $builder->claim(['build_id'=>$jobId,'channel'=>'testing','source_sha'=>str_repeat('b',40)]));problem(403,fn()=> $builder->claim(['build_id'=>$jobId,'channel'=>'production','source_sha'=>$sha]));
        $context=$builder->claim(['build_id'=>$jobId,'channel'=>'testing','source_sha'=>$sha,'runner_run_id'=>'1234']);same($sha,$context['source_sha']);same($brand['id'],$context['bundle']['manifest']['brand_id']);same('2.0.115.1-test',$context['expected']['version_name']);same(43100001,$context['expected']['version_code']);same($brand['package_name'].'.test',$context['expected']['package_name']);
        same(null,$artifacts->enrollmentPackage($context['expected']['package_name']));
        problem(409,fn()=> $builder->claim(['build_id'=>$jobId,'channel'=>'testing','source_sha'=>$sha]));
        $row=$app->db->one('SELECT * FROM app_brand_builds WHERE id=?',[$jobId]);check($row['lease_hash']!==$context['lease_token']);same(hash('sha256',$context['lease_token']),$row['lease_hash']);
    };
    $tests['expired build leases fence old runners and pinned source manifests survive branding edits']=function()use($app,$builder,$call,$sha,&$context,&$jobId,&$tenant,&$brand,$owner):void {
        $old=$context;$app->db->query('UPDATE app_brand_builds SET lease_until=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 SECOND) WHERE id=?',[$jobId]);$context=$builder->claim(['build_id'=>$jobId,'channel'=>'testing','source_sha'=>$sha,'runner_run_id'=>'1234']);
        check($context['lease_token']!==$old['lease_token']);problem(409,fn()=> $builder->heartbeat($call($old)));same('building',$builder->heartbeat($call($context))['state']);
        $before=$context['bundle'];$current=$app->appBrands->get($owner,['tenant_id'=>$tenant['id']])['brand'];$app->appBrands->save($owner,['tenant_id'=>$tenant['id'],'name'=>'TEST Builder Changed','expected_version'=>1,'loading_asset'=>$current['loading_asset'],'logo_asset'=>$current['logo_asset'],'icon_asset'=>$current['icon_asset']]);same($before,$app->appBrands->export($jobId));
        problem(409,fn()=> $app->appBrands->requestBuild($owner,['tenant_id'=>$tenant['id'],'expected_version'=>2]));
    };
    $tests['verified ready receipts reject generic packages wrong signer and false publish success']=function()use($builder,$call,&$context,&$receipt,$app,$config,$settings):void {
        $receipt=$context['expected'];unset($receipt['release_tag'],$receipt['asset_name']);$receipt+=['source_sha'=>$context['source_sha'],'apk_sha256'=>str_repeat('d',64),'apk_size_bytes'=>150000000,'provenance_sha256'=>str_repeat('e',64)];
        foreach(['package_name'=>'com.arvio.tv.test','version_name'=>'2.0.114-test','signer_sha256'=>str_repeat('0',64),'artifact_url'=>'https://example.test/not.apk'] as $key=>$value)problem(409,fn()=> $builder->prepared($call($context)+['receipt'=>array_replace($receipt,[$key=>$value])]));
        same(false,$builder->prepared($call($context)+['receipt'=>$receipt])['published']);same('ready_to_publish',$app->db->one('SELECT state FROM app_brand_builds WHERE id=?',[$context['build_id']])['state']);same(null,$app->db->one('SELECT id FROM app_brand_artifacts WHERE build_id=?',[$context['build_id']]));
        $off=new ControlCenter\Config($config->root,array_replace($config->values,['brand_builder'=>array_replace($settings,['publication_enabled'=>false])]));$secrets=new ControlCenter\Secrets($off);$blocked=new ControlCenter\BrandBuilder($app->db,$off,$app->appBrands,new ControlCenter\BrandArtifacts($app->db,$secrets,$off),$secrets,$app->auth);problem(403,fn()=> $blocked->authorize($call($context)+['confirmed_publication'=>true]));problem(403,fn()=> $builder->authorize($call($context)));
    };
    $tests['prepared artifacts retain exact frozen bytes and original Actions run instead of rebuilding a version']=function()use($builder,$call,$sha,$app,&$context,&$receipt):void {
        same('ready_to_publish',$builder->fail($call($context))['state']);$old=$context;$context=$builder->claim(['build_id'=>$context['build_id'],'channel'=>'testing','source_sha'=>$sha,'runner_run_id'=>'9999']);same('1234',$context['prepared_run_id']);$expected=$receipt;ksort($expected);same($expected,$context['prepared_receipt']);same(2,(int)$app->db->one('SELECT build_attempt FROM app_brand_builds WHERE id=?',[$context['build_id']])['build_attempt']);
        problem(409,fn()=> $builder->authorize($call($old)+['confirmed_publication'=>true]));problem(409,fn()=> $builder->prepared($call($context)+['receipt'=>array_replace($receipt,['apk_sha256'=>str_repeat('f',64)])]));
    };
    $tests['uncertain publication is never blindly reclaimed and reconciles only identical verified downloaded bytes']=function()use($builder,$call,$sha,$app,$artifacts,$config,$settings,&$brand,&$context,&$receipt):void {
        same('publishing',$builder->authorize($call($context)+['confirmed_publication'=>true])['state']);same('publication_review',$builder->fail($call($context))['state']);
        problem(409,fn()=> $builder->claim(['build_id'=>$context['build_id'],'channel'=>'testing','source_sha'=>$sha]));$old=$context;
        $rc=new ControlCenter\Config($config->root,array_replace($config->values,['brand_builder'=>array_replace($settings,['sources'=>['2.0.115'=>str_repeat('f',40)],'reconciliation_sources'=>[$sha]])]));$rs=new ControlCenter\Secrets($rc);$recovery=new ControlCenter\BrandBuilder($app->db,$rc,$app->appBrands,new ControlCenter\BrandArtifacts($app->db,$rs,$rc),$rs,$app->auth);
        problem(409,fn()=> $recovery->claim(['build_id'=>$context['build_id'],'channel'=>'testing','source_sha'=>$sha]));
        problem(409,fn()=> $recovery->reconcile(['build_id'=>$context['build_id'],'channel'=>'testing','source_sha'=>str_repeat('b',40)]));
        $context=$recovery->reconcile(['build_id'=>$context['build_id'],'channel'=>'testing','source_sha'=>$sha]);same($sha,$context['source_sha']);
        problem(409,fn()=> $builder->finish($call($old)+['receipt'=>$receipt]));problem(409,fn()=> $builder->reconcile(['build_id'=>$context['build_id'],'channel'=>'testing','source_sha'=>$sha]));
        problem(409,fn()=> $builder->finish($call($context)+['receipt'=>array_replace($receipt,['apk_sha256'=>str_repeat('f',64)])]));same('published',$builder->finish($call($context)+['receipt'=>$receipt])['state']);same(true,$builder->finish($call($context)+['receipt'=>array_reverse($receipt,true)])['idempotent']);
        $saved=$artifacts->approved($brand['id'],'testing');
        check($saved!==null);same($sha,$saved['source_sha']);same(150000000,(int)$saved['apk_size_bytes']);
        same($context['expected']['package_name'],$artifacts->enrollmentPackage($context['expected']['package_name']));same(null,$artifacts->enrollmentPackage(str_replace('.test','',$context['expected']['package_name'])));same(null,$artifacts->enrollmentPackage('com.extreme.brand.b'.str_repeat('0',32).'.test'));
    };
    $tests['builder retry cap and tenant suspension cannot be bypassed by runner claims']=function()use($app,$builder,$owner,$sha,&$tenant):void {
        $job=$app->appBrands->requestBuild($owner,['tenant_id'=>$tenant['id'],'expected_version'=>2]);$app->management->updateTenant($owner,$tenant['id'],['status'=>'suspended','device_limit'=>5]);try{problem(409,fn()=> $builder->claim(['build_id'=>$job['id'],'channel'=>'testing','source_sha'=>$sha]));}finally{$app->management->updateTenant($owner,$tenant['id'],['status'=>'active','device_limit'=>5]);}
        $app->db->query('UPDATE app_brand_builds SET build_attempt=3 WHERE id=?',[$job['id']]);problem(409,fn()=> $builder->claim(['build_id'=>$job['id'],'channel'=>'testing','source_sha'=>$sha]));
    };
    $tests['builder cannot be invoked through public management routes']=function()use($http):void {
        same(404,$http->request('GET','/api/brand-dispatch/queue',null,[],'anonymous-builder')['status']);
        same(401,$http->request('POST','/api/brand-builder/claim',['source_sha'=>str_repeat('a',40)],[],'anonymous-builder')['status']);same(401,$http->request('GET','/api/app-brand/artifacts',null,[],'anonymous-builder')['status']);
    };
    $tests['maintenance expires runner states honestly and only unpublished work can be cancelled']=function()use($app,$builder,$owner,&$tenant):void {
        $job=$app->db->one('SELECT j.id FROM app_brand_builds j JOIN app_brands b ON b.id=j.brand_id WHERE b.tenant_id=? AND j.brand_version=2',[$tenant['id']]);
        $app->db->query("UPDATE app_brand_builds SET state='building',lease_until=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 SECOND) WHERE id=?",[$job['id']]);$builder->maintain();same('build_failed',$app->db->one('SELECT state FROM app_brand_builds WHERE id=?',[$job['id']])['state']);same('cancelled',$app->appBrands->cancelBuild($owner,$job['id'],[])['state']);
        $app->db->query("UPDATE app_brand_builds SET state='publishing',lease_until=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 SECOND) WHERE id=?",[$job['id']]);$builder->maintain();same('publication_review',$app->db->one('SELECT state FROM app_brand_builds WHERE id=?',[$job['id']])['state']);problem(409,fn()=> $app->appBrands->cancelBuild($owner,$job['id'],[]));
    };
    $tests['owner-brand build preparation and artifact registration do not require a reseller link or enable trials']=function()use($app,$builder,$artifacts,$sha,$owner):void {
        $space=$app->db->one("SELECT id FROM tenants WHERE kind='owner'");$brand=$app->appBrands->get($owner,[])['brand'];$job=$app->appBrands->get($owner,[])['builds'][0];same(null,$app->db->one('SELECT panel_id FROM panel_resellers WHERE tenant_id=?',[$space['id']]));
        $context=$builder->claim(['build_id'=>$job['id'],'channel'=>'testing','source_sha'=>$sha,'runner_run_id'=>'1234']);same($brand['id'],$context['bundle']['manifest']['brand_id']);$receipt=$context['expected'];unset($receipt['release_tag'],$receipt['asset_name']);$receipt+=['source_sha'=>$sha,'apk_sha256'=>str_repeat('9',64),'apk_size_bytes'=>150000000,'provenance_sha256'=>str_repeat('8',64)];$lease=['build_id'=>$context['build_id'],'lease_token'=>$context['lease_token']];$builder->prepared($lease+['receipt'=>$receipt]);$builder->authorize($lease+['confirmed_publication'=>true]);same('published',$builder->finish($lease+['receipt'=>$receipt])['state']);same($brand['package_name'].'.test',$artifacts->approved($brand['id'],'testing')['package_name']);same(false,$app->trialOffers->publicInfo($brand['id'])['available']);same(null,$app->db->one('SELECT panel_id FROM panel_resellers WHERE tenant_id=?',[$space['id']]));
    };
    $tests['read-only dispatch hint excludes active prepared uncertain exhausted and wrong-source jobs']=function()use($app,$config,$sha,&$jobId):void {
        $token=str_repeat('1',64);$c=new ControlCenter\Config($config->root,array_replace($config->values,['brand_builder'=>['enabled'=>true,'publication_enabled'=>true,'automatic_testing_publication'=>true,'channels'=>['testing'],'sources'=>['9.0.0'=>$sha]],'brand_dispatch'=>['enabled'=>true,'token_sha256'=>hash('sha256',$token)]]));$s=new ControlCenter\Secrets($c);$poller=new ControlCenter\BrandBuilder($app->db,$c,$app->appBrands,new ControlCenter\BrandArtifacts($app->db,$s,$c),$s,$app->auth);
        $columns=['app_version','source_sha','build_channel','state','build_attempt','artifact_receipt','lease_until','build_finished_at','failure_code'];$original=$app->db->one('SELECT * FROM app_brand_builds WHERE id=?',[$jobId]);
        $poll=fn()=> $poller->poll(['channel'=>'testing','source_sha'=>$sha],'Bearer '.$token);
        $automatic=fn()=> $poller->poll(['channel'=>'testing','source_sha'=>$sha,'mode'=>'publish'],'Bearer '.$token);
        try {
            $app->db->query("UPDATE app_brand_builds SET app_version='9.0.0',source_sha=NULL,build_channel=NULL,state='waiting_builder',build_attempt=0,artifact_receipt=NULL,lease_until=NULL,build_finished_at=NULL WHERE id=?",[$jobId]);
            same(['state'=>'queued','build_id'=>$jobId,'channel'=>'testing','source_sha'=>$sha],$poll());
            same('publish',$automatic()['mode']);
            foreach(['automatic_testing_publication','publication_enabled'] as $flag){$disabled=new ControlCenter\Config($config->root,array_replace($c->values,['brand_builder'=>array_replace($c->values['brand_builder'],[$flag=>false])]));$ds=new ControlCenter\Secrets($disabled);$denied=new ControlCenter\BrandBuilder($app->db,$disabled,$app->appBrands,new ControlCenter\BrandArtifacts($app->db,$ds,$disabled),$ds,$app->auth);problem(403,fn()=> $denied->poll(['channel'=>'testing','source_sha'=>$sha,'mode'=>'publish'],'Bearer '.$token));}
            foreach(['ready_to_publish','publishing','publication_review','published','cancelled'] as $state){$app->db->query('UPDATE app_brand_builds SET state=? WHERE id=?',[$state,$jobId]);same(['state'=>'no_job'],$poll());}
            $app->db->query("UPDATE app_brand_builds SET state='building',lease_until=DATE_ADD(UTC_TIMESTAMP(),INTERVAL 30 MINUTE) WHERE id=?",[$jobId]);same(['state'=>'no_job'],$poll());
            $app->db->query('UPDATE app_brand_builds SET lease_until=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 SECOND) WHERE id=?',[$jobId]);same('queued',$poll()['state']);
            $app->db->query("UPDATE app_brand_builds SET state='build_failed',build_finished_at=UTC_TIMESTAMP() WHERE id=?",[$jobId]);same(['state'=>'no_job'],$poll());
            $app->db->query('UPDATE app_brand_builds SET build_finished_at=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 6 MINUTE) WHERE id=?',[$jobId]);same('queued',$poll()['state']);
            $app->db->query('UPDATE app_brand_builds SET build_attempt=3 WHERE id=?',[$jobId]);same(['state'=>'no_job'],$poll());
            $app->db->query('UPDATE app_brand_builds SET build_attempt=0,source_sha=? WHERE id=?',[str_repeat('b',40),$jobId]);same(['state'=>'no_job'],$poll());
            $app->db->query("UPDATE app_brand_builds SET source_sha=NULL,artifact_receipt='{}' WHERE id=?",[$jobId]);same(['state'=>'no_job'],$poll());
            $app->db->query("UPDATE app_brand_builds SET state='ready_to_publish',failure_code=NULL,lease_until=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 SECOND) WHERE id=?",[$jobId]);same(['state'=>'no_job'],$poll());same('publish',$automatic()['mode']);
            foreach(['publishing','publication_review','published','cancelled'] as $state){$app->db->query('UPDATE app_brand_builds SET state=? WHERE id=?',[$state,$jobId]);same(['state'=>'no_job'],$automatic());}
            $app->db->query("UPDATE app_brand_builds SET state='ready_to_publish',failure_code='prepared_artifact_review' WHERE id=?",[$jobId]);same(['state'=>'no_job'],$automatic());
        } finally {$app->db->query('UPDATE app_brand_builds SET '.implode(',',array_map(fn($key)=>$key.'=?',$columns)).' WHERE id=?',[...array_map(fn($key)=>$original[$key],$columns),$jobId]);}
    };
}
