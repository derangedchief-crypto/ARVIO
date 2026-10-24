<?php
declare(strict_types=1);
function brandDownloadCases(array &$tests,ControlCenter\Application $app,ControlCenter\Config $config,array $owner,HttpFixture $http):void {
    $tests['APK byte ranges are bounded single ranges with suffix and invalid-range handling']=function():void {
        same(null,ControlCenter\BrandDownloads::range(null,2048));same([0,99],ControlCenter\BrandDownloads::range('bytes=0-99',2048));same([2000,2047],ControlCenter\BrandDownloads::range('bytes=2000-',2048));same([2038,2047],ControlCenter\BrandDownloads::range('bytes=-10',2048));same([0,2047],ControlCenter\BrandDownloads::range('bytes=-9999',2048));
        foreach(['bytes=2048-','bytes=20-10','bytes=-0','bytes=0-1,3-4','bytes=9999999999999999999999-','bytes=-','bad'] as $range)problem(416,fn()=>ControlCenter\BrandDownloads::range($range,2048));
    };
    $tests['site APK transfer verifies bytes and current lease before publication, never exposes upstream links']=function()use($app,$config,$owner,$http):void {
        $sha=str_repeat('b',40);$settings=['enabled'=>true,'publication_enabled'=>true,'channels'=>['testing'],'sources'=>['2.0.115'=>$sha]];
        $c=new ControlCenter\Config($config->root,array_replace($config->values,['brand_builder'=>$settings,'brand_downloads'=>['enabled'=>true]]));$secrets=new ControlCenter\Secrets($c);$artifacts=new ControlCenter\BrandArtifacts($app->db,$secrets,$c);$downloads=new ControlCenter\BrandDownloads($c,$artifacts);$builder=new ControlCenter\BrandBuilder($app->db,$c,$app->appBrands,$artifacts,$secrets,$app->auth);
        $tenant=$app->management->createReseller($owner,['company'=>'TEST local delivery','name'=>'TEST Delivery','email'=>'delivery@example.test','password'=>'Synthetic-Delivery-Password-123!','device_limit'=>5]);$app->db->query('INSERT INTO panel_resellers (panel_id,tenant_id,username,linked_by) VALUES (?,?,?,?)',['800007',$tenant['id'],'SYNTHETIC-delivery',$owner['id']]);
        $asset=$app->appBrands->upload($owner,['tenant_id'=>$tenant['id'],'image'=>'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jSuoAAAAASUVORK5CYII=']);
        $brand=$app->appBrands->save($owner,['tenant_id'=>$tenant['id'],'name'=>'TEST Private Brand','loading_asset'=>$asset['id'],'logo_asset'=>$asset['id'],'icon_asset'=>$asset['id']]);$job=$app->appBrands->requestBuild($owner,['tenant_id'=>$tenant['id'],'expected_version'=>1]);
        $context=$builder->claim(['build_id'=>$job['id'],'channel'=>'testing','source_sha'=>$sha,'runner_run_id'=>'1234']);$lease=['build_id'=>$job['id'],'lease_token'=>$context['lease_token']];
        $bytes=str_repeat('TEST APK',256);$receipt=$context['expected'];unset($receipt['release_tag'],$receipt['asset_name']);$receipt+=['source_sha'=>$sha,'apk_sha256'=>hash('sha256',$bytes),'apk_size_bytes'=>strlen($bytes),'provenance_sha256'=>str_repeat('d',64)];
        $builder->prepared($lease+['receipt'=>$receipt]);same(true,$builder->delivery($lease)['ready']);$builder->authorize($lease+['confirmed_publication'=>true]);
        problem(409,fn()=> $builder->finish($lease+['receipt'=>$receipt]));same(null,$artifacts->approved($brand['id'],'testing'));
        $stream=static function(string $data){$stream=fopen('php://temp','w+b');fwrite($stream,$data);rewind($stream);return $stream;};
        $input=$stream($bytes.'EXCESS');try{problem(422,fn()=> $builder->mirror($lease,$input));}finally{fclose($input);}
        $input=$stream(substr($bytes,1));try{problem(409,fn()=> $builder->mirror($lease,$input));}finally{fclose($input);}
        $input=$stream(str_repeat('X',strlen($bytes)));try{problem(409,fn()=> $builder->mirror($lease,$input));}finally{fclose($input);}
        $input=$stream($bytes);try{problem(409,fn()=> $builder->mirror(array_replace($lease,['lease_token'=>str_repeat('0',64)]),$input));}finally{fclose($input);}
        $input=$stream($bytes);try{same(true,$builder->mirror($lease,$input)['stored']);}finally{fclose($input);}
        $uncommitted=array_replace($receipt,['apk_sha256'=>hash('sha256',str_repeat('Y',strlen($bytes)))]);$input=$stream(str_repeat('Y',strlen($bytes)));
        try{problem(409,fn()=> $downloads->ingest($uncommitted,$input,static function(callable $install):void{throw new ControlCenter\Problem(409,'Lease changed during transfer.');}));}finally{fclose($input);}
        check(!file_exists($downloads->file($uncommitted)));
        $input=$stream($bytes);try{same(true,$builder->mirror($lease,$input)['stored']);}finally{fclose($input);}
        same('published',$builder->finish($lease+['receipt'=>$receipt])['state']);$artifact=$artifacts->approved($brand['id'],'testing');same(true,$downloads->available($artifact));same(0640,fileperms($downloads->file($artifact))&0777);
        same([],glob($config->root.'/storage/brand-apks/.upload-*'));
        $public=$app->appBrands->get($owner,['tenant_id'=>$tenant['id']]);check(!str_contains(json_encode($public),'github'));check(!str_contains(json_encode($downloads->metadata($brand['id'],'testing')),'github'));same(false,$public['artifacts'][0]['download_ready']);
        // Only this isolated HTTP fixture's config is changed, never a live config.
        $file=$config->root.'/config.php';$original=file_get_contents($file);$values=require $file;$values['brand_downloads']=['enabled'=>true];file_put_contents($file,'<?php return '.var_export($values,true).';');
        try {
            $metadata=$http->request('GET','/api/app-updates/'.$brand['id'].'/testing');same(200,$metadata['status']);check(!str_contains($metadata['raw'],'github'));check(!str_contains($metadata['raw'],'source_sha'));
            $url=$downloads->publicInfo($artifact)['artifact_url'];$full=$http->request('GET',$url);same(200,$full['status']);same($bytes,$full['raw']);check(!isset($full['headers']['location']));
            $partial=$http->request('GET',$url,null,['Range'=>'bytes=10-29']);same(206,$partial['status']);same(substr($bytes,10,20),$partial['raw']);same(['bytes 10-29/'.strlen($bytes)],$partial['headers']['content-range']);
            $suffix=$http->request('GET',$url,null,['Range'=>'bytes=-12']);same(206,$suffix['status']);same(substr($bytes,-12),$suffix['raw']);
            same(416,$http->request('GET',$url,null,['Range'=>'bytes=999999-'])['status']);
            $head=$http->request('HEAD',$url);same(200,$head['status']);same('',$head['raw']);same([(string)strlen($bytes)],$head['headers']['content-length']);
            same(404,$http->request('GET',str_replace('Brand-2.0.115.1-test.apk','Brand-2.0.115.2-test.apk',$url))['status']);
            same(404,$http->request('GET','/api/app-updates/'.str_repeat('0',32).'/testing')['status']);
            same(401,$http->request('POST','/api/brand-builder/mirror',[])['status']);
        } finally {file_put_contents($file,$original);}
    };
}
