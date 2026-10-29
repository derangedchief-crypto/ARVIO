<?php
declare(strict_types=1);
namespace ControlCenter;

/** Isolated localhost simulator only. Never reads/writes the panel or Rampex. */
final class RenewalSimulation {
    public function __construct(private readonly Database $db,private readonly Config $config) {}
    private function ready():void {if(!$this->config->local()||($this->config->get('renewal_payments',[])['simulation_enabled']??false)!==true)throw new Problem(503,'Simulator is unavailable on this host.');}
    public function transport():\Closure {return function(string $action,string $raw,array $headers):string {
        $this->ready();$h=[];foreach($headers as $line){[$k,$v]=explode(': ',$line,2);$h[$k]=$v;}
        $key=$this->config->get('renewal_worker')['shared_secret'];$stamp=$h['X-Renewal-Timestamp'];$nonce=$h['X-Renewal-Nonce'];
        if(!hash_equals(hash_hmac('sha256',"branded-renewal-request-v1\n$stamp\n$nonce\n$raw",$key),$h['X-Renewal-Signature']))throw new Problem(403,'Simulation signature mismatch.');
        $input=json_decode($raw,true,16,JSON_THROW_ON_ERROR);$result=$this->handle($action,$input);
        $payload=json_encode($result,JSON_THROW_ON_ERROR);
        return json_encode(['payload'=>$payload,'signature'=>hash_hmac('sha256',"branded-renewal-result-v1\n$stamp\n$nonce\n$payload",$key)],JSON_THROW_ON_ERROR);
    };}
    private function handle(string $action,array $input):array {
        if($action==='catalog'){
            $rows=$this->db->all('SELECT plans FROM renewal_simulated_targets WHERE reseller_id=?',[$input['reseller_id']]);$plans=[];
            foreach($rows as $row)foreach(json_decode($row['plans'],true,16,JSON_THROW_ON_ERROR) as $p)$plans[$p['id']]=$p;
            return ['reseller_id'=>$input['reseller_id'],'plans'=>array_values($plans)];
        }
        $target=$this->db->one('SELECT * FROM renewal_simulated_targets WHERE reseller_id=? AND line_id=?',[$input['reseller_id'],$input['line_id']]);
        if(!$target||!hash_equals($target['primary_fingerprint'],$input['primary_fingerprint']))return ['reseller_id'=>$input['reseller_id'],'line_id'=>$input['line_id'],'state'=>'unavailable','primary_match'=>false,'checked_at'=>time(),'plans'=>[]];
        $plans=json_decode($target['plans'],true,16,JSON_THROW_ON_ERROR);
        if($action==='eligibility')return ['reseller_id'=>$input['reseller_id'],'line_id'=>$input['line_id'],'state'=>$target['state'],'primary_match'=>true,'checked_at'=>time(),'expires_at'=>(int)$target['expires_at'],'plans'=>$plans];
        return $this->db->transaction(function()use($input,$target,$plans):array{
            $fresh=$this->db->one('SELECT * FROM renewal_simulated_targets WHERE reseller_id=? AND line_id=? FOR UPDATE',[$input['reseller_id'],$input['line_id']]);
            $fp=hash('sha256',json_encode($input,JSON_THROW_ON_ERROR));$old=$this->db->one('SELECT * FROM renewal_simulated_operations WHERE operation_id=?',[$input['operation_id']]);
            if($old){if(!hash_equals($fp,$old['fingerprint']))throw new Problem(409,'Simulation operation mismatch.');return json_decode($old['receipt'],true,16,JSON_THROW_ON_ERROR);}
            $plans=json_decode($fresh['plans'],true,16,JSON_THROW_ON_ERROR);
            $plan=null;foreach($plans as $p)if($p['id']===$input['package_id']&&hash_equals($p['fingerprint'],$input['plan_fingerprint']))$plan=$p;
            if(!$plan||!$plan['funded']||!hash_equals($fresh['primary_fingerprint'],$input['primary_fingerprint'])||!in_array($fresh['state'],['expired','active'],true))throw new Problem(409,'Simulation plan unavailable.');
            $expiry=self::expiry(max(time(),(int)$fresh['expires_at']),$plan); // DEMO data only.
            $receipt=['state'=>'fulfilled','operation_id'=>$input['operation_id'],'reseller_id'=>$input['reseller_id'],'line_id'=>$input['line_id'],'package_id'=>$input['package_id'],'expires_at'=>$expiry,'simulated'=>true];
            $this->db->query('UPDATE renewal_simulated_targets SET state=\'active\',expires_at=? WHERE reseller_id=? AND line_id=?',[$expiry,$input['reseller_id'],$input['line_id']]);
            $this->db->query('INSERT INTO renewal_simulated_operations VALUES (?,?,?)',[$input['operation_id'],$fp,json_encode($receipt,JSON_THROW_ON_ERROR)]);
            return $receipt;
        });
    }
    public static function expiry(int $base,array $plan):int {
        $date=(new \DateTimeImmutable('@'.$base))->setTimezone(new \DateTimeZone('UTC'));
        $unit=rtrim($plan['period'],'s');$n=$plan['duration'];
        if(in_array($unit,['month','year'],true)){
            $months=$n*($unit==='year'?12:1);$day=(int)$date->format('j');
            $next=$date->modify('first day of this month')->modify('+'.$months.' months');
            return $next->setDate((int)$next->format('Y'),(int)$next->format('n'),min($day,(int)$next->format('t')))->getTimestamp();
        }
        if(!in_array($unit,['hour','day','week'],true))throw new Problem(422,'Invalid simulation duration.');
        return $date->modify('+'.$n.' '.$unit)->getTimestamp();
    }
}
