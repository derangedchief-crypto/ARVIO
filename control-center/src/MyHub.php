<?php
declare(strict_types=1);
namespace ControlCenter;

/** The reseller website is the sole My Hub license authority. */
final class MyHub
{
    public function __construct(private readonly Database $db,private readonly Config $config,private readonly ?\Closure $transport=null) {}
    public function installed(): bool
    { return (bool)$this->db->one('SELECT name FROM schema_migrations WHERE name=?',['034_my_hub_sessions.sql']); }
    public function ready(): void
    { if (!$this->installed()) throw new Problem(503,'Install the My Hub database update first.'); }
    public function isNative(string $token): bool
    { return $this->installed() && (bool)$this->db->one('SELECT token_hash FROM my_hub_sessions WHERE token_hash=?',[hash('sha256',$token)]); }
    public static function reseller(array $actor): void
    { if (($actor['role'] ?? '')!=='reseller' || empty($actor['tenant_id'])) throw new Problem(403,'Sign in with a Hub reseller account.'); }
    private static function signature(string $key,string $domain,string $timestamp,string $nonce,string $payload): string
    { return hash_hmac('sha256',"$domain\n$timestamp\n$nonce\n$payload",$key); }
    public function status(array $actor,?string $activationKey=null): array
    {
        self::reseller($actor);
        $link=$this->db->one('SELECT panel_id FROM panel_resellers WHERE tenant_id=?',[$actor['tenant_id']]);
        if (!$link) throw new Problem(403,'Ask the administrator to link your Hub workspace to your reseller-panel account.');
        $request=['reseller_id'=>(string)$link['panel_id'],'action'=>$activationKey===null?'status':'redeem'];
        if ($activationKey!==null) $request['key']=$activationKey;
        $result=$this->exchange($request);
        if (($result['reseller_id'] ?? null)!==(string)$link['panel_id'] || !is_bool($result['enabled'] ?? null)
            || !in_array($result['state'] ?? '',['unlicensed','pending','active','expired','suspended','revoked'],true)
            || !array_key_exists('expires',$result) || ($result['expires']!==null && !is_int($result['expires']))
            || !array_key_exists('error',$result) || ($result['error']!==null && !is_string($result['error']))) throw new Problem(503,'My Hub license response could not be verified.');
        if ($result['error']!==null) throw new Problem(422,$result['error']);
        $allowed=$result['enabled'] && $result['state']==='active' && ($result['expires']===null || $result['expires']>time());
        return ['allowed'=>$allowed,'state'=>$result['state'],'account_enabled'=>$result['enabled'],'expires'=>$result['expires']];
    }
    public function authenticate(string $username,string $password): string
    {
        $result=$this->exchange(['action'=>'login','username'=>$username,'password'=>$password]);
        if (($result['action'] ?? null)!=='login' || !is_bool($result['authenticated'] ?? null)) throw new Problem(503,'My Hub login response could not be verified.');
        if (!$result['authenticated']) throw new Problem(401,'Username or password is incorrect, or the reseller account is disabled.');
        if (($result['enabled'] ?? null)!==true || !is_string($result['reseller_id'] ?? null) || !preg_match('/^[1-9][0-9]{0,19}$/D',$result['reseller_id'])) throw new Problem(503,'My Hub login identity could not be verified.');
        return $result['reseller_id'];
    }
    private function exchange(array $request): array
    {
        $keyFile=$this->config->privateFile('my-hub-bridge.key');
        $key=is_file($keyFile)?file_get_contents($keyFile):false;
        if (!is_string($key) || strlen($key)!==32) throw new Problem(503,'My Hub license verification is not configured.');
        $body=json_encode($request,JSON_THROW_ON_ERROR|JSON_UNESCAPED_SLASHES);
        $timestamp=(string)time();$nonce=bin2hex(random_bytes(16));
        $headers=['Content-Type: application/json','Accept: application/json','X-My-Hub-Timestamp: '.$timestamp,'X-My-Hub-Nonce: '.$nonce,
            'X-My-Hub-Signature: '.self::signature($key,'my-hub-request-v1',$timestamp,$nonce,$body)];
        if ($this->transport) $raw=($this->transport)($body,$headers);
        else {
            $raw=''; $curl=curl_init('https://fecxbrfzku.net/am-app/my-hub-bridge.php');
            curl_setopt_array($curl,[CURLOPT_POST=>true,CURLOPT_POSTFIELDS=>$body,CURLOPT_HTTPHEADER=>$headers,CURLOPT_FOLLOWLOCATION=>false,
                CURLOPT_CONNECTTIMEOUT=>4,CURLOPT_TIMEOUT=>12,CURLOPT_PROTOCOLS=>CURLPROTO_HTTPS,CURLOPT_SSL_VERIFYPEER=>true,CURLOPT_SSL_VERIFYHOST=>2,
                CURLOPT_WRITEFUNCTION=>static function($c,$chunk)use(&$raw){if(strlen($raw)+strlen($chunk)>8192)return 0;$raw.=$chunk;return strlen($chunk);}]);
            $ok=curl_exec($curl);$code=curl_getinfo($curl,CURLINFO_RESPONSE_CODE);curl_close($curl);
            if ($ok===false || $code!==200) throw new Problem(503,'My Hub license verification is temporarily unavailable.');
        }
        try {
            $envelope=json_decode($raw,true,8,JSON_THROW_ON_ERROR);
            if (!is_string($envelope['payload'] ?? null) || !is_string($envelope['signature'] ?? null)
                || !hash_equals(self::signature($key,'my-hub-response-v1',$timestamp,$nonce,$envelope['payload']),$envelope['signature'])) throw new \RuntimeException();
            $result=json_decode($envelope['payload'],true,8,JSON_THROW_ON_ERROR);
            if (!is_array($result)) throw new \RuntimeException();
        } catch (\Throwable $e) { throw new Problem(503,'My Hub license response could not be verified.'); }
        return $result;
    }
    public function requireActive(array $actor): void
    { if (!$this->status($actor)['allowed']) throw new Problem(403,'Activate an active My Hub license for your reseller account.'); }
}
