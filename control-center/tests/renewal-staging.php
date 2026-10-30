<?php
declare(strict_types=1);
function renewalStagingCases(array &$tests,ControlCenter\Application $app,ControlCenter\Config $config,array $owner,array $alpha,array $beta,array $tenantA,array $tenantB):void {
    $simulationConfig=new ControlCenter\Config($config->root,array_replace($config->values,['renewal_payments'=>['simulation_enabled'=>true],'renewal_worker'=>['shared_secret'=>str_repeat('a',64)]]));
    $secrets=new ControlCenter\Secrets($simulationConfig);
    $gateway=new ControlCenter\RampexGateway($simulationConfig,$app->db,$secrets);
    $worker=new ControlCenter\RenewalWorker($simulationConfig,(new ControlCenter\RenewalSimulation($app->db,$simulationConfig))->transport());
    $settings=new ControlCenter\RenewalSettings($app->db,$simulationConfig,$app->management,$app->auth,$secrets,$gateway,$worker);
    $tests['renewals default off and reseller cannot change rollout policy']=function()use($app,$owner,$alpha):void{
        same(['enabled'=>false,'testing_only'=>true],$app->renewalSettings->policy());
        problem(403,fn()=> $app->renewalSettings->setPolicy($alpha,['enabled'=>true,'testing_only'=>true]));
        same(['enabled'=>true,'testing_only'=>true],$app->renewalSettings->setPolicy($owner,['enabled'=>true,'testing_only'=>true]));
        $app->renewalSettings->setPolicy($owner,['enabled'=>false,'testing_only'=>true]);
    };
    $tests['renewals reject generic packages even under enabled rollout']=function()use($app,$owner):void{
        $app->renewalSettings->setPolicy($owner,['enabled'=>true,'testing_only'=>false]);
        try{foreach(['com.arvio.tv','com.arvio.tv.beta','com.arvio.tv.test'] as $package)problem(403,fn()=> $app->renewalPayments->challenge(['device_id'=>str_repeat('a',32),'app_package'=>$package,'key_fingerprint'=>str_repeat('b',64),'primary_fingerprint'=>str_repeat('c',64)],'127.0.0.99'));}
        finally{$app->renewalSettings->setPolicy($owner,['enabled'=>false,'testing_only'=>true]);}
    };
    $tests['renewal live and simulation APIs remain separately gated']=function()use($app,$gateway,$config,$secrets):void{
        problem(503,fn()=> $gateway->validate(str_repeat('x',32),'live'));
        $production=new ControlCenter\Config($config->root,array_replace($config->values,['environment'=>'production','renewal_payments'=>['simulation_enabled'=>true]]));
        $blocked=new ControlCenter\RampexGateway($production,$app->db,$secrets);
        same(false,$blocked->mockAllowed());problem(422,fn()=> $blocked->validate('mock_abcdefghijklmnop','mock'));
        same(true,$gateway->mockAllowed());
    };
    $tests['renewal merchant credentials are write-only scoped and rotation preserves old secrets']=function()use($app,$settings,$alpha,$beta,$tenantA,$tenantB,$secrets):void{
        $input=['api_key'=>'mock_abcdefghijklmnop','webhook_secret'=>'Synthetic-Renewal-Secret-Only','mode'=>'mock'];
        $saved=$settings->saveConnection($alpha,$input);$id=$saved['connection']['id'];
        check(!str_contains(json_encode($saved),$input['api_key']));check(!str_contains(json_encode($saved),$input['webhook_secret']));
        $row=$app->db->one('SELECT * FROM renewal_connections WHERE id=?',[$id]);same($input['api_key'],$secrets->decrypt($row['api_cipher']));check($row['api_cipher']!==$input['api_key']);
        problem(404,fn()=> $settings->status($beta,['tenant_id'=>$tenantA['id']]));
        problem(409,fn()=> $settings->saveConnection($beta,$input));
        $new=$settings->saveConnection($alpha,array_replace($input,['webhook_secret'=>'Synthetic-Rotated-Secret-Only']));
        check($id!==$new['connection']['id']);same(0,(int)$app->db->one('SELECT enabled FROM renewal_connections WHERE id=?',[$id])['enabled']);
        same($input['webhook_secret'],$secrets->decrypt($app->db->one('SELECT webhook_cipher FROM renewal_connections WHERE id=?',[$id])['webhook_cipher']));
    };
    $tests['renewal simulated worker verifies original fingerprint and immutable package']=function()use($app,$worker,$tenantA):void{
        $r=$app->db->one('SELECT panel_id FROM panel_resellers WHERE tenant_id=?',[$tenantA['id']]);$id=$r['panel_id'];
        $p=['id'=>777,'name'=>'Demo monthly','duration'=>1,'period'=>'months','max_connections'=>1,'fingerprint'=>str_repeat('d',64),'funded'=>true];
        $app->db->query('INSERT INTO renewal_simulated_targets (reseller_id,line_id,primary_fingerprint,expires_at,plans,package_id) VALUES (?,?,?,?,?,?)',[$id,'987654',str_repeat('e',64),time()-60,json_encode([$p]),777]);
        same([$p],$worker->catalog($id));same('expired',$worker->eligibility($id,'987654',str_repeat('e',64))['state']);
        same('unavailable',$worker->eligibility($id,'987654',str_repeat('f',64))['state']);
        $spec=['operation_id'=>str_repeat('a',32),'brand_id'=>str_repeat('b',32),'reseller_id'=>$id,'line_id'=>'987654','package_id'=>777,'plan_fingerprint'=>str_repeat('d',64),'merchant_id'=>'demo','payment_link_id'=>'demo-link','primary_fingerprint'=>str_repeat('e',64),'expected_expiry'=>(int)$app->db->one('SELECT expires_at FROM renewal_simulated_targets WHERE reseller_id=? AND line_id=?',[$id,'987654'])['expires_at']];
        $first=$worker->renew($spec);same('fulfilled',$first['state']);same($first,$worker->renew($spec));
        same(1,(int)$app->db->query('SELECT COUNT(*) FROM renewal_simulated_operations WHERE operation_id=?',[$spec['operation_id']])->fetchColumn());
        problem(409,fn()=> $worker->renew(array_replace($spec,['payment_link_id'=>'different'])));
    };
    $tests['only money pricing for offered packages is editable and revision scoped']=function()use($settings,$alpha,$beta,$tenantA):void{
        $input=['package_id'=>777,'currency'=>'USD','amount'=>'10.50','enabled'=>true];
        foreach(['name'=>'Custom plan','duration'=>99,'max_connections'=>99,'credits'=>0] as $key=>$value)problem(422,fn()=> $settings->savePrice($alpha,$input+[$key=>$value]));
        $result=$settings->savePrice($alpha,$input);$p=$result['prices'][0];same(1050,$p['amount_minor']);same(1,$p['revision']);same('Demo monthly',$p['name']);
        $edit=$input+['id'=>$p['id'],'expected_revision'=>1];$result=$settings->savePrice($alpha,$edit);same(2,$result['prices'][0]['revision']);
        problem(409,fn()=> $settings->savePrice($alpha,$edit));
        problem(409,fn()=> $settings->savePrice($alpha,$input)); // No duplicate price per package.
        problem(404,fn()=> $settings->status($beta,['tenant_id'=>$tenantA['id']]));
        problem(409,fn()=> $settings->savePrice($alpha,array_replace($input,['package_id'=>999])));
    };
    $tests['renewal worker rejects unsigned or stale status replies']=function()use($simulationConfig):void{
        $worker=new ControlCenter\RenewalWorker($simulationConfig,static fn()=>json_encode(['payload'=>'{}','signature'=>str_repeat('0',64)]));
        problem(503,fn()=> $worker->catalog('101'));
        $worker=new ControlCenter\RenewalWorker($simulationConfig,static function($action,$raw,$headers):string{
            $h=[];foreach($headers as $line){[$k,$v]=explode(': ',$line,2);$h[$k]=$v;}
            $payload=json_encode(['reseller_id'=>'101','line_id'=>'999','primary_match'=>true,'state'=>'expired','checked_at'=>time()-100,'expires_at'=>time()-200,'plans'=>[]]);
            return json_encode(['payload'=>$payload,'signature'=>hash_hmac('sha256',"branded-renewal-result-v1\n".$h['X-Renewal-Timestamp']."\n".$h['X-Renewal-Nonce']."\n".$payload,str_repeat('a',64))]);
        });
        problem(503,fn()=> $worker->eligibility('101','999',str_repeat('a',64)));
    };
    $tests['renewal login signature has a native-compatible domain and binds identity without raw secrets']=static function():void{
        $text=ControlCenter\RenewalPayments::loginSigningText('nonce','install','brand','key',['activation_code'=>'123456']);
        same("ECRENEWLOGIN1\nnonce\ninstall\nbrand\nkey\nc9621e3692279fb32c54f841fa993e72d62c601bfd4e702c825b8d2e7f46ac8c",$text);
        check(!str_contains($text,'123456'));
        check($text!==ControlCenter\RenewalPayments::loginSigningText('nonce','other','brand','key',['activation_code'=>'123456']));
        check(ControlCenter\RenewalPayments::loginSigningText('n','i','b','k',['username'=>"a\nb",'password'=>'c'])!==ControlCenter\RenewalPayments::loginSigningText('n','i','b','k',['username'=>'a','password'=>"b\nc"]));
    };
    $tests['signed renewal login cannot select another reseller target or smuggle account credentials']=function()use($simulationConfig):void{
        $valid=['reseller_id'=>'101','line_id'=>'999','primary_fingerprint'=>str_repeat('a',64)];
        foreach([array_replace($valid,['reseller_id'=>'102']),array_replace($valid,['line_id'=>999]),$valid+['password'=>'MUST-NOT-PASS']] as $reply){
            $reader=new ControlCenter\RenewalWorker($simulationConfig,static function($action,$raw,$headers)use($reply):string{
                $h=[];foreach($headers as $line){[$k,$v]=explode(': ',$line,2);$h[$k]=$v;}$payload=json_encode($reply);
                return json_encode(['payload'=>$payload,'signature'=>hash_hmac('sha256',"branded-renewal-result-v1\n".$h['X-Renewal-Timestamp']."\n".$h['X-Renewal-Nonce']."\n".$payload,str_repeat('a',64))]);
            });
            problem(503,fn()=> $reader->identify('101',['activation_code'=>'123456']));
        }
    };
    $tests['signed eligibility cannot omit the current package or offer an alternate package']=function()use($simulationConfig):void{
        $base=['reseller_id'=>'101','line_id'=>'999','primary_match'=>true,'state'=>'expired','checked_at'=>time(),'expires_at'=>time()-60,'plans'=>[]];
        $p=['id'=>8,'name'=>'Other offered package','duration'=>1,'period'=>'months','max_connections'=>1,'fingerprint'=>str_repeat('d',64),'funded'=>true];
        foreach([$base,array_replace($base,['current_package_id'=>7,'plans'=>[$p]])] as $reply){
            $transport=static function($action,$raw,$headers)use($reply):string{
                $h=[];foreach($headers as $line){[$k,$v]=explode(': ',$line,2);$h[$k]=$v;}$payload=json_encode($reply);
                return json_encode(['payload'=>$payload,'signature'=>hash_hmac('sha256',"branded-renewal-result-v1\n".$h['X-Renewal-Timestamp']."\n".$h['X-Renewal-Nonce']."\n".$payload,str_repeat('a',64))]);
            };
            $reader=new ControlCenter\RenewalWorker($simulationConfig,$transport);
            problem(503,fn()=> $reader->eligibility('101','999',str_repeat('a',64)));
        }
    };
    $checkoutFlow=function(array $alpha,array $tenantA,string $lineId,bool $lostReply=false,bool $login=false)use($app,$simulationConfig,$owner,$settings,$gateway,$worker,$secrets):void{
        $artifacts=new ControlCenter\BrandArtifacts($app->db,$secrets,$simulationConfig);
        $brand=$app->db->one('SELECT * FROM app_brands WHERE tenant_id=?',[$tenantA['id']]);check($brand!==null);
        $app->db->query('UPDATE app_brands SET enabled=1 WHERE id=?',[$brand['id']]);
        $build=ControlCenter\Database::id();$revision=(int)$brand['version'];
        $manifest=null;foreach($app->db->all('SELECT manifest FROM app_brand_builds WHERE brand_id=? ORDER BY created_at,id',[$brand['id']]) as $old){$candidate=json_decode($old['manifest'],true);if(isset($candidate['brand_id'],$candidate['assets'],$candidate['package_name'])){$manifest=$candidate;break;}}
        check($manifest!==null,'Published test fixture needs a complete cosmetic snapshot');
        $manifest=array_replace($manifest,['app_version'=>'2.0.126','app_version_code'=>442,'brand_version'=>$revision]);
        if(!$login){
        $app->db->query('INSERT INTO app_brand_builds (id,brand_id,brand_version,app_version,manifest,requested_by,build_channel) VALUES (?,?,?,?,?,?,?)',[$build,$brand['id'],$revision,'2.0.126',json_encode($manifest),$owner['id'],'testing']);
        $artifacts->recordVerified($build,['channel'=>'testing','package_name'=>$brand['package_name'].'.test','version_name'=>'2.0.126.'.$revision.'-test','version_code'=>44200000+$revision,'trial_protocol'=>1,'signer_sha256'=>'3273d0b0294a689565ab4b0c8ef7e1887e2ca680240718f5521628b5c46328b7','apk_sha256'=>str_repeat('a',64),'artifact_url'=>'https://github.com/derangedchief-crypto/ARVIO/releases/download/brand-'.$brand['id'].'-testing/Brand-synthetic.apk']);
        }
        $artifact=$artifacts->approved($brand['id'],'testing');check($artifact!==null);
        $customer=$app->management->createCustomer($alpha,['name'=>'TEST renewal customer','notes'=>'Keep these notes']);
        $device=$app->management->createDevice($alpha,['customer_id'=>$customer['id'],'label'=>'TEST renewal TV']);
        $private=openssl_pkey_new(['private_key_type'=>OPENSSL_KEYTYPE_EC,'curve_name'=>'prime256v1']);
        $public=preg_replace('~-----[^\n]+-----|\s~','',openssl_pkey_get_details($private)['key']);$key=hash('sha256',base64_decode($public));
        $install=ControlCenter\Database::id();$account=bin2hex(random_bytes(32));$primary=hash('sha256',"demo-user\ndemo-pass");$package=$artifact['package_name'];
        $reseller=$app->db->one('SELECT panel_id FROM panel_resellers WHERE tenant_id=?',[$tenantA['id']])['panel_id'];
        $app->db->query('UPDATE devices SET installation_id=?,key_fingerprint=?,app_package=? WHERE id=?',[$install,$key,$package,$device['id']]);
        $app->db->query('INSERT INTO panel_customers (panel_id,account_ref,customer_id,tenant_id,reseller_id) VALUES (?,?,?,?,?)',[$lineId,$account,$customer['id'],$tenantA['id'],$reseller]);
        $app->db->query("INSERT INTO enrollment_requests (id,installation_id,key_fingerprint,public_key,account_ref,account_hint,app_package,label,model,os_version,app_version,status,poll_hash,poll_expires_at,device_id,panel_customer_id,panel_reseller_id) VALUES (?,?,?,?,?,?,?,'TV','Demo','Test','test','approved',?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR),?,?,?)",[ControlCenter\Database::id(),$install,$key,$public,$account,'demo',$package,str_repeat('a',64),$device['id'],$lineId,$reseller]);
        $plan=$worker->catalog($reseller)[0]??['id'=>777,'name'=>'Demo monthly','duration'=>1,'period'=>'months','max_connections'=>1,'fingerprint'=>str_repeat('d',64),'funded'=>true];
        $other=['id'=>778,'name'=>'Demo yearly','duration'=>12,'period'=>'months','max_connections'=>3,'fingerprint'=>str_repeat('8',64),'funded'=>true];
        $app->db->query('INSERT INTO renewal_simulated_targets (reseller_id,line_id,primary_fingerprint,expires_at,plans,package_id) VALUES (?,?,?,?,?,?)',[$reseller,$lineId,$primary,time()-60,json_encode([$plan,$other]),$plan['id']]);
        if(!$settings->connection($tenantA['id']))$settings->saveConnection($alpha,['api_key'=>'mock_'.$tenantA['id'],'webhook_secret'=>'Synthetic-Owner-Renewal-Secret','mode'=>'mock']);
        if(!$settings->status($alpha,[])['prices'])$settings->savePrice($alpha,['package_id'=>777,'currency'=>'USD','amount'=>'10.50','enabled'=>true]);
        if(!$app->db->one('SELECT id FROM renewal_plans WHERE tenant_id=? AND package_id=778',[$tenantA['id']]))$settings->savePrice($alpha,['package_id'=>778,'currency'=>'USD','amount'=>'99.00','enabled'=>true]);
        $otherPrice=array_values(array_filter($settings->status($alpha,[])['prices'],static fn($p)=>$p['package_id']===778))[0];
        $checkoutWorker=$worker;
        if($login){
            $base=(new ControlCenter\RenewalSimulation($app->db,$simulationConfig))->transport();
            $checkoutWorker=new ControlCenter\RenewalWorker($simulationConfig,static function($action,$raw,$headers)use($base,$reseller,$lineId,$primary):string{
                if($action!=='identify')return $base($action,$raw,$headers);
                $v=json_decode($raw,true);same($reseller,$v['reseller_id']);same('123456',$v['activation_code']);
                $h=[];foreach($headers as $line){[$k,$value]=explode(': ',$line,2);$h[$k]=$value;}
                $payload=json_encode(['reseller_id'=>$reseller,'line_id'=>$lineId,'primary_fingerprint'=>$primary]);
                return json_encode(['payload'=>$payload,'signature'=>hash_hmac('sha256',"branded-renewal-result-v1\n".$h['X-Renewal-Timestamp']."\n".$h['X-Renewal-Nonce']."\n".$payload,str_repeat('a',64))]);
            });
            $app->db->query('DELETE FROM enrollment_requests WHERE device_id=?',[$device['id']]);
            $app->db->query('UPDATE devices SET installation_id=?,key_fingerprint=?,app_package=? WHERE id=?',[ControlCenter\Database::id(),bin2hex(random_bytes(32)),$package,$device['id']]);
            $app->db->query('DELETE FROM panel_customers WHERE panel_id=?',[$lineId]);
        }
        $payments=new ControlCenter\RenewalPayments($app->db,$simulationConfig,$app->auth,$app->management,$secrets,$artifacts,$settings,$gateway,$checkoutWorker);
        $refreshed=$plan;$refreshed['credits']='3';$refreshed['fingerprint']=str_repeat('e',64);
        $app->db->query('UPDATE renewal_simulated_targets SET plans=? WHERE reseller_id=? AND line_id=?',[json_encode([$refreshed,$other]),$reseller,$lineId]);
        $settings->setPolicy($owner,['enabled'=>true,'testing_only'=>true]);
        try{
            if($login){
                $counts=$app->db->one('SELECT (SELECT COUNT(*) FROM devices) AS devices,(SELECT COUNT(*) FROM enrollment_requests) AS requests,(SELECT COUNT(*) FROM device_credentials) AS credentials');
                problem(403,fn()=> $payments->loginChallenge(['installation_id'=>$install,'app_package'=>'com.arvio.tv.test','key_fingerprint'=>$key],'127.0.0.91'));
                $body=['installation_id'=>$install,'app_package'=>$package,'key_fingerprint'=>$key];
                $challenge=$payments->loginChallenge($body,'127.0.0.91');$identity=['activation_code'=>'123456'];
                openssl_sign(ControlCenter\RenewalPayments::loginSigningText($challenge['challenge'],$install,$package,$key,$identity,'US'),$signature,$private,OPENSSL_ALGO_SHA256);
                $open=['installation_id'=>$install,'app_package'=>$package,'challenge'=>$challenge['challenge'],'public_key'=>$public,'identity'=>$identity,'signature'=>base64_encode($signature),'country'=>'US'];
                problem(403,fn()=> $payments->loginOpen(array_replace($open,['identity'=>['activation_code'=>'654321']]),'127.0.0.91'));
                problem(403,fn()=> $payments->loginOpen(array_replace($open,['country'=>'CA']),'127.0.0.91'));
                $withoutCountry=$open;unset($withoutCountry['country']);same('country_required',$payments->loginOpen($withoutCountry,'127.0.0.91')['reason']);
                $session=$payments->loginOpen($open,'127.0.0.91');
                problem(403,fn()=> $payments->loginOpen($open,'127.0.0.91'));
                same(true,$session['available']);same(true,$session['simulated']);
                $row=$app->db->one('SELECT * FROM renewal_sessions WHERE token_hash=?',[$secrets->mac("renewal-checkout-token-v1\n".$session['session_token'])]);
                same(null,$row['device_id']);same($install,$row['login_installation_id']);same($public,$row['login_public_key']);
                same($counts,$app->db->one('SELECT (SELECT COUNT(*) FROM devices) AS devices,(SELECT COUNT(*) FROM enrollment_requests) AS requests,(SELECT COUNT(*) FROM device_credentials) AS credentials'));
                check(!str_contains(json_encode($session),'123456'));check(!isset($session['credential'],$session['username'],$session['password']));
            }else{
            $body=['device_id'=>$device['id'],'app_package'=>$package,'key_fingerprint'=>$key,'primary_fingerprint'=>$primary];
            $challenge=$payments->challenge($body,'127.0.0.98');same(true,$challenge['available']);
            openssl_sign(ControlCenter\RenewalPayments::signingText($challenge['challenge'],$device['id'],$package,$key,$primary,'US'),$signature,$private,OPENSSL_ALGO_SHA256);
            $open=$body+['challenge'=>$challenge['challenge'],'signature'=>base64_encode($signature),'country'=>'US'];
            problem(403,fn()=> $payments->open(array_replace($open,['country'=>'CA']),'127.0.0.98'));
            $session=$payments->open($open,'127.0.0.98');same(true,$session['available']);same(true,$session['simulated']);
            problem(403,fn()=> $payments->open($open,'127.0.0.98'));
            }
            $token=$session['session_token'];$info=$payments->info($token);check(!array_key_exists('plans',$info));$p=$info['renewal'];same(777,$p['package_id']);
            same(1050,$p['amount_minor']);same(str_repeat('e',64),$p['package_fingerprint']);check(!isset($p['package_snapshot'])); // Current panel cost can change without inventing/re-saving a reseller plan.
            problem(422,fn()=> $settings->savePrice($alpha,['id'=>$p['id'],'expected_revision'=>$p['revision'],'package_id'=>778,'currency'=>'USD','amount'=>'12.50','enabled'=>true]));
            $checkout=['session_token'=>$token,'price_id'=>$p['id'],'price_revision'=>$p['revision'],'package_fingerprint'=>$p['package_fingerprint'],'email'=>'receipt@example.test'];
            $count=(int)$app->db->query('SELECT COUNT(*) FROM renewal_orders')->fetchColumn();$withoutEmail=$checkout;unset($withoutEmail['email']);
            problem(422,fn()=> $payments->checkout($withoutEmail,'127.0.0.98'));
            same($count,(int)$app->db->query('SELECT COUNT(*) FROM renewal_orders')->fetchColumn());
            problem(422,fn()=> $payments->checkout($checkout+['package_id'=>778],'127.0.0.98'));
            problem(409,fn()=> $payments->checkout(array_replace($checkout,['package_fingerprint'=>str_repeat('d',64)]),'127.0.0.98'));
            problem(409,fn()=> $payments->checkout(array_replace($checkout,['price_id'=>$otherPrice['id'],'price_revision'=>$otherPrice['revision']]),'127.0.0.98'));
            $app->db->query('UPDATE renewal_simulated_targets SET package_id=778 WHERE reseller_id=? AND line_id=?',[$reseller,$lineId]);
            try{problem(409,fn()=> $payments->checkout($checkout,'127.0.0.98'));}
            finally{$app->db->query('UPDATE renewal_simulated_targets SET package_id=777 WHERE reseller_id=? AND line_id=?',[$reseller,$lineId]);}
            if($login)$app->db->query('UPDATE devices SET installation_id=?,key_fingerprint=?,app_package=? WHERE id=?',[$install,$key,$package,$device['id']]);
            $app->db->query("UPDATE devices SET policy='blocked' WHERE id=?",[$device['id']]);
            try{problem(403,fn()=> $payments->checkout($checkout,'127.0.0.98'));}
            finally{$app->db->query("UPDATE devices SET policy='active' WHERE id=?",[$device['id']]);}
            $order=$payments->checkout($checkout,'127.0.0.98')['order'];same('awaiting',$order['payment_state']);
            $app->db->query('UPDATE renewal_orders SET expires_at=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 MINUTE) WHERE id=?',[$order['id']]);
            same($order['id'],$payments->checkout($checkout,'127.0.0.98')['order']['id']);
            same(1,(int)$app->db->query('SELECT COUNT(*) FROM renewal_orders WHERE session_id=(SELECT id FROM renewal_sessions WHERE token_hash=?)',[$secrets->mac("renewal-checkout-token-v1\n$token")])->fetchColumn());
            $app->db->query('UPDATE renewal_orders SET expires_at=DATE_ADD(UTC_TIMESTAMP(),INTERVAL 30 MINUTE) WHERE id=?',[$order['id']]);
            $connection=$settings->connection($tenantA['id']);problem(401,fn()=> $payments->webhook($connection['id'],'{}',str_repeat('0',64)));
            $expiredOrder=null;
            if(!$lostReply){
                $expiredOrder=$order['id'];$ref=$app->db->one('SELECT provider_link_id FROM renewal_orders WHERE id=?',[$expiredOrder])['provider_link_id'];
                $app->db->query("UPDATE renewal_simulations SET status='expired' WHERE link_id=?",[$ref]);
                $raw=json_encode(['event'=>'payment.expired','status'=>'expired','link_id'=>$ref]);
                $payments->webhook($connection['id'],$raw,hash_hmac('sha256',$raw,$secrets->decrypt($connection['webhook_cipher'])));same(true,$payments->processOne()['worked']);same('expired',$app->db->one('SELECT payment_state FROM renewal_orders WHERE id=?',[$expiredOrder])['payment_state']);same(null,$payments->status($token)['order']);
                $order=$payments->checkout($checkout,'127.0.0.98')['order'];check($order['id']!==$expiredOrder);
            }
            $settings->saveConnection($alpha,['api_key'=>'mock_newmerchant'.$tenantA['id'],'webhook_secret'=>'Synthetic-New-Merchant-Secret','mode'=>'mock']);
            same($connection['id'],$app->db->one('SELECT connection_id FROM renewal_orders WHERE id=?',[$order['id']])['connection_id']);
            if($lostReply){
                $reference=$app->db->one('SELECT provider_link_id FROM renewal_orders WHERE id=?',[$order['id']])['provider_link_id'];
                $app->db->query("UPDATE renewal_orders SET provider_link_id=NULL,provider_url=NULL,payment_state='link_uncertain' WHERE id=?",[$order['id']]);
                $app->db->query("UPDATE renewal_simulations SET status='completed' WHERE link_id=?",[$reference]);
                $raw=json_encode(['event'=>'payment.completed','status'=>'completed','link_id'=>$reference,'woo_order_id'=>$order['id'],'currency'=>'USD','amount'=>'10.50','txid_out'=>'0x'.hash('sha256',$order['id'])]);
                $payments->webhook($connection['id'],$raw,hash_hmac('sha256',$raw,$secrets->decrypt($connection['webhook_cipher'])));
                same('link_uncertain',$payments->status($token)['order']['payment_state']);
            }else{$payments->simulate($alpha,$order['id']);same('awaiting',$payments->status($token)['order']['payment_state']);}
            $app->db->query('UPDATE renewal_simulated_targets SET package_id=778 WHERE reseller_id=? AND line_id=?',[$reseller,$lineId]);
            same(true,$payments->processOne()['worked']);$held=$payments->status($token)['order'];same('paid',$held['payment_state']);same('needs_review',$held['fulfilment_state']);
            if($lostReply)same($reference,$app->db->one('SELECT provider_link_id FROM renewal_orders WHERE id=?',[$order['id']])['provider_link_id']);
            same(0,(int)$app->db->query('SELECT COUNT(*) FROM renewal_simulated_operations WHERE operation_id=?',[$order['id']])->fetchColumn());
            same(778,(int)$app->db->one('SELECT package_id FROM renewal_simulated_targets WHERE reseller_id=? AND line_id=?',[$reseller,$lineId])['package_id']);
            $app->db->query('UPDATE renewal_simulated_targets SET package_id=777 WHERE reseller_id=? AND line_id=?',[$reseller,$lineId]);
            if($lostReply||$login)$settings->setPolicy($owner,['enabled'=>false,'testing_only'=>true]); // Paid invoices still fulfil after new checkout is paused.
            $payments->retry($alpha,$order['id']);same(true,$payments->processOne()['worked']);$done=$payments->status($token)['order'];same('paid',$done['payment_state']);same('fulfilled',$done['fulfilment_state']);
            $payments->simulate($alpha,$order['id']);$payments->processOne();
            same(1,(int)$app->db->query('SELECT COUNT(*) FROM renewal_simulated_operations WHERE operation_id=?',[$order['id']])->fetchColumn());
            if($expiredOrder){
                $expiry=(int)$app->db->one('SELECT expires_at FROM renewal_simulated_targets WHERE reseller_id=? AND line_id=?',[$reseller,$lineId])['expires_at'];
                $payments->simulate($alpha,$expiredOrder);$payments->processOne();
                same('paid',$app->db->one('SELECT payment_state FROM renewal_orders WHERE id=?',[$expiredOrder])['payment_state']);same('needs_review',$app->db->one('SELECT fulfilment_state FROM renewal_orders WHERE id=?',[$expiredOrder])['fulfilment_state']);
                same($expiry,(int)$app->db->one('SELECT expires_at FROM renewal_simulated_targets WHERE reseller_id=? AND line_id=?',[$reseller,$lineId])['expires_at']);same(0,(int)$app->db->query('SELECT COUNT(*) FROM renewal_simulated_operations WHERE operation_id=?',[$expiredOrder])->fetchColumn());
            }
            same('Keep these notes',$app->db->one('SELECT notes FROM customers WHERE id=?',[$customer['id']])['notes']);
        }finally{$settings->setPolicy($owner,['enabled'=>false,'testing_only'=>true]);}
    };
    $tests['renewal signed checkout verifies payment then fulfils same demo line once']=fn()=> $checkoutFlow($alpha,$tenantA,'987655');
    $tests['expired activation code creates checkout without enrollment or app grants and fulfils once']=fn()=> $checkoutFlow($alpha,$tenantA,'987657',false,true);
    $tests['verified owner reseller uses the same signed checkout and same-line fulfilment safeguards']=function()use($app,$owner,$checkoutFlow):void{
        $tenant=$app->management->workspace($owner);
        $checkoutFlow($owner,$tenant,'987656',true);
    };
    $tests['owner renewal settings require its verified link and stay hidden from other resellers']=function()use($app,$owner,$beta,$settings):void{
        $tenant=$app->management->workspace($owner);same(null,$owner['tenant_id']);
        same($tenant['id'],$settings->status($owner,[])['tenant_id']);
        problem(404,fn()=> $settings->status($beta,['tenant_id'=>$tenant['id']]));
        $link=$app->db->one('SELECT * FROM panel_resellers WHERE tenant_id=?',[$tenant['id']]);check($link!==null);
        $unlinked=$app->management->createReseller($owner,['company'=>'TEST renewal unlinked','name'=>'Unlinked','email'=>'renewal-unlinked@example.test','password'=>'Synthetic-Test-Password-Only-123!']);
        problem(409,fn()=> $settings->status($owner,['tenant_id'=>$unlinked['id']]));
        $app->db->query('UPDATE panel_resellers SET tenant_id=? WHERE panel_id=?',[$unlinked['id'],$link['panel_id']]);
        try{problem(409,fn()=> $settings->status($owner,[]));}
        finally{$app->db->query('UPDATE panel_resellers SET tenant_id=? WHERE panel_id=?',[$tenant['id'],$link['panel_id']]);}
    };
    $tests['renewal simulator clamps calendar month end without touching real lines']=static function():void{
        $base=strtotime('2026-01-31 12:34:56 UTC');$p=['period'=>'months','duration'=>1];
        same('2026-02-28 12:34:56',gmdate('Y-m-d H:i:s',ControlCenter\RenewalSimulation::expiry($base,$p)));
    };
    $tests['owner staging queue preserves one testing brand without production or main fanout']=function()use($app,$config,$tenantA):void{
        $sha=str_repeat('a',40);$brand=$app->db->one('SELECT * FROM app_brands WHERE tenant_id=?',[$tenantA['id']]);
        $artifacts=new ControlCenter\BrandArtifacts($app->db,new ControlCenter\Secrets($config),$config);
        $live=$artifacts->approved($brand['id'],'testing');check($live!==null);
        $old=json_decode($app->db->one('SELECT manifest FROM app_brand_builds WHERE id=?',[$live['build_id']])['manifest'],true);
        problem(409,fn()=> (new ControlCenter\BrandTestingUpdates($app->db,$config,$artifacts,$app->auth))->queue($brand['id'],$sha,'2.0.127',443));
        $pinned=new ControlCenter\Config($config->root,array_replace($config->values,['brand_builder'=>['enabled'=>true,'channels'=>['testing','production'],'sources'=>[]]]));
        $queue=new ControlCenter\BrandTestingUpdates($app->db,$pinned,$artifacts,$app->auth);
        $before=$app->db->all("SELECT id,state FROM app_brand_builds WHERE build_channel='production' ORDER BY id");$sources=$app->db->all('SELECT * FROM brand_main_sources ORDER BY source_sha');
        $job=$queue->queue($brand['id'],$sha,'2.0.127',443);same('testing',$job['channel']);same(true,$job['created']);
        $row=$app->db->one('SELECT * FROM app_brand_builds WHERE id=?',[$job['build_id']]);$m=json_decode($row['manifest'],true);
        same($old['assets'],$m['assets']);same($old['app_name'],$m['app_name']);same($old['brand_version'],$m['brand_version']);same($old['package_name'],$m['package_name']);
        same('2.0.127',$m['app_version']);same(443,$m['app_version_code']);same($sha,$row['source_sha']);
        $again=$queue->queue($brand['id'],$sha,'2.0.127',443);same(false,$again['created']);same($job['build_id'],$again['build_id']);
        same($before,$app->db->all("SELECT id,state FROM app_brand_builds WHERE build_channel='production' ORDER BY id"));same($sources,$app->db->all('SELECT * FROM brand_main_sources ORDER BY source_sha'));
        problem(409,fn()=> $queue->queue($brand['id'],str_repeat('b',40),'2.0.127',443));
        $builder=new ControlCenter\BrandBuilder($app->db,$pinned,$app->appBrands,$artifacts,new ControlCenter\Secrets($config),$app->auth);
        problem(409,fn()=> $builder->claim(['build_id'=>$job['build_id'],'source_sha'=>$sha,'channel'=>'production']));
        $claimed=$builder->claim(['build_id'=>$job['build_id'],'source_sha'=>$sha,'channel'=>'testing']);same($sha,$claimed['source_sha']);same('testing',$claimed['channel']);same($brand['package_name'].'.test',$claimed['expected']['package_name']);
    };
    $tests['catalogue connection merge preserves unrelated secrets and cannot enable money or simulations']=static function():void{
        $current=['origin'=>'https://control.fecxbrfzku.net','database'=>['password'=>'SYNTHETIC-KEEP'],'trial_worker'=>['shared_secret'=>str_repeat('b',64)],'brand_builder'=>['channels'=>['production','testing'],'sources'=>['2.0.127'=>str_repeat('c',40)]],'renewal_payments'=>['allow_live_api'=>true,'simulation_enabled'=>true,'keep'=>'original']];
        $connection=['base_url'=>'https://resellerworker.extremeiptv.net','shared_secret'=>str_repeat('a',64),'mode'=>'catalogue_only'];
        $after=ControlCenter\RenewalCatalogueConnection::configuration($current,$connection);same($connection,$after['renewal_worker']);same(false,$after['renewal_payments']['allow_live_api']);same(false,$after['renewal_payments']['simulation_enabled']);same('original',$after['renewal_payments']['keep']);
        $restored=$after;unset($restored['renewal_worker']);$restored['renewal_payments']=$current['renewal_payments'];same($current,$restored);
        same($after,ControlCenter\RenewalCatalogueConnection::configuration($after,$connection));
        problem(409,fn()=> ControlCenter\RenewalCatalogueConnection::configuration($after,array_replace($connection,['shared_secret'=>str_repeat('f',64)])));
        problem(422,fn()=> ControlCenter\RenewalCatalogueConnection::configuration($current,array_replace($connection,['mode'=>'write_enabled'])));
        problem(422,fn()=> ControlCenter\RenewalCatalogueConnection::configuration($current,array_replace($connection,['base_url'=>'https://untrusted.example.test'])));
    };
    $tests['catalogue-only connection proves signed status and owned packages while denying all renewal calls']=function()use($app,$config,$owner):void{
        $c=new ControlCenter\Config($config->root,array_replace($config->values,['renewal_worker'=>['mode'=>'catalogue_only','shared_secret'=>str_repeat('a',64)],'renewal_payments'=>['allow_live_api'=>false,'simulation_enabled'=>false]]));$actions=[];
        $transport=static function($action,$raw,$headers)use(&$actions):string{
            $actions[]=$action;$h=[];foreach($headers as $line){[$k,$v]=explode(': ',$line,2);$h[$k]=$v;}
            if($action==='status'){same('{}',$raw);$r=['schema_version'=>1,'configured'=>true,'catalogue_only'=>true,'writes_enabled'=>false];}
            else{$input=json_decode($raw,true);same(['reseller_id'],array_keys($input));$r=['reseller_id'=>$input['reseller_id'],'catalogue_only'=>true,'writes_enabled'=>false,'plans'=>[['id'=>7,'name'=>'Offered package','credits'=>'2','duration'=>1,'period'=>'months','max_connections'=>2,'fingerprint'=>str_repeat('d',64),'funded'=>true]]];}
            $payload=json_encode($r);return json_encode(['payload'=>$payload,'signature'=>hash_hmac('sha256',"branded-renewal-result-v1\n".$h['X-Renewal-Timestamp']."\n".$h['X-Renewal-Nonce']."\n".$payload,str_repeat('a',64))]);
        };
        $worker=new ControlCenter\RenewalWorker($c,$transport);$r=ControlCenter\RenewalCatalogueConnection::probe($app,$c,$worker);same(true,$r['connected']);same(1,$r['package_count']);same(false,$r['writes_enabled']);same(false,$r['payments_enabled']);same(false,$r['live_rampex_api_enabled']);same(['status','catalog'],$actions);
        $readonlyApp=new ControlCenter\Application($c);$view=$readonlyApp->renewalSettings->status($owner,[]);same(true,$view['catalogue_only']);check(!str_contains(json_encode($view),str_repeat('a',64)));
        problem(409,fn()=> $readonlyApp->renewalSettings->setPolicy($owner,['enabled'=>true,'testing_only'=>true]));
        problem(503,fn()=> $worker->eligibility('101','999',str_repeat('a',64)));problem(503,fn()=> $worker->renew([]));same(['status','catalog'],$actions);
        $app->renewalSettings->setPolicy($owner,['enabled'=>true,'testing_only'=>true]);
        try{problem(409,fn()=> ControlCenter\RenewalCatalogueConnection::probe($app,$c,$worker));}
        finally{$app->renewalSettings->setPolicy($owner,['enabled'=>false,'testing_only'=>true]);}
        same(['status','catalog'],$actions);
    };
    $tests['catalogue-only handshake refuses a signed worker with write capability or missing mode proof']=function()use($config):void{
        $c=new ControlCenter\Config($config->root,array_replace($config->values,['renewal_worker'=>['mode'=>'catalogue_only','shared_secret'=>str_repeat('a',64)]]));
        foreach([['schema_version'=>1,'configured'=>true,'writes_enabled'=>true,'catalogue_only'=>true],['schema_version'=>1,'configured'=>true,'writes_enabled'=>false]] as $reply){
            $worker=new ControlCenter\RenewalWorker($c,static function($action,$raw,$headers)use($reply):string{
                $h=[];foreach($headers as $line){[$k,$v]=explode(': ',$line,2);$h[$k]=$v;}$payload=json_encode($reply);
                return json_encode(['payload'=>$payload,'signature'=>hash_hmac('sha256',"branded-renewal-result-v1\n".$h['X-Renewal-Timestamp']."\n".$h['X-Renewal-Nonce']."\n".$payload,str_repeat('a',64))]);
            });
            problem(503,fn()=> $worker->connectionStatus());
        }
    };
    $tests['standalone worker import upgrades only the old catalogue connection and opens real merchant setup']=static function():void{
        $before=['origin'=>'https://control.fecxbrfzku.net','renewal_worker'=>['mode'=>'catalogue_only','shared_secret'=>str_repeat('b',64)],'trial_worker'=>['shared_secret'=>str_repeat('c',64)],'brand_builder'=>['channels'=>['production','testing']],'renewal_payments'=>['allow_live_api'=>false,'simulation_enabled'=>true,'keep'=>'original']];
        $connection=['base_url'=>'https://resellerworker.extremeiptv.net','shared_secret'=>str_repeat('a',64),'mode'=>'renewals'];
        $after=ControlCenter\RenewalWorkerConnection::configuration($before,$connection);same('renewals',$after['renewal_worker']['mode']);same(true,$after['renewal_payments']['allow_live_api']);same(false,$after['renewal_payments']['simulation_enabled']);same($before['trial_worker'],$after['trial_worker']);same($before['brand_builder'],$after['brand_builder']);same('original',$after['renewal_payments']['keep']);
        same($after,ControlCenter\RenewalWorkerConnection::configuration($after,$connection));
        problem(409,fn()=> ControlCenter\RenewalWorkerConnection::configuration($after,array_replace($connection,['shared_secret'=>str_repeat('d',64)])));
    };
    $tests['standalone full worker status and package probe permits the normal owner rollout control']=function()use($app,$config,$owner):void{
        $c=new ControlCenter\Config($config->root,array_replace($config->values,['renewal_worker'=>['mode'=>'renewals','shared_secret'=>str_repeat('a',64)],'renewal_payments'=>['allow_live_api'=>true,'simulation_enabled'=>false]]));$actions=[];
        $reader=new ControlCenter\RenewalWorker($c,static function($action,$raw,$headers)use(&$actions):string{
            $actions[]=$action;$h=[];foreach($headers as $line){[$k,$v]=explode(': ',$line,2);$h[$k]=$v;}
            if($action==='status')$r=['schema_version'=>1,'service'=>'iptv-renewal-worker','mode'=>'renewals','configured'=>true,'catalogue_only'=>false,'writes_enabled'=>true,'atomic_renewal'=>1,'unique_payment_reference'=>1,'current_package_only'=>true,'renewal_login'=>1];
            else{$v=json_decode($raw,true);$r=['reseller_id'=>$v['reseller_id'],'plans'=>[['id'=>7,'name'=>'Current offered package','credits'=>'2','duration'=>1,'period'=>'months','max_connections'=>1,'fingerprint'=>str_repeat('d',64),'funded'=>true]]];}
            $payload=json_encode($r);return json_encode(['payload'=>$payload,'signature'=>hash_hmac('sha256',"branded-renewal-result-v1\n".$h['X-Renewal-Timestamp']."\n".$h['X-Renewal-Nonce']."\n".$payload,str_repeat('a',64))]);
        });
        $r=ControlCenter\RenewalWorkerConnection::probe($app,$c,$reader);same(true,$r['writes_enabled']);same(true,$r['rampex_account_setup_enabled']);same(1,$r['package_count']);same(['status','catalog'],$actions);
        $fullApp=new ControlCenter\Application($c);same(true,$fullApp->renewalSettings->setPolicy($owner,['enabled'=>true,'testing_only'=>true])['enabled']);$fullApp->renewalSettings->setPolicy($owner,['enabled'=>false,'testing_only'=>true]);
    };
    $tests['normal Rampex merchant contract accepts key validation HTTP201 links and authenticated completion without comparing net crypto payout']=function()use($app,$config,$secrets):void{
        $o=$app->db->one('SELECT * FROM renewal_orders ORDER BY created_at DESC,id DESC LIMIT 1');check($o!==null);
        $connection=$app->db->one('SELECT * FROM renewal_connections WHERE id=?',[$o['connection_id']]);$connection['mode']='live';
        $c=new ControlCenter\Config($config->root,array_replace($config->values,['renewal_payments'=>['allow_live_api'=>true,'simulation_enabled'=>false]]));$calls=[];
        $transport=static function($path,$body,$headers)use($o,$connection,&$calls):array{
            $calls[]=$path;
            if($path==='api-validate-key')return ['status'=>200,'body'=>json_encode(['valid'=>true,'merchant_id'=>$connection['merchant_id'],'business_name'=>'Synthetic merchant'])];
            if($path==='api-create-payment-link'){
                same($o['id'],$body['woo_order_id']);same(10.5,$body['amount']);same('USD',$body['currency']);check(str_contains($body['payment_url'],'/renew/'));check(!isset($body['wallet']));check(!isset($body['description']));
                return ['status'=>201,'body'=>json_encode(['success'=>true,'data'=>['link_id'=>'contract-link','payment_url'=>'https://checkout.rampex.io/pay.php?invoice=contract-link']])];
            }
            return ['status'=>200,'body'=>json_encode(['success'=>true,'data'=>['link_id'=>$o['provider_link_id'],'status'=>'completed','amount'=>'10.500000','currency'=>'USD','received_amount'=>10.50,'received_currency'=>'polygon_usdc','payout_amount'=>'9.95']])];
        };
        $g=new ControlCenter\RampexGateway($c,$app->db,$secrets,$transport);same($connection['merchant_id'],$g->validate('SYNTHETIC-VALID-API-KEY-ONLY','live')['merchant_id']);same('contract-link',$g->create($connection,$o,'receipt@example.test')['link_id']);same(true,$g->status($connection,$o)['fully_paid']);same(1050,$g->status($connection,$o)['amount_minor']);
        foreach([['status'=>'active'],['status'=>'completed','fully_paid'=>false],['status'=>'completed','received_currency'=>'USD','received_amount'=>'5.25']] as $override){
            $status=array_replace(['link_id'=>$o['provider_link_id'],'amount'=>'10.50','currency'=>'USD'],$override);
            $g=new ControlCenter\RampexGateway($c,$app->db,$secrets,static fn()=>json_encode(['success'=>true,'data'=>$status]));same(false,$g->status($connection,$o)['fully_paid']);
        }
        $g=new ControlCenter\RampexGateway($c,$app->db,$secrets,static fn()=>['status'=>400,'body'=>json_encode(['success'=>false,'error'=>['code'=>'NO_WALLET']])]);
        try{$g->create($connection,$o,'receipt@example.test');throw new RuntimeException('Expected definite provider rejection');}catch(ControlCenter\RampexLinkRejected){}
    };
    $tests['valid signed Rampex setup notifications verify the webhook but never authorize a payment']=function()use($app,$config,$owner,$settings,$gateway,$worker,$secrets):void{
        $c=$settings->connection($app->management->workspace($owner)['id']);check($c!==null);
        $artifacts=new ControlCenter\BrandArtifacts($app->db,$secrets,$config);$payments=new ControlCenter\RenewalPayments($app->db,$config,$app->auth,$app->management,$secrets,$artifacts,$settings,$gateway,$worker);
        $before=$app->db->all('SELECT id,payment_state,fulfilment_state FROM renewal_orders ORDER BY id');$raw='{"event":"webhook.test"}';
        $result=$payments->webhook($c['id'],$raw,hash_hmac('sha256',$raw,$secrets->decrypt($c['webhook_cipher'])));same(true,$result['ignored']);check($app->db->one('SELECT webhook_verified_at FROM renewal_connections WHERE id=?',[$c['id']])['webhook_verified_at']!==null);same($before,$app->db->all('SELECT id,payment_state,fulfilment_state FROM renewal_orders ORDER BY id'));
    };
}
