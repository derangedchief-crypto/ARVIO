<?php
declare(strict_types=1);

function enrollmentCases(array &$tests, ControlCenter\Application $app, ControlCenter\Config $config, array $owner, array $alpha, array $beta,
    array $tenantA, array $tenantB, callable $makeCustomer, HttpFixture $http, string $password): void
{
    $proofs=new ControlCenter\EnrollmentProof($config,new ControlCenter\Secrets($config));
    $identity=static function (?string $account=null):array {
        $private=openssl_pkey_new(['private_key_type'=>OPENSSL_KEYTYPE_EC,'curve_name'=>'prime256v1']);
        $pem=openssl_pkey_get_details($private)['key'];
        $encoded=preg_replace('~-----[^\n]+-----|\s~','',$pem);
        return ['private'=>$private,'public'=>$encoded,'key'=>hash('sha256',base64_decode($encoded)),'install'=>ControlCenter\Database::id(),'account'=>$account??bin2hex(random_bytes(32))];
    };
    $token=static function (array $id,string $nonce,array $overrides=[]) use($proofs,$config):string {
        $claim=array_replace(['v'=>1,'iss'=>'extreme-activation','aud'=>$config->origin(),'sub'=>$id['account'],'hint'=>'…test','install'=>$id['install'],'key'=>$id['key'],
            'package'=>'com.arvio.tv.test','challenge'=>hash('sha256',$nonce),'iat'=>time(),'exp'=>time()+60,'jti'=>ControlCenter\Database::id()],$overrides);
        $encode=static fn($s)=>rtrim(strtr(base64_encode($s),'+/','-_'),'=');
        $payload=$encode(json_encode($claim,JSON_UNESCAPED_SLASHES|JSON_UNESCAPED_UNICODE|JSON_THROW_ON_ERROR));
        return 'ec1.'.$payload.'.'.$encode(hash_hmac('sha256','proof-v1.'.$payload,hex2bin($proofs->bridgeSecret()),true));
    };
    $body=static function (array $id,array $claims=[]) use($app,$token):array {
        $nonce=$app->enrollment->challenge(['installation_id'=>$id['install'],'key_fingerprint'=>$id['key'],'app_package'=>'com.arvio.tv.test'],'127.0.0.41')['challenge'];
        $proof=$token($id,$nonce,$claims);
        openssl_sign(ControlCenter\EnrollmentProof::signingText($nonce,$proof,$id['install'],'com.arvio.tv.test',$id['key']),$signature,$id['private'],OPENSSL_ALGO_SHA256);
        return ['challenge'=>$nonce,'proof'=>$proof,'public_key'=>$id['public'],'signature'=>base64_encode($signature),'label'=>'TEST signed fixture','model'=>'Synthetic device','os_version'=>'Test','app_version'=>'test'];
    };
    $register=static fn(array $id)=>$app->enrollment->register($body($id),'127.0.0.41');
    $delivery=static fn(array $r)=>['request_id'=>$r['request_id'],'poll_token'=>$r['poll_token']];
    $approve=static function (array $r,?array $customer=null) use($app,$owner,$alpha,$tenantA,$makeCustomer):array {
        $customer??=$makeCustomer($alpha,$tenantA['id']);
        return $app->enrollment->approve($owner,$r['request_id'],['customer_id'=>$customer['id']]);
    };
    $tests['new enrollment is paused until owner enables it'] = function () use($app,$owner):void {
        same(false,$app->enrollment->settings($owner)['enabled']);
        problem(503,fn()=> $app->enrollment->challenge(['installation_id'=>str_repeat('a',32),'key_fingerprint'=>str_repeat('b',64),'app_package'=>'com.arvio.tv.test'],'127.0.0.41'));
        $app->enrollment->setEnabled($owner,['enabled'=>true]);
    };
    $tests['only owner can enable enrollment, review requests or download bridge configuration'] = function () use($app,$alpha):void {
        problem(403,fn()=> $app->enrollment->settings($alpha)); problem(403,fn()=> $app->enrollment->setEnabled($alpha,['enabled'=>true]));
        problem(403,fn()=> $app->enrollment->bridgeConfiguration($alpha)); problem(403,fn()=> $app->enrollment->listing($alpha,[]));
    };
    $tests['proof verifier enforces audience, lifetime, issuer and signature'] = function () use($identity,$token,$proofs):void {
        $id=$identity(); $nonce=str_repeat('a',64); $valid=$token($id,$nonce);
        same($id['account'],$proofs->verify($valid)['sub']);
        foreach([['aud'=>'https://foreign.test'],['iss'=>'wrong'],['iat'=>time()-400,'exp'=>time()-340],['exp'=>time()+3600]] as $override) problem(403,fn()=> $proofs->verify($token($id,$nonce,$override)));
        $parts=explode('.',$valid); $parts[2][0]=$parts[2][0]==='a'?'b':'a'; problem(403,fn()=> $proofs->verify(implode('.',$parts)));
    };
    $tests['device must prove possession of the key named by the trusted account proof'] = function () use($app,$identity,$body):void {
        $a=$identity(); $request=$body($a); $b=$identity();
        openssl_sign('wrong',$signature,$b['private'],OPENSSL_ALGO_SHA256); $request['signature']=base64_encode($signature);
        problem(403,fn()=> $app->enrollment->register($request,'127.0.0.41'));
        $request=$body($a); $request['public_key']=$b['public']; problem(403,fn()=> $app->enrollment->register($request,'127.0.0.41'));
    };
    $tests['client supplied tenant and customer claims never assign ownership'] = function () use($app,$identity,$body,$tenantB):void {
        $request=$body($identity()); $request['tenant_id']=$tenantB['id']; problem(422,fn()=> $app->enrollment->register($request,'127.0.0.41'));
    };
    $tests['nonce/proof replay cannot enroll another key and same signed retry is idempotent'] = function () use($app,$identity,$body):void {
        $request=$body($identity()); $a=$app->enrollment->register($request,'127.0.0.41'); $b=$app->enrollment->register($request,'127.0.0.41'); same($a,$b);
        $request['challenge']=str_repeat('f',64); problem(403,fn()=> $app->enrollment->register($request,'127.0.0.41'));
    };
    $tests['pending enrollment gives no management credential before approval'] = function () use($app,$identity,$register,$delivery):void {
        $r=$register($identity()); $poll=$app->enrollment->poll($delivery($r),'127.0.0.41'); same('pending',$poll['status']); check(!isset($poll['credential'],$poll['device_id']));
        $wrong=$delivery($r); $wrong['poll_token']=str_repeat('0',64); problem(401,fn()=> $app->enrollment->poll($wrong,'127.0.0.41'));
    };
    $tests['reseller cannot approve or reject even a request for its own account'] = function () use($app,$identity,$register,$alpha,$tenantA,$makeCustomer):void {
        $r=$register($identity()); $customer=$makeCustomer($alpha,$tenantA['id']);
        problem(403,fn()=> $app->enrollment->approve($alpha,$r['request_id'],['customer_id'=>$customer['id']])); problem(403,fn()=> $app->enrollment->reject($alpha,$r['request_id']));
    };
    $tests['approval binds the verified identity and tenant, stores only encrypted delivery and hashed bearer'] = function () use($app,$owner,$alpha,$beta,$identity,$register,$delivery,$approve):void {
        $r=$register($identity()); $device=$approve($r); $poll=$app->enrollment->poll($delivery($r),'127.0.0.41');
        same('approved',$poll['status']); same($device['id'],$poll['device_id']); check(strlen($poll['credential'])===64);
        same('com.arvio.tv.test',$device['app_package']); same(false,$device['is_test']);
        same(0,(int)$app->db->query('SELECT COUNT(*) FROM device_credentials WHERE token_hash=?',[$poll['credential']])->fetchColumn());
        $row=$app->db->one('SELECT * FROM enrollment_requests WHERE id=?',[$r['request_id']]); check(!str_contains(json_encode($row),$poll['credential']));
        $public=json_encode($app->enrollment->listing($owner,['status'=>'approved'])); check(!str_contains($public,$r['poll_token'])&&!str_contains($public,$poll['credential']));
        problem(404,fn()=> $app->management->device($beta,$device['id'])); same($alpha['tenant_id'],$device['tenant_id']);
        $heartbeat=$app->commands->checkIn($poll['credential'],['capabilities'=>['request_checkin','show_message']],'127.0.0.41'); same($device['id'],$heartbeat['device_id']);
    };
    $tests['approval is idempotent and credential receipt confirmation is crash/retry safe'] = function () use($app,$identity,$register,$delivery,$approve,$owner):void {
        $r=$register($identity()); $d=$approve($r);
        same($d['id'],$app->enrollment->approve($owner,$r['request_id'],['customer_id'=>$d['customer_id']])['id']);
        $a=$app->enrollment->poll($delivery($r),'127.0.0.41'); $b=$app->enrollment->poll($delivery($r),'127.0.0.41'); same($a['credential'],$b['credential']);
        same(true,$app->enrollment->poll($delivery($r),'127.0.0.41',true)['confirmed']); same(true,$app->enrollment->poll($delivery($r),'127.0.0.41',true)['confirmed']);
        problem(409,fn()=> $app->enrollment->poll($delivery($r),'127.0.0.41'));
    };
    $tests['same verified device renews after proof without adding a device or clearing suspension'] = function () use($app,$identity,$register,$delivery,$approve,$owner):void {
        $id=$identity(); $r=$register($id); $d=$approve($r); $old=$app->enrollment->poll($delivery($r),'127.0.0.41')['credential']; $app->enrollment->poll($delivery($r),'127.0.0.41',true);
        $app->management->updateDevice($owner,$d['id'],['policy'=>'suspended']);
        $again=$register($id); same($r['request_id'],$again['request_id']); $new=$app->enrollment->poll($delivery($again),'127.0.0.41'); same('approved',$new['status']); same($d['id'],$new['device_id']);
        check($old!==$new['credential']); problem(401,fn()=> $app->commands->deviceIdentity($old)); same('suspended',$app->commands->checkIn($new['credential'],['capabilities'=>[]],'127.0.0.41')['policy']);
    };
    $tests['a verified account cannot be silently assigned to another customer or reseller'] = function () use($app,$identity,$register,$approve,$owner,$beta,$tenantB,$makeCustomer):void {
        $first=$identity(); $a=$register($first); $approve($a);
        $b=$register($identity($first['account'])); $foreign=$makeCustomer($beta,$tenantB['id']);
        problem(409,fn()=> $app->enrollment->approve($owner,$b['request_id'],['customer_id'=>$foreign['id']]));
    };
    $tests['pausing new enrollment does not prevent an approved identity renewing its own credential'] = function () use($app,$owner,$identity,$register,$approve,$delivery):void {
        $id=$identity(); $r=$register($id); $device=$approve($r); $app->enrollment->poll($delivery($r),'127.0.0.41',true);
        $app->enrollment->setEnabled($owner,['enabled'=>false]);
        try {
            $again=$register($id); same($device['id'],$app->enrollment->poll($delivery($again),'127.0.0.41')['device_id']);
            $other=$identity(); problem(503,fn()=> $app->enrollment->challenge(['installation_id'=>$other['install'],'key_fingerprint'=>$other['key'],'app_package'=>'com.arvio.tv.test'],'127.0.0.41'));
        } finally {$app->enrollment->setEnabled($owner,['enabled'=>true]);}
    };
    $tests['removed credential has only a removal notice and re-enrollment requires owner review'] = function () use($app,$identity,$register,$delivery,$approve,$owner):void {
        $id=$identity(); $r=$register($id); $d=$approve($r); $credential=$app->enrollment->poll($delivery($r),'127.0.0.41')['credential']; $app->enrollment->poll($delivery($r),'127.0.0.41',true);
        $app->management->updateDevice($owner,$d['id'],['policy'=>'removed']); problem(401,fn()=> $app->commands->deviceIdentity($credential));
        $notice=$app->commands->removalNotice($credential); same('removed',$notice['policy']); same([],$notice['commands']); same(null,$app->commands->removalNotice(str_repeat('f',64)));
        $again=$register($id); same('pending',$app->enrollment->poll($delivery($again),'127.0.0.41')['status']);
        $app->enrollment->approve($owner,$again['request_id'],['customer_id'=>$d['customer_id']]); same('approved',$app->enrollment->poll($delivery($again),'127.0.0.41')['status']);
    };
    $tests['rejected enrollment is not activation revocation and never yields a credential'] = function () use($app,$identity,$register,$delivery,$owner):void {
        $r=$register($identity()); $app->enrollment->reject($owner,$r['request_id']); $poll=$app->enrollment->poll($delivery($r),'127.0.0.41'); same('rejected',$poll['status']); check(!isset($poll['credential']));
    };
    $tests['HTTP schema upgrade, bridge setup and pending reviews are owner-only and CSRF protected'] = function () use($http,$password):void {
        $owner=$http->request('POST','/api/login',['email'=>'owner@example.test','password'=>$password],[],'enrollment-owner')['body'];
        $alpha=$http->request('POST','/api/login',['email'=>'alpha@example.test','password'=>$password],[],'enrollment-alpha')['body'];
        same(403,$http->request('GET','/api/enrollments',null,[],'enrollment-alpha')['status']);
        same(403,$http->request('POST','/api/system/migrate',new stdClass(),['X-CSRF-Token'=>$alpha['csrf']],'enrollment-alpha')['status']);
        same(403,$http->request('POST','/api/enrollments/bridge-configuration',new stdClass(),['X-CSRF-Token'=>$alpha['csrf']],'enrollment-alpha')['status']);
        same(403,$http->request('POST','/api/enrollments/settings',['enabled'=>true],[],'enrollment-owner')['status']);
        same(200,$http->request('POST','/api/system/migrate',new stdClass(),['X-CSRF-Token'=>$owner['csrf']],'enrollment-owner')['status']);
        $response=$http->request('POST','/api/enrollments/bridge-configuration',new stdClass(),['X-CSRF-Token'=>$owner['csrf']],'enrollment-owner'); same(200,$response['status']);
        check(str_contains($response['body']['content'],'MANAGEMENT_CONTROL_ORIGIN=')); check(str_contains($response['body']['content'],'MANAGEMENT_ENROLLMENT_SECRET='));
    };
    $bridge=static function(string $id,string $username,bool $tamper=false)use($proofs):ControlCenter\PanelBridge {
        return new ControlCenter\PanelBridge($proofs,static function($raw,$headers)use($proofs,$id,$username,$tamper):string {
            $h=[]; foreach($headers as $header) {[$name,$value]=explode(': ',$header,2);$h[$name]=$value;}
            same($username,json_decode($raw,true)['username']);
            same(ControlCenter\PanelBridge::signature($proofs->bridgeSecret(),'reseller-lookup-v1',$h['X-Management-Timestamp'],$h['X-Management-Nonce'],$raw),$h['X-Management-Signature']);
            $payload=json_encode(['id'=>$id,'username'=>$username]);
            return json_encode(['payload'=>$payload,'signature'=>$tamper?str_repeat('0',64):ControlCenter\PanelBridge::signature($proofs->bridgeSecret(),'reseller-result-v1',$h['X-Management-Timestamp'],$h['X-Management-Nonce'],$payload)]);
        });
    };
    $newTenant=static function(string $suffix,int $limit=100)use($app,$owner,$password):array {
        return $app->management->createReseller($owner,['company'=>'Panel '.$suffix,'name'=>'Fixture','email'=>'panel-'.$suffix.'@example.test','password'=>$password,'device_limit'=>$limit]);
    };
    $panelClaim=static fn(string $reseller,string $line)=>['panel'=>['customer_id'=>$line,'reseller_id'=>$reseller,'reseller_username'=>'panel-fixture']];
    $tests['panel links require owner and signed exact reseller lookup; no name-only ownership'] = function ()use($app,$owner,$alpha,$bridge,$newTenant):void {
        $tenant=$newTenant('link');
        problem(403,fn()=> $app->enrollment->linkPanelReseller($alpha,$tenant['id'],['username'=>'panel-fixture'],$bridge('1001','panel-fixture')));
        problem(503,fn()=> $app->enrollment->linkPanelReseller($owner,$tenant['id'],['username'=>'panel-fixture'],$bridge('1001','panel-fixture',true)));
        $linked=$app->enrollment->linkPanelReseller($owner,$tenant['id'],['username'=>'panel-fixture'],$bridge('1001','panel-fixture'));
        same('1001',$linked['panel_id']);
        same('1001',$app->enrollment->linkPanelReseller($owner,$tenant['id'],['username'=>'panel-fixture'],$bridge('1001','panel-fixture'))['panel_id']);
        $other=$newTenant('duplicate');problem(409,fn()=> $app->enrollment->linkPanelReseller($owner,$other['id'],['username'=>'panel-fixture'],$bridge('1001','panel-fixture')));
        problem(409,fn()=> $app->enrollment->linkPanelReseller($owner,$tenant['id'],['username'=>'another'],$bridge('1002','another')));
    };
    $tests['verified linked reseller automatically gets customer and device without owner approval'] = function ()use($app,$identity,$body,$panelClaim,$delivery,$owner):void {
        $id=$identity();$request=$app->enrollment->register($body($id,$panelClaim('1001','2001')),'127.0.0.41');
        same('approved',$request['status']);$poll=$app->enrollment->poll($delivery($request),'127.0.0.41');
        $device=$app->management->device($owner,$poll['device_id']);$link=$app->db->one('SELECT * FROM panel_resellers WHERE panel_id=?',['1001']);same($link['tenant_id'],$device['tenant_id']);
        same('2001',$app->db->one('SELECT panel_id FROM panel_customers WHERE customer_id=?',[$device['customer_id']])['panel_id']);
        $another=$identity($id['account']);$r=$app->enrollment->register($body($another,$panelClaim('1001','2001')),'127.0.0.41');
        same('approved',$r['status']);$d=$app->enrollment->poll($delivery($r),'127.0.0.41');same($device['customer_id'],$app->management->device($owner,$d['device_id'])['customer_id']);
    };
    $tests['unlinked matching names and legacy proofs stay pending, not automatic'] = function ()use($app,$identity,$body,$panelClaim):void {
        same('pending',$app->enrollment->register($body($identity(),$panelClaim('9999','2002')),'127.0.0.41')['status']);
        same('pending',$app->enrollment->register($body($identity()),'127.0.0.41')['status']);
    };
    $tests['signed customer identity cannot be reused for another account reference'] = function ()use($app,$identity,$body,$panelClaim):void {
        same('pending',$app->enrollment->register($body($identity(),$panelClaim('1001','2001')),'127.0.0.41')['status']);
    };
    $tests['automatic assignment never overrides rejected removed or manually cross-tenant identities'] = function ()use($app,$owner,$identity,$body,$panelClaim,$delivery,$approve):void {
        $id=$identity();$r=$app->enrollment->register($body($id),'127.0.0.41');$app->enrollment->reject($owner,$r['request_id']);
        same('rejected',$app->enrollment->register($body($id,$panelClaim('1001','2003')),'127.0.0.41')['status']);
        $id=$identity();$r=$app->enrollment->register($body($id,$panelClaim('1001','2004')),'127.0.0.41');$d=$app->enrollment->poll($delivery($r),'127.0.0.41');
        $app->management->updateDevice($owner,$d['device_id'],['policy'=>'removed']);
        same('pending',$app->enrollment->register($body($id,$panelClaim('1001','2004')),'127.0.0.41')['status']);
        $id=$identity();$r=$app->enrollment->register($body($id),'127.0.0.41');$approve($r);
        problem(409,fn()=> $app->enrollment->register($body($id,$panelClaim('1001','2005')),'127.0.0.41'));
    };
    $tests['automatic enrollment enforces allocation and reseller suspension'] = function ()use($app,$owner,$newTenant,$bridge,$identity,$body,$panelClaim,$delivery):void {
        $t=$newTenant('limits',1);$app->enrollment->linkPanelReseller($owner,$t['id'],['username'=>'limited'],$bridge('1003','limited'));
        $id=$identity();$r=$app->enrollment->register($body($id,$panelClaim('1003','2006')),'127.0.0.41');same('approved',$r['status']);
        same('pending',$app->enrollment->register($body($identity(),$panelClaim('1003','2007')),'127.0.0.41')['status']);
        $app->management->updateTenant($owner,$t['id'],['status'=>'suspended','device_limit'=>10]);
        same('pending',$app->enrollment->register($body($identity(),$panelClaim('1003','2008')),'127.0.0.41')['status']);
    };
    $tests['new panel link causes pending installed apps to retry with a fresh proof'] = function ()use($app,$owner,$newTenant,$bridge,$identity,$body,$panelClaim,$delivery):void {
        $id=$identity();$r=$app->enrollment->register($body($id,$panelClaim('1004','2009')),'127.0.0.41');same('pending',$r['status']);
        $t=$newTenant('retry');$app->enrollment->linkPanelReseller($owner,$t['id'],['username'=>'retry'],$bridge('1004','retry'));
        problem(401,fn()=> $app->enrollment->poll($delivery($r),'127.0.0.41'));
        $again=$app->enrollment->register($body($id,$panelClaim('1004','2009')),'127.0.0.41');same('approved',$again['status']);same($r['request_id'],$again['request_id']);
    };
    $tests['signed panel identity is validated and no browser reseller may link ownership'] = function ()use($app,$identity,$body,$panelClaim,$http,$password):void {
        $claim=$panelClaim('1001','2010');$claim['panel']['reseller_id']='arbitrary';problem(403,fn()=> $app->enrollment->register($body($identity(),$claim),'127.0.0.41'));
        $login=$http->request('POST','/api/login',['email'=>'alpha@example.test','password'=>$password],[],'panel-alpha')['body'];
        same(403,$http->request('GET','/api/panel-resellers',null,[],'panel-alpha')['status']);
        same(403,$http->request('POST','/api/tenants/'.str_repeat('a',32).'/panel-link',['username'=>'foreign'],['X-CSRF-Token'=>$login['csrf']],'panel-alpha')['status']);
    };
    $tests['new reseller creation verifies and saves the permanent panel link atomically'] = function ()use($app,$owner,$config,$password,$bridge):void {
        $management=new ControlCenter\Management($app->db,$config,$app->auth,$bridge('1005','created'));
        $input=['company'=>'Workspace display','name'=>'Fixture','email'=>'panel-created@example.test','password'=>$password,'device_limit'=>10,'panel_username'=>'created'];
        $tenant=$management->createReseller($owner,$input);
        same('1005',$app->db->one('SELECT panel_id FROM panel_resellers WHERE tenant_id=?',[$tenant['id']])['panel_id']);
        $input['email']='duplicate-created@example.test';problem(409,fn()=> $management->createReseller($owner,$input));
        same(0,(int)$app->db->query('SELECT COUNT(*) FROM users WHERE email=?',[$input['email']])->fetchColumn());
        $management=new ControlCenter\Management($app->db,$config,$app->auth,$bridge('1006','created',true));
        problem(503,fn()=> $management->createReseller($owner,$input));
        same(0,(int)$app->db->query('SELECT COUNT(*) FROM users WHERE email=?',[$input['email']])->fetchColumn());
    };
}
