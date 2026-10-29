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
        $spec=['operation_id'=>str_repeat('a',32),'brand_id'=>str_repeat('b',32),'reseller_id'=>$id,'line_id'=>'987654','package_id'=>777,'plan_fingerprint'=>str_repeat('d',64),'merchant_id'=>'demo','payment_link_id'=>'demo-link','primary_fingerprint'=>str_repeat('e',64)];
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
    $checkoutFlow=function(array $alpha,array $tenantA,string $lineId)use($app,$simulationConfig,$owner,$settings,$gateway,$worker,$secrets):void{
        $artifacts=new ControlCenter\BrandArtifacts($app->db,$secrets,$simulationConfig);
        $brand=$app->db->one('SELECT * FROM app_brands WHERE tenant_id=?',[$tenantA['id']]);check($brand!==null);
        $app->db->query('UPDATE app_brands SET enabled=1 WHERE id=?',[$brand['id']]);
        $build=ControlCenter\Database::id();$revision=(int)$brand['version'];
        $manifest=null;foreach($app->db->all('SELECT manifest FROM app_brand_builds WHERE brand_id=? ORDER BY created_at,id',[$brand['id']]) as $old){$candidate=json_decode($old['manifest'],true);if(isset($candidate['brand_id'],$candidate['assets'],$candidate['package_name'])){$manifest=$candidate;break;}}
        check($manifest!==null,'Published test fixture needs a complete cosmetic snapshot');
        $manifest=array_replace($manifest,['app_version'=>'2.0.126','app_version_code'=>442,'brand_version'=>$revision]);
        $app->db->query('INSERT INTO app_brand_builds (id,brand_id,brand_version,app_version,manifest,requested_by,build_channel) VALUES (?,?,?,?,?,?,?)',[$build,$brand['id'],$revision,'2.0.126',json_encode($manifest),$owner['id'],'testing']);
        $artifacts->recordVerified($build,['channel'=>'testing','package_name'=>$brand['package_name'].'.test','version_name'=>'2.0.126.'.$revision.'-test','version_code'=>44200000+$revision,'trial_protocol'=>1,'signer_sha256'=>'3273d0b0294a689565ab4b0c8ef7e1887e2ca680240718f5521628b5c46328b7','apk_sha256'=>str_repeat('a',64),'artifact_url'=>'https://github.com/derangedchief-crypto/ARVIO/releases/download/brand-'.$brand['id'].'-testing/Brand-synthetic.apk']);
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
        $settings->savePrice($alpha,['package_id'=>778,'currency'=>'USD','amount'=>'99.00','enabled'=>true]);
        $otherPrice=array_values(array_filter($settings->status($alpha,[])['prices'],static fn($p)=>$p['package_id']===778))[0];
        $payments=new ControlCenter\RenewalPayments($app->db,$simulationConfig,$app->auth,$app->management,$secrets,$artifacts,$settings,$gateway,$worker);
        $settings->setPolicy($owner,['enabled'=>true,'testing_only'=>true]);
        try{
            $body=['device_id'=>$device['id'],'app_package'=>$package,'key_fingerprint'=>$key,'primary_fingerprint'=>$primary];
            $challenge=$payments->challenge($body,'127.0.0.98');same(true,$challenge['available']);
            openssl_sign(ControlCenter\RenewalPayments::signingText($challenge['challenge'],$device['id'],$package,$key,$primary),$signature,$private,OPENSSL_ALGO_SHA256);
            $open=$body+['challenge'=>$challenge['challenge'],'signature'=>base64_encode($signature)];
            $session=$payments->open($open,'127.0.0.98');same(true,$session['available']);same(true,$session['simulated']);
            problem(403,fn()=> $payments->open($open,'127.0.0.98'));
            $token=$session['session_token'];$info=$payments->info($token);check(!array_key_exists('plans',$info));$p=$info['renewal'];same(777,$p['package_id']);
            problem(422,fn()=> $settings->savePrice($alpha,['id'=>$p['id'],'expected_revision'=>$p['revision'],'package_id'=>778,'currency'=>'USD','amount'=>'12.50','enabled'=>true]));
            $checkout=['session_token'=>$token,'price_id'=>$p['id'],'price_revision'=>$p['revision'],'email'=>'receipt@example.test'];
            problem(422,fn()=> $payments->checkout($checkout+['package_id'=>778],'127.0.0.98'));
            problem(409,fn()=> $payments->checkout(array_replace($checkout,['price_id'=>$otherPrice['id'],'price_revision'=>$otherPrice['revision']]),'127.0.0.98'));
            $app->db->query('UPDATE renewal_simulated_targets SET package_id=778 WHERE reseller_id=? AND line_id=?',[$reseller,$lineId]);
            try{problem(409,fn()=> $payments->checkout($checkout,'127.0.0.98'));}
            finally{$app->db->query('UPDATE renewal_simulated_targets SET package_id=777 WHERE reseller_id=? AND line_id=?',[$reseller,$lineId]);}
            $app->db->query("UPDATE devices SET policy='blocked' WHERE id=?",[$device['id']]);
            try{problem(403,fn()=> $payments->checkout($checkout,'127.0.0.98'));}
            finally{$app->db->query("UPDATE devices SET policy='active' WHERE id=?",[$device['id']]);}
            $order=$payments->checkout($checkout,'127.0.0.98')['order'];same('awaiting',$order['payment_state']);
            $app->db->query('UPDATE renewal_orders SET expires_at=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 MINUTE) WHERE id=?',[$order['id']]);
            same($order['id'],$payments->checkout($checkout,'127.0.0.98')['order']['id']);
            same(1,(int)$app->db->query('SELECT COUNT(*) FROM renewal_orders WHERE session_id=(SELECT id FROM renewal_sessions WHERE token_hash=?)',[$secrets->mac("renewal-checkout-token-v1\n$token")])->fetchColumn());
            $app->db->query('UPDATE renewal_orders SET expires_at=DATE_ADD(UTC_TIMESTAMP(),INTERVAL 30 MINUTE) WHERE id=?',[$order['id']]);
            $connection=$settings->connection($tenantA['id']);problem(401,fn()=> $payments->webhook($connection['id'],'{}',str_repeat('0',64)));
            $settings->saveConnection($alpha,['api_key'=>'mock_newmerchant'.$tenantA['id'],'webhook_secret'=>'Synthetic-New-Merchant-Secret','mode'=>'mock']);
            same($connection['id'],$app->db->one('SELECT connection_id FROM renewal_orders WHERE id=?',[$order['id']])['connection_id']);
            $payments->simulate($alpha,$order['id']);same('awaiting',$payments->status($token)['order']['payment_state']);
            $app->db->query('UPDATE renewal_simulated_targets SET package_id=778 WHERE reseller_id=? AND line_id=?',[$reseller,$lineId]);
            same(true,$payments->processOne()['worked']);$held=$payments->status($token)['order'];same('paid',$held['payment_state']);same('needs_review',$held['fulfilment_state']);
            same(0,(int)$app->db->query('SELECT COUNT(*) FROM renewal_simulated_operations WHERE operation_id=?',[$order['id']])->fetchColumn());
            same(778,(int)$app->db->one('SELECT package_id FROM renewal_simulated_targets WHERE reseller_id=? AND line_id=?',[$reseller,$lineId])['package_id']);
            $app->db->query('UPDATE renewal_simulated_targets SET package_id=777 WHERE reseller_id=? AND line_id=?',[$reseller,$lineId]);
            $payments->retry($alpha,$order['id']);same(true,$payments->processOne()['worked']);$done=$payments->status($token)['order'];same('paid',$done['payment_state']);same('fulfilled',$done['fulfilment_state']);
            $payments->simulate($alpha,$order['id']);$payments->processOne();
            same(1,(int)$app->db->query('SELECT COUNT(*) FROM renewal_simulated_operations WHERE operation_id=?',[$order['id']])->fetchColumn());
            same('Keep these notes',$app->db->one('SELECT notes FROM customers WHERE id=?',[$customer['id']])['notes']);
        }finally{$settings->setPolicy($owner,['enabled'=>false,'testing_only'=>true]);}
    };
    $tests['renewal signed checkout verifies payment then fulfils same demo line once']=fn()=> $checkoutFlow($alpha,$tenantA,'987655');
    $tests['verified owner reseller uses the same signed checkout and same-line fulfilment safeguards']=function()use($app,$owner,$checkoutFlow):void{
        $tenant=$app->management->workspace($owner);
        $checkoutFlow($owner,$tenant,'987656');
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
}
