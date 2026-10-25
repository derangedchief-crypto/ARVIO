<?php
declare(strict_types=1);
function brandMainUpdateCases(array &$tests,ControlCenter\Application $app,ControlCenter\Config $config,array $owner):void {
    $sha=str_repeat('6',40);$token=str_repeat('7',64);$checks=0;$jobs=[];
    $input=['source_sha'=>$sha,'workflow_ref'=>'brand-source-2.0.116-'.$sha,'app_version'=>'2.0.116','version_code'=>432];
    $c=new ControlCenter\Config($config->root,array_replace($config->values,['brand_main_updates'=>['testing_enabled'=>true],'brand_builder'=>['enabled'=>true,'publication_enabled'=>true,'automatic_testing_publication'=>true,'channels'=>['testing','production'],'sources'=>['9.9.9'=>str_repeat('a',40)]],'brand_dispatch'=>['enabled'=>true,'token_sha256'=>hash('sha256',$token)]]));
    $s=new ControlCenter\Secrets($c);$artifacts=new ControlCenter\BrandArtifacts($app->db,$s,$c);$brands=new ControlCenter\AppBrands($app->db,$app->management,$app->auth,$artifacts,$c);$builder=new ControlCenter\BrandBuilder($app->db,$c,$brands,$artifacts,$s,$app->auth);
    $updates=new ControlCenter\BrandMainUpdates($app->db,$c,$artifacts,$app->auth,function($document)use(&$checks,$input):void{same($input,$document);$checks++;});
    $tests['main fanout is default off and rejects old incompatible or unverified source identities']=function()use($app,$config,$artifacts,$updates,$input):void {
        $off=new ControlCenter\BrandMainUpdates($app->db,$config,$artifacts,$app->auth);problem(503,fn()=> $off->fanout($input));
        foreach([['version_code'=>431],['app_version'=>'2.0.115'],['source_sha'=>'main'],['workflow_ref'=>'main']] as $override)problem(409,fn()=> $updates->fanout(array_replace($input,$override)));
        $reject=new ControlCenter\BrandMainUpdates($app->db,new ControlCenter\Config($config->root,array_replace($config->values,['brand_main_updates'=>['testing_enabled'=>true]])),$artifacts,$app->auth,static function(){throw new ControlCenter\Problem(409,'Unverified source');});problem(409,fn()=> $reject->fanout($input));same(null,$app->db->one('SELECT source_sha FROM brand_main_sources'));
    };
    $tests['main fanout snapshots published branding not drafts and never touches production or existing APK records']=function()use($app,$updates,$input,&$checks,&$jobs):void {
        $before=$app->db->all('SELECT * FROM app_brands ORDER BY id');$published=$app->db->all('SELECT * FROM app_brand_artifacts ORDER BY id');
        $result=$updates->fanout($input);same(true,$result['complete']);check($result['queued']>=2);same(1,$checks);
        same($before,$app->db->all('SELECT * FROM app_brands ORDER BY id'));same($published,$app->db->all('SELECT * FROM app_brand_artifacts ORDER BY id'));
        $jobs=$app->db->all('SELECT j.* FROM brand_main_jobs m JOIN app_brand_builds j ON j.id=m.build_id ORDER BY j.id');same($result['queued'],count($jobs));$draftSeen=false;
        foreach($jobs as $job){same('testing',$job['build_channel']);same($input['source_sha'],$job['source_sha']);$live=$app->db->one("SELECT j.manifest FROM app_brand_artifacts a JOIN app_brand_builds j ON j.id=a.build_id WHERE a.brand_id=? AND a.channel='testing'",[$job['brand_id']]);$expected=json_decode($live['manifest'],true);$expected['app_version']='2.0.116';$expected['app_version_code']=432;same($expected,json_decode($job['manifest'],true));$brand=$app->db->one('SELECT version FROM app_brands WHERE id=?',[$job['brand_id']]);if((int)$brand['version']>(int)$job['brand_version'])$draftSeen=true;}
        check($draftSeen,'Fixture must exercise unpublished branding changes');same(['complete'=>true,'queued'=>0],$updates->fanout($input));same(1,$checks);
        problem(409,fn()=> $updates->fanout(array_replace($input,['version_code'=>433])));
    };
    $tests['main queue uses exact tag source and preserves retry publication and tenant fencing']=function()use($app,$builder,$input,$token,&$jobs):void {
        $poll=fn($mode='build')=> $builder->poll(['channel'=>'testing','source_sha'=>str_repeat('a',40),'mode'=>$mode],'Bearer '.$token);
        $hint=$poll();same($input['workflow_ref'],$hint['workflow_ref']);same($input['source_sha'],$hint['source_sha']);$id=$hint['build_id'];
        $row=$app->db->one('SELECT * FROM app_brand_builds WHERE id=?',[$id]);
        try {
            // Hide other main jobs only inside this synthetic fixture.
            $app->db->query("UPDATE app_brand_builds j JOIN brand_main_jobs m ON m.build_id=j.id SET j.state='cancelled' WHERE j.id<>?",[$id]);
            foreach(['published','publication_review','publishing','cancelled'] as $state){$app->db->query('UPDATE app_brand_builds SET state=? WHERE id=?',[$state,$id]);same(['state'=>'no_job'],$poll('publish'));}
            $app->db->query("UPDATE app_brand_builds SET state='build_failed',build_finished_at=UTC_TIMESTAMP() WHERE id=?",[$id]);same(['state'=>'no_job'],$poll());
            $app->db->query('UPDATE app_brand_builds SET build_finished_at=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 6 MINUTE),build_attempt=3 WHERE id=?',[$id]);same(['state'=>'no_job'],$poll());
            $app->db->query("UPDATE app_brand_builds SET state='ready_to_publish',artifact_receipt='{}',runner_run_id='123',failure_code=NULL,lease_until=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 SECOND) WHERE id=?",[$id]);same(['state'=>'no_job'],$poll());same('publish',$poll('publish')['mode']);
        } finally {
            foreach($jobs as $job)$app->db->query('UPDATE app_brand_builds SET state=?,artifact_receipt=?,runner_run_id=?,failure_code=?,lease_until=?,build_finished_at=?,build_attempt=? WHERE id=?',[$job['state'],$job['artifact_receipt'],$job['runner_run_id'],$job['failure_code'],$job['lease_until'],$job['build_finished_at'],$job['build_attempt'],$job['id']]);
        }
        problem(409,fn()=> $builder->claim(['build_id'=>$id,'channel'=>'production','source_sha'=>$input['source_sha']]));
        problem(409,fn()=> $builder->claim(['build_id'=>$id,'channel'=>'testing','source_sha'=>str_repeat('0',40)]));
    };
    $tests['main sourced APK follows existing signer receipt and monotonic publication checks']=function()use($app,$builder,$brands,$owner,$input,$artifacts,&$jobs):void {
        $job=$jobs[0];$ctx=$builder->claim(['build_id'=>$job['id'],'channel'=>'testing','source_sha'=>$input['source_sha'],'runner_run_id'=>'456']);same(43200000+(int)$job['brand_version'],$ctx['expected']['version_code']);
        $manifest=json_decode($job['manifest'],true);same($manifest['package_name'].'.test',$ctx['expected']['package_name']);same($manifest['app_name'],$ctx['bundle']['manifest']['app_name']);
        $receipt=$ctx['expected'];unset($receipt['release_tag'],$receipt['asset_name']);$receipt+=['source_sha'=>$input['source_sha'],'apk_sha256'=>str_repeat('8',64),'apk_size_bytes'=>150000000,'provenance_sha256'=>str_repeat('9',64)];$lease=['build_id'=>$job['id'],'lease_token'=>$ctx['lease_token']];
        problem(409,fn()=> $builder->prepared($lease+['receipt'=>array_replace($receipt,['signer_sha256'=>str_repeat('0',64)])]));$builder->prepared($lease+['receipt'=>$receipt]);$builder->authorize($lease+['confirmed_publication'=>true]);same('published',$builder->finish($lease+['receipt'=>$receipt])['state']);same(43200000+(int)$job['brand_version'],(int)$artifacts->approved($job['brand_id'],'testing')['version_code']);
        // Subsequent manual branding requests must use latest registered main too.
        $current=$app->db->one('SELECT * FROM app_brands WHERE id=?',[$job['brand_id']]);
        $saved=$brands->save($owner,['tenant_id'=>$current['tenant_id'],'expected_version'=>(int)$current['version'],'name'=>$current['name'],'loading_asset'=>$current['loading_asset'],'logo_asset'=>$current['logo_asset'],'icon_asset'=>$current['icon_asset']]);
        // Earlier recovery tests intentionally leave uncertain SYNTHETIC rows.
        // They must still block manual requests; clear only this disposable fixture.
        if($app->db->one("SELECT id FROM app_brand_builds WHERE brand_id=? AND state IN ('waiting_builder','building','ready_to_publish','publishing','publication_review')",[$job['brand_id']])){
            problem(409,fn()=> $brands->requestBuild($owner,['tenant_id'=>$current['tenant_id'],'expected_version'=>$saved['version']]));
            $app->db->query("UPDATE app_brand_builds SET state='cancelled' WHERE brand_id=? AND state IN ('waiting_builder','building','ready_to_publish','publishing','publication_review')",[$job['brand_id']]);
        }
        $new=$brands->requestBuild($owner,['tenant_id'=>$current['tenant_id'],'expected_version'=>$saved['version']]);
        $row=$app->db->one('SELECT * FROM app_brand_builds WHERE id=?',[$new['id']]);same('2.0.116',$row['app_version']);same($input['source_sha'],$row['source_sha']);same('testing',$row['build_channel']);check($app->db->one('SELECT build_id FROM brand_main_jobs WHERE build_id=?',[$new['id']])!==null);
    };
}
