<?php
declare(strict_types=1);
namespace ControlCenter;

/** Fixed HTTPS worker, signed requests AND nonce-bound signed responses. */
final class PanelBridge
{
    public function __construct(private readonly EnrollmentProof $proofs, private readonly ?\Closure $transport=null) {}
    public static function signature(string $secret,string $domain,string $timestamp,string $nonce,string $payload): string
    { return hash_hmac('sha256',"$domain\n$timestamp\n$nonce\n$payload",hex2bin($secret)); }
    public function reseller(string $username): array
    {
        $username=Input::text($username,'panel username',150);
        $payload=json_encode(['username'=>$username],JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES|JSON_THROW_ON_ERROR);
        $timestamp=(string)time(); $nonce=bin2hex(random_bytes(16)); $secret=$this->proofs->bridgeSecret();
        $headers=['Content-Type: application/json','Accept: application/json','X-Management-Timestamp: '.$timestamp,'X-Management-Nonce: '.$nonce,
            'X-Management-Signature: '.self::signature($secret,'reseller-lookup-v1',$timestamp,$nonce,$payload)];
        if($this->transport) $raw=($this->transport)($payload,$headers);
        else {
            if(!function_exists('curl_init')) throw new Problem(503,'Enable the PHP cURL extension to verify panel resellers.');
            $curl=curl_init('https://activationworker.extremeiptv.net/management/reseller-lookup'); $raw='';
            curl_setopt_array($curl,[CURLOPT_POST=>true,CURLOPT_POSTFIELDS=>$payload,CURLOPT_HTTPHEADER=>$headers,
                CURLOPT_FOLLOWLOCATION=>false,CURLOPT_CONNECTTIMEOUT=>5,CURLOPT_TIMEOUT=>20,CURLOPT_SSL_VERIFYPEER=>true,CURLOPT_SSL_VERIFYHOST=>2,
                CURLOPT_PROTOCOLS=>CURLPROTO_HTTPS,CURLOPT_WRITEFUNCTION=>static function($handle,$chunk)use(&$raw){if(strlen($raw)+strlen($chunk)>4096)return 0;$raw.=$chunk;return strlen($chunk);}]);
            $ok=curl_exec($curl);$status=curl_getinfo($curl,CURLINFO_RESPONSE_CODE);curl_close($curl);
            if($status===404)throw new Problem(422,'No unique active panel reseller with that exact username.');
            if($ok===false||$status!==200)throw new Problem(503,'Cannot verify the panel reseller. Update/check the activation-worker bridge and try again.');
        }
        try { $result=json_decode($raw,true,8,JSON_THROW_ON_ERROR); } catch(\JsonException) { throw new Problem(503,'Invalid panel lookup response.'); }
        if(!is_array($result)||!is_string($result['payload']??null)||!is_string($result['signature']??null)
            ||!hash_equals(self::signature($secret,'reseller-result-v1',$timestamp,$nonce,$result['payload']),$result['signature'])) throw new Problem(503,'Panel lookup signature verification failed.');
        try { $reseller=json_decode($result['payload'],true,8,JSON_THROW_ON_ERROR); } catch(\JsonException) { throw new Problem(503,'Invalid panel reseller.'); }
        if(!is_array($reseller)||!is_string($reseller['id']??null)||!preg_match('/^[1-9][0-9]{0,19}$/D',$reseller['id'])||($reseller['username']??null)!==$username)throw new Problem(503,'Panel reseller identity did not match.');
        return $reseller;
    }
    /** Reseller notes only, using server-owned line/account/reseller bindings. */
    public function lineNotes(array $lines):array {
        if(!array_is_list($lines)||!$lines||count($lines)>25)throw new Problem(422,'Invalid line notes batch.');
        $expected=[];
        foreach($lines as $line){
            if(!is_array($line)||array_diff(array_keys($line),['id','account_ref','reseller_id']))throw new Problem(422,'Invalid line notes identity.');
            foreach(['id','reseller_id'] as $key)if(!is_string($line[$key]??null)||!preg_match('/^[1-9][0-9]{0,19}$/D',$line[$key]))throw new Problem(422,'Invalid line notes identity.');
            EnrollmentProof::hex($line['account_ref']??null,64);
            if(isset($expected[$line['id']]))throw new Problem(422,'Duplicate line notes identity.');
            $expected[$line['id']]=$line;
        }
        $body=json_encode(['lines'=>$lines],JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES|JSON_THROW_ON_ERROR);
        $timestamp=(string)time();$nonce=bin2hex(random_bytes(16));$secret=$this->proofs->bridgeSecret();
        $headers=['Content-Type: application/json','Accept: application/json','X-Management-Timestamp: '.$timestamp,'X-Management-Nonce: '.$nonce,'X-Management-Signature: '.self::signature($secret,'line-notes-request-v1',$timestamp,$nonce,$body)];
        if($this->transport)$raw=($this->transport)($body,$headers);
        else {
            if(!function_exists('curl_init'))throw new Problem(503,'Panel notes connection unavailable.');
            $raw='';$curl=curl_init('https://activationworker.extremeiptv.net/management/line-notes');
            curl_setopt_array($curl,[CURLOPT_POST=>true,CURLOPT_POSTFIELDS=>$body,CURLOPT_HTTPHEADER=>$headers,CURLOPT_FOLLOWLOCATION=>false,CURLOPT_CONNECTTIMEOUT=>3,CURLOPT_TIMEOUT=>8,CURLOPT_SSL_VERIFYPEER=>true,CURLOPT_SSL_VERIFYHOST=>2,CURLOPT_PROTOCOLS=>CURLPROTO_HTTPS,CURLOPT_WRITEFUNCTION=>static function($c,$chunk)use(&$raw){if(strlen($raw)+strlen($chunk)>12582912)return 0;$raw.=$chunk;return strlen($chunk);}]);
            $ok=curl_exec($curl);$status=curl_getinfo($curl,CURLINFO_RESPONSE_CODE);curl_close($curl);
            if($ok===false||$status!==200)throw new Problem(503,'Panel line notes unavailable.');
        }
        try {
            $envelope=json_decode($raw,true,16,JSON_THROW_ON_ERROR);
            if(!is_string($envelope['payload']??null)||!is_string($envelope['signature']??null)||!hash_equals(self::signature($secret,'line-notes-result-v1',$timestamp,$nonce,$envelope['payload']),$envelope['signature']))throw new \RuntimeException();
            $response=json_decode($envelope['payload'],true,16,JSON_THROW_ON_ERROR);
            $rows=$response['lines']??null;
            if(!is_array($rows)||!array_is_list($rows)||count($rows)!==count($lines))throw new \RuntimeException();
            $out=[];
            foreach($rows as $row){
                if(!is_array($row)||array_diff(array_keys($row),['id','account_ref','reseller_id','state','notes']))throw new \RuntimeException();
                $id=$row['id']??'';$want=$expected[$id]??null;
                if(!$want||isset($out[$id])||($row['account_ref']??null)!==$want['account_ref']||($row['reseller_id']??null)!==$want['reseller_id']||!in_array($row['state']??null,['current','unavailable'],true))throw new \RuntimeException();
                if($row['state']==='current'?(!is_string($row['notes']??null)||strlen($row['notes'])>65535):($row['notes']??null)!==null)throw new \RuntimeException();
                $out[$id]=['state'=>$row['state'],'notes'=>$row['notes']??null];
            }
            return $out;
        }catch(\Throwable){throw new Problem(503,'Invalid signed panel notes response.');}
    }
    /** Signed read-only check for a panel line ALREADY bound by enrollment. */
    public function primaryLine(string $customerId,string $accountRef):array {
        if(!preg_match('/^[1-9][0-9]{0,19}$/D',$customerId)||!preg_match('/^[a-f0-9]{64}$/D',$accountRef))throw new Problem(503,'Missing trusted primary identity.');
        $body=json_encode(['customer_id'=>$customerId,'account_ref'=>$accountRef],JSON_THROW_ON_ERROR);
        $timestamp=(string)time();$nonce=bin2hex(random_bytes(16));$secret=$this->proofs->bridgeSecret();
        $headers=['Content-Type: application/json','Accept: application/json','X-Management-Timestamp: '.$timestamp,'X-Management-Nonce: '.$nonce,'X-Management-Signature: '.self::signature($secret,'primary-line-request-v1',$timestamp,$nonce,$body)];
        if($this->transport)$raw=($this->transport)($body,$headers);
        else {
            $raw='';$curl=curl_init('https://activationworker.extremeiptv.net/management/primary-line-status');
            curl_setopt_array($curl,[CURLOPT_POST=>true,CURLOPT_POSTFIELDS=>$body,CURLOPT_HTTPHEADER=>$headers,CURLOPT_FOLLOWLOCATION=>false,CURLOPT_CONNECTTIMEOUT=>4,CURLOPT_TIMEOUT=>8,CURLOPT_SSL_VERIFYPEER=>true,CURLOPT_SSL_VERIFYHOST=>2,CURLOPT_PROTOCOLS=>CURLPROTO_HTTPS,CURLOPT_WRITEFUNCTION=>static function($c,$chunk)use(&$raw){if(strlen($raw)+strlen($chunk)>4096)return 0;$raw.=$chunk;return strlen($chunk);}]);
            $ok=curl_exec($curl);$status=curl_getinfo($curl,CURLINFO_RESPONSE_CODE);curl_close($curl);
            if($ok===false||$status!==200)throw new Problem(503,'Primary validation unavailable. Check the activation-worker primary-status update.');
        }
        try {
            $envelope=json_decode($raw,true,8,JSON_THROW_ON_ERROR);
            if(!is_string($envelope['payload']??null)||!is_string($envelope['signature']??null)||!hash_equals(self::signature($secret,'primary-line-result-v1',$timestamp,$nonce,$envelope['payload']),$envelope['signature']))throw new \RuntimeException();
            $r=json_decode($envelope['payload'],true,8,JSON_THROW_ON_ERROR);
            if(array_diff(array_keys($r),['id','account_ref','reseller_id','identity_valid','active','valid_for_seconds'])||($r['id']??null)!==$customerId||($r['account_ref']??null)!==$accountRef||!is_bool($r['identity_valid']??null)||!is_bool($r['active']??null)||!is_int($r['valid_for_seconds']??null)||$r['valid_for_seconds']<0||$r['valid_for_seconds']>900||($r['reseller_id']!==null&&(!is_string($r['reseller_id'])||!preg_match('/^[1-9][0-9]{0,19}$/D',$r['reseller_id']))))throw new \RuntimeException();
            return $r;
        }catch(\Throwable){throw new Problem(503,'Invalid signed primary validation response.');}
    }
}
