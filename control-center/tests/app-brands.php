<?php
declare(strict_types=1);
function appBrandCases(array &$tests,ControlCenter\Application $app,array $owner,array $alpha,array $beta,array $tenantA,array $tenantB,HttpFixture $http,string $password):void {
    $image='iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jSuoAAAAASUVORK5CYII=';
    $input=function(array $actor,string $name)use($app,$image):array {$asset=$app->appBrands->upload($actor,['image'=>$image]);return ['name'=>$name,'loading_asset'=>$asset['id'],'logo_asset'=>$asset['id'],'icon_asset'=>$asset['id']];};
    $tests['one persistent brand per reseller with stable package and scoped immutable images']=function()use($app,$alpha,$beta,$tenantA,$tenantB,$input):void {
        $a=$input($alpha,'TEST North TV');$brand=$app->appBrands->save($alpha,$a);$current=$app->appBrands->get($alpha,[])['brand'];same($brand['id'],$current['id']);
        $updated=$app->appBrands->save($alpha,array_replace($a,['name'=>'TEST North renamed','expected_version'=>1]));same($brand['id'],$updated['id']);same($brand['package_name'],$updated['package_name']);same(2,$updated['version']);same(1,(int)$app->db->query('SELECT COUNT(*) FROM app_brands WHERE tenant_id=?',[$tenantA['id']])->fetchColumn());
        problem(409,fn()=> $app->appBrands->save($alpha,$a));problem(404,fn()=> $app->appBrands->get($beta,['tenant_id'=>$tenantA['id']]));problem(404,fn()=> $app->appBrands->image($beta,$a['logo_asset']));
        $b=$input($beta,'TEST South');$app->appBrands->save($beta,$b);problem(404,fn()=> $app->appBrands->save($alpha,array_replace($a,['expected_version'=>2,'icon_asset'=>$b['icon_asset']])));
    };
    $tests['branding never accepts reseller package signing updater fields or executable image formats']=function()use($app,$alpha,$input):void {
        $a=$input($alpha,'TEST safe');foreach(['package_name','update_url','signing_key','brand_id','tenant_owner'] as $field)problem(422,fn()=> $app->appBrands->save($alpha,array_replace($a,[$field=>'forbidden'])));
        problem(422,fn()=> $app->appBrands->save($alpha,array_replace($a,['name'=>'<script>'])));problem(422,fn()=> $app->appBrands->upload($alpha,['image'=>base64_encode('<svg xmlns="http://www.w3.org/2000/svg"><script>bad</script></svg>')]));problem(422,fn()=> $app->appBrands->upload($alpha,['image'=>'not base64']));
    };
    $tests['build requests deduplicate pinned versions without pretending that APKs already exist']=function()use($app,$alpha,$input):void {
        $brand=$app->appBrands->get($alpha,[])['brand'];$request=['expected_version'=>(int)$brand['version']];$job=$app->appBrands->requestBuild($alpha,$request);same('waiting_builder',$job['state']);same($job,$app->appBrands->requestBuild($alpha,$request));
        $before=$app->appBrands->export($job['id']);$a=$input($alpha,'TEST later name');$app->appBrands->save($alpha,array_replace($a,['expected_version'=>(int)$brand['version']]));same($before,$app->appBrands->export($job['id']));
        problem(409,fn()=> $app->appBrands->requestBuild($alpha,['expected_version'=>3]));same(false,$app->appBrands->get($alpha,[])['trial_creation_enabled']);same($brand['id'],$before['manifest']['brand_id']);same($brand['package_name'],$before['manifest']['package_name']);
        foreach($before['manifest']['assets'] as $role=>$asset)same($asset['sha256'],hash('sha256',base64_decode($before['images'][$role],true)));
    };
    $tests['unlinked workspaces cannot create brands and suspended publishers cannot export builds']=function()use($app,$owner,$alpha,$tenantA):void {
        $tenant=$app->management->createReseller($owner,['company'=>'TEST unlinked brand','name'=>'TEST brand operator','email'=>'brand-'.ControlCenter\Database::id().'@example.test','password'=>'Synthetic-Test-Password-123!','device_limit'=>5]);
        problem(409,fn()=> $app->appBrands->get($owner,['tenant_id'=>$tenant['id']]));
        $job=$app->appBrands->get($alpha,[])['builds'][0];$app->management->updateTenant($owner,$tenantA['id'],['status'=>'suspended','device_limit'=>100]);try{problem(409,fn()=> $app->appBrands->export($job['id']));}finally{$app->management->updateTenant($owner,$tenantA['id'],['status'=>'active','device_limit'=>100]);}
    };
    $tests['branding HTTP paths require authentication CSRF and actual tenant ownership']=function()use($app,$owner,$alpha,$tenantA,$http,$image,$password):void {
        // Earlier isolation cases suspend/revoke the original alpha session: sign in fresh.
        $login=$http->request('POST','/api/login',['email'=>'alpha@example.test','password'=>$password],[],'brand-alpha');same(200,$login['status']);
        $csrf=$login['body']['csrf'];same(403,$http->request('POST','/api/app-brand/assets',['image'=>$image],[],'brand-alpha')['status']);
        same(201,$http->request('POST','/api/app-brand/assets',['image'=>$image],['X-CSRF-Token'=>$csrf],'brand-alpha')['status']);
        $beta=$http->request('POST','/api/login',['email'=>'beta@example.test','password'=>$password],[],'brand-beta');same(200,$beta['status']);
        $brand=$app->appBrands->get($alpha,[])['brand'];same(404,$http->request('GET','/api/app-brand?tenant_id='.$tenantA['id'],null,[],'brand-beta')['status']);same(404,$http->request('GET','/api/app-brand/assets/'.$brand['logo_asset'],null,[],'brand-beta')['status']);same(401,$http->request('GET','/api/app-brand/assets/'.$brand['logo_asset'],null,[],'brand-anonymous')['status']);
    };
    $tests['only scoped requester or owner cancels waiting builds and cancelled exports are rejected']=function()use($app,$alpha,$beta):void {
        $job=$app->appBrands->get($alpha,[])['builds'][0];problem(404,fn()=> $app->appBrands->cancelBuild($beta,$job['id'],[]));same('cancelled',$app->appBrands->cancelBuild($alpha,$job['id'],[])['state']);problem(404,fn()=> $app->appBrands->export($job['id']));
        $brand=$app->appBrands->get($alpha,[])['brand'];same('waiting_builder',$app->appBrands->requestBuild($alpha,['expected_version'=>(int)$brand['version']])['state']);
    };
    $tests['only authenticated owner downloads pinned build inputs without server secrets']=function()use($app,$owner,$alpha,$beta,$http,$password):void {
        $job=$app->appBrands->get($alpha,[])['builds'][0];$before=$app->appBrands->export($job['id']);problem(403,fn()=> $app->appBrands->downloadInputs($alpha,$job['id']));problem(403,fn()=> $app->appBrands->downloadInputs($beta,$job['id']));
        $file=$app->appBrands->downloadInputs($owner,$job['id']);same('brand-input-'.$job['id'].'.json',$file['filename']);same($before,json_decode($file['body'],true));same(['manifest','images'],array_keys(json_decode($file['body'],true)));
        $login=$http->request('POST','/api/login',['email'=>'owner@example.test','password'=>$password],[],'brand-export-owner');same(200,$login['status']);$route='/api/app-brand/builds/'.$job['id'].'/inputs';same(200,$http->request('GET',$route,null,[],'brand-export-owner')['status']);same(403,$http->request('GET',$route,null,[],'brand-alpha')['status']);same(401,$http->request('GET',$route,null,[],'brand-input-anonymous')['status']);
    };
}
