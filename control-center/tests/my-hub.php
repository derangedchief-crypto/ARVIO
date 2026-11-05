<?php
declare(strict_types=1);
function myHubCases(array &$tests,ControlCenter\Application $app,array $owner,string $password,HttpFixture $http,ControlCenter\Config $config):void {
    $tests['My Hub owner panel login stays scoped to its existing workspace without global owner powers']=function()use($app,$owner,$config):void {
        $keyFile=$config->privateFile('my-hub-bridge.key');$previous=is_file($keyFile)?file_get_contents($keyFile):null;
        $secret=random_bytes(32);file_put_contents($keyFile,$secret);$app->db->pdo->beginTransaction();
        try {
            $space=$app->db->one("SELECT id FROM tenants WHERE kind='owner' ORDER BY id LIMIT 1");
            $app->db->query('DELETE FROM panel_resellers WHERE tenant_id=?',[$space['id']]);
            $app->db->query('INSERT INTO panel_resellers(panel_id,tenant_id,username,linked_by) VALUES (?,?,?,?)',['991236',$space['id'],'OwnerPanel',$owner['id']]);
            $license='active';
            $bridge=new ControlCenter\MyHub($app->db,$config,function($body,$headers)use($secret,&$license):string {
                $request=json_decode($body,true);$h=[];foreach($headers as $header){[$k,$v]=explode(': ',$header,2);$h[$k]=$v;}
                if ($request['action']==='login') {same('OwnerPanel',$request['username']);$reply=['action'=>'login','authenticated'=>true,'enabled'=>true,'reseller_id'=>'991236'];}
                else {same('991236',$request['reseller_id']);$reply=['enabled'=>true,'reseller_id'=>'991236','state'=>$license,'expires'=>null,'error'=>null];}
                $payload=json_encode($reply);return json_encode(['payload'=>$payload,'signature'=>hash_hmac('sha256',"my-hub-response-v1\n{$h['X-My-Hub-Timestamp']}\n{$h['X-My-Hub-Nonce']}\n$payload",$secret)]);
            });
            $input=['username'=>'OwnerPanel','password'=>'verified-panel-password'];
            $count=(int)$app->db->query('SELECT COUNT(*) FROM users')->fetchColumn();
            $session=$app->auth->loginPanel($input,'127.0.0.217',$bridge);$actor=$app->auth->user($session['token'],true);
            same($owner['id'],$actor['id']);same('reseller',$actor['role']);same($space['id'],$actor['tenant_id']);same($actor,$session['user']);
            same($count,(int)$app->db->query('SELECT COUNT(*) FROM users')->fetchColumn());
            same('owner',$app->db->one('SELECT role FROM users WHERE id=?',[$owner['id']])['role']);
            problem(401,fn()=>$app->auth->user($session['token']));problem(403,fn()=>ControlCenter\Auth::owner($actor));
            $foreign=$app->db->one("SELECT id FROM tenants WHERE kind='reseller' LIMIT 1");problem(404,fn()=>$app->management->tenant($actor,$foreign['id']));
            $customer=$app->management->createCustomer($actor,['name'=>'Owner native customer']);same($space['id'],$customer['tenant_id']);
            // Regression: login/overview worked, but owner-kind feature gates
            // rejected this intentionally reseller-scoped native actor.
            same($space['id'],$app->renewalSettings->workspace($actor)['id']);
            same($space['id'],$app->appBrands->get($actor,[])['tenant_id']);
            same($space['id'],$app->trialPolicies->get($actor,[])['tenant_id']);
            same($space['id'],$app->trialSetup->status($actor,[])['tenant_id']);
            same($space['id'],$app->trialSelfService->status($actor,[])['tenant_id']);
            foreach ([$app->appBrands,$app->trialPolicies] as $service) problem(404,fn()=>$service->get($actor,['tenant_id'=>$foreign['id']]));
            problem(404,fn()=>$app->renewalSettings->workspace($actor,$foreign['id']));
            problem(403,fn()=>$app->renewalSettings->setPolicy($actor,['enabled'=>false,'testing_only'=>true]));
            problem(403,fn()=>$app->trialPolicies->refresh($actor,[]));
            $unverified=$actor;unset($unverified['_native_owner_workspace']);
            problem(409,fn()=>$app->renewalSettings->workspace($unverified));
            problem(403,fn()=>$app->appBrands->get($unverified,[]));
            problem(403,fn()=>$app->trialPolicies->get($unverified,[]));
            same(false,ControlCenter\Auth::ownsWorkspace($actor,['id'=>$foreign['id'],'kind'=>'owner']));
            same(true,$bridge->status($actor)['allowed']);$license='suspended';problem(403,fn()=>$bridge->requireActive($actor));
            $app->db->query("UPDATE tenants SET status='suspended' WHERE id=?",[$space['id']]);problem(401,fn()=>$app->auth->user($session['token'],true));
            problem(403,fn()=>$app->auth->loginPanel($input,'127.0.0.217',$bridge));$app->db->query("UPDATE tenants SET status='active' WHERE id=?",[$space['id']]);
            $app->db->query('UPDATE users SET active=0 WHERE id=?',[$owner['id']]);problem(401,fn()=>$app->auth->user($session['token'],true));problem(403,fn()=>$app->auth->loginPanel($input,'127.0.0.217',$bridge));$app->db->query('UPDATE users SET active=1 WHERE id=?',[$owner['id']]);
            $app->db->query('DELETE FROM panel_resellers WHERE tenant_id=?',[$space['id']]);problem(401,fn()=>$app->auth->user($session['token'],true));
            $app->auth->logout($session['token']);same(0,(int)$app->db->query('SELECT COUNT(*) FROM my_hub_owner_workspaces WHERE token_hash=?',[hash('sha256',$session['token'])])->fetchColumn());
        } finally {
            $app->db->pdo->rollBack();if ($previous===null) unlink($keyFile);else file_put_contents($keyFile,$previous);
        }
    };
    $tests['My Hub panel login uses verified identity not email or caller tenant and enforces workspace state']=function()use($app,$owner,$password,$config):void {
        $tenant=$app->management->createReseller($owner,['company'=>'Panel username login','name'=>'Panel reseller','email'=>'panel-login-'.bin2hex(random_bytes(5)).'@example.test','password'=>$password]);
        $app->db->query('INSERT INTO panel_resellers (panel_id,tenant_id,username,linked_by) VALUES (?,?,?,?)',['991235',$tenant['id'],'real-panel-name',$owner['id']]);
        $key=random_bytes(32);file_put_contents($config->privateFile('my-hub-bridge.key'),$key);
        $authenticated=true;$panelId='991235';$tamper=false;$calls=0;
        $bridge=new ControlCenter\MyHub($app->db,$config,function($body,$headers)use($key,&$authenticated,&$panelId,&$tamper,&$calls):string {
            $calls++;$request=json_decode($body,true);same(['action'=>'login','username'=>'real-panel-name','password'=>'panel-only-password'],$request);
            $h=[];foreach($headers as $header){[$k,$v]=explode(': ',$header,2);$h[$k]=$v;}
            same(hash_hmac('sha256',"my-hub-request-v1\n{$h['X-My-Hub-Timestamp']}\n{$h['X-My-Hub-Nonce']}\n$body",$key),$h['X-My-Hub-Signature']);
            $payload=json_encode(['action'=>'login','authenticated'=>$authenticated,'enabled'=>$authenticated,'reseller_id'=>$panelId]);
            return json_encode(['payload'=>$payload,'signature'=>$tamper?str_repeat('0',64):hash_hmac('sha256',"my-hub-response-v1\n{$h['X-My-Hub-Timestamp']}\n{$h['X-My-Hub-Nonce']}\n$payload",$key)]);
        });
        $input=['username'=>'real-panel-name','password'=>'panel-only-password'];
        $native=$app->auth->loginPanel($input,'127.0.0.216',$bridge);
        same($tenant['id'],$native['user']['tenant_id']);same('reseller',$native['user']['role']);
        same($native['user'],$app->auth->user($native['token'],true));problem(401,fn()=>$app->auth->user($native['token']));
        $authenticated=false;problem(401,fn()=>$app->auth->loginPanel($input,'127.0.0.216',$bridge));
        $authenticated=true;$panelId='999999';problem(403,fn()=>$app->auth->loginPanel($input,'127.0.0.216',$bridge));
        $panelId='991235';$tamper=true;problem(503,fn()=>$app->auth->loginPanel($input,'127.0.0.216',$bridge));$tamper=false;
        $app->db->query("UPDATE tenants SET status='suspended' WHERE id=?",[$tenant['id']]);
        problem(403,fn()=>$app->auth->loginPanel($input,'127.0.0.216',$bridge));
        $app->db->query("UPDATE tenants SET status='active' WHERE id=?",[$tenant['id']]);
        $app->db->query('UPDATE users SET active=0 WHERE id=?',[$native['user']['id']]);
        problem(403,fn()=>$app->auth->loginPanel($input,'127.0.0.216',$bridge));
        $before=$calls;problem(422,fn()=>$app->auth->loginPanel($input+['tenant_id'=>$tenant['id']],'127.0.0.216',$bridge));same($before,$calls);
        same(1,(int)$app->db->query('SELECT COUNT(*) FROM sessions WHERE user_id=?',[$native['user']['id']])->fetchColumn());
    };
    $tests['My Hub native tokens cannot bypass licensing through website cookies']=function()use($app,$owner,$password,$http):void {
        $email='hub-'.bin2hex(random_bytes(5)).'@example.test';
        $app->management->createReseller($owner,['company'=>'My Hub test','name'=>'Native reseller','email'=>$email,'password'=>$password]);
        $native=$app->auth->login(['email'=>$email,'password'=>$password],'127.0.0.214',true);
        same($native['user'],$app->auth->user($native['token'],true));
        problem(401,fn()=>$app->auth->user($native['token']));
        $web=$app->auth->login(['email'=>$email,'password'=>$password],'127.0.0.214');
        problem(401,fn()=>$app->auth->user($web['token'],true));
        same(401,$http->request('GET','/api/overview',null,['Cookie'=>'control_session='.$native['token']],'hub-cookie')['status']);
        same(401,$http->request('GET','/api/my-hub/manage/overview',null,['Authorization'=>'Bearer '.$web['token']],'hub-web-bearer')['status']);
        same(403,$http->request('GET','/api/my-hub/manage/overview',null,['Authorization'=>'Bearer '.$native['token']],'hub-no-link')['status']);
        $app->auth->logout($native['token']);problem(401,fn()=>$app->auth->user($native['token'],true));
        same(0,(int)$app->db->query('SELECT COUNT(*) FROM my_hub_sessions WHERE token_hash=?',[hash('sha256',$native['token'])])->fetchColumn());
    };
    $tests['My Hub verifies exact linked panel identity and rejects forged or stale responses']=function()use($app,$owner,$password,$config):void {
        $email='hub-bridge-'.bin2hex(random_bytes(4)).'@example.test';
        $tenant=$app->management->createReseller($owner,['company'=>'Hub bridge','name'=>'Hub bridge','email'=>$email,'password'=>$password]);
        $actor=$app->auth->login(['email'=>$email,'password'=>$password],'127.0.0.215',true)['user'];
        $secret=random_bytes(32);file_put_contents($config->privateFile('my-hub-bridge.key'),$secret);
        $app->db->query('INSERT INTO panel_resellers (panel_id,tenant_id,username,linked_by) VALUES (?,?,?,?)',['991234',$tenant['id'],'hub-fixture',$owner['id']]);
        $state='active';$enabled=true;$wrong=false;$tamper=false;$expires=null;
        $bridge=new ControlCenter\MyHub($app->db,$config,function($body,$headers)use($secret,&$state,&$enabled,&$wrong,&$tamper,&$expires):string {
            $h=[];foreach($headers as $header){[$k,$v]=explode(': ',$header,2);$h[$k]=$v;}
            $request=json_decode($body,true);same('991234',$request['reseller_id']);
            same(hash_hmac('sha256',"my-hub-request-v1\n{$h['X-My-Hub-Timestamp']}\n{$h['X-My-Hub-Nonce']}\n$body",$secret),$h['X-My-Hub-Signature']);
            $payload=json_encode(['reseller_id'=>$wrong?'different':'991234','state'=>$state,'enabled'=>$enabled,'expires'=>$expires,'error'=>null]);
            return json_encode(['payload'=>$payload,'signature'=>$tamper?str_repeat('0',64):hash_hmac('sha256',"my-hub-response-v1\n{$h['X-My-Hub-Timestamp']}\n{$h['X-My-Hub-Nonce']}\n$payload",$secret)]);
        });
        same(true,$bridge->status($actor)['allowed']);
        $state='suspended';problem(403,fn()=>$bridge->requireActive($actor));
        $state='active';$enabled=false;problem(403,fn()=>$bridge->requireActive($actor));
        $enabled=true;$expires=time()-1;problem(403,fn()=>$bridge->requireActive($actor));
        $expires=null;$wrong=true;problem(503,fn()=>$bridge->status($actor));
        $wrong=false;$tamper=true;problem(503,fn()=>$bridge->status($actor));
        problem(403,fn()=>$bridge->status($owner));
    };
}
