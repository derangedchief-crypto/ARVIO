<?php
declare(strict_types=1);
namespace ControlCenter;

/** Pure verification boundary. No network, fulfilment, credentials or panel writes.
 * Provider adapters must supply a merchant-scoped, verified canonical status;
 * a browser redirect, completed string alone or net crypto payout is insufficient.
 */
final class RampexPaymentVerifier {
    public static function signatureValid(string $raw,string $signature,string $secret):bool {
        $signature=strtolower(trim($signature));
        return strlen($raw)<=65536&&strlen($secret)>=16&&strlen($secret)<=512
            &&preg_match('/^[a-f0-9]{64}$/D',$signature)===1
            &&hash_equals(hash_hmac('sha256',$raw,$secret),$signature);
    }
    public static function minor(mixed $amount,string $currency):int {
        // Initial pilot currencies all have two minor digits. Other currencies
        // require an explicitly reviewed exponent, not an assumed decimal scale.
        if(!in_array($currency,['USD','CAD','EUR','GBP','AUD'],true))throw new Problem(422,'Unsupported renewal currency.');
        if(is_float($amount)&&!is_finite($amount))throw new Problem(422,'Invalid payment amount.');
        if(!is_string($amount)&&!is_int($amount)&&!is_float($amount))throw new Problem(422,'Invalid payment amount.');
        $value=(string)$amount;
        if(!preg_match('/^(0|[1-9][0-9]{0,4})(?:\.([0-9]{1,2}))?$/D',$value,$m))throw new Problem(422,'Invalid payment amount.');
        $minor=(int)$m[1]*100+(int)str_pad($m[2]??'',2,'0');
        if($minor<1||$minor>5000000)throw new Problem(422,'Payment amount outside supported limits.');
        return $minor;
    }
    public static function completed(string $raw,string $signature,string $secret,array $order,array $verifiedStatus):array {
        // Authenticate exact bytes BEFORE parsing; never re-encode JSON for HMAC.
        if(!self::signatureValid($raw,$signature,$secret))throw new Problem(401,'Invalid payment notification signature.');
        try{$event=json_decode($raw,true,16,JSON_THROW_ON_ERROR);}catch(\JsonException){throw new Problem(422,'Invalid payment notification.');}
        if(!is_array($event)||array_is_list($event)||($event['event']??null)!=='payment.completed'||($event['status']??null)!=='completed')throw new Problem(422,'Not a completed payment notification.');
        foreach(['link_id','merchant_id','currency','amount_minor'] as $key)if(!isset($order[$key]))throw new \LogicException('Incomplete immutable renewal quote.');
        if(!is_string($order['link_id'])||!preg_match('/^[A-Za-z0-9._:-]{1,200}$/D',$order['link_id'])
            ||!is_string($order['merchant_id'])||!preg_match('/^[A-Za-z0-9._:-]{1,200}$/D',$order['merchant_id'])
            ||!is_int($order['amount_minor'])||$order['amount_minor']<1||$order['amount_minor']>5000000)throw new \LogicException('Invalid immutable renewal quote.');
        if(($event['link_id']??null)!==$order['link_id']||($event['currency']??null)!==$order['currency']
            ||self::minor($event['amount']??null,$order['currency'])!==$order['amount_minor'])throw new Problem(409,'Payment does not match the renewal quote.');
        // fully_paid is an internal adapter assertion. Do NOT manufacture it
        // from a browser result or an undocumented received_amount denomination.
        if(($verifiedStatus['merchant_id']??null)!==$order['merchant_id']||($verifiedStatus['link_id']??null)!==$order['link_id']
            ||($verifiedStatus['status']??null)!=='completed'||($verifiedStatus['fully_paid']??null)!==true
            ||($verifiedStatus['currency']??null)!==$order['currency']||($verifiedStatus['amount_minor']??null)!==$order['amount_minor'])
            throw new Problem(409,'Merchant-scoped payment verification is incomplete.');
        $transaction=$event['txid_out']??$event['transaction_hash']??null;
        if(!is_string($transaction)||!preg_match('/^0x[a-fA-F0-9]{64}$/D',$transaction))throw new Problem(422,'Missing settlement reference.');
        return ['link_id'=>$order['link_id'],'merchant_id'=>$order['merchant_id'],'amount_minor'=>$order['amount_minor'],
            'currency'=>$order['currency'],'settlement_ref'=>strtolower($transaction),'notification_sha256'=>hash('sha256',$raw)];
    }
}
