<?php
declare(strict_types=1);

function deviceRecoveryCases(array &$tests,ControlCenter\Application $app,ControlCenter\Config $config,array $owner,array $other,string $fixture):void {
    $tenant=null;$actor=null;$panel='95551';$proofs=new ControlCenter\EnrollmentProof($config,new ControlCenter\Secrets($config));
    $window=new ControlCenter\DeviceListWindow($app->db,$app->auth);
    $identity=static function(?string $account=null):array{
        $private=openssl_pkey_new(['private_key_type'=>OPENSSL_KEYTYPE_EC,'curve_name'=>'prime256v1']);
        $public=preg_replace('~-----[^\n]+-----|\s~','',openssl_pkey_get_details($private)['key']);
        return ['private'=>$private,'public'=>$public,'key'=>hash('sha256',base64_decode($public)),'install'=>ControlCenter\Database::id(),'account'=>$account??bin2hex(random_bytes(32))];
    };
    $body=static function(array $id,?string $hint=null,string $package='com.arvio.tv.test',?string $reseller=null,?string $audience=null)use($app,$config,$proofs,$panel):array{
        $nonce=$app->enrollment->challenge(['installation_id'=>$id['install'],'key_fingerprint'=>$id['key'],'app_package'=>$package],'127.0.0.91')['challenge'];
        $claim=['v'=>1,'iss'=>'extreme-activation','aud'=>$audience??$config->origin(),'sub'=>$id['account'],'hint'=>'…recovery','install'=>$id['install'],'key'=>$id['key'],'package'=>$package,'challenge'=>hash('sha256',$nonce),'iat'=>time(),'exp'=>time()+60,'jti'=>ControlCenter\Database::id(),
            'panel'=>['customer_id'=>(string)(hexdec(substr($id['account'],0,12))+1),'reseller_id'=>$reseller??$panel,'reseller_username'=>'recovery-fixture']];
        $encode=static fn(string $s)=>rtrim(strtr(base64_encode($s),'+/','-_'),'=');
        $payload=$encode(json_encode($claim,JSON_UNESCAPED_SLASHES|JSON_THROW_ON_ERROR));
        $proof='ec1.'.$payload.'.'.$encode(hash_hmac('sha256','proof-v1.'.$payload,hex2bin($proofs->bridgeSecret()),true));
        openssl_sign(ControlCenter\EnrollmentProof::signingText($nonce,$proof,$id['install'],$package,$id['key'],$hint),$signature,$id['private'],OPENSSL_ALGO_SHA256);
        return ['challenge'=>$nonce,'proof'=>$proof,'public_key'=>$id['public'],'signature'=>base64_encode($signature),'label'=>'Recovery fixture','model'=>'Fixture TV','os_version'=>'Android fixture','app_version'=>'future-fixture']+($hint===null?[]:['recovery_hint'=>$hint]);
    };
    $register=static fn(array $id,?string $hint=null)=>$app->enrollment->register($body($id,$hint),'127.0.0.91');
    $poll=static fn(array $r)=>$app->enrollment->poll(['request_id'=>$r['request_id'],'poll_token'=>$r['poll_token']],'127.0.0.91');
    $quiet=static function(string $id)use($app):void{
        $app->db->query('UPDATE devices SET last_seen_at=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 10 MINUTE) WHERE id=?',[$id]);
        $app->db->query('UPDATE device_recovery_hints SET updated_at=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 10 MINUTE) WHERE device_id=?',[$id]);
    };
    $count=static fn()=>(int)$app->db->query('SELECT COUNT(*) FROM devices')->fetchColumn();
    $tests['recovery fixture setup remains owner controlled and default off']=function()use($app,$owner,$other,$window,&$tenant,&$actor,$panel):void{
        same(false,$window->state()['recovery_enabled']);
        problem(403,fn()=> $window->setRecovery($other,['enabled'=>true]));
        $tenant=$app->management->createReseller($owner,['company'=>'Recovery fixture','name'=>'Recovery operator','email'=>'recovery-fixture@example.test','password'=>'Synthetic-Recovery-Password-42!','device_limit'=>100]);
        $actor=$app->db->one('SELECT * FROM users WHERE tenant_id=?',[$tenant['id']]);
        $app->db->query('INSERT INTO panel_resellers (panel_id,tenant_id,username,linked_by) VALUES (?,?,?,?)',[$panel,$tenant['id'],'recovery-fixture',$owner['id']]);
        $app->enrollment->setEnabled($owner,['enabled'=>true]);$window->setRecovery($owner,['enabled'=>true]);
    };
    $tests['unique inactive recovery keeps record notes assignment and restrictions and retires old credentials']=function()use($app,$owner,$identity,$register,$poll,$quiet,$count):void{
        foreach(['active','suspended','blocked'] as $policy){
            $a=$identity();$hint=hash('sha256',$a['install']);$old=$poll($register($a,$hint));$id=$old['device_id'];
            $app->management->updateDevice($owner,$id,['label'=>'Custom retained name','notes'=>'Keep my notes','policy'=>$policy]);
            $before=$app->db->one('SELECT * FROM devices WHERE id=?',[$id]);$quiet($id);$total=$count();
            $b=$identity($a['account']);$new=$poll($register($b,$hint));same('approved',$new['status']);same($id,$new['device_id']);same($total,$count());
            $after=$app->db->one('SELECT * FROM devices WHERE id=?',[$id]);
            foreach(['label','notes','tenant_id','customer_id','policy','enrolled_at'] as $key)same($before[$key],$after[$key]);
            same($b['install'],$after['installation_id']);same($b['key'],$after['key_fingerprint']);
            problem(401,fn()=> $app->commands->deviceIdentity($old['credential']));same($id,$app->commands->deviceIdentity($new['credential'])['id']);
            same($policy,$app->commands->checkIn($new['credential'],[],'127.0.0.91')['policy']);
        }
    };
    $tests['online old installation is not silently displaced by a copied hint']=function()use($app,$identity,$register,$poll,$count):void{
        $a=$identity();$hint=hash('sha256',$a['install']);$old=$poll($register($a,$hint));$total=$count();
        $new=$register($identity($a['account']),$hint);same('pending',$new['status']);same('busy',$new['recovery_state']);same('suspended',$new['recovery_policy']);same(true,$new['recovery_retry']);same($total,$count());
        check(!isset($poll($new)['credential']));same($old['device_id'],$app->commands->deviceIdentity($old['credential'])['id']);
    };
    $tests['simultaneous reset claims cannot rotate the same device twice or create duplicates']=function()use($app,$identity,$body,$register,$poll,$quiet,$count,$fixture):void{
        $a=$identity();$hint=hash('sha256',$a['install']);$old=$poll($register($a,$hint));$quiet($old['device_id']);$total=$count();
        $first=$identity($a['account']);$second=$identity($a['account']);
        // Worker processes read the HTTP fixture's actual random-port origin;
        // the in-process test Config intentionally retains its original origin.
        $audience=(new ControlCenter\Config($fixture))->origin();
        $results=concurrentOperations($fixture,['operation'=>'recovery-register','bodies'=>[$body($first,$hint,'com.arvio.tv.test',null,$audience),$body($second,$hint,'com.arvio.tv.test',null,$audience)]]);
        same([200,200],array_column($results,'status'));
        $states=array_column($results,'enrollment_status');sort($states);same(['approved','pending'],$states);
        same($total,$count());same(1,(int)$app->db->query('SELECT COUNT(*) FROM device_credentials WHERE device_id=? AND revoked_at IS NULL',[$old['device_id']])->fetchColumn());
    };
    $tests['recovery uses the same slot even at allocation capacity']=function()use($app,$identity,$register,$poll,$quiet,$count,&$tenant):void{
        $a=$identity();$hint=hash('sha256',$a['install']);$old=$poll($register($a,$hint));$quiet($old['device_id']);
        $used=(int)$app->db->query("SELECT COUNT(*) FROM devices WHERE tenant_id=? AND policy!='removed'",[$tenant['id']])->fetchColumn();
        $app->db->query('UPDATE tenants SET device_limit=? WHERE id=?',[$used,$tenant['id']]);$total=$count();
        try{same($old['device_id'],$poll($register($identity($a['account']),$hint))['device_id']);same($total,$count());}
        finally{$app->db->query('UPDATE tenants SET device_limit=100 WHERE id=?',[$tenant['id']]);}
    };
    $tests['owner-unassigned recovery preserves owner assignment and former reseller denial']=function()use($app,$owner,$identity,$register,$poll,$quiet,&$actor):void{
        $a=$identity();$hint=hash('sha256',$a['install']);$old=$poll($register($a,$hint));
        $retained=$app->management->detachReseller($owner,$old['device_id']);$quiet($old['device_id']);
        $new=$poll($register($identity($a['account']),$hint));same($old['device_id'],$new['device_id']);
        $after=$app->management->device($owner,$new['device_id']);same($retained['tenant_id'],$after['tenant_id']);same($retained['customer_id'],$after['customer_id']);same(true,$after['reseller_detached']);
        problem(404,fn()=> $app->management->device($actor,$new['device_id']));
    };
    $tests['busy reinstall recovers automatically after quiet period with fresh proof']=function()use($app,$identity,$register,$poll,$quiet,$count):void{
        $a=$identity();$hint=hash('sha256',$a['install']);$old=$poll($register($a,$hint));$b=$identity($a['account']);
        $pending=$register($b,$hint);same('busy',$pending['recovery_state']);$quiet($old['device_id']);$total=$count();
        $new=$poll($register($b,$hint));same($old['device_id'],$new['device_id']);same($total,$count());
    };
    $tests['removed records stay removed with no new credential or duplicate']=function()use($app,$owner,$identity,$register,$poll,$quiet,$count):void{
        $a=$identity();$hint=hash('sha256',$a['install']);$old=$poll($register($a,$hint));$app->management->updateDevice($owner,$old['device_id'],['policy'=>'removed']);$quiet($old['device_id']);$total=$count();
        $new=$register($identity($a['account']),$hint);same('pending',$new['status']);same('removed',$new['recovery_policy']);same(false,$new['recovery_retry']);
        check(!isset($poll($new)['credential']));same($total,$count());same('removed',$app->db->one('SELECT policy FROM devices WHERE id=?',[$old['device_id']])['policy']);
    };
    $tests['duplicate hints are held outside device list instead of selecting first record']=function()use($app,$owner,$window,$identity,$register,$poll,$quiet,$count):void{
        $a=$identity();$hint=hash('sha256',$a['install']);$old=$poll($register($a,$hint));
        $window->setRecovery($owner,['enabled'=>false]);$second=$poll($register($identity($a['account']),$hint));$window->setRecovery($owner,['enabled'=>true]);
        $quiet($old['device_id']);$quiet($second['device_id']);$total=$count();
        $pending=$register($identity($a['account']),$hint);same('pending',$pending['status']);same('ambiguous',$pending['recovery_state']);same($total,$count());
    };
    $tests['different known hints allow two real TVs on one account']=function()use($identity,$register,$poll,$count):void{
        $a=$identity();$first=$poll($register($a,hash('sha256','first-'.$a['install'])));$total=$count();
        $second=$poll($register($identity($a['account']),hash('sha256','second-'.$a['install'])));
        check($first['device_id']!==$second['device_id']);same($total+1,$count());
    };
    $tests['legacy account without a prior hint is not guessed from account or model']=function()use($identity,$register,$poll,$count):void{
        $a=$identity();$poll($register($a));$total=$count();
        $pending=$register($identity($a['account']),hash('sha256','new-hint'));same('pending',$pending['status']);same('unidentified',$pending['recovery_state']);same($total,$count());
    };
    $tests['recovery hints are bound into the new enrollment signature']=function()use($app,$identity,$body,$count):void{
        $request=$body($identity(),str_repeat('a',64));$request['recovery_hint']=str_repeat('b',64);$total=$count();
        problem(403,fn()=> $app->enrollment->register($request,'127.0.0.91'));same($total,$count());
    };
    $tests['same hint cannot transfer another account or reseller record']=function()use($app,$identity,$body,$register,$poll,$quiet,$count):void{
        $a=$identity();$hint=hash('sha256',$a['install']);$old=$poll($register($a,$hint));$quiet($old['device_id']);
        $foreign=$poll($register($identity(),$hint));check($foreign['device_id']!==$old['device_id']);
        $total=$count();$other=$app->enrollment->register($body($identity($a['account']),$hint,'com.arvio.tv.test','9002'),'127.0.0.91');
        same('pending',$other['status']);same($total,$count());same($a['install'],$app->db->one('SELECT installation_id FROM devices WHERE id=?',[$old['device_id']])['installation_id']);
    };
    $tests['authenticated existing signing key can backfill hint without exposing raw value']=function()use($app,$identity,$register,$poll):void{
        $a=$identity();$old=$poll($register($a));$hint=hash('sha256','protected-client-hint');
        openssl_sign(ControlCenter\DeviceRecovery::hintSigningText($old['device_id'],'com.arvio.tv.test',$hint),$signature,$a['private'],OPENSSL_ALGO_SHA256);
        $reply=$app->commands->checkIn($old['credential'],['recovery_hint'=>$hint,'recovery_signature'=>base64_encode($signature)],'127.0.0.91');same(1,$reply['device_recovery_version']);
        $row=$app->db->one('SELECT * FROM device_recovery_hints WHERE device_id=?',[$old['device_id']]);check($row!==null&&$row['hint_hash']!==$hint);
        problem(403,fn()=> $app->commands->checkIn($old['credential'],['recovery_hint'=>str_repeat('b',64),'recovery_signature'=>base64_encode($signature)],'127.0.0.91'));
        same($row['hint_hash'],$app->db->one('SELECT hint_hash FROM device_recovery_hints WHERE device_id=?',[$old['device_id']])['hint_hash']);
    };
    $tests['fresh list reset is owner-only global visibility not deletion or logout']=function()use($app,$owner,$other,$window,$identity,$register,$poll,$count,&$actor):void{
        $a=$identity();$connected=$poll($register($a,hash('sha256',$a['install'])));$id=$connected['device_id'];$total=$count();
        $before=$app->db->all('SELECT * FROM devices ORDER BY id');$creds=$app->db->all('SELECT * FROM device_credentials ORDER BY token_hash');
        problem(403,fn()=> $window->reset($other,['scope'=>'all_workspaces','confirm'=>true]));
        problem(422,fn()=> $window->reset($owner,['scope'=>'all_workspaces','confirm'=>false]));
        $state=$window->reset($owner,['scope'=>'all_workspaces','confirm'=>true]);check($state['generation']>0);
        same($before,$app->db->all('SELECT * FROM devices ORDER BY id'));same($creds,$app->db->all('SELECT * FROM device_credentials ORDER BY token_hash'));
        same(0,$app->management->listing($owner,'devices',[])['total']);same(0,$app->management->listing($actor,'devices',[])['total']);
        check($app->management->listing($owner,'devices',['visibility'=>'all'])['total']>0);
        $app->commands->checkIn($connected['credential'],['app_version'=>'after-update'],'127.0.0.91');
        same($total,$count());$visible=$app->management->listing($actor,'devices',[]);same(1,$visible['total']);same($id,$visible['items'][0]['id']);
        same('after-update',$visible['items'][0]['app_version']);problem(404,fn()=> $app->management->device($other,$id));
        same(1,(int)$app->management->overview($actor)['total']);
    };
}
