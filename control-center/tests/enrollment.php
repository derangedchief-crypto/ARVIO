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
        $claims=array_replace(['panel'=>['customer_id'=>(string)(hexdec(substr($id['account'],0,14))+1),'reseller_id'=>'9001','reseller_username'=>'fixture-alpha']],$claims);
        $proof=$token($id,$nonce,$claims);
        openssl_sign(ControlCenter\EnrollmentProof::signingText($nonce,$proof,$id['install'],'com.arvio.tv.test',$id['key']),$signature,$id['private'],OPENSSL_ALGO_SHA256);
        return ['challenge'=>$nonce,'proof'=>$proof,'public_key'=>$id['public'],'signature'=>base64_encode($signature),'label'=>'TEST signed fixture','model'=>'Synthetic device','os_version'=>'Test','app_version'=>'test'];
    };
    // Exercise the exceptional/manual-review path using an admitted reseller at
    // allocation capacity, NOT an unverified legacy assertion. Restore capacity
    // before the owner approves. Normal auto-assignment tests call register directly.
    $register=static function(array $id)use($app,$body,$tenantA,$alpha,$makeCustomer):array {
        $limit=(int)$app->db->query('SELECT device_limit FROM tenants WHERE id=?',[$tenantA['id']])->fetchColumn();
        $used=(int)$app->db->query("SELECT COUNT(*) FROM devices WHERE tenant_id=? AND policy!='removed'",[$tenantA['id']])->fetchColumn();
        if($used===0){$c=$makeCustomer($alpha,$tenantA['id']);$app->management->createDevice($alpha,['customer_id'=>$c['id'],'label'=>'TEST capacity fixture']);$used=1;}
        $app->db->query('UPDATE tenants SET device_limit=? WHERE id=?',[$used,$tenantA['id']]);
        try{return $app->enrollment->register($body($id),'127.0.0.41');}
        finally{$app->db->query('UPDATE tenants SET device_limit=? WHERE id=?',[$limit,$tenantA['id']]);}
    };
    $delivery=static fn(array $r)=>['request_id'=>$r['request_id'],'poll_token'=>$r['poll_token']];
    $approve=static function (array $r,?array $customer=null) use($app,$owner,$alpha,$tenantA,$makeCustomer):array {
        $customer??=$makeCustomer($alpha,$tenantA['id']);
        return $app->enrollment->approve($owner,$r['request_id'],['customer_id'=>$customer['id']]);
    };
    $tests['new enrollment is paused until owner enables it'] = function () use($app,$owner,$tenantA,$tenantB):void {
        same(false,$app->enrollment->settings($owner)['enabled']);
        problem(503,fn()=> $app->enrollment->challenge(['installation_id'=>str_repeat('a',32),'key_fingerprint'=>str_repeat('b',64),'app_package'=>'com.arvio.tv.test'],'127.0.0.41'));
        $app->enrollment->setEnabled($owner,['enabled'=>true]);
        $app->db->query('INSERT INTO panel_resellers (panel_id,tenant_id,username,linked_by) VALUES (?,?,?,?),(?,?,?,?)',['9001',$tenantA['id'],'fixture-alpha',$owner['id'],'9002',$tenantB['id'],'fixture-beta',$owner['id']]);
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
    $tests['unlinked names and legacy proofs create no permanent enrollment or customer/device record'] = function ()use($app,$identity,$body,$panelClaim):void {
        $before=[];foreach(['devices','customers','enrollment_requests','enrollment_proofs','audit_events'] as $table)$before[$table]=(int)$app->db->query("SELECT COUNT(*) FROM $table")->fetchColumn();
        $result=$app->enrollment->register($body($identity(),$panelClaim('9999','2002')),'127.0.0.41');same('not_managed',$result['status']);check(!isset($result['request_id'],$result['poll_token']));
        same('not_managed',$app->enrollment->register($body($identity(),['panel'=>null]),'127.0.0.41')['status']);
        foreach($before as $table=>$count)same($count,(int)$app->db->query("SELECT COUNT(*) FROM $table")->fetchColumn());
    };
    $tests['signed customer identity cannot be reused for another account reference'] = function ()use($app,$identity,$body,$panelClaim):void {
        same('pending',$app->enrollment->register($body($identity(),$panelClaim('1001','2001')),'127.0.0.41')['status']);
    };
    $tests['automatic assignment never overrides rejected removed or manually cross-tenant identities'] = function ()use($app,$owner,$identity,$body,$panelClaim,$delivery,$approve,$register):void {
        $id=$identity();$r=$register($id);$app->enrollment->reject($owner,$r['request_id']);
        same('rejected',$app->enrollment->register($body($id,$panelClaim('1001','2003')),'127.0.0.41')['status']);
        $id=$identity();$r=$app->enrollment->register($body($id,$panelClaim('1001','2004')),'127.0.0.41');$d=$app->enrollment->poll($delivery($r),'127.0.0.41');
        $app->management->updateDevice($owner,$d['device_id'],['policy'=>'removed']);
        same('pending',$app->enrollment->register($body($id,$panelClaim('1001','2004')),'127.0.0.41')['status']);
        $id=$identity();$r=$register($id);$approve($r);
        problem(409,fn()=> $app->enrollment->register($body($id,$panelClaim('1001','2005')),'127.0.0.41'));
    };
    $tests['automatic enrollment enforces allocation and reseller suspension'] = function ()use($app,$owner,$newTenant,$bridge,$identity,$body,$panelClaim,$delivery):void {
        $t=$newTenant('limits',1);$app->enrollment->linkPanelReseller($owner,$t['id'],['username'=>'limited'],$bridge('1003','limited'));
        $id=$identity();$r=$app->enrollment->register($body($id,$panelClaim('1003','2006')),'127.0.0.41');same('approved',$r['status']);
        same('pending',$app->enrollment->register($body($identity(),$panelClaim('1003','2007')),'127.0.0.41')['status']);
        $app->management->updateTenant($owner,$t['id'],['status'=>'suspended','device_limit'=>10]);
        same('not_managed',$app->enrollment->register($body($identity(),$panelClaim('1003','2008')),'127.0.0.41')['status']);
    };
    $tests['a previously ignored device can enroll after its reseller is created and linked'] = function ()use($app,$owner,$newTenant,$bridge,$identity,$body,$panelClaim,$delivery):void {
        $id=$identity();$r=$app->enrollment->register($body($id,$panelClaim('1004','2009')),'127.0.0.41');same('not_managed',$r['status']);
        $t=$newTenant('retry');same('not_managed',$app->enrollment->register($body($id,$panelClaim('1004','2009')),'127.0.0.41')['status']);
        $app->enrollment->linkPanelReseller($owner,$t['id'],['username'=>'retry'],$bridge('1004','retry'));
        $again=$app->enrollment->register($body($id,$panelClaim('1004','2009')),'127.0.0.41');same('approved',$again['status']);check(isset($again['request_id']));
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
    $tests['removing reseller assignment moves the device owner-only without locking or revoking credentials'] = function ()use($app,$owner,$identity,$body,$panelClaim,$delivery):void {
        $id=$identity();$r=$app->enrollment->register($body($id,$panelClaim('1001','2011')),'127.0.0.41');$poll=$app->enrollment->poll($delivery($r),'127.0.0.41');$app->enrollment->poll($delivery($r),'127.0.0.41',true);
        $device=$app->management->device($owner,$poll['device_id']);
        $seller=['id'=>$owner['id'],'role'=>'reseller','tenant_id'=>$device['tenant_id']];
        $app->commands->checkIn($poll['credential'],['capabilities'=>['request_checkin']],'127.0.0.41');
        $command=$app->commands->enqueue($seller,$device['id'],['kind'=>'request_checkin'],bin2hex(random_bytes(16)));
        $lease=$app->commands->checkIn($poll['credential'],['capabilities'=>['request_checkin']],'127.0.0.41')['commands'][0]['lease_token'];
        problem(403,fn()=> $app->management->detachReseller($seller,$device['id']));
        $detached=$app->management->detachReseller($owner,$device['id']);same('active',$detached['policy']);check((bool)$detached['reseller_detached']);check($device['tenant_id']!==$detached['tenant_id']);
        problem(404,fn()=> $app->management->device($seller,$device['id']));problem(404,fn()=> $app->commands->listing($seller,$device['id'],[]));
        same('active',$app->commands->checkIn($poll['credential'],['capabilities'=>['request_checkin']],'127.0.0.41')['policy']);
        same('cancelled',$app->db->one('SELECT status FROM commands WHERE id=?',[$command['id']])['status']);
        same($detached['tenant_id'],$app->db->one('SELECT tenant_id FROM commands WHERE id=?',[$command['id']])['tenant_id']);
        problem(409,fn()=> $app->commands->acknowledge($poll['credential'],$command['id'],['lease_token'=>$lease,'status'=>'succeeded']));
        same($detached['id'],$app->management->detachReseller($owner,$device['id'])['id']);
        $renew=$app->enrollment->register($body($id,$panelClaim('1001','2011')),'127.0.0.41');
        same('approved',$renew['status']);$new=$app->enrollment->poll($delivery($renew),'127.0.0.41');same($detached['id'],$new['device_id']);same($detached['tenant_id'],$app->commands->deviceIdentity($new['credential'])['tenant_id']);
        // The panel account binding still belongs to the source reseller for
        // OTHER installations. Detaching one device never transfers the line.
        $other=$identity($id['account']);$sibling=$app->enrollment->register($body($other,$panelClaim('1001','2011')),'127.0.0.41');
        $s=$app->enrollment->poll($delivery($sibling),'127.0.0.41');same($device['tenant_id'],$app->commands->deviceIdentity($s['credential'])['tenant_id']);
        $source=$app->management->tenant($owner,$device['tenant_id']);
        $app->management->updateTenant($owner,$source['id'],['status'=>'suspended','device_limit'=>(int)$source['device_limit']]);
        try {same('active',$app->commands->checkIn($new['credential'],['capabilities'=>[]],'127.0.0.41')['policy']);}
        finally {$app->management->updateTenant($owner,$source['id'],['status'=>'active','device_limit'=>(int)$source['device_limit']]);}
    };
    $tests['detaching an already revoked device permits proof-based recovery without restoring old revoked tokens'] = function ()use($app,$owner,$identity,$body,$panelClaim,$delivery):void {
        $id=$identity();$r=$app->enrollment->register($body($id,$panelClaim('1001','2012')),'127.0.0.41');$poll=$app->enrollment->poll($delivery($r),'127.0.0.41');$app->enrollment->poll($delivery($r),'127.0.0.41',true);
        $app->management->updateDevice($owner,$poll['device_id'],['policy'=>'removed']);
        same('pending',$app->enrollment->register($body($id,$panelClaim('1001','2012')),'127.0.0.41')['status']);
        $app->management->detachReseller($owner,$poll['device_id']);problem(401,fn()=> $app->commands->deviceIdentity($poll['credential']));
        $again=$app->enrollment->register($body($id,$panelClaim('1001','2012')),'127.0.0.41');same('approved',$again['status']);
        $new=$app->enrollment->poll($delivery($again),'127.0.0.41');same('active',$app->commands->checkIn($new['credential'],['capabilities'=>[]],'127.0.0.41')['policy']);
    };
    $tests['read-only settings results are closed-schema leased and tenant scoped'] = function ()use($app,$owner,$alpha,$beta,$tenantA,$makeCustomer):void {
        $c=$makeCustomer($alpha,$tenantA['id']);$d=$app->management->createDevice($alpha,['customer_id'=>$c['id'],'label'=>'TEST snapshots']);$token=$app->commands->issueTestCredential($owner,$d['id']);
        $app->commands->checkIn($token,['capabilities'=>['get_settings','request_checkin']],'127.0.0.41');
        $command=$app->commands->enqueue($alpha,$d['id'],['kind'=>'get_settings'],bin2hex(random_bytes(16)));
        $lease=$app->commands->checkIn($token,['capabilities'=>['get_settings','request_checkin']],'127.0.0.41')['commands'][0]['lease_token'];
        $snapshot=['schema_version'=>'1','profile_ref'=>str_repeat('a',64),'sort_order'=>'provider','playlist_count'=>'2','enabled_playlist_count'=>'1','portal_count'=>'0','favourite_channel_count'=>'3','favourite_group_count'=>'1'];
        $input=['lease_token'=>$lease,'status'=>'succeeded','summary'=>'Read-only settings','snapshot'=>$snapshot];
        $missing=$input;unset($missing['snapshot']);problem(422,fn()=> $app->commands->acknowledge($token,$command['id'],$missing));
        $bad=$input;$bad['snapshot']['password']='must-not-store';problem(422,fn()=> $app->commands->acknowledge($token,$command['id'],$bad));
        $result=$app->commands->acknowledge($token,$command['id'],$input);same(2,$result['result']['snapshot']['playlist_count']);
        problem(404,fn()=> $app->commands->listing($beta,$d['id'],[]));
        same($snapshot['profile_ref'],$app->commands->listing($alpha,$d['id'],[])['items'][0]['result']['snapshot']['profile_ref']);
        problem(422,fn()=> ControlCenter\CommandSnapshots::validate('show_message',$snapshot));
        $snapshot['enabled_playlist_count']='3';problem(422,fn()=> ControlCenter\CommandSnapshots::validate('get_settings',$snapshot));
    };
    $tests['basic diagnostics reject secrets URLs unsupported packages and impossible counters'] = function ():void {
        $snapshot=['schema_version'=>'1','profile_ref'=>str_repeat('a',64),'app_package'=>'com.arvio.tv.test','app_version'=>'2.0.108-test','sdk'=>'36','storage_total'=>'1000','storage_free'=>'900','channel_count'=>'4000','guide_state'=>'loading'];
        same(4000,ControlCenter\CommandSnapshots::validate('get_diagnostics',$snapshot)['channel_count']);
        foreach([['storage_free'=>'2000'],['guide_state'=>'https://provider.test/password'],['app_version'=>'user:password'],['app_package'=>'foreign.app'],['sdk'=>'1']] as $override)problem(422,fn()=> ControlCenter\CommandSnapshots::validate('get_diagnostics',array_replace($snapshot,$override)));
        $snapshot['logs']='private raw text';problem(422,fn()=> ControlCenter\CommandSnapshots::validate('get_diagnostics',$snapshot));
    };
    $tests['extended diagnostics accept closed enums and reject partial payloads impossible heaps and private network data'] = function ():void {
        $snapshot=['schema_version'=>'1','profile_ref'=>str_repeat('a',64),'app_package'=>'com.arvio.tv.test','app_version'=>'2.0.112-test','sdk'=>'36','storage_total'=>'1000','storage_free'=>'900','channel_count'=>'4000','guide_state'=>'idle',
            'diagnostics_version'=>'2','heap_used'=>'100','heap_limit'=>'200','memory_state'=>'low','network_state'=>'unvalidated','network_transport'=>'vpn','network_metered'=>'yes'];
        $validated=ControlCenter\CommandSnapshots::validate('get_diagnostics',$snapshot);same(100,$validated['heap_used']);same('unvalidated',$validated['network_state']);
        foreach([['heap_used'=>'201'],['heap_limit'=>'0'],['heap_used'=>'-1'],['diagnostics_version'=>'3'],['memory_state'=>'https://private.test'],['network_state'=>'online'],['network_transport'=>'private-ssid'],['network_metered'=>'sometimes'],['ip_address'=>'127.0.0.1'],['raw_logs'=>'secret']] as $override)
            problem(422,fn()=> ControlCenter\CommandSnapshots::validate('get_diagnostics',array_replace($snapshot,$override)));
        foreach(['unknown','permission_denied','offline','validated','unvalidated','local_only'] as $state)same($state,ControlCenter\CommandSnapshots::validate('get_diagnostics',array_replace($snapshot,['network_state'=>$state]))['network_state']);
        unset($snapshot['heap_limit']);problem(422,fn()=> ControlCenter\CommandSnapshots::validate('get_diagnostics',$snapshot));
    };
    $tests['extended diagnostic receipts require the device lease and remain isolated after reseller removal'] = function ()use($app,$owner,$alpha,$beta,$tenantA,$makeCustomer):void {
        $c=$makeCustomer($alpha,$tenantA['id']);$d=$app->management->createDevice($alpha,['customer_id'=>$c['id'],'label'=>'TEST extended diagnostics']);$token=$app->commands->issueTestCredential($owner,$d['id']);
        $app->commands->checkIn($token,['capabilities'=>['get_diagnostics']],'127.0.0.41');
        $command=$app->commands->enqueue($alpha,$d['id'],['kind'=>'get_diagnostics'],bin2hex(random_bytes(16)));
        $lease=$app->commands->checkIn($token,['capabilities'=>['get_diagnostics']],'127.0.0.41')['commands'][0]['lease_token'];
        $snapshot=['schema_version'=>'1','profile_ref'=>str_repeat('a',64),'app_package'=>'com.arvio.tv.test','app_version'=>'2.0.112-test','sdk'=>'36','storage_total'=>'1000','storage_free'=>'900','channel_count'=>'4000','guide_state'=>'idle',
            'diagnostics_version'=>'2','heap_used'=>'100','heap_limit'=>'200','memory_state'=>'low','network_state'=>'permission_denied','network_transport'=>'unknown','network_metered'=>'unknown'];
        $input=['lease_token'=>$lease,'status'=>'succeeded','snapshot'=>$snapshot];
        problem(409,fn()=> $app->commands->acknowledge($token,$command['id'],array_replace($input,['lease_token'=>str_repeat('0',64)])));
        $bad=$input;$bad['snapshot']['ssid']='never-store';problem(422,fn()=> $app->commands->acknowledge($token,$command['id'],$bad));
        $result=$app->commands->acknowledge($token,$command['id'],$input);same('permission_denied',$result['result']['snapshot']['network_state']);same(100,$result['result']['snapshot']['heap_used']);
        problem(404,fn()=> $app->commands->listing($beta,$d['id'],[]));
        $app->management->detachReseller($owner,$d['id']);problem(404,fn()=> $app->commands->listing($alpha,$d['id'],[]));
        same('permission_denied',$app->commands->listing($owner,$d['id'],[])['items'][0]['result']['snapshot']['network_state']);
    };
    $tests['device live prebuffer is capability gated bounded tenant scoped and confirmed only by matching receipt'] = function ()use($app,$owner,$alpha,$beta,$tenantA,$makeCustomer):void {
        $c=$makeCustomer($alpha,$tenantA['id']);$d=$app->management->createDevice($alpha,['customer_id'=>$c['id'],'label'=>'TEST prebuffer']);$token=$app->commands->issueTestCredential($owner,$d['id']);
        $payload=['profile_ref'=>str_repeat('a',64),'expected_revision'=>str_repeat('b',64),'prebuffer_seconds'=>'5'];
        $input=['kind'=>'set_live_buffer','payload'=>$payload];
        problem(409,fn()=> $app->commands->enqueue($alpha,$d['id'],$input,bin2hex(random_bytes(16))));
        $app->commands->checkIn($token,['capabilities'=>['set_live_buffer','get_settings']],'127.0.0.41');
        problem(404,fn()=> $app->commands->enqueue($beta,$d['id'],$input,bin2hex(random_bytes(16))));
        foreach(['-1','1','60','999','https://private.test'] as $invalid)problem(422,fn()=> $app->commands->enqueue($alpha,$d['id'],['kind'=>'set_live_buffer','payload'=>array_replace($payload,['prebuffer_seconds'=>$invalid])],bin2hex(random_bytes(16))));
        problem(422,fn()=> $app->commands->enqueue($alpha,$d['id'],['kind'=>'set_live_buffer','payload'=>$payload+['buffer_bytes'=>'999999999']],bin2hex(random_bytes(16))));
        $command=$app->commands->enqueue($alpha,$d['id'],$input,bin2hex(random_bytes(16)));same('pending',$command['status']);same(null,$command['result']);
        $lease=$app->commands->checkIn($token,['capabilities'=>['set_live_buffer','get_settings']],'127.0.0.41')['commands'][0]['lease_token'];
        $receipt=['lease_token'=>$lease,'status'=>'succeeded'];problem(422,fn()=> $app->commands->acknowledge($token,$command['id'],$receipt));
        $snapshot=['schema_version'=>'1','profile_ref'=>str_repeat('a',64),'sort_order'=>'provider','playlist_count'=>'1','enabled_playlist_count'=>'1','portal_count'=>'0','favourite_channel_count'=>'3','favourite_group_count'=>'1','live_prebuffer_seconds'=>'5','live_buffer_revision'=>str_repeat('c',64)];
        $bad=$snapshot;$bad['live_prebuffer_seconds']='2';problem(422,fn()=> $app->commands->acknowledge($token,$command['id'],$receipt+['snapshot'=>$bad]));
        $bad=$snapshot;$bad['profile_ref']=str_repeat('d',64);problem(422,fn()=> $app->commands->acknowledge($token,$command['id'],$receipt+['snapshot'=>$bad]));
        $result=$app->commands->acknowledge($token,$command['id'],$receipt+['snapshot'=>$snapshot]);same('5',$result['result']['snapshot']['live_prebuffer_seconds']);
        unset($snapshot['live_buffer_revision']);problem(422,fn()=> ControlCenter\CommandSnapshots::validate('get_settings',$snapshot));
    };
    $tests['HTTP reseller detachment is owner-only CSRF-protected and immediately removes old reseller reads'] = function ()use($app,$owner,$alpha,$tenantA,$makeCustomer,$http,$password):void {
        $customer=$makeCustomer($alpha,$tenantA['id']);$device=$app->management->createDevice($alpha,['customer_id'=>$customer['id'],'label'=>'TEST HTTP detach']);
        $o=$http->request('POST','/api/login',['email'=>'owner@example.test','password'=>$password],[],'detach-owner')['body'];
        $a=$http->request('POST','/api/login',['email'=>'alpha@example.test','password'=>$password],[],'detach-alpha')['body'];
        $path='/api/devices/'.$device['id'].'/detach-reseller';
        same(403,$http->request('POST',$path,new stdClass(),[],'detach-owner')['status']);
        same(403,$http->request('POST',$path,new stdClass(),['X-CSRF-Token'=>$a['csrf']],'detach-alpha')['status']);
        $response=$http->request('POST',$path,new stdClass(),['X-CSRF-Token'=>$o['csrf']],'detach-owner');same(200,$response['status']);same('active',$response['body']['policy']);same(true,$response['body']['reseller_detached']);
        same(404,$http->request('GET','/api/devices/'.$device['id'],null,[],'detach-alpha')['status']);
        same(404,$http->request('GET','/api/devices/'.$device['id'].'/commands',null,[],'detach-alpha')['status']);
    };
    $tests['unmatched HTTP enrollment returns ignored status without a request or audit record'] = function ()use($app,$identity,$body,$panelClaim,$http):void {
        $id=$identity();$input=$body($id,array_replace($panelClaim('999991','888881'),['aud'=>$http->origin]));$before=(int)$app->db->query('SELECT COUNT(*) FROM audit_events')->fetchColumn();
        $response=$http->request('POST','/api/device/enrollment/register',$input);
        same(200,$response['status']);same('not_managed',$response['body']['status']);same(3600,$response['body']['retry_after_seconds']);
        check(!isset($response['body']['request_id'],$response['body']['poll_token']));
        same(0,(int)$app->db->query('SELECT COUNT(*) FROM enrollment_requests WHERE installation_id=?',[$id['install']])->fetchColumn());
        same(0,(int)$app->db->query('SELECT COUNT(*) FROM enrollment_challenges WHERE nonce_hash=?',[hash('sha256',$input['challenge'])])->fetchColumn());
        same($before,(int)$app->db->query('SELECT COUNT(*) FROM audit_events')->fetchColumn());
    };
    $tests['legacy unassigned requests disappear and are pruned without deleting approved inventory'] = function ()use($app,$owner,$register,$identity,$delivery,$approve):void {
        $r=$register($identity());$before=$app->enrollment->listing($owner,['status'=>'pending'])['total'];$app->db->query('UPDATE enrollment_requests SET panel_reseller_id=NULL,poll_expires_at=UTC_TIMESTAMP() WHERE id=?',[$r['request_id']]);
        $rows=$app->enrollment->listing($owner,['status'=>'pending']);same($before-1,$rows['total']);check(!in_array($r['request_id'],array_column($rows['items'],'id'),true));
        problem(401,fn()=> $app->enrollment->poll($delivery($r),'127.0.0.41'));
        $kept=$register($identity());$d=$approve($kept);$app->db->query('UPDATE enrollment_requests SET panel_reseller_id=NULL WHERE id=?',[$kept['request_id']]);
        $app->enrollment->maintain();
        same(null,$app->db->one('SELECT id FROM enrollment_requests WHERE id=?',[$r['request_id']]));
        same($d['id'],$app->management->device($owner,$d['id'])['id']);check($app->db->one('SELECT id FROM enrollment_requests WHERE id=?',[$kept['request_id']])!==null);
    };
    $tests['owner-held unassigned device can renew but does not admit another unlinked installation'] = function ()use($app,$owner,$identity,$body,$panelClaim,$delivery):void {
        $id=$identity();$r=$app->enrollment->register($body($id,$panelClaim('1001','2111')),'127.0.0.41');$p=$app->enrollment->poll($delivery($r),'127.0.0.41');$app->enrollment->poll($delivery($r),'127.0.0.41',true);
        $app->management->detachReseller($owner,$p['device_id']);
        same('approved',$app->enrollment->register($body($id,['panel'=>null]),'127.0.0.41')['status']);
        same('not_managed',$app->enrollment->register($body($identity($id['account']),['panel'=>null]),'127.0.0.41')['status']);
    };
    $tests['new-device setup is assigned once after verified admission and never for unmatched attempts'] = function()use($app,$alpha,$tenantA,$identity,$body,$delivery,$register,$approve):void {
        $profile=$app->deviceProfiles->save($alpha,null,['name'=>'TEST admitted default','prebuffer_seconds'=>'2']);$revision=(int)($app->deviceProfiles->policy($alpha,$tenantA['id'])['policy']['revision']??0);$app->deviceProfiles->setPolicy($alpha,['profile_id'=>$profile['id'],'profile_version'=>1,'enabled'=>true,'expected_revision'=>$revision]);
        $id=$identity();$r=$app->enrollment->register($body($id),'127.0.0.41');same('approved',$r['status']);$p=$app->enrollment->poll($delivery($r),'127.0.0.41');$jobs=$app->deviceProfiles->jobs($alpha,['device_id'=>$p['device_id']]);same(1,$jobs['total']);same(1,(int)$jobs['items'][0]['profile_version']);
        $app->enrollment->register($body($id),'127.0.0.41');same(1,$app->deviceProfiles->jobs($alpha,['device_id'=>$p['device_id']])['total']);$manual=$register($identity());$approved=$approve($manual);same(1,$app->deviceProfiles->jobs($alpha,['device_id'=>$approved['id']])['total']);$before=(int)$app->db->query('SELECT COUNT(*) FROM device_setup_jobs')->fetchColumn();same('not_managed',$app->enrollment->register($body($identity(),['panel'=>null]),'127.0.0.41')['status']);same($before,(int)$app->db->query('SELECT COUNT(*) FROM device_setup_jobs')->fetchColumn());
        $app->deviceProfiles->setPolicy($alpha,['profile_id'=>$profile['id'],'profile_version'=>1,'enabled'=>false,'expected_revision'=>$revision+1]);same('paused',$app->deviceProfiles->detail($alpha,$jobs['items'][0]['id'])['status']);
    };
    $tests['line usernames are cryptographically bound to the activation account not a reported device label'] = function()use($app,$owner,$alpha,$identity,$register,$approve,$delivery,$proofs):void {
        $username='verified-line-user';$ref=hash_hmac('sha256',"account-v1\n".$username,hex2bin($proofs->bridgeSecret()));
        $r=$register($identity($ref));$device=$approve($r);$p=$app->enrollment->poll($delivery($r),'127.0.0.41');
        $app->commands->checkIn($p['credential'],['account_username'=>'spoofed-other-line','capabilities'=>[]],'127.0.0.41');
        same(null,$app->db->one('SELECT username FROM verified_line_usernames WHERE account_ref=?',[$ref]));
        $app->commands->checkIn($p['credential'],['account_username'=>$username,'capabilities'=>[]],'127.0.0.41');
        same($username,$app->management->device($alpha,$device['id'])['customer_name']);
        $customers=$app->management->listing($alpha,'customers',['search'=>$username]);same(1,$customers['total']);same($username,$customers['items'][0]['line_username']);
        same(1,$app->management->listing($alpha,'devices',['search'=>$username])['total']);
        $app->management->detachReseller($owner,$device['id']);same($username,$app->management->device($owner,$device['id'])['customer_name']);problem(404,fn()=> $app->management->device($alpha,$device['id']));
    };
}
