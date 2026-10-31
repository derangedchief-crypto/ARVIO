<?php
declare(strict_types=1);
namespace ControlCenter;

/** Merchant facade: preserve legacy Rampex evidence and route direct card
 * providers through their own fixed-host verification. External calls default OFF. */
final class RampexGateway {
    public function __construct(private readonly Config $config,private readonly Database $db,private readonly Secrets $secrets,private readonly ?\Closure $transport=null) {}
    public function mockAllowed():bool {return $this->config->local()&&($this->config->get('renewal_payments',[])['simulation_enabled']??false)===true;}
    private function request(string $path,string $key,?array $body=null):array {
        if(($this->config->get('renewal_payments',[])['allow_live_api']??false)!==true)throw new Problem(503,'Live Rampex API calls have not been approved.');
        $headers=['X-API-Key: '.$key,'Accept: application/json','Content-Type: application/json'];
        $status=200;
        if($this->transport){$reply=($this->transport)($path,$body,$headers);if(is_array($reply)){$status=$reply['status'];$raw=$reply['body'];}else $raw=$reply;}
        else{
            $raw='';$curl=curl_init('https://api.rampex.io/'.$path);
            $opts=[CURLOPT_HTTPHEADER=>$headers,CURLOPT_FOLLOWLOCATION=>false,CURLOPT_SSL_VERIFYPEER=>true,CURLOPT_SSL_VERIFYHOST=>2,CURLOPT_CONNECTTIMEOUT=>5,CURLOPT_TIMEOUT=>15,CURLOPT_PROTOCOLS=>CURLPROTO_HTTPS,CURLOPT_WRITEFUNCTION=>static function($h,$chunk)use(&$raw){if(strlen($raw)+strlen($chunk)>131072)return 0;$raw.=$chunk;return strlen($chunk);}];
            if($body!==null){$opts[CURLOPT_POST]=true;$opts[CURLOPT_POSTFIELDS]=json_encode($body,JSON_THROW_ON_ERROR);}
            curl_setopt_array($curl,$opts);$ok=curl_exec($curl);$status=curl_getinfo($curl,CURLINFO_RESPONSE_CODE);curl_close($curl);
            if($ok===false)throw new Problem(503,'Rampex request unavailable; keep the same order and do not pay again.');
        }
        try{$data=json_decode($raw,true,16,JSON_THROW_ON_ERROR);}catch(\JsonException){throw new Problem(503,'Invalid Rampex response.');}
        if(!is_array($data))throw new Problem(503,'Invalid Rampex response.');
        $code=is_array($data['error']??null)?($data['error']['code']??null):null;
        $definite=in_array($status,[400,401,403],true)||(in_array($status,[200,201],true)&&in_array($code,['NO_WALLET','INVALID_API_KEY','INACTIVE_API_KEY','VALIDATION_ERROR','MISSING_API_KEY','UNAUTHORIZED'],true));
        if($path==='api-create-payment-link'&&$definite&&($data['success']??null)===false&&empty($data['data']['link_id'])&&empty($data['data']['payment_url']))throw new RampexLinkRejected($code==='NO_WALLET'?'Configure your payout wallet in Rampex Settings. No payment link or charge was created.':'Rampex rejected payment-link creation. Check the merchant settings; no payment link or charge was created.');
        if(in_array($status,[401,403],true))throw new Problem(422,'Rampex rejected this API key. Check its active status and permissions in your Rampex account.');
        if($code==='NO_WALLET')throw new Problem(409,'Configure your payout wallet in Rampex Settings before creating a checkout.');
        if(!in_array($status,[200,201],true)||($data['success']??true)!==true)throw new Problem(503,'Rampex request did not succeed; retain the original order.');
        return $data;
    }
    private function cards():CardGateway {return new CardGateway($this->config,$this->secrets,$this->transport);}
    public function validate(string $key,string $mode,string $gateway='rampex',array $options=[]):array {
        if($gateway!=='rampex')return $this->cards()->validate($gateway,$key,$mode,$options);
        if($mode==='mock'){
            if(!$this->mockAllowed()||!preg_match('/^mock_[A-Za-z0-9_-]{16,100}$/D',$key))throw new Problem(422,'Use a synthetic mock key on the isolated simulator.');
            return ['merchant_id'=>'mock-'.substr(hash('sha256',$key),0,24),'business_name'=>'DEMO merchant'];
        }
        if($mode!=='live'||strlen($key)<16||strlen($key)>512||preg_match('/[\x00-\x20\x7f]/',$key))throw new Problem(422,'Invalid merchant connection.');
        if($this->mockAllowed())throw new Problem(503,'Live merchants cannot be connected to the isolated renewal simulator.');
        $r=$this->request('api-validate-key',$key,[]);
        if(($r['valid']??false)!==true||!is_string($r['merchant_id']??null)||!preg_match('/^[A-Za-z0-9._:-]{1,200}$/D',$r['merchant_id']))throw new Problem(422,'Merchant key could not be verified.');
        $name=is_string($r['business_name']??null)?trim($r['business_name']):'';
        return ['merchant_id'=>$r['merchant_id'],'business_name'=>Input::text(mb_substr($name!==''?$name:'Rampex merchant',0,120),'merchant name',120)];
    }
    public function create(array $connection,array $order,?string $email):array {
        if(($connection['gateway']??'rampex')!=='rampex'){
            $session=$this->db->one('SELECT token_cipher FROM renewal_sessions WHERE id=?',[$order['session_id']]);
            if(!$session)throw new Problem(409,'Original checkout session is unavailable.');
            return $this->cards()->create($connection,$order,$email,$this->config->origin().'/renew/'.$this->secrets->decrypt($session['token_cipher']));
        }
        if($email===null)throw new RampexLinkRejected('Rampex requires an email address. Select Stripe or Square to continue without entering one here.');
        if($connection['mode']==='mock'){
            if(!$this->mockAllowed())throw new Problem(503,'Payment simulator unavailable.');
            $id='mock-'.$order['id'];
            $this->db->query('INSERT INTO renewal_simulations (link_id,merchant_id,currency,amount_minor) VALUES (?,?,?,?)',[$id,$connection['merchant_id'],$order['currency'],$order['amount_minor']]);
            return ['link_id'=>$id,'url'=>$this->config->origin().'/renew/demo/'.$order['id']];
        }
        $session=$this->db->one('SELECT token_cipher FROM renewal_sessions WHERE id=?',[$order['session_id']]);
        if(!$session)throw new Problem(409,'Original checkout session is unavailable.');
        $return=$this->config->origin().'/renew/'.$this->secrets->decrypt($session['token_cipher']);
        $data=$this->request('api-create-payment-link',$this->secrets->decrypt($connection['api_cipher']),[
            'amount'=>$order['amount_minor']/(10**RampexPaymentVerifier::exponent($order['currency'])),'currency'=>$order['currency'],'customer_email'=>$email,
            'provider'=>'hosted','woo_order_id'=>$order['id'],'woo_store_url'=>$this->config->origin(),'payment_url'=>$return]);
        $r=$data['data']??$data;
        if(!is_array($r))throw new Problem(503,'Invalid payment link response; retain the original order.');
        $id=$r['link_id']??null;$url=null;
        foreach([$r['redirect_url']??null,$r['payment_url']??null,$r['short_url']??null] as $candidate){
            $u=is_string($candidate)?parse_url($candidate):false;
            if($u&&($u['scheme']??null)==='https'&&in_array($u['host']??null,['checkout.rampex.io','rampex.io','pay.rampex.io'],true)&&(!isset($u['port'])||$u['port']===443)&&!isset($u['user'])&&!isset($u['pass'])){$url=$candidate;break;}
        }
        if(!is_string($id)||!preg_match('/^[A-Za-z0-9._:-]{1,200}$/D',$id)||$url===null)throw new Problem(503,'Payment link response could not be verified; do not retry creation.');
        return ['link_id'=>$id,'url'=>$url];
    }
    public function status(array $connection,array $order):array {
        if(($connection['gateway']??'rampex')!=='rampex')return $this->cards()->status($connection,$order);
        if($connection['mode']==='mock'){
            if(!$this->mockAllowed())throw new Problem(503,'Payment simulator unavailable.');
            $r=$this->db->one('SELECT * FROM renewal_simulations WHERE link_id=? AND merchant_id=?',[$order['provider_link_id'],$connection['merchant_id']])??throw new Problem(404,'Payment not found.');
            return ['link_id'=>$r['link_id'],'merchant_id'=>$r['merchant_id'],'status'=>$r['status'],'fully_paid'=>$r['status']==='completed','amount_minor'=>(int)$r['amount_minor'],'currency'=>$r['currency']];
        }
        $data=$this->request('api-get-payment-status?link_id='.rawurlencode($order['provider_link_id']),$this->secrets->decrypt($connection['api_cipher']));$r=$data['data']??$data;
        if(!is_array($r)||array_is_list($r))throw new Problem(503,'Invalid Rampex merchant status response.');
        $currency=Input::text($r['currency']??null,'Rampex currency',3);$amount=RampexPaymentVerifier::minor($r['amount']??null,$currency);
        if(isset($r['merchant_id'])&&$r['merchant_id']!==$connection['merchant_id'])throw new Problem(409,'Rampex merchant identity does not match this order.');
        // Documented merchant API terminal completion, NOT a browser result or
        // a guessed crypto/net-payout -> fiat conversion. Quote amount/currency
        // are independently matched by the verifier and signed notification.
        $full=($r['status']??null)==='completed'&&($r['fully_paid']??true)===true&&($r['partial_payment']??false)!==true;
        if($full&&($r['received_currency']??null)===$currency&&isset($r['received_amount'])){
            // Exact comparison against integer minor units. Do not round a
            // fractional-minor-unit underpayment UP; excess precision/overpayment is
            // not a second subscription period or a net-payout comparison.
            $value=$r['received_amount'];$text=is_string($value)||is_int($value)||is_float($value)?(string)$value:'';
            if(!preg_match('/^(0|[1-9][0-9]{0,11})(?:\.([0-9]{1,12}))?$/D',$text,$m))$full=false;
            else{$e=RampexPaymentVerifier::exponent($currency);$full=(int)$m[1]*(10**$e)+(int)str_pad(substr($m[2]??'',0,$e),$e,'0')>=$amount;}
        }
        $status=$r['status']??null;
        $received=$r['received_amount']??null;$zero=$received===null||((is_string($received)||is_int($received)||is_float($received))&&preg_match('/^0+(?:\.0+)?$/D',(string)$received));
        if($status==='expired'&&(($r['fully_paid']??false)===true||($r['partial_payment']??false)===true||!$zero))$status='pending';
        return ['link_id'=>$r['link_id']??null,'merchant_id'=>$connection['merchant_id'],'status'=>$status,'fully_paid'=>$full,'amount_minor'=>$amount,'currency'=>$currency];
    }
    public function supports(array $c,string $country,string $currency):bool {
        if(($c['gateway']??'rampex')==='rampex')return $country==='US'&&isset(RampexPaymentVerifier::currencyExponents()[$currency]);
        return $this->cards()->supports($c,$country,$currency);
    }
    public static function unpaidExpiryConfirmed(array $order,array $status):bool {
        return ($order['payment_state']??null)==='awaiting'&&($order['fulfilment_state']??null)!=='fulfilled'
            &&($status['status']??null)==='expired'&&($status['fully_paid']??null)===false
            &&($status['merchant_id']??null)===$order['merchant_id']&&($status['link_id']??null)===$order['provider_link_id']
            &&($status['amount_minor']??null)===(int)$order['amount_minor']&&($status['currency']??null)===$order['currency'];
    }
    public function voidUnpaid(array $c,array $o):array {
        if(($c['gateway']??'rampex')!=='rampex')return $this->cards()->voidUnpaid($c,$o);
        $status=$this->status($c,$o);if(self::unpaidExpiryConfirmed($o,$status))return $status;
        throw new Problem(409,'Rampex does not document an automatic cancellation API. The original Rampex link must be expired by Rampex before another gateway can be opened; no invoice was deleted.');
    }
    public function event(array $c,string $raw,string $signature,bool $fresh=true):array {
        if(($c['gateway']??'rampex')!=='rampex')return $this->cards()->event($c,$raw,$signature,$this->config->origin().'/api/renewals/webhooks/'.$c['id'],$fresh);
        if(!RampexPaymentVerifier::signatureValid($raw,$signature,$this->secrets->decrypt($c['webhook_cipher'])))throw new Problem(401,'Invalid payment notification.');
        try{$r=json_decode($raw,true,16,JSON_THROW_ON_ERROR);}catch(\JsonException){throw new Problem(422,'Invalid payment notification.');}
        if(!is_array($r)||!str_starts_with(ltrim($raw),'{'))throw new Problem(422,'Invalid payment notification.');return $r;
    }
    public function orderReference(array $c,array $event):?string {return $this->cards()->orderReference($c,$event);}
    public function completed(array $c,string $raw,string $signature,array $order,array $status):array {
        if(($c['gateway']??'rampex')!=='rampex')return $this->cards()->completed($c,$this->event($c,$raw,$signature,false),$order,$status);
        return RampexPaymentVerifier::completed($raw,$signature,$this->secrets->decrypt($c['webhook_cipher']),['link_id'=>$order['provider_link_id'],'merchant_id'=>$order['merchant_id'],'currency'=>$order['currency'],'amount_minor'=>(int)$order['amount_minor']],$status);
    }
}
