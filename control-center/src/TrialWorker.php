<?php
declare(strict_types=1);
namespace ControlCenter;

/** Separate auth-worker bridge. Never uses the activation-worker secret or app inputs. */
final class TrialWorker
{
    public function __construct(private readonly Config $config,private readonly ?\Closure $transport=null) {}
    public function configured():bool {
        $c=$this->config->get('trial_worker',[]);if(!is_array($c)||!is_string($c['shared_secret']??null))return false;$url=$c['base_url']??'https://resellerworker.extremeiptv.net';$u=is_string($url)&&strlen($url)<=200?parse_url($url):false;
        return preg_match('/^[a-f0-9]{64}$/D',$c['shared_secret'])===1&&$u&&($u['scheme']??'')==='https'&&isset($u['host'])&&!isset($u['user'])&&!isset($u['pass'])&&!isset($u['query'])&&!isset($u['fragment'])&&in_array($u['path']??'',['','/'],true);
    }
    private function request(string $path,string $domain,array $input):array {
        if(!$this->configured())throw new Problem(503,'Configure the separate trial auth-worker connection first.');
        $c=$this->config->get('trial_worker');$body=json_encode($input,JSON_THROW_ON_ERROR);$stamp=(string)time();
        $signature=hash_hmac('sha256',"$domain\n$stamp\n$body",$c['shared_secret']);
        $headers=['Content-Type: application/json','Accept: application/json','X-Trial-Timestamp: '.$stamp,'X-Trial-Signature: '.$signature];
        if($this->transport)$raw=($this->transport)($path,$body,$headers);
        else {
            $raw='';$curl=curl_init(rtrim($c['base_url']??'https://resellerworker.extremeiptv.net','/').$path);
            curl_setopt_array($curl,[CURLOPT_POST=>true,CURLOPT_POSTFIELDS=>$body,CURLOPT_HTTPHEADER=>$headers,CURLOPT_FOLLOWLOCATION=>false,CURLOPT_CONNECTTIMEOUT=>5,CURLOPT_TIMEOUT=>20,CURLOPT_SSL_VERIFYPEER=>true,CURLOPT_SSL_VERIFYHOST=>2,CURLOPT_PROTOCOLS=>CURLPROTO_HTTPS,CURLOPT_WRITEFUNCTION=>static function($handle,$chunk)use(&$raw){if(strlen($raw)+strlen($chunk)>131072)return 0;$raw.=$chunk;return strlen($chunk);}]);
            $ok=curl_exec($curl);$status=curl_getinfo($curl,CURLINFO_RESPONSE_CODE);curl_close($curl);
            if($ok===false||$status!==200)throw new Problem(503,'The trial auth-worker is unavailable. No new accounts were requested.');
        }
        if(!is_string($raw)||strlen($raw)>131072)throw new Problem(503,'Invalid trial worker response.');
        try{$result=json_decode($raw,true,12,JSON_THROW_ON_ERROR);}catch(\JsonException){throw new Problem(503,'Invalid trial worker response.');}if(!is_array($result))throw new Problem(503,'Invalid trial worker response.');return $result;
    }
    public function catalog(string $resellerId):array {
        if(!preg_match('/^[1-9][0-9]{0,18}$/D',$resellerId))throw new Problem(422,'Invalid verified reseller identity.');
        $result=$this->request('/branded-trials/plans','branded-trial-plans-v1',['reseller_id'=>$resellerId]);
        if(!is_array($result)||array_diff(array_keys($result),['reseller_id','plans'])||($result['reseller_id']??null)!==$resellerId||!is_array($result['plans']??null)||!array_is_list($result['plans'])||count($result['plans'])>200)throw new Problem(503,'Trial catalog ownership did not match.');
        $seen=[];foreach($result['plans'] as &$plan){
            if(!is_array($plan)||array_diff(array_keys($plan),['id','name','duration','duration_value','period','max_connections','credits','bouquets','fingerprint']))throw new Problem(503,'Invalid trial plan.');
            foreach(['id','duration','max_connections'] as $key)if(!is_int($plan[$key]??null)||$plan[$key]<1)throw new Problem(503,'Invalid trial limits.');
            if($plan['id']>2147483647||$plan['duration']>1000000||$plan['max_connections']>100||isset($seen[$plan['id']]))throw new Problem(503,'Invalid trial limits.');$seen[$plan['id']]=true;
            if(isset($plan['duration_value'])&&(!is_int($plan['duration_value'])||$plan['duration_value']<0||$plan['duration_value']>1000000))throw new Problem(503,'Invalid legacy trial duration.');
            if(!is_string($plan['name']??null)||!mb_check_encoding($plan['name'],'UTF-8')||mb_strlen($plan['name'])<1||mb_strlen($plan['name'])>80||preg_match('/[\x00-\x1F\x7F]/u',$plan['name']))throw new Problem(503,'Invalid trial label.');
            if(!in_array($plan['period']??null,['hour','hours','day','days','week','weeks','month','months','year','years'],true)||!is_string($plan['credits']??null)||!preg_match('/^(0|[1-9][0-9]{0,9})(\.[0-9]{1,8})?$/D',$plan['credits'])||(float)$plan['credits']>1000000000||!is_string($plan['fingerprint']??null)||!preg_match('/^[a-f0-9]{64}$/D',$plan['fingerprint']))throw new Problem(503,'Invalid trial revision.');
            if(!is_array($plan['bouquets']??null)||!array_is_list($plan['bouquets'])||!$plan['bouquets'])throw new Problem(503,'Invalid trial groups.');foreach($plan['bouquets'] as $group)if(!is_int($group)||$group<1||$group>2147483647)throw new Problem(503,'Invalid trial groups.');
        }unset($plan);return $result['plans'];
    }
    public function status(string $operation,string $resellerId):array {
        Input::id($operation);if(!preg_match('/^[1-9][0-9]{0,18}$/D',$resellerId))throw new Problem(422,'Invalid verified reseller identity.');
        $r=$this->request('/branded-trials/status','branded-trial-status-v1',['operation_id'=>$operation,'reseller_id'=>$resellerId]);
        if(array_diff(array_keys($r),['operation_id','reseller_id','line_id','state','expires_at','checked_at_us'])||($r['operation_id']??null)!==$operation||($r['reseller_id']??null)!==$resellerId||!is_string($r['line_id']??null)||!preg_match('/^[1-9][0-9]{0,18}$/D',$r['line_id'])||!in_array($r['state']??null,['active','expired','pending_activation','restricted','converted','unavailable','needs_review'],true)||!array_key_exists('expires_at',$r)||!is_int($r['checked_at_us']??null)||abs($r['checked_at_us']/1000000-time())>300)throw new Problem(503,'Trial status ownership or freshness did not match.');
        if($r['expires_at']!==null&&(!is_int($r['expires_at'])||$r['expires_at']<1||$r['expires_at']>4102444800))throw new Problem(503,'Invalid confirmed trial expiry.');return $r;
    }
}
