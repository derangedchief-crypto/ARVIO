<?php
declare(strict_types=1);

function settingsCases(array &$tests,ControlCenter\Application $app,array $owner,array $alpha,array $beta,array $tenantA,array $tenantB,HttpFixture $http,string $password):void
{
    $fixture=static function(array $caps)use($app,$owner,$alpha,$tenantA):array {
        $c=$app->management->createCustomer($alpha,['tenant_id'=>$tenantA['id'],'name'=>'TEST managed settings']);$d=$app->management->createDevice($alpha,['customer_id'=>$c['id'],'label'=>'TEST settings client']);$token=$app->commands->issueTestCredential($owner,$d['id']);$app->commands->checkIn($token,['capabilities'=>$caps],'127.0.0.51');return [$d,$token];
    };
    $tests['settings writes use a closed profile-revision schema and capability check'] = function()use($app,$alpha,$beta,$fixture):void {
        [$d,$token]=$fixture(['set_settings']);$payload=['profile_ref'=>str_repeat('a',64),'expected_revision'=>str_repeat('b',64),'sort_order'=>'name'];
        $bad=$payload;$bad['update_url']='https://forbidden.example';problem(422,fn()=> $app->commands->enqueue($alpha,$d['id'],['kind'=>'set_settings','payload'=>$bad],bin2hex(random_bytes(16))));
        problem(404,fn()=> $app->commands->enqueue($beta,$d['id'],['kind'=>'set_settings','payload'=>$payload],bin2hex(random_bytes(16))));
        $command=$app->commands->enqueue($alpha,$d['id'],['kind'=>'set_settings','payload'=>$payload],bin2hex(random_bytes(16)));
        $delivery=$app->commands->checkIn($token,['capabilities'=>['set_settings']],'127.0.0.51')['commands'][0];same('name',$delivery['payload']->sort_order);
        $snapshot=['schema_version'=>'1','profile_ref'=>str_repeat('a',64),'settings_revision'=>str_repeat('c',64),'sort_order'=>'name','playlist_count'=>'1','enabled_playlist_count'=>'1','portal_count'=>'0','favourite_channel_count'=>'0','favourite_group_count'=>'0'];
        $result=$app->commands->acknowledge($token,$command['id'],['status'=>'succeeded','lease_token'=>$delivery['lease_token'],'snapshot'=>$snapshot]);same('name',$result['result']['snapshot']['sort_order']);
        unset($snapshot['settings_revision']);problem(422,fn()=> ControlCenter\CommandSnapshots::validate('set_settings',$snapshot));
        $app->commands->checkIn($token,['capabilities'=>[]],'127.0.0.51');problem(409,fn()=> $app->commands->enqueue($alpha,$d['id'],['kind'=>'set_settings','payload'=>$payload],bin2hex(random_bytes(16))));
    };
    $tests['settings presets are workspace scoped with an explicit safe allowlist'] = function()use($app,$owner,$alpha,$beta,$tenantA,$tenantB):void {
        $preset=$app->presets->create($alpha,['name'=>'TEST literal <script>preset</script>','sort_order'=>'name']);
        same($tenantA['id'],$preset['tenant_id']);same(1,$app->presets->listing($alpha,[])['total']);same(0,$app->presets->listing($beta,[])['total']);
        problem(404,fn()=> $app->presets->listing($beta,['tenant_id'=>$tenantA['id']]));
        problem(404,fn()=> $app->presets->change($beta,$preset['id'],['sort_order'=>'number']));problem(404,fn()=> $app->presets->change($beta,$preset['id'],[],true));
        problem(404,fn()=> $app->presets->create($alpha,['tenant_id'=>$tenantB['id'],'name'=>'Foreign','sort_order'=>'name']));
        problem(422,fn()=> $app->presets->create($alpha,['name'=>'Unsafe','sort_order'=>'name','playlist_url'=>'https://forbidden.example']));
        problem(422,fn()=> $app->presets->change($alpha,$preset['id'],['sort_order'=>'script']));
        $app->presets->change($owner,$preset['id'],['sort_order'=>'number']);same('number',$app->presets->listing($alpha,[])['items'][0]['sort_order']);
        $app->presets->change($alpha,$preset['id'],[],true);same(0,$app->presets->listing($alpha,[])['total']);
    };
    $tests['preset count is bounded and archive retention does not remove active presets'] = function()use($app,$owner,$alpha,$tenantA):void {
        for($i=0;$i<100;$i++)$app->db->query('INSERT INTO settings_presets (id,tenant_id,name,sort_order,created_by) VALUES (?,?,?,?,?)',[ControlCenter\Database::id(),$tenantA['id'],'TEST preset '.$i,'provider',$owner['id']]);
        problem(409,fn()=> $app->presets->create($alpha,['name'=>'Over quota','sort_order'=>'name']));
        $first=$app->presets->listing($alpha,[])['items'][0];$app->presets->change($alpha,$first['id'],[],true);
        $created=$app->presets->create($alpha,['name'=>'Replacement','sort_order'=>'number']);same(100,$app->presets->listing($alpha,[])['total']);
        $app->db->query('UPDATE settings_presets SET updated_at=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 366 DAY) WHERE archived=1');$app->presets->maintain();
        check($app->db->one('SELECT id FROM settings_presets WHERE id=?',[$created['id']])!==null);same(0,(int)$app->db->query('SELECT COUNT(*) FROM settings_presets WHERE archived=1')->fetchColumn());
    };
    $tests['queued and delivered commands can be cancelled and old lease acknowledgments are rejected'] = function()use($app,$owner,$alpha,$beta,$fixture):void {
        [$d,$token]=$fixture(['request_checkin']);$c=$app->commands->enqueue($alpha,$d['id'],['kind'=>'request_checkin'],bin2hex(random_bytes(16)));
        problem(404,fn()=> $app->commands->cancel($beta,$d['id'],$c['id']));
        same('cancelled',$app->commands->cancel($alpha,$d['id'],$c['id'])['status']);same('cancelled',$app->commands->cancel($alpha,$d['id'],$c['id'])['status']);
        same([],$app->commands->checkIn($token,['capabilities'=>['request_checkin']],'127.0.0.51')['commands']);
        $c=$app->commands->enqueue($alpha,$d['id'],['kind'=>'request_checkin'],bin2hex(random_bytes(16)));$delivery=$app->commands->checkIn($token,['capabilities'=>['request_checkin']],'127.0.0.51')['commands'][0];
        $app->commands->cancel($owner,$d['id'],$c['id']);problem(409,fn()=> $app->commands->acknowledge($token,$c['id'],['status'=>'running','lease_token'=>$delivery['lease_token']]));
    };
    $tests['running commands cannot be falsely recalled and another user cannot cancel an owners command'] = function()use($app,$owner,$alpha,$fixture):void {
        [$d,$token]=$fixture(['request_checkin']);$c=$app->commands->enqueue($owner,$d['id'],['kind'=>'request_checkin'],bin2hex(random_bytes(16)));
        problem(403,fn()=> $app->commands->cancel($alpha,$d['id'],$c['id']));
        $delivery=$app->commands->checkIn($token,['capabilities'=>['request_checkin']],'127.0.0.51')['commands'][0];
        $app->commands->acknowledge($token,$c['id'],['status'=>'running','lease_token'=>$delivery['lease_token']]);problem(409,fn()=> $app->commands->cancel($owner,$d['id'],$c['id']));
        $app->commands->acknowledge($token,$c['id'],['status'=>'succeeded','lease_token'=>$delivery['lease_token']]);problem(409,fn()=> $app->commands->cancel($owner,$d['id'],$c['id']));
    };
    $tests['preset and command cancellation HTTP mutations enforce CSRF and tenant authorization'] = function()use($app,$owner,$alpha,$beta,$tenantA,$tenantB,$http,$password,$fixture):void {
        // Earlier HTTP cases intentionally rotate this synthetic account password.
        $app->db->query('UPDATE users SET password_hash=? WHERE id=?',[ControlCenter\Auth::hashPassword($password),$beta['id']]);
        $o=$http->request('POST','/api/login',['email'=>'owner@example.test','password'=>$password],[],'settings-owner')['body'];
        $a=$http->request('POST','/api/login',['email'=>'alpha@example.test','password'=>$password],[],'settings-alpha')['body'];
        $b=$http->request('POST','/api/login',['email'=>'beta@example.test','password'=>$password],[],'settings-beta')['body'];
        same(403,$http->request('POST','/api/settings-presets',['tenant_id'=>$tenantB['id'],'name'=>'CSRF','sort_order'=>'name'],[],'settings-owner')['status']);
        $p=$http->request('POST','/api/settings-presets',['tenant_id'=>$tenantB['id'],'name'=>'TEST HTTP preset','sort_order'=>'name'],['X-CSRF-Token'=>$o['csrf']],'settings-owner');same(201,$p['status']);$id=$p['body']['id'];
        same(404,$http->request('POST','/api/settings-presets/'.$id,['sort_order'=>'number'],['X-CSRF-Token'=>$a['csrf']],'settings-alpha')['status']);
        same(403,$http->request('POST','/api/settings-presets/'.$id.'/archive',new stdClass(),[],'settings-beta')['status']);
        same(200,$http->request('POST','/api/settings-presets/'.$id.'/archive',new stdClass(),['X-CSRF-Token'=>$b['csrf']],'settings-beta')['status']);
        [$d,$token]=$fixture(['request_checkin']);$c=$app->commands->enqueue($alpha,$d['id'],['kind'=>'request_checkin'],bin2hex(random_bytes(16)));$path='/api/devices/'.$d['id'].'/commands/'.$c['id'].'/cancel';
        same(403,$http->request('POST',$path,new stdClass(),[],'settings-alpha')['status']);
        same(404,$http->request('POST',$path,new stdClass(),['X-CSRF-Token'=>$b['csrf']],'settings-beta')['status']);
        same(200,$http->request('POST',$path,new stdClass(),['X-CSRF-Token'=>$a['csrf']],'settings-alpha')['status']);
    };
}
