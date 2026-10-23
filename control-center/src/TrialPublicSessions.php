<?php
declare(strict_types=1);
namespace ControlCenter;

/** Key POSSESSION sessions for an authorized public offer; not APK/device attestation. */
final class TrialPublicSessions
{
    public function __construct(private readonly Database $db,private readonly TrialOffers $offers,private readonly Secrets $secrets,private readonly Auth $auth) {}
    public static function requestHash(array $contact):string {return hash('sha256',"trial-contact-v1\n".$contact['first_name']."\n".$contact['phone']);}
    public function create(array $input,string $ip):array {
        Input::keys($input,['brand_id','device_claim','public_key','request_hash','offer_revision']);$brand=Input::id($input['brand_id']??null);$offer=$this->offers->admitted($brand);if(!$offer)throw new Problem(409,'No automatic trial offer is currently available.');
        if(!is_string($input['offer_revision']??null)||!hash_equals(hash('sha256',$offer['policy_stamp']),$input['offer_revision']))throw new Problem(409,'Trial terms changed; reopen and review the current offer.');
        $this->auth->limit('trial-session-ip',$this->secrets->mac("trial-ip-v1\n$ip"),20,60);
        foreach(['device_claim','request_hash'] as $field)if(!is_string($input[$field]??null)||!preg_match('/^[a-f0-9]{64}$/D',$input[$field]))throw new Problem(422,'Invalid trial request identity.');
        $encoded=$input['public_key']??null;if(!is_string($encoded)||strlen($encoded)>512)throw new Problem(422,'Invalid session key.');$der=base64_decode($encoded,true);if(!$der||strlen($der)>256)throw new Problem(422,'Invalid session key.');
        $pem="-----BEGIN PUBLIC KEY-----\n".chunk_split(base64_encode($der),64,"\n")."-----END PUBLIC KEY-----\n";$key=@openssl_pkey_get_public($pem);$details=$key?openssl_pkey_get_details($key):false;
        if(!$details||$details['type']!==OPENSSL_KEYTYPE_EC||($details['ec']['curve_name']??null)!=='prime256v1')throw new Problem(422,'Use a P-256 trial session key.');
        $token=bin2hex(random_bytes(32));$tokenHash=hash('sha256',$token);$nonce=bin2hex(random_bytes(32));$operation=Database::id();$keyHash=hash('sha256',$der);$device=$this->secrets->mac("trial-device-v1\n".$input['device_claim']);$ipRef=$this->secrets->mac("trial-ip-v1\n$ip");
        $text=self::signingText($tokenHash,$brand,$operation,$nonce,$input['request_hash']);
        $this->db->transaction(function()use($offer,$tokenHash,$operation,$brand,$device,$details,$keyHash,$input,$nonce,$ipRef):void {
            $this->db->one('SELECT id FROM tenants WHERE id=? FOR UPDATE',[$offer['tenant_id']]);$current=$this->offers->admitted($brand);if(!$current||$current['policy_stamp']!==$offer['policy_stamp'])throw new Problem(409,'Trial offer changed; review it again.');
            $this->db->query('INSERT INTO trial_public_sessions (token_hash,operation_id,brand_id,tenant_id,device_ref,client_key,client_key_hash,request_hash_mac,nonce_hash,policy_stamp,ip_ref,expires_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 15 MINUTE))',[$tokenHash,$operation,$brand,$offer['tenant_id'],$device,$details['key'],$keyHash,$this->secrets->mac("trial-request-hash-v1\n".$input['request_hash']),hash('sha256',$nonce),$offer['policy_stamp'],$ipRef]);
        });
        return ['token'=>$token,'operation_id'=>$operation,'nonce'=>$nonce,'signing_text'=>$text,'expires_seconds'=>900];
    }
    private static function signingText(string $tokenHash,string $brand,string $operation,string $nonce,string $requestHash):string {return "trial-submit-v1\n$tokenHash\n$brand\n$operation\n$nonce\n$requestHash";}
    public function prove(array $input):array {
        Input::keys($input,['token','nonce','signature','request_hash','first_name','phone','notice_accepted']);
        foreach(['token','nonce','request_hash'] as $field)if(!is_string($input[$field]??null)||!preg_match('/^[a-f0-9]{64}$/D',$input[$field]))throw new Problem(401,'Invalid trial session.');
        $hash=hash('sha256',$input['token']);$session=$this->db->one('SELECT * FROM trial_public_sessions WHERE token_hash=? AND expires_at>UTC_TIMESTAMP()',[$hash]);
        if(!$session||!hash_equals($session['nonce_hash'],hash('sha256',$input['nonce']))||!hash_equals($session['request_hash_mac'],$this->secrets->mac("trial-request-hash-v1\n".$input['request_hash'])))throw new Problem(401,'Trial session is invalid or expired.');
        $encoded=$input['signature']??null;$signature=is_string($encoded)&&strlen($encoded)<=256?base64_decode($encoded,true):false;
        if(!$signature||strlen($signature)>128||@openssl_verify(self::signingText($hash,$session['brand_id'],$session['operation_id'],$input['nonce'],$input['request_hash']),$signature,$session['client_key'],OPENSSL_ALGO_SHA256)!==1)throw new Problem(401,'Trial session signature did not match.');
        return $session;
    }
}
