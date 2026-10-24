<?php
declare(strict_types=1);
function brandBuilderCases(array &$tests,ControlCenter\Application $app,ControlCenter\Config $config,array $owner,HttpFixture $http):void {
    $sha=str_repeat('a',40);$jobId=null;$tenant=null;$brand=null;$context=null;$receipt=null;
    $settings=['enabled'=>true,'publication_enabled'=>true,'channels'=>['testing'],'sources'=>['2.0.114'=>$sha]];
    $c=new ControlCenter\Config($config->root,array_replace($config->values,['brand_builder'=>$settings]));$secrets=new ControlCenter\Secrets($c);$artifacts=new ControlCenter\BrandArtifacts($app->db,$secrets,$c);$builder=new ControlCenter\BrandBuilder($app->db,$c,$app->appBrands,$artifacts,$secrets,$app->auth);
    $call=fn($ctx)=>['build_id'=>$ctx['build_id'],'lease_token'=>$ctx['lease_token']];
    $tests['central builder claims only owner-pinned source channel and verified active workspaces']=function()use($app,$owner,$builder,$sha,$artifacts,&$jobId,&$tenant,&$brand,&$context):void {
        $tenant=$app->management->createReseller($owner,['company'=>'TEST central builds','name'=>'TEST Builder Operator','email'=>'builder@example.test','password'=>'Synthetic-Builder-Password-123!','device_limit'=>5]);$app->db->query('INSERT INTO panel_resellers (panel_id,tenant_id,username,linked_by) VALUES (?,?,?,?)',['800001',$tenant['id'],'SYNTHETIC-builder',$owner['id']]);
        $asset=$app->appBrands->upload($owner,['tenant_id'=>$tenant['id'],'image'=>'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jSuoAAAAASUVORK5CYII=']);
        $brand=$app->appBrands->save($owner,['tenant_id'=>$tenant['id'],'name'=>'TEST Builder TV','loading_asset'=>$asset['id'],'logo_asset'=>$asset['id'],'icon_asset'=>$asset['id']]);$jobId=$app->appBrands->requestBuild($owner,['tenant_id'=>$tenant['id'],'expected_version'=>1])['id'];
        problem(409,fn()=> $builder->claim(['build_id'=>$jobId,'channel'=>'testing','source_sha'=>str_repeat('b',40)]));problem(403,fn()=> $builder->claim(['build_id'=>$jobId,'channel'=>'production','source_sha'=>$sha]));
        $context=$builder->claim(['build_id'=>$jobId,'channel'=>'testing','source_sha'=>$sha,'runner_run_id'=>'1234']);same($sha,$context['source_sha']);same($brand['id'],$context['bundle']['manifest']['brand_id']);same('2.0.114.1-test',$context['expected']['version_name']);same(43000001,$context['expected']['version_code']);same($brand['package_name'].'.test',$context['expected']['package_name']);
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
    $tests['uncertain publication is never blindly reclaimed and reconciles only identical verified downloaded bytes']=function()use($builder,$call,$sha,$app,$artifacts,&$brand,&$context,&$receipt):void {
        same('publishing',$builder->authorize($call($context)+['confirmed_publication'=>true])['state']);same('publication_review',$builder->fail($call($context))['state']);
        problem(409,fn()=> $builder->claim(['build_id'=>$context['build_id'],'channel'=>'testing','source_sha'=>$sha]));$old=$context;$context=$builder->reconcile(['build_id'=>$context['build_id'],'channel'=>'testing','source_sha'=>$sha]);
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
}
