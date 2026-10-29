<?php
declare(strict_types=1);
namespace ControlCenter;

/** Existing reseller worker only. Distinct key/domain, no portal key in website. */
final class RenewalWorker {
    public function __construct(private readonly Config $config,private readonly ?\Closure $transport=null) {}
    private function request(string $action,array $input):array {
        $c=$this->config->get('renewal_worker',[]);$key=$c['shared_secret']??'';
        if(($c['mode']??null)==='catalogue_only'&&!in_array($action,['catalog','status'],true))throw new Problem(503,'Catalogue-only connection: checkout and renewal writes remain disabled.');
        if(!is_string($key)||!preg_match('/^[a-f0-9]{64}$/D',$key))throw new Problem(503,'Configure the separate renewal worker connection.');
        if(!in_array($action,['catalog','eligibility','renew','status'],true))throw new \LogicException('Invalid internal renewal action');
        $raw=json_encode($action==='status'?(object)$input:$input,JSON_THROW_ON_ERROR);$stamp=(string)time();$nonce=bin2hex(random_bytes(16));
        $signature=hash_hmac('sha256',"branded-renewal-request-v1\n$stamp\n$nonce\n$raw",$key);
        $headers=['Content-Type: application/json','X-Renewal-Timestamp: '.$stamp,'X-Renewal-Nonce: '.$nonce,'X-Renewal-Signature: '.$signature];
        if($this->transport)$response=($this->transport)($action,$raw,$headers);
        else{
            $url=$c['base_url']??'https://resellerworker.extremeiptv.net';$u=is_string($url)?parse_url($url):false;
            if(!$u||($u['scheme']??'')!=='https'||isset($u['user'])||isset($u['pass'])||isset($u['query'])||isset($u['fragment'])||!in_array($u['path']??'',['','/'],true))throw new Problem(503,'Invalid fixed renewal worker origin.');
            $response='';$curl=curl_init(rtrim($url,'/').'/branded-renewals/'.$action);
            curl_setopt_array($curl,[CURLOPT_POST=>true,CURLOPT_POSTFIELDS=>$raw,CURLOPT_HTTPHEADER=>$headers,CURLOPT_FOLLOWLOCATION=>false,CURLOPT_SSL_VERIFYPEER=>true,CURLOPT_SSL_VERIFYHOST=>2,CURLOPT_CONNECTTIMEOUT=>5,CURLOPT_TIMEOUT=>25,CURLOPT_PROTOCOLS=>CURLPROTO_HTTPS,CURLOPT_WRITEFUNCTION=>static function($h,$chunk)use(&$response){if(strlen($response)+strlen($chunk)>131072)return 0;$response.=$chunk;return strlen($chunk);}]);
            $ok=curl_exec($curl);$status=curl_getinfo($curl,CURLINFO_RESPONSE_CODE);curl_close($curl);
            if($ok===false||$status!==200)throw new Problem(503,($c['mode']??null)==='catalogue_only'?'Catalogue-only worker unavailable (HTTP '.$status.'). Check the auth-host connection and /branded-renewals/ proxy; no panel changes requested.':'Renewal worker unavailable; retain the original order.');
        }
        try{
            $e=json_decode($response,true,16,JSON_THROW_ON_ERROR);
            if(!is_string($e['payload']??null)||!is_string($e['signature']??null)||!hash_equals(hash_hmac('sha256',"branded-renewal-result-v1\n$stamp\n$nonce\n".$e['payload'],$key),$e['signature']))throw new \RuntimeException();
            $r=json_decode($e['payload'],true,16,JSON_THROW_ON_ERROR);if(!is_array($r))throw new \RuntimeException();return $r;
        }catch(\Throwable){throw new Problem(503,'Renewal worker response verification failed.');}
    }
    public function catalog(string $reseller):array {
        if(!preg_match('/^[1-9][0-9]{0,18}$/D',$reseller))throw new Problem(422,'Invalid verified reseller.');
        $r=$this->request('catalog',['reseller_id'=>$reseller]);
        if(($this->config->get('renewal_worker',[])['mode']??null)==='catalogue_only'&&(($r['catalogue_only']??null)!==true||($r['writes_enabled']??null)!==false))throw new Problem(503,'Worker has not confirmed catalogue-only mode.');
        if(($r['reseller_id']??null)!==$reseller||!is_array($r['plans']??null)||!array_is_list($r['plans'])||count($r['plans'])>200)throw new Problem(503,'Renewal catalog scope mismatch.');
        foreach($r['plans'] as $p)self::plan($p);return $r['plans'];
    }
    public function connectionStatus():array {
        $r=$this->request('status',[]);
        if(($r['schema_version']??null)!==1||($r['configured']??null)!==true||($r['catalogue_only']??null)!==true||($r['writes_enabled']??null)!==false)throw new Problem(503,'Worker has not confirmed that renewal writes are disabled.');
        return ['connected'=>true,'catalogue_only'=>true,'writes_enabled'=>false];
    }
    public static function plan(array $p):void {
        foreach(['id','duration','max_connections'] as $k)if(!is_int($p[$k]??null)||$p[$k]<1)throw new Problem(503,'Invalid renewal package limits.');
        if($p['id']>2147483647||$p['duration']>1000000||$p['max_connections']>100||!is_string($p['fingerprint']??null)||!preg_match('/^[a-f0-9]{64}$/D',$p['fingerprint'])||!is_string($p['name']??null)||mb_strlen($p['name'])>80||!in_array($p['period']??null,['hour','hours','day','days','week','weeks','month','months','year','years'],true)||!is_bool($p['funded']??null))throw new Problem(503,'Invalid renewal package metadata.');
    }
    public function eligibility(string $reseller,string $line,string $fingerprint):array {
        if(!preg_match('/^[1-9][0-9]{0,18}$/D',$reseller)||!preg_match('/^[1-9][0-9]{0,18}$/D',$line)||!preg_match('/^[a-f0-9]{64}$/D',$fingerprint))throw new Problem(422,'Invalid original renewal target.');
        $r=$this->request('eligibility',['reseller_id'=>$reseller,'line_id'=>$line,'primary_fingerprint'=>$fingerprint]);
        if(($r['reseller_id']??null)!==$reseller||($r['line_id']??null)!==$line||!is_bool($r['primary_match']??null)||!in_array($r['state']??null,['expired','active','restricted','unlimited','unavailable'],true)||!is_int($r['checked_at']??null)||abs(time()-$r['checked_at'])>60||!is_array($r['plans']??null)||!array_is_list($r['plans']))throw new Problem(503,'Renewal identity/expiry verification failed.');
        if($r['state']==='expired'&&(($r['primary_match']??false)!==true||!is_int($r['expires_at']??null)||$r['expires_at']<1||$r['expires_at']>time()))throw new Problem(503,'Expiry has not been confirmed by server time.');
        if($r['state']==='expired'&&(!is_int($r['current_package_id']??null)||$r['current_package_id']<1||count($r['plans'])>1))throw new Problem(503,'Current package has not been confirmed.');
        foreach($r['plans'] as $p){self::plan($p);if($p['id']!==($r['current_package_id']??null))throw new Problem(503,'Renewal may only use the current package.');}return $r;
    }
    public function renew(array $spec):array {
        // Legacy workers reject this extra mandatory field before any write;
        // never silently use the older package-switch-capable paid contract.
        $spec['current_package_only']=true;
        $r=$this->request('renew',$spec);
        if(($r['operation_id']??null)!==$spec['operation_id']||!in_array($r['state']??null,['fulfilled','refresh_pending','needs_review'],true))throw new Problem(503,'Renewal operation response mismatch.');
        if($r['state']==='fulfilled'&&(($r['reseller_id']??null)!==$spec['reseller_id']||($r['line_id']??null)!==$spec['line_id']||($r['package_id']??null)!==$spec['package_id']||!is_int($r['expires_at']??null)||$r['expires_at']<=time()||$r['expires_at']>4102444800))throw new Problem(503,'Renewal has not confirmed the original line.');
        return $r;
    }
}
