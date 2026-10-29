<?php
declare(strict_types=1);
namespace ControlCenter;

/** Fixed-host, bounded server-only merchant API. Live calls default OFF. */
final class RampexGateway {
    public function __construct(private readonly Config $config,private readonly Database $db,private readonly Secrets $secrets,private readonly ?\Closure $transport=null) {}
    public function mockAllowed():bool {return $this->config->local()&&($this->config->get('renewal_payments',[])['simulation_enabled']??false)===true;}
    private function request(string $path,string $key,?array $body=null):array {
        if(($this->config->get('renewal_payments',[])['allow_live_api']??false)!==true)throw new Problem(503,'Live Rampex API calls have not been approved.');
        $headers=['X-API-Key: '.$key,'Accept: application/json','Content-Type: application/json'];
        if($this->transport)$raw=($this->transport)($path,$body,$headers);
        else{
            $raw='';$curl=curl_init('https://api.rampex.io/'.$path);
            $opts=[CURLOPT_HTTPHEADER=>$headers,CURLOPT_FOLLOWLOCATION=>false,CURLOPT_SSL_VERIFYPEER=>true,CURLOPT_SSL_VERIFYHOST=>2,CURLOPT_CONNECTTIMEOUT=>5,CURLOPT_TIMEOUT=>15,CURLOPT_PROTOCOLS=>CURLPROTO_HTTPS,CURLOPT_WRITEFUNCTION=>static function($h,$chunk)use(&$raw){if(strlen($raw)+strlen($chunk)>131072)return 0;$raw.=$chunk;return strlen($chunk);}];
            if($body!==null){$opts[CURLOPT_POST]=true;$opts[CURLOPT_POSTFIELDS]=json_encode($body,JSON_THROW_ON_ERROR);}
            curl_setopt_array($curl,$opts);$ok=curl_exec($curl);$status=curl_getinfo($curl,CURLINFO_RESPONSE_CODE);curl_close($curl);
            if($ok===false||$status!==200)throw new Problem(503,'Rampex request unavailable; do not create another paid order.');
        }
        try{$data=json_decode($raw,true,16,JSON_THROW_ON_ERROR);}catch(\JsonException){throw new Problem(503,'Invalid Rampex response.');}
        if(!is_array($data))throw new Problem(503,'Invalid Rampex response.');return $data;
    }
    public function validate(string $key,string $mode):array {
        if($mode==='mock'){
            if(!$this->mockAllowed()||!preg_match('/^mock_[A-Za-z0-9_-]{16,100}$/D',$key))throw new Problem(422,'Use a synthetic mock key on the isolated simulator.');
            return ['merchant_id'=>'mock-'.substr(hash('sha256',$key),0,24),'business_name'=>'DEMO merchant'];
        }
        if($mode!=='live'||strlen($key)<16||strlen($key)>512||preg_match('/[\x00-\x20\x7f]/',$key))throw new Problem(422,'Invalid merchant connection.');
        if($this->mockAllowed())throw new Problem(503,'Live merchants cannot be connected to the isolated renewal simulator.');
        $r=$this->request('api-validate-key',$key,[]);
        if(($r['valid']??false)!==true||!is_string($r['merchant_id']??null)||!preg_match('/^[A-Za-z0-9._:-]{1,200}$/D',$r['merchant_id']))throw new Problem(422,'Merchant key could not be verified.');
        return ['merchant_id'=>$r['merchant_id'],'business_name'=>Input::text($r['business_name']??'Merchant','merchant name',120)];
    }
    public function create(array $connection,array $order,string $email):array {
        if($connection['mode']==='mock'){
            if(!$this->mockAllowed())throw new Problem(503,'Payment simulator unavailable.');
            $id='mock-'.$order['id'];
            $this->db->query('INSERT INTO renewal_simulations (link_id,merchant_id,currency,amount_minor) VALUES (?,?,?,?)',[$id,$connection['merchant_id'],$order['currency'],$order['amount_minor']]);
            return ['link_id'=>$id,'url'=>$this->config->origin().'/renew/demo/'.$order['id']];
        }
        $data=$this->request('api-create-payment-link',$this->secrets->decrypt($connection['api_cipher']),[
            'amount'=>number_format($order['amount_minor']/100,2,'.',''),'currency'=>$order['currency'],'customer_email'=>$email,
            'description'=>'Renewal order '.$order['id'],'provider'=>'hosted','woo_order_id'=>$order['id'],'woo_store_url'=>$this->config->origin()]);
        $r=$data['data']??$data;$id=$r['link_id']??null;$url=$r['payment_url']??$r['redirect_url']??$r['short_url']??null;
        $u=is_string($url)?parse_url($url):false;
        if(!is_string($id)||!preg_match('/^[A-Za-z0-9._:-]{1,200}$/D',$id)||!$u||($u['scheme']??null)!=='https'||!in_array($u['host']??null,['checkout.rampex.io','rampex.io','pay.rampex.io'],true)||isset($u['user'])||isset($u['pass']))throw new Problem(503,'Payment link response could not be verified; do not retry creation.');
        return ['link_id'=>$id,'url'=>$url];
    }
    public function status(array $connection,array $order):array {
        if($connection['mode']==='mock'){
            if(!$this->mockAllowed())throw new Problem(503,'Payment simulator unavailable.');
            $r=$this->db->one('SELECT * FROM renewal_simulations WHERE link_id=? AND merchant_id=?',[$order['provider_link_id'],$connection['merchant_id']])??throw new Problem(404,'Payment not found.');
            return ['link_id'=>$r['link_id'],'merchant_id'=>$r['merchant_id'],'status'=>$r['status'],'fully_paid'=>$r['status']==='completed','amount_minor'=>(int)$r['amount_minor'],'currency'=>$r['currency']];
        }
        $data=$this->request('api-get-payment-status?link_id='.rawurlencode($order['provider_link_id']),$this->secrets->decrypt($connection['api_cipher']));$r=$data['data']??$data;
        $currency=$r['currency']??'';$amount=RampexPaymentVerifier::minor($r['amount']??null,$currency);
        // Do not guess a crypto->fiat conversion. Until Rampex confirms the
        // live gross-payment contract, an ambiguous denomination stays pending.
        $full=($r['fully_paid']??false)===true;
        if(!$full&&($r['received_currency']??null)===$currency){$full=RampexPaymentVerifier::minor($r['received_amount']??null,$currency)>=$amount;}
        return ['link_id'=>$r['link_id']??null,'merchant_id'=>$connection['merchant_id'],'status'=>$r['status']??null,'fully_paid'=>$full,'amount_minor'=>$amount,'currency'=>$currency];
    }
}
