<?php
declare(strict_types=1);
namespace ControlCenter;

/** Direct hosted card checkout. No card data, onramp, client credentials or
 * browser-success trust. Each connection is the reseller's own merchant. */
final class CardGateway {
    public function __construct(private readonly Config $config,private readonly Secrets $secrets,private readonly ?\Closure $transport=null) {}
    private function request(string $gateway,string $mode,string $path,string $key,?array $body=null,?string $idempotency=null,?string $method=null):array {
        if(($this->config->get('renewal_payments',[])['allow_live_api']??false)!==true)throw new Problem(503,'Merchant API access is not enabled.');
        if($this->transport===null&&$this->config->local()&&($this->config->get('renewal_payments',[])['simulation_enabled']??false)===true)throw new Problem(503,'External merchant calls are disabled in the isolated simulator.');
        if(!in_array($gateway,['stripe','square'],true)||!in_array($mode,['live','sandbox'],true)||!str_starts_with($path,'/'))throw new \LogicException('Invalid card adapter request.');
        $stripe=$gateway==='stripe';$host=$stripe?'https://api.stripe.com':($mode==='sandbox'?'https://connect.squareupsandbox.com':'https://connect.squareup.com');
        $headers=['Authorization: Bearer '.$key,'Accept: application/json',$stripe?'Stripe-Version: 2026-10-28.endive':'Square-Version: 2026-09-16',$stripe?'Content-Type: application/x-www-form-urlencoded':'Content-Type: application/json'];
        if($stripe&&$idempotency!==null)$headers[]='Idempotency-Key: '.$idempotency;
        $status=200;
        $method??=$body===null?'GET':'POST';if(!in_array($method,['GET','POST','DELETE'],true))throw new \LogicException('Invalid merchant request method.');
        if($this->transport){$reply=($this->transport)($gateway.$path,$body,$headers,$method);if(is_array($reply)){$status=$reply['status'];$raw=$reply['body'];}else $raw=$reply;}
        else {
            $raw='';$curl=curl_init($host.$path);
            $options=[CURLOPT_HTTPHEADER=>$headers,CURLOPT_FOLLOWLOCATION=>false,CURLOPT_SSL_VERIFYPEER=>true,CURLOPT_SSL_VERIFYHOST=>2,CURLOPT_CONNECTTIMEOUT=>5,CURLOPT_TIMEOUT=>20,CURLOPT_PROTOCOLS=>CURLPROTO_HTTPS,CURLOPT_WRITEFUNCTION=>static function($h,$chunk)use(&$raw){if(strlen($raw)+strlen($chunk)>262144)return 0;$raw.=$chunk;return strlen($chunk);}];
            if($body!==null){$options[CURLOPT_POST]=true;$options[CURLOPT_POSTFIELDS]=$stripe?http_build_query($body,'','&',PHP_QUERY_RFC3986):json_encode($body,JSON_THROW_ON_ERROR);}
            if($method==='DELETE')$options[CURLOPT_CUSTOMREQUEST]='DELETE';
            curl_setopt_array($curl,$options);$ok=curl_exec($curl);$status=curl_getinfo($curl,CURLINFO_RESPONSE_CODE);curl_close($curl);
            if($ok===false)throw new Problem(503,'Payment provider unavailable. Retain the same invoice; do not pay again.');
        }
        if(!in_array($status,[200,201],true)){
            $creation=$method==='POST'&&in_array($path,['/v1/checkout/sessions','/v2/online-checkout/payment-links'],true);
            if($creation&&$idempotency!==null&&in_array($status,[400,401,403,404,422],true))throw new RampexLinkRejected('The selected card provider rejected checkout creation. No new checkout was confirmed. Review the merchant settings.');
            throw new Problem(in_array($status,[401,403],true)?422:503,'Merchant API request failed. Check the account permissions and environment.');
        }
        try{$r=json_decode($raw,true,32,JSON_THROW_ON_ERROR);}catch(\JsonException){throw new Problem(503,'Invalid merchant response.');}
        if(!is_array($r)||isset($r['error'])||!empty($r['errors']))throw new Problem(503,'Merchant request was not confirmed.');return $r;
    }
    private static function id(mixed $value):string {
        if(!is_string($value)||!preg_match('/^[A-Za-z0-9_-]{1,180}$/D',$value))throw new Problem(503,'Invalid merchant resource identity.');return $value;
    }
    private static function url(mixed $value,string $gateway,string $mode):string {
        $u=is_string($value)?parse_url($value):false;$hosts=$gateway==='stripe'?['checkout.stripe.com']:['square.link','checkout.square.site'];
        if($gateway==='square'&&$mode==='sandbox')$hosts=array_merge($hosts,['sandbox.square.link','sandbox.checkout.square.site']);
        if(!$u||($u['scheme']??'')!=='https'||!in_array($u['host']??'',$hosts,true)||isset($u['user'])||isset($u['pass'])||(isset($u['port'])&&$u['port']!==443))throw new Problem(503,'Untrusted merchant checkout destination.');return $value;
    }
    public function validate(string $gateway,string $key,string $mode,array $options):array {
        if(!in_array($mode,['live','sandbox'],true)||strlen($key)<16||strlen($key)>512||preg_match('/[\x00-\x20\x7f]/',$key))throw new Problem(422,'Invalid card merchant connection.');
        if($gateway==='stripe'){
            if(!preg_match($mode==='live'?'/^(sk|rk)_live_/':'/^(sk|rk)_test_/',$key))throw new Problem(422,'Stripe key does not match the selected environment.');
            $r=$this->request('stripe',$mode,'/v1/account',$key);$id=self::id($r['id']??null);
            if(!is_string($r['country']??null)||!preg_match('/^[A-Z]{2}$/D',$r['country'])||($mode==='live'&&(($r['charges_enabled']??false)!==true||($r['details_submitted']??false)!==true)))throw new Problem(422,'Complete Stripe merchant onboarding before connecting this account.');
            return ['merchant_id'=>'stripe:'.$id,'business_name'=>mb_substr($r['business_profile']['name']??'Stripe merchant',0,120),'options'=>['country'=>$r['country'],'currencies'=>['USD','CAD']]];
        }
        if($gateway!=='square')throw new Problem(422,'Unsupported merchant gateway.');
        $location=self::id($options['location_id']??null);
        $r=$this->request('square',$mode,'/v2/merchants/me',$key);$m=$r['merchant']??[];$id=self::id($m['id']??null);
        $r=$this->request('square',$mode,'/v2/locations/'.rawurlencode($location),$key);$l=$r['location']??[];
        if(($m['status']??null)!=='ACTIVE'||($l['status']??null)!=='ACTIVE'||($l['merchant_id']??null)!==$id||!is_string($m['country']??null)||!preg_match('/^[A-Z]{2}$/D',$m['country'])||($l['country']??null)!==$m['country']||!isset(RampexPaymentVerifier::currencyExponents()[$l['currency']??''])||!in_array('CREDIT_CARD_PROCESSING',$l['capabilities']??[],true))throw new Problem(422,'Square requires an active, card-enabled location owned by this merchant.');
        return ['merchant_id'=>'square:'.$id,'business_name'=>mb_substr($m['business_name']??'Square merchant',0,120),'options'=>['location_id'=>$location,'country'=>$l['country'],'currencies'=>[$l['currency']]]];
    }
    public function options(array $c):array {
        try{$r=json_decode($this->secrets->decrypt($c['provider_options_cipher']),true,8,JSON_THROW_ON_ERROR);}catch(\Throwable){throw new Problem(503,'Merchant location settings are unavailable.');}
        if(!is_array($r))throw new Problem(503,'Merchant settings are invalid.');return $r;
    }
    public function supports(array $c,string $country,string $currency):bool {
        $options=$this->options($c);return in_array($country,['US','CA'],true)&&in_array($currency,$options['currencies']??[],true)&&is_string($options['country']??null)&&preg_match('/^[A-Z]{2}$/D',$options['country'])===1;
    }
    public function create(array $c,array $o,string $email,string $return):array {
        $g=$c['gateway'];$key=$this->secrets->decrypt($c['api_cipher']);$options=$this->options($c);$amount=(int)$o['amount_minor'];
        if(!$this->supports($c,$o['country'],$o['currency']))throw new RampexLinkRejected('This gateway cannot process the selected country and invoice currency.');
        if($g==='stripe'){
            $r=$this->request($g,$c['mode'],'/v1/checkout/sessions',$key,['mode'=>'payment','allowed_payment_method_types'=>['card'],'billing_address_collection'=>'required','customer_email'=>$email,'client_reference_id'=>$o['id'],'metadata'=>['order_id'=>$o['id'],'country'=>$o['country']],'payment_intent_data'=>['metadata'=>['order_id'=>$o['id']]],'line_items'=>[['quantity'=>1,'price_data'=>['currency'=>strtolower($o['currency']),'unit_amount'=>$amount,'product_data'=>['name'=>'Current subscription renewal']]]],'success_url'=>$return,'cancel_url'=>$return],$o['id']);
            if(($r['client_reference_id']??null)!==$o['id']||($r['amount_total']??null)!==$amount||strtoupper($r['currency']??'')!==$o['currency']||($r['livemode']??null)!==($c['mode']==='live'))throw new Problem(503,'Stripe quote verification failed; retain the original invoice.');
            return ['link_id'=>self::id($r['id']??null),'checkout_id'=>self::id($r['id']??null),'url'=>self::url($r['url']??null,$g,$c['mode'])];
        }
        $r=$this->request($g,$c['mode'],'/v2/online-checkout/payment-links',$key,['idempotency_key'=>$o['id'],'order'=>['location_id'=>$options['location_id'],'reference_id'=>$o['id'],'line_items'=>[['name'=>'Current subscription renewal','quantity'=>'1','base_price_money'=>['amount'=>$amount,'currency'=>$o['currency']]]]],'checkout_options'=>['allow_tipping'=>false,'ask_for_shipping_address'=>false,'redirect_url'=>$return,'accepted_payment_methods'=>['apple_pay'=>true,'google_pay'=>true,'cash_app_pay'=>false,'afterpay_clearpay'=>false]],'pre_populated_data'=>['buyer_email'=>$email],'payment_note'=>'Renewal order '.$o['id']],$o['id']);
        $p=$r['payment_link']??[];$id=self::id($p['order_id']??null);
        $order=$this->request($g,$c['mode'],'/v2/orders/'.rawurlencode($id),$key)['order']??[];
        $this->squareOrder($order,$o,$options);
        return ['link_id'=>$id,'checkout_id'=>self::id($p['id']??null),'url'=>self::url($p['url']??null,$g,$c['mode'])];
    }
    private function squareOrder(array $r,array $o,array $options):void {
        if(!empty($o['provider_link_id'])&&($r['id']??null)!==$o['provider_link_id'])throw new Problem(409,'Square order identity does not match the original invoice.');
        if(($r['reference_id']??null)!==$o['id']||($r['location_id']??null)!==$options['location_id']||($r['total_money']['amount']??null)!==(int)$o['amount_minor']||($r['total_money']['currency']??null)!==$o['currency'])throw new Problem(409,'Square order does not match the original invoice.');
    }
    public function status(array $c,array $o):array {
        $g=$c['gateway'];$key=$this->secrets->decrypt($c['api_cipher']);$id=self::id($o['provider_link_id']);$options=$this->options($c);
        $base=['link_id'=>$id,'merchant_id'=>$c['merchant_id'],'status'=>'pending','fully_paid'=>false,'amount_minor'=>(int)$o['amount_minor'],'currency'=>$o['currency'],'order_ref'=>$o['id']];
        if($g==='stripe'){
            $r=$this->request($g,$c['mode'],'/v1/checkout/sessions/'.rawurlencode($id).'?expand%5B%5D=payment_intent.latest_charge',$key);
            if(($r['id']??null)!==$id||($r['client_reference_id']??null)!==$o['id']||($r['metadata']['order_id']??null)!==$o['id']||($r['amount_total']??null)!==(int)$o['amount_minor']||strtoupper($r['currency']??'')!==$o['currency']||($r['livemode']??null)!==($c['mode']==='live'))throw new Problem(409,'Stripe payment does not match this invoice.');
            $p=$r['payment_intent']??[];$charge=is_array($p)?($p['latest_charge']??[]):[];
            $unpaid=($r['payment_status']??null)==='unpaid'&&array_key_exists('payment_intent',$r)&&($r['payment_intent']===null||(is_array($p)&&in_array($p['status']??null,['requires_payment_method','canceled'],true)&&($p['amount_received']??null)===0));
            if(($r['status']??null)==='expired')return $unpaid?array_replace($base,['status'=>'expired']):$base;
            if(($r['status']??null)==='open')return array_replace($base,['cancellable'=>$unpaid]);
            if(($r['status']??null)!=='complete'||($r['payment_status']??null)!=='paid'||!is_array($p)||($p['status']??null)!=='succeeded'||!is_array($charge)||($charge['paid']??false)!==true||($charge['captured']??false)!==true)return $base;
            if(($p['amount_received']??null)!==(int)$o['amount_minor']||strtoupper($p['currency']??'')!==$o['currency']||($p['metadata']['order_id']??null)!==$o['id']||($p['livemode']??null)!==($c['mode']==='live')||($charge['amount_refunded']??null)!==0||($charge['payment_method_details']['type']??null)!=='card')throw new Problem(409,'Stripe settlement is not a matching, unrefunded captured card payment.');
            return array_replace($base,['status'=>'completed','fully_paid'=>true,'settlement_ref'=>'stripe:'.self::id($p['id']??null)]);
        }
        $r=$this->request($g,$c['mode'],'/v2/orders/'.rawurlencode($id),$key)['order']??[];$this->squareOrder($r,$o,$options);
        if(($r['state']??null)==='CANCELED'&&empty($r['tenders'])&&$this->squareNoPaymentInFlight($c,$r,$options))return array_replace($base,['status'=>'expired']);
        // Square checkout may leave a paid digital order OPEN until its
        // fulfilment is completed. Captured Payment status is authoritative.
        if(!in_array($r['state']??null,['OPEN','COMPLETED'],true)||($r['net_amount_due_money']['amount']??0)!==0)return $base;
        $tenders=$r['tenders']??[];if(!$tenders)return $base;if(count($tenders)!==1)throw new Problem(409,'Square split settlement requires review of the original invoice.');
        $pid=self::id($tenders[0]['payment_id']??null);$p=$this->request($g,$c['mode'],'/v2/payments/'.rawurlencode($pid),$key)['payment']??[];
        if(($p['status']??null)!=='COMPLETED')return $base;
        if(($p['id']??null)!==$pid||($p['order_id']??null)!==$id||($p['location_id']??null)!==$options['location_id']||($p['amount_money']['amount']??null)!==(int)$o['amount_minor']||($p['amount_money']['currency']??null)!==$o['currency']||($p['refunded_money']['amount']??0)!==0||($p['tip_money']['amount']??0)!==0||($p['source_type']??null)!=='CARD'||($p['card_details']['status']??null)!=='CAPTURED')throw new Problem(409,'Square settlement is not a matching, unrefunded captured card payment.');
        return array_replace($base,['status'=>'completed','fully_paid'=>true,'settlement_ref'=>'square:'.$pid]);
    }
    private function squareNoPaymentInFlight(array $c,array $order,array $options):bool {
        if(!is_string($order['created_at']??null)||!preg_match('/^\d{4}-\d{2}-\d{2}T/',$order['created_at']))throw new Problem(503,'Square order creation time is unavailable; cancellation cannot be confirmed.');
        try{$begin=(new \DateTimeImmutable($order['created_at']))->modify('-1 second')->format(DATE_ATOM);}catch(\Throwable){throw new Problem(503,'Square order time could not be verified.');}
        $cursor=null;$key=$this->secrets->decrypt($c['api_cipher']);
        for($page=0;$page<50;$page++){
            $path='/v2/payments?'.http_build_query(['begin_time'=>$begin,'location_id'=>$options['location_id'],'sort_order'=>'DESC','limit'=>100]+($cursor!==null?['cursor'=>$cursor]:[]),'','&',PHP_QUERY_RFC3986);
            $r=$this->request('square',$c['mode'],$path,$key);
            if(isset($r['payments'])&&!is_array($r['payments']))throw new Problem(503,'Square payment lookup is incomplete.');
            foreach($r['payments']??[] as $p)if(($p['order_id']??null)===$order['id']&&!in_array($p['status']??null,['FAILED','CANCELED'],true))return false;
            $cursor=$r['cursor']??null;if($cursor===null||$cursor==='')return true;
            if(!is_string($cursor)||strlen($cursor)>4096)throw new Problem(503,'Square payment lookup cursor is invalid.');
        }throw new Problem(503,'Square payment lookup is incomplete; retain the original invoice.');
    }
    private function squareLink(array $c,array $o,string $key):string {
        $known=$o['provider_checkout_id']??null;
        if($known){$r=$this->request('square',$c['mode'],'/v2/online-checkout/payment-links/'.rawurlencode(self::id($known)),$key);if(($r['payment_link']['id']??null)!==$known||($r['payment_link']['order_id']??null)!==$o['provider_link_id'])throw new Problem(409,'Square checkout identity does not match this invoice.');return $known;}
        $cursor=null;
        for($page=0;$page<50;$page++){
            $r=$this->request('square',$c['mode'],'/v2/online-checkout/payment-links?'.http_build_query(['limit'=>100]+($cursor!==null?['cursor'=>$cursor]:[]),'','&',PHP_QUERY_RFC3986),$key);
            foreach($r['payment_links']??[] as $p)if(($p['order_id']??null)===$o['provider_link_id'])return self::id($p['id']??null);
            $cursor=$r['cursor']??null;if($cursor===null||$cursor==='')break;
            if(!is_string($cursor)||strlen($cursor)>4096)break;
        }throw new Problem(503,'Original Square checkout link could not be located; retain the invoice.');
    }
    public function voidUnpaid(array $c,array $o):array {
        $status=$this->status($c,$o);if(RampexGateway::unpaidExpiryConfirmed($o,$status))return $status;
        $key=$this->secrets->decrypt($c['api_cipher']);$id=self::id($o['provider_link_id']);
        if($c['gateway']==='stripe'){
            if(($status['cancellable']??false)!==true)throw new Problem(409,'Stripe payment is paid, processing or not safely cancellable. Retain the original invoice.');
            $this->request('stripe',$c['mode'],'/v1/checkout/sessions/'.rawurlencode($id).'/expire',$key,[],'void-'.$o['id']);
        }else{
            $options=$this->options($c);$order=$this->request('square',$c['mode'],'/v2/orders/'.rawurlencode($id),$key)['order']??[];$this->squareOrder($order,$o,$options);
            if(!in_array($order['state']??null,['DRAFT','OPEN'],true)||!empty($order['tenders'])||!$this->squareNoPaymentInFlight($c,$order,$options))throw new Problem(409,'Square payment is paid or processing. Retain the original invoice.');
            $link=$this->squareLink($c,$o,$key);$r=$this->request('square',$c['mode'],'/v2/online-checkout/payment-links/'.rawurlencode($link),$key,null,null,'DELETE');
            if(($r['id']??null)!==$link||($r['cancelled_order_id']??null)!==$id)throw new Problem(503,'Square cancellation reply could not be confirmed.');
        }
        $status=$this->status($c,$o);
        if(!RampexGateway::unpaidExpiryConfirmed($o,$status))throw new Problem(503,'Provider has not confirmed cancellation without payment. No replacement checkout was opened.');
        return $status;
    }
    public function event(array $c,string $raw,string $signature,string $url,bool $fresh=true):array {
        $g=$c['gateway'];$secret=$this->secrets->decrypt($c['webhook_cipher']);$valid=false;
        if(strlen($raw)>65536||strlen($signature)>1024)throw new Problem(401,'Invalid payment notification.');
        if($g==='stripe'){
            $timestamp=null;$signatures=[];foreach(explode(',',$signature) as $part){$v=explode('=',trim($part),2);if(count($v)!==2)continue;if($v[0]==='t'&&ctype_digit($v[1]))$timestamp=$v[1];if($v[0]==='v1'&&preg_match('/^[a-f0-9]{64}$/D',$v[1]))$signatures[]=$v[1];}
            if($timestamp!==null&&(!$fresh||abs(time()-(int)$timestamp)<=300))foreach($signatures as $s)$valid=hash_equals(hash_hmac('sha256',$timestamp.'.'.$raw,$secret),$s)||$valid;
        }elseif($g==='square')$valid=hash_equals(base64_encode(hash_hmac('sha256',$url.$raw,$secret,true)),$signature);
        if(!$valid)throw new Problem(401,'Invalid payment notification signature.');
        try{$r=json_decode($raw,true,32,JSON_THROW_ON_ERROR);}catch(\JsonException){throw new Problem(422,'Invalid payment notification.');}
        $native=substr($c['merchant_id'],strlen($g)+1);$event=['event'=>'ignored'];
        if($g==='stripe'){
            if(isset($r['account'])&&$r['account']!==$native)throw new Problem(403,'Notification belongs to another merchant.');
            $p=$r['data']['object']??[];if(($r['livemode']??null)!==($c['mode']==='live'))throw new Problem(403,'Notification environment mismatch.');
            if(!in_array($r['type']??null,['checkout.session.completed','checkout.session.async_payment_succeeded','checkout.session.expired'],true))return $event;
            $expired=$r['type']==='checkout.session.expired';if(!$expired&&($p['payment_status']??null)!=='paid')return $event;
            $ref=$p['client_reference_id']??null;if(($p['metadata']['order_id']??null)!==$ref)throw new Problem(409,'Notification invoice reference mismatch.');
            return ['event'=>$expired?'payment.expired':'payment.completed','link_id'=>self::id($p['id']??null),'metadata'=>['order_id'=>$ref],'amount_minor'=>$p['amount_total']??null,'currency'=>strtoupper($p['currency']??''),'settlement_ref'=>$expired?null:'stripe:'.self::id($p['payment_intent']??null)];
        }
        if(($r['merchant_id']??null)!==$native)throw new Problem(403,'Notification belongs to another merchant.');
        if(!in_array($r['type']??null,['payment.created','payment.updated'],true))return $event;
        $p=$r['data']['object']['payment']??[];if(($p['status']??null)!=='COMPLETED')return $event;
        $note=$p['note']??'';$ref=is_string($note)&&preg_match('/^Renewal order ([a-f0-9]{32})$/D',$note,$m)?$m[1]:null;
        return ['event'=>'payment.completed','link_id'=>self::id($p['order_id']??null),'metadata'=>['order_id'=>$ref],'amount_minor'=>$p['amount_money']['amount']??null,'currency'=>$p['amount_money']['currency']??null,'settlement_ref'=>'square:'.self::id($p['id']??null)];
    }
    public function completed(array $c,array $event,array $order,array $status):array {
        if(($status['status']??null)==='pending'&&($status['fully_paid']??null)===false)throw new Problem(503,'Provider settlement is still being verified. Retain the original invoice; do not pay again.');
        if(($event['event']??null)!=='payment.completed'||($event['link_id']??null)!==$order['provider_link_id']||(isset($event['metadata']['order_id'])&&$event['metadata']['order_id']!==$order['id'])||($event['amount_minor']??null)!==(int)$order['amount_minor']||($event['currency']??null)!==$order['currency']||($status['merchant_id']??null)!==$order['merchant_id']||($status['link_id']??null)!==$order['provider_link_id']||($status['order_ref']??null)!==$order['id']||($status['fully_paid']??null)!==true||($status['status']??null)!=='completed'||($status['amount_minor']??null)!==(int)$order['amount_minor']||($status['currency']??null)!==$order['currency']||($status['settlement_ref']??null)!==($event['settlement_ref']??null)||!is_string($status['settlement_ref']??null)||strlen($status['settlement_ref'])>220)throw new Problem(409,'Merchant payment verification does not match the immutable invoice.');
        return ['settlement_ref'=>$status['settlement_ref']];
    }
}
