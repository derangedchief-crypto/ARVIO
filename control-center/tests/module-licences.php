<?php
declare(strict_types=1);
function moduleLicenceCases(array &$tests,ControlCenter\Application $app,array $owner,array $alpha,array $beta,array $tenantA,HttpFixture $http,string $password,string $fixtureRoot):void {
    $service=$app->moduleLicences;
    $fixture=function()use($app,$owner,$password):array {
        $email='module-'.bin2hex(random_bytes(4)).'@example.test';$t=$app->management->createReseller($owner,['company'=>'TEST paid add-on','name'=>'TEST licence','email'=>$email,'password'=>$password]);
        $session=$app->auth->login(['email'=>$email,'password'=>$password],'127.0.0.213');return [$t,$session['user'],$session];
    };
    $toggle=function(bool $on)use($service,$owner):void {$p=$service->policy();$service->setPolicy($owner,['enforced'=>$on,'expected_revision'=>$p['revision'],'confirmed'=>true]);};
    $tests['module subscriptions clamp calendar months in UTC including leap years']=function():void {
        same('2027-02-28 23:12:01',ControlCenter\ModuleLicences::monthAfter('2027-01-31 23:12:01'));
        same('2028-02-29 10:00:00',ControlCenter\ModuleLicences::monthAfter('2028-01-31 10:00:00'));
        same('2027-01-15 00:00:00',ControlCenter\ModuleLicences::monthAfter('2026-12-15 00:00:00'));
    };
    $tests['owner-issued module key is scoped hidden and replay does not extend monthly access']=function()use($app,$service,$owner,$beta,$fixture):void {
        [$t,$seller]=$fixture();$issued=$service->issue($owner,['tenant_id'=>$t['id'],'module'=>'extra_playlists']);$key=$issued['key'];same('awaiting_activation',$issued['licence']['effective_status']);
        check(!str_contains(json_encode($service->status($seller,[])),$key));check(!str_contains(json_encode($app->db->all('SELECT * FROM module_licences')),$key));
        problem(403,fn()=> $service->issue($seller,['module'=>'extra_playlists']));problem(404,fn()=> $service->status($beta,['tenant_id'=>$t['id']]));
        problem(422,fn()=> $service->redeem($beta,['key'=>$key]));problem(409,fn()=> $service->issue($owner,['tenant_id'=>$t['id'],'module'=>'extra_playlists']));
        $active=$service->redeem($seller,['key'=>$key]);same('active',$active['licence']['effective_status']);same($active,$service->redeem($seller,['key'=>$key]));
        problem(403,fn()=> $service->change($seller,$active['licence']['id'],['action'=>'renew','expected_revision'=>$active['licence']['revision']]));
        check(!str_contains(json_encode($app->db->all("SELECT metadata FROM audit_events WHERE action LIKE 'module.%'")),$key));
    };
    $tests['module expiry suspension renewal and permanent revocation never restore old keys']=function()use($app,$service,$owner,$fixture):void {
        [$t,$seller]=$fixture();$issued=$service->issue($owner,['tenant_id'=>$t['id'],'module'=>'extra_playlists']);$key=$issued['key'];$r=$service->redeem($seller,['key'=>$key])['licence'];
        $change=fn($row,$action)=>$service->change($owner,$row['id'],['action'=>$action,'expected_revision'=>$row['revision']])['licence'];
        $r=$change($r,'suspend');same('suspended',$r['effective_status']);problem(403,fn()=> $service->redeem($seller,['key'=>$key]));
        $app->db->query('UPDATE module_licences SET expires_at=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 DAY) WHERE id=?',[$r['id']]);$r=$change($r,'reactivate');same('expired',$r['effective_status']);
        same('expired',$service->redeem($seller,['key'=>$key])['licence']['effective_status']);$stale=$r;$r=$change($r,'renew');same('active',$r['effective_status']);problem(409,fn()=> $change($stale,'renew'));
        $r=$change($r,'revoke');same('revoked',$r['effective_status']);problem(409,fn()=> $change($r,'reactivate'));problem(403,fn()=> $service->redeem($seller,['key'=>$key]));
        $replacement=$service->issue($owner,['tenant_id'=>$t['id'],'module'=>'extra_playlists']);check($replacement['key']!==$key);problem(422,fn()=> $service->redeem($seller,['key'=>$key]));
    };
    $tests['simultaneous licence redemption grants exactly one month']=function()use($service,$owner,$fixture,$fixtureRoot):void {
        [$t,$seller]=$fixture();$issued=$service->issue($owner,['tenant_id'=>$t['id'],'module'=>'extra_playlists']);
        $results=concurrentOperations($fixtureRoot,['operation'=>'module-redeem','actor'=>$seller,'key'=>$issued['key']]);
        same(200,$results[0]['status']);same($results[0],$results[1]);same(2,$results[0]['revision']);
    };
    $tests['monthly add-on covers the workspace fleet only and never exempts owner targets']=function()use($app,$service,$owner,$fixture,$toggle,$http):void {
        same(false,$service->policy()['enforced']);[$t,$seller]=$fixture();$devices=[];
        for($i=0;$i<2;$i++){$c=$app->management->createCustomer($seller,['name'=>'TEST module customer','tenant_id'=>$t['id']]);$devices[]=$app->management->createDevice($seller,['customer_id'=>$c['id'],'label'=>'TEST module device']);}
        $issued=$service->issue($owner,['tenant_id'=>$t['id'],'module'=>'extra_playlists']);$r=$service->redeem($seller,['key'=>$issued['key']])['licence'];$toggle(true);
        foreach($devices as $d)$app->commands->issueTestCredential($owner,$d['id']);
        try {
            foreach($devices as $d)same(true,$service->decision($d)['allowed']);
            $credential=$app->commands->issueTestCredential($owner,$devices[0]['id']);
            $response=$http->request('POST','/api/device/addons/authorize',new stdClass(),['Authorization'=>'Bearer '.$credential],'module-device-fleet');same(200,$response['status']);same(true,$response['body']['allowed']);same($devices[0]['id'],$response['body']['device_id']);
            $app->management->detachReseller($owner,$devices[0]['id']);same(false,$service->decision($app->commands->deviceIdentity($credential))['allowed']);same('active',$app->commands->deviceIdentity($credential)['policy']);
            $own=$app->management->workspace($owner);same(false,$service->decision(['id'=>str_repeat('a',32),'tenant_id'=>$own['id'],'policy'=>'active'])['allowed']);
            $before=$app->db->all('SELECT * FROM device_credentials ORDER BY token_hash');$service->change($owner,$r['id'],['action'=>'revoke','expected_revision'=>$r['revision']]);
            foreach($devices as $d)same(false,$service->decision($d)['allowed']);same($before,$app->db->all('SELECT * FROM device_credentials ORDER BY token_hash'));
        } finally {$toggle(false);}
    };
    $tests['source additions cannot bypass the module with replacements reorder or removed identities']=function()use($service,$toggle):void {
        $a=['id'=>'list_1','m3uUrl'=>'https://a.test'];$b=['id'=>'list_2','m3uUrl'=>'https://b.test'];$c=['id'=>'list_3','m3uUrl'=>'https://c.test'];
        $config=fn($lists,$portals=[])=>['m3uUrl'=>$lists[0]['m3uUrl']??'','playlists'=>$lists,'stalkerPortals'=>$portals];
        same(false,ControlCenter\ModuleLicences::needsLicence($config([]),$config([$a])));
        same(true,ControlCenter\ModuleLicences::needsLicence($config([$a]),$config([$a,$b])));
        same(false,ControlCenter\ModuleLicences::needsLicence($config([$a,$b]),$config([$b,$a])));
        same(true,ControlCenter\ModuleLicences::needsLicence($config([$b,$a]),$config([array_replace($b,['m3uUrl'=>'https://changed.test']),$a])));
        same(true,ControlCenter\ModuleLicences::needsLicence($config([$a,$b]),$config([array_replace($a,['m3uUrl'=>'https://changed.test']),$a])));
        same(true,ControlCenter\ModuleLicences::needsLicence($config([$a,$b]),$config([$c,$a,$b])));
        same(true,ControlCenter\ModuleLicences::needsLicence($config([$a,$b]),$config([$a,$c])));
        same(false,ControlCenter\ModuleLicences::needsLicence($config([$a,$b]),$config([$a])));
    };
    $tests['module HTTP endpoints enforce CSRF roles and workspace scope']=function()use($app,$service,$owner,$alpha,$beta,$fixture,$http):void {
        [$t,$seller,$session]=$fixture();$h=['Cookie'=>'control_session='.$session['token'],'X-CSRF-Token'=>$session['csrf']];
        same(401,$http->request('GET','/api/addons',null,[],'module-anon')['status']);
        same(403,$http->request('POST','/api/addons/issue',['module'=>'extra_playlists'],$h,'module-issue-denied')['status']);
        same(403,$http->request('POST','/api/addons/redeem',['key'=>'invalid'],['Cookie'=>$h['Cookie']],'module-csrf')['status']);
        same(404,$http->request('GET','/api/addons?tenant_id='.$beta['tenant_id'],null,$h,'module-other')['status']);
        same(422,$http->request('POST','/api/addons/redeem',['key'=>'invalid','expires_at'=>'2099-01-01'],$h,'module-closed')['status']);
        same(200,$http->request('POST','/api/device/addons/policy',new stdClass(),[],'module-policy')['status']);
        same(401,$http->request('POST','/api/device/addons/authorize',new stdClass(),[],'module-device-auth')['status']);
        same(403,$http->request('POST','/api/addons/policy',['enforced'=>true,'expected_revision'=>1,'confirmed'=>true],$h,'module-policy-role')['status']);
    };
}
