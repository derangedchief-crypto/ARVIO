<?php
declare(strict_types=1);
/** Synthetic merchant APIs only. No external API keys, charges or panel writes. */
function cardGatewayCases(array &$tests,ControlCenter\Application $app,ControlCenter\Config $config,array $owner,array $alpha,array $beta,array $tenantA):void {
    $c=new ControlCenter\Config($config->root,array_replace($config->values,['renewal_payments'=>['allow_live_api'=>true,'simulation_enabled'=>true],'renewal_worker'=>['shared_secret'=>str_repeat('a',64)]]));
    $secrets=new ControlCenter\Secrets($c);$records=[];$overrides=[];$calls=[];
    $transport=static function($path,$body,$headers)use(&$records,&$overrides,&$calls):array {
        $calls[]=['path'=>$path,'body'=>$body,'headers'=>$headers];
        if(isset($overrides[$path]))return ['status'=>200,'body'=>json_encode($overrides[$path])];
        if($path==='stripe/v1/account')$r=['id'=>'acct_test_CA','country'=>'CA','charges_enabled'=>true,'details_submitted'=>true,'business_profile'=>['name'=>'Synthetic Stripe merchant']];
        elseif($path==='square/v2/merchants/me')$r=['merchant'=>['id'=>'M_CA','country'=>'CA','status'=>'ACTIVE','business_name'=>'Synthetic Square merchant']];
        elseif($path==='square/v2/locations/LOC_CA')$r=['location'=>['id'=>'LOC_CA','merchant_id'=>'M_CA','country'=>'CA','currency'=>'CAD','status'=>'ACTIVE','capabilities'=>['CREDIT_CARD_PROCESSING']]];
        elseif($path==='stripe/v1/checkout/sessions'){
            $id=$body['client_reference_id'];same('payment',$body['mode']);same(['card'],$body['allowed_payment_method_types']);same($id,$body['metadata']['order_id']);same($id,$body['payment_intent_data']['metadata']['order_id']);
            check(!isset($body['wallet_addresses'],$body['destination_currency']));check(in_array('Idempotency-Key: '.$id,$headers,true));
            $live=str_contains(implode('\n',$headers),'_live_');$records[$id]=['id'=>$id,'gateway'=>'stripe','live'=>$live,'amount'=>$body['line_items'][0]['price_data']['unit_amount'],'currency'=>strtoupper($body['line_items'][0]['price_data']['currency'])];
            $r=['id'=>'cs_'.$id,'url'=>'https://checkout.stripe.com/c/pay/cs_'.$id,'client_reference_id'=>$id,'amount_total'=>$records[$id]['amount'],'currency'=>strtolower($records[$id]['currency']),'livemode'=>$live];
        }elseif(str_starts_with($path,'stripe/v1/checkout/sessions/cs_')){
            $id=explode('?',substr($path,strlen('stripe/v1/checkout/sessions/cs_')))[0];$v=$records[$id];
            $r=['id'=>'cs_'.$id,'client_reference_id'=>$id,'metadata'=>['order_id'=>$id],'amount_total'=>$v['amount'],'currency'=>strtolower($v['currency']),'livemode'=>$v['live'],'status'=>'complete','payment_status'=>'paid','payment_intent'=>['id'=>'pi_'.$id,'status'=>'succeeded','amount_received'=>$v['amount'],'currency'=>strtolower($v['currency']),'metadata'=>['order_id'=>$id],'livemode'=>$v['live'],'latest_charge'=>['paid'=>true,'captured'=>true,'amount_refunded'=>0,'payment_method_details'=>['type'=>'card']]]];
        }elseif($path==='square/v2/online-checkout/payment-links'){
            $id=$body['order']['reference_id'];same($id,$body['idempotency_key']);same('LOC_CA',$body['order']['location_id']);same(false,$body['checkout_options']['allow_tipping']);same('Renewal order '.$id,$body['payment_note']);
            same(false,$body['checkout_options']['accepted_payment_methods']['cash_app_pay']);same(false,$body['checkout_options']['accepted_payment_methods']['afterpay_clearpay']);
            $records[$id]=['id'=>$id,'gateway'=>'square','amount'=>$body['order']['line_items'][0]['base_price_money']['amount'],'currency'=>$body['order']['line_items'][0]['base_price_money']['currency']];
            $r=['payment_link'=>['id'=>'link_'.$id,'order_id'=>'sq_'.$id,'url'=>'https://square.link/u/'.$id]];
        }elseif(str_starts_with($path,'square/v2/orders/sq_')){
            $id=substr($path,strlen('square/v2/orders/sq_'));$v=$records[$id];
            $r=['order'=>['id'=>'sq_'.$id,'reference_id'=>$id,'location_id'=>'LOC_CA','total_money'=>['amount'=>$v['amount'],'currency'=>$v['currency']],'net_amount_due_money'=>['amount'=>0,'currency'=>$v['currency']],'state'=>'OPEN','tenders'=>[['payment_id'=>'pay_'.$id]]]];
        }elseif(str_starts_with($path,'square/v2/payments/pay_')){
            $id=substr($path,strlen('square/v2/payments/pay_'));$v=$records[$id];
            $r=['payment'=>['id'=>'pay_'.$id,'order_id'=>'sq_'.$id,'location_id'=>'LOC_CA','status'=>'COMPLETED','source_type'=>'CARD','card_details'=>['status'=>'CAPTURED'],'amount_money'=>['amount'=>$v['amount'],'currency'=>$v['currency']],'tip_money'=>['amount'=>0,'currency'=>$v['currency']],'refunded_money'=>['amount'=>0,'currency'=>$v['currency']]]];
        }else throw new RuntimeException('Unexpected synthetic provider path: '.$path);
        return ['status'=>200,'body'=>json_encode($r,JSON_THROW_ON_ERROR)];
    };
    $gateway=new ControlCenter\RampexGateway($c,$app->db,$secrets,$transport);
    $worker=new ControlCenter\RenewalWorker($c,(new ControlCenter\RenewalSimulation($app->db,$c))->transport());
    $settings=new ControlCenter\RenewalSettings($app->db,$c,$app->management,$app->auth,$secrets,$gateway,$worker);
    $tests['direct Stripe and Square merchant connections coexist with Rampex without exposing secrets']=function()use($app,$settings,$alpha,$beta,$tenantA):void{
        $old=$settings->connection($tenantA['id']);check($old!==null);
        $stripe=['gateway'=>'stripe','mode'=>'live','api_key'=>'sk_live_SYNTHETIC_MERCHANT_KEY_ONLY','webhook_secret'=>'whsec_SYNTHETIC_STRIPE_SECRET_ONLY'];
        $s=$settings->saveConnection($alpha,$stripe);check(!str_contains(json_encode($s),$stripe['api_key']));check(!str_contains(json_encode($s),$stripe['webhook_secret']));
        problem(409,fn()=> $settings->saveConnection($beta,$stripe));
        $square=['gateway'=>'square','mode'=>'live','api_key'=>'SYNTHETIC_SQUARE_ACCESS_TOKEN_ONLY','webhook_secret'=>'SYNTHETIC_SQUARE_SIGNATURE_KEY_ONLY','location_id'=>'LOC_CA'];
        $s=$settings->saveConnection($alpha,$square);check(!str_contains(json_encode($s),$square['api_key']));check(!str_contains(json_encode($s),$square['webhook_secret']));
        same($old['id'],$settings->connection($tenantA['id'])['id']);same(3,count($s['connections']));
        same(['rampex','square','stripe'],array_values((function($x){sort($x);return $x;})(array_column($s['connections'],'gateway'))));
    };
    $tests['country preferences filter by currency preserve tenant isolation and prohibit Canadian crypto fallback']=function()use($app,$settings,$alpha,$beta,$tenantA):void{
        $settings->saveRoutes($alpha,['country'=>'CA','gateways'=>['square','stripe'],'expected_revision'=>0]);
        $settings->saveRoutes($alpha,['country'=>'US','gateways'=>['stripe','rampex','square'],'expected_revision'=>0]);
        same(['square','stripe'],array_column($settings->candidates($tenantA['id'],'CA','CAD',true),'gateway'));
        same(['stripe'],array_column($settings->candidates($tenantA['id'],'CA','USD',true),'gateway'));
        same(['stripe','rampex'],array_column($settings->candidates($tenantA['id'],'US','USD',true),'gateway'));
        problem(422,fn()=> $settings->saveRoutes($alpha,['country'=>'CA','gateways'=>['rampex'],'expected_revision'=>1]));
        problem(422,fn()=> $settings->saveRoutes($alpha,['country'=>'US','gateways'=>[['square']],'expected_revision'=>1]));
        problem(409,fn()=> $settings->saveRoutes($alpha,['country'=>'CA','gateways'=>['stripe'],'expected_revision'=>0]));
        problem(404,fn()=> $settings->saveRoutes($beta,['tenant_id'=>$tenantA['id'],'country'=>'CA','gateways'=>[],'expected_revision'=>1]));
        problem(422,fn()=> $settings->candidates($tenantA['id'],'GB','CAD',true));
        $settings->savePrice($alpha,['package_id'=>777,'country'=>'CA','currency'=>'CAD','amount'=>'10.00','enabled'=>true]);
        $prices=$settings->status($alpha,[])['prices'];same(1,count(array_filter($prices,static fn($p)=>$p['package_id']===777&&$p['country']==='CA')));
    };
    $tests['merchant validation refuses incomplete Stripe onboarding wrong Square owner and wrong environment keys']=function()use($gateway,&$overrides):void{
        problem(422,fn()=> $gateway->validate('sk_test_SYNTHETIC_KEY_ONLY','live','stripe'));
        $overrides['stripe/v1/account']=['id'=>'acct_bad','country'=>'CA','charges_enabled'=>false,'details_submitted'=>false];
        problem(422,fn()=> $gateway->validate('sk_live_SYNTHETIC_KEY_ONLY','live','stripe'));
        check(str_starts_with($gateway->validate('sk_test_SYNTHETIC_KEY_ONLY','sandbox','stripe')['merchant_id'],'stripe:'));
        unset($overrides['stripe/v1/account']);
        $overrides['square/v2/locations/LOC_CA']=['location'=>['id'=>'LOC_CA','merchant_id'=>'WRONG','country'=>'CA','currency'=>'CAD','status'=>'ACTIVE','capabilities'=>['CREDIT_CARD_PROCESSING']]];
        problem(422,fn()=> $gateway->validate('SYNTHETIC_SQUARE_TOKEN_ONLY','live','square',['location_id'=>'LOC_CA']));unset($overrides['square/v2/locations/LOC_CA']);
    };
    $flow=function(string $provider,string $mode,string $line,string $behavior='normal')use($app,$c,$owner,$alpha,$tenantA,$settings,$gateway,$worker,$secrets,&$records,&$overrides):void{
        $settings->saveConnection($alpha,['gateway'=>$provider,'mode'=>$mode,'api_key'=>$provider==='stripe'?($mode==='live'?'sk_live_SYNTHETIC_KEY_ONLY':'sk_test_SYNTHETIC_KEY_ONLY'):'SYNTHETIC_SQUARE_TOKEN_ONLY','webhook_secret'=>'SYNTHETIC_'.$mode.'_WEBHOOK_SECRET','location_id'=>'LOC_CA']);
        $brand=$app->db->one('SELECT * FROM app_brands WHERE tenant_id=?',[$tenantA['id']]);$artifacts=new ControlCenter\BrandArtifacts($app->db,$secrets,$c);$artifact=$artifacts->approved($brand['id'],'testing');check($artifact!==null);
        $reseller=$app->db->one('SELECT panel_id FROM panel_resellers WHERE tenant_id=?',[$tenantA['id']])['panel_id'];
        $p=$worker->catalog($reseller)[0];$p['id']=777;$primary=str_repeat('e',64);
        $app->db->query('INSERT INTO renewal_simulated_targets (reseller_id,line_id,primary_fingerprint,expires_at,plans,package_id) VALUES (?,?,?,?,?,777)',[$reseller,$line,$primary,time()-120,json_encode([$p])]);
        $private=openssl_pkey_new(['private_key_type'=>OPENSSL_KEYTYPE_EC,'curve_name'=>'prime256v1']);$public=preg_replace('~-----[^\n]+-----|\s~','',openssl_pkey_get_details($private)['key']);$fingerprint=hash('sha256',base64_decode($public));
        $base=(new ControlCenter\RenewalSimulation($app->db,$c))->transport();
        $loginWorker=new ControlCenter\RenewalWorker($c,static function($action,$raw,$headers)use($base,$reseller,$line,$primary):string{
            if($action!=='identify')return $base($action,$raw,$headers);
            $v=json_decode($raw,true);same($reseller,$v['reseller_id']);same('123456',$v['activation_code']);$h=[];
            foreach($headers as $header){[$k,$value]=explode(': ',$header,2);$h[$k]=$value;}
            $payload=json_encode(['reseller_id'=>$reseller,'line_id'=>$line,'primary_fingerprint'=>$primary]);
            return json_encode(['payload'=>$payload,'signature'=>hash_hmac('sha256',"branded-renewal-result-v1\n".$h['X-Renewal-Timestamp']."\n".$h['X-Renewal-Nonce']."\n".$payload,str_repeat('a',64))]);
        });
        $payments=new ControlCenter\RenewalPayments($app->db,$c,$app->auth,$app->management,$secrets,$artifacts,$settings,$gateway,$loginWorker);$settings->setPolicy($owner,['enabled'=>true,'testing_only'=>true]);
        try {
            $install=ControlCenter\Database::id();$ip='127.0.1.'.((int)$line%250+1);$package=$artifact['package_name'];
            $challenge=$payments->loginChallenge(['installation_id'=>$install,'key_fingerprint'=>$fingerprint,'app_package'=>$package],$ip);$identity=['activation_code'=>'123456'];
            openssl_sign(ControlCenter\RenewalPayments::loginSigningText($challenge['challenge'],$install,$package,$fingerprint,$identity,'CA'),$signature,$private,OPENSSL_ALGO_SHA256);
            $opened=$payments->loginOpen(['installation_id'=>$install,'app_package'=>$package,'public_key'=>$public,'challenge'=>$challenge['challenge'],'identity'=>$identity,'signature'=>base64_encode($signature),'country'=>'CA'],$ip);
            same(true,$opened['available']);same('CA',$opened['country']);same('square',$opened['gateway']);
            $token=$opened['session_token'];if($opened['gateway']!==$provider)$payments->selectRoute(['session_token'=>$token,'country'=>'CA','gateway'=>$provider]);
            $info=$payments->info($token);$price=$info['renewal'];same('CAD',$price['currency']);same(1000,$price['amount_minor']);same($provider,$info['gateway']);
            $order=$payments->checkout(['session_token'=>$token,'price_id'=>$price['id'],'price_revision'=>$price['revision'],'package_fingerprint'=>$price['package_fingerprint'],'email'=>'receipt@example.test'],'127.0.0.67')['order'];
            $o=$app->db->one('SELECT * FROM renewal_orders WHERE id=?',[$order['id']]);$connection=$app->db->one('SELECT * FROM renewal_connections WHERE id=?',[$o['connection_id']]);
            $stripeStatus=static fn($o,$mode,$state='succeeded',$refund=0)=>['id'=>'cs_'.$o['id'],'client_reference_id'=>$o['id'],'metadata'=>['order_id'=>$o['id']],'amount_total'=>(int)$o['amount_minor'],'currency'=>strtolower($o['currency']),'livemode'=>$mode==='live','status'=>'complete','payment_status'=>'paid','payment_intent'=>['id'=>'pi_'.$o['id'],'status'=>$state,'amount_received'=>(int)$o['amount_minor'],'currency'=>strtolower($o['currency']),'metadata'=>['order_id'=>$o['id']],'livemode'=>$mode==='live','latest_charge'=>['paid'=>true,'captured'=>true,'amount_refunded'=>$refund,'payment_method_details'=>['type'=>'card']]]];
            $expiredOrder=null;
            if($behavior==='switch'){
                $expiredOrder=$o;$statusPath='stripe/v1/checkout/sessions/cs_'.$o['id'].'?expand%5B%5D=payment_intent.latest_charge';
                $overrides[$statusPath]=array_replace($stripeStatus($o,$mode),['status'=>'expired','payment_status'=>'unpaid']);
                $changed=$payments->selectRoute(['session_token'=>$token,'country'=>'CA','gateway'=>'square']);
                same('square',$changed['gateway']);same('expired',$app->db->one('SELECT payment_state FROM renewal_orders WHERE id=?',[$o['id']])['payment_state']);
                unset($overrides[$statusPath]);$provider='square';$price=$changed['renewal'];
                $order=$payments->checkout(['session_token'=>$token,'price_id'=>$price['id'],'price_revision'=>$price['revision'],'package_fingerprint'=>$price['package_fingerprint'],'email'=>'receipt@example.test'],'127.0.0.67')['order'];
                $o=$app->db->one('SELECT * FROM renewal_orders WHERE id=?',[$order['id']]);$connection=$app->db->one('SELECT * FROM renewal_connections WHERE id=?',[$o['connection_id']]);
            }
            $secret=$secrets->decrypt($connection['webhook_cipher']);$url=$c->origin().'/api/renewals/webhooks/'.$connection['id'];$id=$o['id'];
            problem(409,fn()=> $payments->selectRoute(['session_token'=>$token,'country'=>'CA','gateway'=>$provider==='square'?'stripe':'square']));
            if($provider==='stripe'){
                $event=['type'=>'checkout.session.completed','livemode'=>$mode==='live','data'=>['object'=>['id'=>'cs_'.$id,'payment_status'=>'paid','client_reference_id'=>$id,'metadata'=>['order_id'=>$id],'amount_total'=>1000,'currency'=>'cad','payment_intent'=>'pi_'.$id]]];
                $raw=json_encode($event);$stamp=(string)time();$signature='t='.$stamp.',v1='.hash_hmac('sha256',$stamp.'.'.$raw,$secret);
                problem(401,fn()=> $payments->webhook($connection['id'],$raw,'t='.(time()-600).',v1='.hash_hmac('sha256',(time()-600).'.'.$raw,$secret)));
            }else{
                $event=['type'=>'payment.updated','merchant_id'=>'M_CA','data'=>['object'=>['payment'=>['id'=>'pay_'.$id,'order_id'=>'sq_'.$id,'status'=>'COMPLETED','amount_money'=>['amount'=>1000,'currency'=>'CAD'],'note'=>'Renewal order '.$id]]]];
                $raw=json_encode($event);$signature=base64_encode(hash_hmac('sha256',$url.$raw,$secret,true));
                problem(401,fn()=> $payments->webhook($connection['id'],$raw,base64_encode(hash_hmac('sha256','https://wrong.example'.$raw,$secret,true))));
            }
            problem(401,fn()=> $payments->webhook($connection['id'],$raw.' ',$signature));
            same('awaiting',$payments->status($token)['order']['payment_state']);
            $payments->webhook($connection['id'],$raw,$signature);$payments->webhook($connection['id'],$raw,$signature);
            same('awaiting',$payments->status($token)['order']['payment_state']);
            if(in_array($behavior,['delay','refund'],true)){
                $statusPath='stripe/v1/checkout/sessions/cs_'.$o['id'].'?expand%5B%5D=payment_intent.latest_charge';
                $overrides[$statusPath]=$stripeStatus($o,$mode,$behavior==='delay'?'processing':'succeeded',$behavior==='refund'?1:0);
                same(true,$payments->processOne()['worked']);
                same($behavior==='delay'?'verification_pending':'review',$payments->status($token)['order']['payment_state']);
                same(0,(int)$app->db->query('SELECT COUNT(*) FROM renewal_simulated_operations WHERE operation_id=?',[$id])->fetchColumn());
                unset($overrides[$statusPath]);
                if($behavior==='refund')same(false,$payments->retry($alpha,$id)['new_charge']);
                else $app->db->query('UPDATE renewal_orders SET next_attempt_at=UTC_TIMESTAMP() WHERE id=?',[$id]);
            }
            same(true,$payments->processOne()['worked']);$done=$payments->status($token);same('paid',$done['order']['payment_state']);same('fulfilled',$done['order']['fulfilment_state']);
            same($mode==='sandbox',$done['simulated']);
            same($mode==='sandbox'?0:1,(int)$app->db->query('SELECT COUNT(*) FROM renewal_simulated_operations WHERE operation_id=?',[$id])->fetchColumn());
            $payments->webhook($connection['id'],$raw,$signature);$payments->processOne();
            same($mode==='sandbox'?0:1,(int)$app->db->query('SELECT COUNT(*) FROM renewal_simulated_operations WHERE operation_id=?',[$id])->fetchColumn());
            same(1,(int)$app->db->query('SELECT COUNT(*) FROM renewal_settlements WHERE order_id=?',[$id])->fetchColumn());
            if($expiredOrder){
                $old=$expiredOrder;$oldConnection=$app->db->one('SELECT * FROM renewal_connections WHERE id=?',[$old['connection_id']]);
                $expiry=(int)$app->db->one('SELECT expires_at FROM renewal_simulated_targets WHERE reseller_id=? AND line_id=?',[$reseller,$line])['expires_at'];
                $event=['type'=>'checkout.session.completed','livemode'=>true,'data'=>['object'=>['id'=>$old['provider_link_id'],'payment_status'=>'paid','client_reference_id'=>$old['id'],'metadata'=>['order_id'=>$old['id']],'amount_total'=>(int)$old['amount_minor'],'currency'=>strtolower($old['currency']),'payment_intent'=>'pi_'.$old['id']]]];
                $oldRaw=json_encode($event);$stamp=(string)time();$oldSig='t='.$stamp.',v1='.hash_hmac('sha256',$stamp.'.'.$oldRaw,$secrets->decrypt($oldConnection['webhook_cipher']));
                $payments->webhook($oldConnection['id'],$oldRaw,$oldSig);$payments->processOne();
                $held=$app->db->one('SELECT payment_state,fulfilment_state FROM renewal_orders WHERE id=?',[$old['id']]);same('paid',$held['payment_state']);same('needs_review',$held['fulfilment_state']);
                same($expiry,(int)$app->db->one('SELECT expires_at FROM renewal_simulated_targets WHERE reseller_id=? AND line_id=?',[$reseller,$line])['expires_at']);
                same(0,(int)$app->db->query('SELECT COUNT(*) FROM renewal_simulated_operations WHERE operation_id=?',[$old['id']])->fetchColumn());
            }
        } finally {$settings->setPolicy($owner,['enabled'=>false,'testing_only'=>true]);}
    };
    $tests['Canadian Square hosted card payment verifies an OPEN paid order and renews the same line once']=fn()=> $flow('square','live','998801');
    $tests['Canadian direct Stripe card payment verifies captured gross fiat amount and renews once']=fn()=> $flow('stripe','live','998802');
    $tests['gateway change requires confirmed unpaid expiry and late money cannot renew a line twice']=fn()=> $flow('stripe','live','998805','switch');
    $tests['provider capture lag defers verification of the same invoice instead of renewing or charging again']=fn()=> $flow('stripe','live','998806','delay');
    $tests['refunded settlement is held and original evidence can be rechecked without a new charge']=fn()=> $flow('stripe','live','998807','refund');
    $tests['Stripe sandbox payment never reaches the panel worker or restores real account access']=fn()=> $flow('stripe','sandbox','998803');
    $tests['Square sandbox payment never reaches the panel worker or restores real account access']=fn()=> $flow('square','sandbox','998804');
    $tests['webhooks cannot cross merchant or live sandbox boundaries even with a valid endpoint signature']=function()use($app,$c,$alpha,$tenantA,$settings,$gateway,$worker,$secrets):void{
        $artifacts=new ControlCenter\BrandArtifacts($app->db,$secrets,$c);$payments=new ControlCenter\RenewalPayments($app->db,$c,$app->auth,$app->management,$secrets,$artifacts,$settings,$gateway,$worker);
        $live=$app->db->one("SELECT o.* FROM renewal_orders o JOIN renewal_connections c ON c.id=o.connection_id WHERE o.tenant_id=? AND c.gateway='stripe' AND c.mode='live' ORDER BY o.created_at DESC,o.id DESC LIMIT 1",[$tenantA['id']]);check($live!==null);
        $sandbox=$settings->connection($tenantA['id'],'stripe');same('sandbox',$sandbox['mode']);
        $event=['type'=>'checkout.session.completed','livemode'=>false,'data'=>['object'=>['id'=>$live['provider_link_id'],'payment_status'=>'paid','client_reference_id'=>$live['id'],'metadata'=>['order_id'=>$live['id']],'amount_total'=>(int)$live['amount_minor'],'currency'=>strtolower($live['currency']),'payment_intent'=>'pi_'.$live['id']]]];
        $raw=json_encode($event);$stamp=(string)time();$sig='t='.$stamp.',v1='.hash_hmac('sha256',$stamp.'.'.$raw,$secrets->decrypt($sandbox['webhook_cipher']));
        problem(403,fn()=> $payments->webhook($sandbox['id'],$raw,$sig));
        $original=$app->db->one('SELECT * FROM renewal_connections WHERE id=?',[$live['connection_id']]);
        $event['livemode']=true;$event['account']='acct_WRONG';$raw=json_encode($event);$sig='t='.$stamp.',v1='.hash_hmac('sha256',$stamp.'.'.$raw,$secrets->decrypt($original['webhook_cipher']));
        problem(403,fn()=> $payments->webhook($original['id'],$raw,$sig));
        same([],array_column($settings->candidates($tenantA['id'],'CA','CAD',false),'gateway'));
    };
    $tests['card verification rejects wrong totals references currencies locations and refunds rather than trusting completion strings']=function()use($app,$c,$tenantA,$gateway,&$overrides):void{
        $o=$app->db->one("SELECT o.* FROM renewal_orders o JOIN renewal_connections c ON c.id=o.connection_id WHERE o.tenant_id=? AND c.gateway='square' AND c.mode='live' ORDER BY o.created_at DESC,o.id DESC LIMIT 1",[$tenantA['id']]);check($o!==null);
        $connection=$app->db->one('SELECT * FROM renewal_connections WHERE id=?',[$o['connection_id']]);$path='square/v2/orders/'.$o['provider_link_id'];
        $valid=['id'=>$o['provider_link_id'],'reference_id'=>$o['id'],'location_id'=>'LOC_CA','total_money'=>['amount'=>(int)$o['amount_minor'],'currency'=>$o['currency']],'net_amount_due_money'=>['amount'=>0],'state'=>'OPEN','tenders'=>[['payment_id'=>'pay_'.$o['id']]]];
        foreach([['reference_id'=>str_repeat('f',32)],['location_id'=>'OTHER'],['total_money'=>['amount'=>999,'currency'=>$o['currency']]],['total_money'=>['amount'=>(int)$o['amount_minor'],'currency'=>'USD']]] as $bad){
            $overrides[$path]=['order'=>array_replace($valid,$bad)];problem(409,fn()=> $gateway->status($connection,$o));
        }unset($overrides[$path]);
        $p=['id'=>'pay_'.$o['id'],'order_id'=>$o['provider_link_id'],'location_id'=>'LOC_CA','status'=>'COMPLETED','source_type'=>'CARD','card_details'=>['status'=>'CAPTURED'],'amount_money'=>['amount'=>(int)$o['amount_minor'],'currency'=>$o['currency']],'refunded_money'=>['amount'=>0],'tip_money'=>['amount'=>0]];
        $path='square/v2/payments/pay_'.$o['id'];
        foreach([['refunded_money'=>['amount'=>1]],['amount_money'=>['amount'=>999,'currency'=>$o['currency']]],['order_id'=>'OTHER'],['location_id'=>'OTHER'],['source_type'=>'CASH'],['source_type'=>'EXTERNAL'],['card_details'=>['status'=>'AUTHORIZED']]] as $bad){$overrides[$path]=['payment'=>array_replace($p,$bad)];problem(409,fn()=> $gateway->status($connection,$o));}unset($overrides[$path]);
        $event=['event'=>'payment.completed','link_id'=>$o['provider_link_id'],'metadata'=>['order_id'=>$o['id']],'amount_minor'=>(int)$o['amount_minor'],'currency'=>$o['currency'],'settlement_ref'=>'square:pay_'.$o['id']];
        $status=$gateway->status($connection,$o);$card=new ControlCenter\CardGateway($c,new ControlCenter\Secrets($c));
        foreach([['amount_minor'=>999],['merchant_id'=>'square:OTHER'],['order_ref'=>str_repeat('a',32)],['currency'=>'USD'],['settlement_ref'=>'square:OTHER']] as $bad)problem(409,fn()=> $card->completed($connection,$event,$o,array_replace($status,$bad)));
        problem(503,fn()=> $card->completed($connection,$event,$o,array_replace($status,['status'=>'pending','fully_paid'=>false])));
    };
    $tests['provider setup reserves its final webhook URL before credentials and retries cannot replace a completed setup']=function()use($app,$c,$alpha,$beta,$tenantA,$settings):void{
        $draft=$settings->prepareConnection($alpha,['gateway'=>'square']);
        same($draft,$settings->prepareConnection($alpha,['gateway'=>'square']));
        same($c->origin().'/api/renewals/webhooks/'.$draft['setup_id'],$draft['webhook_url']);
        $input=['setup_id'=>$draft['setup_id'],'gateway'=>'square','mode'=>'sandbox','api_key'=>'SYNTHETIC_SQUARE_TOKEN_ONLY','webhook_secret'=>'SYNTHETIC_SETUP_WEBHOOK_SECRET','location_id'=>'LOC_CA'];
        problem(404,fn()=> $settings->saveConnection($beta,$input));
        problem(404,fn()=> $settings->saveConnection($alpha,array_replace($input,['gateway'=>'stripe','api_key'=>'sk_test_SYNTHETIC_KEY_ONLY'])));
        $settings->saveConnection($alpha,$input);same($draft['setup_id'],$settings->connection($tenantA['id'],'square')['id']);
        $before=(int)$app->db->query('SELECT COUNT(*) FROM renewal_connections')->fetchColumn();
        $settings->saveConnection($alpha,$input);same($before,(int)$app->db->query('SELECT COUNT(*) FROM renewal_connections')->fetchColumn());
        problem(409,fn()=> $settings->saveConnection($alpha,array_replace($input,['webhook_secret'=>'SYNTHETIC_DIFFERENT_WEBHOOK_SECRET'])));
        check($draft['setup_id']!==$settings->prepareConnection($alpha,['gateway'=>'square'])['setup_id']);
    };
    $tests['disabled Canadian package price does not silently fall back to the default country price']=function()use($app,$c,$owner,$alpha,$tenantA,$settings,$gateway,$worker,$secrets):void{
        $price=$app->db->one("SELECT * FROM renewal_plans WHERE tenant_id=? AND package_id=777 AND country='CA'",[$tenantA['id']]);
        $settings->savePrice($alpha,['id'=>$price['id'],'expected_revision'=>(int)$price['revision'],'package_id'=>777,'country'=>'CA','currency'=>'CAD','amount'=>'10.00','enabled'=>false]);
        $o=$app->db->one("SELECT o.* FROM renewal_orders o JOIN renewal_connections c ON c.id=o.connection_id WHERE o.tenant_id=? AND c.gateway='square' AND c.mode='sandbox' ORDER BY o.created_at DESC LIMIT 1",[$tenantA['id']]);check($o!==null);
        $session=$app->db->one('SELECT * FROM renewal_sessions WHERE id=?',[$o['session_id']]);$id=ControlCenter\Database::id();$token=bin2hex(random_bytes(32));
        $session['id']=$id;$session['token_hash']=$secrets->mac("renewal-checkout-token-v1\n$token");$session['token_cipher']=$secrets->encrypt($token);$session['order_id']=null;$session['expires_at']=gmdate('Y-m-d H:i:s',time()+600);
        $columns=array_keys($session);$app->db->query('INSERT INTO renewal_sessions ('.implode(',',$columns).') VALUES ('.implode(',',array_fill(0,count($columns),'?')).')',array_values($session));
        $artifacts=new ControlCenter\BrandArtifacts($app->db,$secrets,$c);$payments=new ControlCenter\RenewalPayments($app->db,$c,$app->auth,$app->management,$secrets,$artifacts,$settings,$gateway,$worker);
        $settings->setPolicy($owner,['enabled'=>true,'testing_only'=>true]);
        try{same(null,$payments->info($token)['renewal']);problem(409,fn()=> $payments->checkout(['session_token'=>$token,'price_id'=>$price['id'],'price_revision'=>(int)$price['revision']+1,'package_fingerprint'=>$price['plan_fingerprint'],'email'=>'receipt@example.test'],'127.0.0.69'));}
        finally{$settings->setPolicy($owner,['enabled'=>false,'testing_only'=>true]);}
    };
    $tests['invoice diagnostics read provider status without modifying records or requesting another payment']=function()use($app,$c,$tenantA,$gateway,$secrets,&$calls):void{
        $o=$app->db->one("SELECT o.* FROM renewal_orders o JOIN renewal_connections c ON c.id=o.connection_id WHERE o.tenant_id=? AND c.gateway='square' AND c.mode='sandbox' ORDER BY o.created_at DESC LIMIT 1",[$tenantA['id']]);check($o!==null);
        $before=$app->db->all('SELECT * FROM renewal_orders ORDER BY id');$count=count($calls);
        $report=ControlCenter\RenewalInvoiceStatus::probe($app,$c,$o['id'],$gateway);
        same(false,$report['records_changed']);same(false,$report['payment_requested']);same(false,$report['renewal_requested']);same(false,$report['provider_confirmed_expired_unpaid']);same(true,$report['provider_fully_paid']);
        same($before,$app->db->all('SELECT * FROM renewal_orders ORDER BY id'));
        foreach(array_slice($calls,$count) as $call)same(null,$call['body']);
        $conn=$app->db->one('SELECT * FROM renewal_connections WHERE id=?',[$o['connection_id']]);check(!str_contains(json_encode($report),$secrets->decrypt($conn['api_cipher'])));check(!str_contains(json_encode($report),$secrets->decrypt($conn['webhook_cipher'])));
        problem(404,fn()=> ControlCenter\RenewalInvoiceStatus::probe($app,$c,str_repeat('f',32),$gateway));
    };
    $tests['only exact merchant-scoped unpaid provider expiry may release an awaiting invoice']=static function():void{
        $o=['payment_state'=>'awaiting','fulfilment_state'=>'not_paid','merchant_id'=>'merchant','provider_link_id'=>'link','currency'=>'CAD','amount_minor'=>'1000','expires_at'=>'2020-01-01 00:00:00'];
        $s=['status'=>'expired','fully_paid'=>false,'merchant_id'=>'merchant','link_id'=>'link','currency'=>'CAD','amount_minor'=>1000];
        same(true,ControlCenter\RampexGateway::unpaidExpiryConfirmed($o,$s));
        foreach([['status'=>'active'],['fully_paid'=>true],['merchant_id'=>'other'],['link_id'=>'other'],['currency'=>'USD'],['amount_minor'=>999]] as $bad)same(false,ControlCenter\RampexGateway::unpaidExpiryConfirmed($o,array_replace($s,$bad)));
        same(false,ControlCenter\RampexGateway::unpaidExpiryConfirmed(array_replace($o,['payment_state'=>'paid']),$s));
        same(false,ControlCenter\RampexGateway::unpaidExpiryConfirmed(array_replace($o,['fulfilment_state'=>'fulfilled']),$s));
    };
}
