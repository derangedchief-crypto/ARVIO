<?php
declare(strict_types=1);
namespace ControlCenter;

/** Wake hints carry no commands or permissions. The normal API remains authoritative. */
final class LiveConnection
{
    public function __construct(private readonly Config $config) {}

    private function key(): ?string
    {
        $file=$this->config->root.'/storage/live-connection.key';
        if(!is_file($file))return null;
        $key=trim((string)@file_get_contents($file,false,null,0,128));
        if(!preg_match('/^[a-f0-9]{64}$/D',$key))throw new Problem(503,'Live connection configuration unavailable.');
        return $key;
    }
    public function ticket(array $device): array
    {
        $key=$this->key();
        if($key===null)return ['schema_version'=>1,'enabled'=>false];
        $payload=['v'=>1,'device'=>Input::id($device['id']),'tenant'=>Input::id($device['tenant_id']),
            'exp'=>time()+300,'nonce'=>bin2hex(random_bytes(16))];
        $encoded=rtrim(strtr(base64_encode(json_encode($payload,JSON_THROW_ON_ERROR)),'+/','-_'),'=');
        return ['schema_version'=>1,'enabled'=>true,'device_id'=>$device['id'],
            'ticket'=>$encoded.'.'.hash_hmac('sha256',$encoded,hex2bin($key)), 'expires_in'=>300];
    }
    public static function target(string $action, ?string $resource, ?string $tenant, array $metadata): ?array
    {
        if(in_array($action,['command.queued','command.cancelled','setup.default_assigned','setup.manual_requested','setup.job_controlled','recovery_hold.allowed','enrollment.approved'],true)) {
            $device=$metadata['device_id']??null;
            return is_string($device)&&preg_match('/^[a-f0-9]{32}$/D',$device)?['device'=>$device]:null;
        }
        if(in_array($action,['device.updated','device.reseller_detached','device.unassigned','device.reconnected'],true))
            return $resource!==null?['device'=>Input::id($resource)]:null;
        if($action==='reseller.updated'&&$tenant!==null)return ['tenant'=>Input::id($tenant)];
        return null;
    }
    public function audited(Database $db,string $action,?string $resource,?string $tenant,array $metadata): void
    {
        $target=self::target($action,$resource,$tenant,$metadata);
        if($target===null||!is_file($this->config->root.'/storage/live-connection.key'))return;
        $db->afterCommit('live:'.json_encode($target),fn()=>$this->notify($target));
    }
    private function notify(array $target): void
    {
        $key=$this->key();if($key===null||!function_exists('curl_init'))return;
        $body=json_encode($target,JSON_THROW_ON_ERROR);
        $handle=curl_init('http://127.0.0.1:19095/wake');
        curl_setopt_array($handle,[CURLOPT_POST=>true,CURLOPT_POSTFIELDS=>$body,
            CURLOPT_HTTPHEADER=>['Content-Type: application/json','Authorization: Bearer '.$key],
            CURLOPT_CONNECTTIMEOUT_MS=>100,CURLOPT_TIMEOUT_MS=>250,CURLOPT_FOLLOWLOCATION=>false,
            CURLOPT_RETURNTRANSFER=>false,CURLOPT_WRITEFUNCTION=>static fn($h,$s)=>strlen($s)]);
        curl_exec($handle);curl_close($handle);
    }
}
