<?php
declare(strict_types=1);
namespace ControlCenter;

/** Android-derived values are hints, NEVER device authentication/attestation.
 * Binding still requires account proof, key possession, tenant/package fencing,
 * a unique historical match and an inactive old installation. */
final class DeviceRecovery {
    public function __construct(private readonly Database $db,private readonly Config $config,private readonly Secrets $secrets,private readonly Auth $auth) {}
    public function available():bool {return DeviceListWindow::installed($this->db);}
    public function enabled():bool {return $this->available()&&(bool)$this->db->one('SELECT device_recovery_enabled FROM platform_settings WHERE id=1')['device_recovery_enabled'];}
    public function supportsReport(array $device):bool {
        if(!$this->available()||!empty($device['is_test'])||empty($device['key_fingerprint'])||empty($device['app_package']))return false;
        $row=$this->db->one("SELECT panel_reseller_id FROM enrollment_requests WHERE device_id=? AND key_fingerprint=? AND app_package=? AND status='approved' ORDER BY created_at DESC,id DESC LIMIT 1",[$device['id'],$device['key_fingerprint'],$device['app_package']]);
        return !empty($row['panel_reseller_id']);
    }
    public function hash(string $hint,string $account,string $package,string $reseller):string {
        EnrollmentProof::hex($hint,64);
        return $this->secrets->mac("device-recovery:v1\n$account\n$package\n$reseller\n$hint");
    }
    public static function hintSigningText(string $device,string $package,string $hint):string {return "ECRECOVERYHINT1\n$device\n$package\n$hint";}
    public function lockScope(string $account,string $package):void {
        $scope=$this->secrets->mac("device-recovery-lock:v1\n$account\n$package");
        $this->db->query('INSERT INTO device_recovery_locks (scope_hash) VALUES (?) ON DUPLICATE KEY UPDATE touched_at=UTC_TIMESTAMP()',[$scope]);
        $this->db->one('SELECT scope_hash FROM device_recovery_locks WHERE scope_hash=? FOR UPDATE',[$scope]);
    }
    /** Authenticated token plus the EXISTING signing key are required for backfill. */
    public function report(array $device,string $hint,string $signature):void {
        if(!$this->available())throw new Problem(503,'Device recovery upgrade required.');
        EnrollmentProof::hex($hint,64);
        $binding=$this->db->one("SELECT * FROM enrollment_requests WHERE device_id=? AND key_fingerprint=? AND app_package=? AND status='approved' ORDER BY created_at DESC,id DESC LIMIT 1",[$device['id'],$device['key_fingerprint'],$device['app_package']]);
        if(!$binding||!$binding['panel_reseller_id'])throw new Problem(409,'No verified panel identity for recovery hint.');
        $this->db->transaction(function()use($device,$hint,$signature,$binding):void{
        $this->lockScope($binding['account_ref'],$device['app_package']);
        $current=$this->db->one('SELECT * FROM devices WHERE id=? FOR UPDATE',[$device['id']]);
        if(!$current||$current['key_fingerprint']!==$device['key_fingerprint']||$current['policy']==='removed')throw new Problem(409,'Device identity changed.');
        $key=EnrollmentProof::publicKey($binding['public_key']);
        if(!hash_equals($device['key_fingerprint'],$key['fingerprint']))throw new Problem(403,'Device key mismatch.');
        EnrollmentProof::verifyDeviceSignature($key,$signature,self::hintSigningText($device['id'],$device['app_package'],$hint));
        $hash=$this->hash($hint,$binding['account_ref'],$device['app_package'],$binding['panel_reseller_id']);
        $this->db->query('INSERT INTO device_recovery_hints (device_id,account_ref,app_package,panel_reseller_id,hint_hash) VALUES (?,?,?,?,?) ON DUPLICATE KEY UPDATE account_ref=VALUES(account_ref),app_package=VALUES(app_package),panel_reseller_id=VALUES(panel_reseller_id),hint_hash=VALUES(hint_hash),updated_at=UTC_TIMESTAMP()',[$device['id'],$binding['account_ref'],$device['app_package'],$binding['panel_reseller_id'],$hash]);
        });
    }
    /** Return null for a genuinely new device/disabled feature, otherwise a
     * unique fenced candidate or a hold. Caller must NOT auto-add on a hold. */
    public function inspect(array $claim,?string $hintHash):?array {
        if(!$this->enabled()||!isset($claim['panel']))return null;
        $linked=$this->db->one("SELECT p.tenant_id FROM panel_resellers p JOIN tenants t ON t.id=p.tenant_id WHERE p.panel_id=? AND t.status='active'",[$claim['panel']['reseller_id']]);
        $historical=$hintHash===null?null:$this->db->one('SELECT device_id FROM device_recovery_hints WHERE account_ref=? AND app_package=? AND panel_reseller_id=? AND hint_hash=? LIMIT 1',[$claim['sub'],$claim['package'],$claim['panel']['reseller_id'],$hintHash]);
        if(!$linked&&!$historical)return null;
        $this->lockScope($claim['sub'],$claim['package']);
        $args=[$claim['sub'],$claim['package'],$claim['panel']['reseller_id']];
        $matches=$hintHash===null?[]:$this->db->all("SELECT d.*,t.status AS tenant_status,t.kind AS tenant_kind,h.account_ref,h.panel_reseller_id,TIMESTAMPDIFF(SECOND,GREATEST(COALESCE(d.last_seen_at,'1970-01-01'),h.updated_at),UTC_TIMESTAMP()) AS seen_age FROM device_recovery_hints h JOIN devices d ON d.id=h.device_id JOIN tenants t ON t.id=d.tenant_id WHERE h.account_ref=? AND h.app_package=? AND h.panel_reseller_id=? AND h.hint_hash=? ORDER BY d.id FOR UPDATE",[...$args,$hintHash]);
        if(count($matches)>1)return ['state'=>'ambiguous'];
        if(count($matches)===1){
            $device=$matches[0];
            $occupied=$this->db->one('SELECT id FROM devices WHERE installation_id=?',[$claim['install']]);
            if($occupied&&$occupied['id']!==$device['id'])return ['state'=>'ambiguous'];
            $old=$this->db->one("SELECT account_ref,panel_reseller_id,panel_customer_id FROM enrollment_requests WHERE device_id=? AND key_fingerprint=? AND app_package=? AND status='approved' ORDER BY created_at DESC,id DESC LIMIT 1",[$device['id'],$device['key_fingerprint'],$device['app_package']]);
            if(!$old||$old['account_ref']!==$claim['sub']||$old['panel_reseller_id']!==$claim['panel']['reseller_id']||$old['panel_customer_id']!==$claim['panel']['customer_id']||$device['is_test'])return ['state'=>'ambiguous'];
            $link=$this->db->one('SELECT tenant_id FROM panel_resellers WHERE panel_id=?',[$claim['panel']['reseller_id']]);
            $ownerManaged=$device['tenant_kind']==='owner';
            if(!$ownerManaged&&(!$link||$link['tenant_id']!==$device['tenant_id']))return ['state'=>'ambiguous'];
            if($device['policy']==='removed')return ['state'=>'restricted','device'=>$device];
            if($device['seen_age']!==null&&(int)$device['seen_age']<300)return ['state'=>'busy','device'=>$device];
            return ['state'=>'recover','device'=>$device];
        }
        // Legacy rows with no hint cannot be identified from name/model/IP or
        // account alone. Hold outside the device list instead of guessing.
        $legacy=$this->db->one("SELECT d.id FROM devices d JOIN enrollment_requests r ON r.device_id=d.id LEFT JOIN device_recovery_hints h ON h.device_id=d.id WHERE r.account_ref=? AND r.app_package=? AND r.status='approved' AND h.device_id IS NULL LIMIT 1",[$claim['sub'],$claim['package']]);
        if($legacy||$hintHash===null){
            $known=$legacy??$this->db->one("SELECT d.id FROM devices d JOIN enrollment_requests r ON r.device_id=d.id WHERE r.account_ref=? AND r.app_package=? AND r.status='approved' LIMIT 1",[$claim['sub'],$claim['package']]);
            if($known)return ['state'=>'unidentified'];
        }
        return null;
    }
    /** Called after fresh signed-account proof and inspect(), in one transaction. */
    public function reconnect(array $request,array $device):void {
        $this->db->query("UPDATE commands SET status='cancelled',finished_at=UTC_TIMESTAMP(),lease_hash=NULL,lease_until=NULL WHERE device_id=? AND status IN ('pending','delivered','running')",[$device['id']]);
        if(DeviceProfiles::installed($this->db))$this->db->query("UPDATE device_setup_jobs SET status='cancelled',message='Installation recovered; read fresh device state before setup' WHERE device_id=? AND status IN ('waiting','running','paused')",[$device['id']]);
        $this->db->query("UPDATE enrollment_requests SET status='rejected',credential_cipher=NULL,poll_cipher=NULL,poll_expires_at=UTC_TIMESTAMP() WHERE device_id=? AND key_fingerprint<>?",[$device['id'],$request['key_fingerprint']]);
        $this->db->query('UPDATE devices SET installation_id=?,key_fingerprint=?,model=?,os_version=?,app_version=? WHERE id=?',[$request['installation_id'],$request['key_fingerprint'],$request['model'],$request['os_version'],$request['app_version'],$device['id']]);
        $this->db->query("UPDATE enrollment_requests SET status='approved',device_id=?,recovery_state='recovered' WHERE id=?",[$device['id'],$request['id']]);
        $this->db->query('UPDATE device_recovery_hints SET updated_at=UTC_TIMESTAMP() WHERE device_id=?',[$device['id']]);
        $this->auth->audit(null,'device.reconnected',$device['id'],$device['tenant_id'],['request_id'=>$request['id']]);
    }
    public function storeForRequest(array $request):void {
        if(!$this->available()||empty($request['recovery_hint_hash'])||empty($request['device_id'])||empty($request['panel_reseller_id'])||$request['status']!=='approved')return;
        $this->db->query('INSERT INTO device_recovery_hints (device_id,account_ref,app_package,panel_reseller_id,hint_hash) VALUES (?,?,?,?,?) ON DUPLICATE KEY UPDATE hint_hash=VALUES(hint_hash),updated_at=UTC_TIMESTAMP()',[$request['device_id'],$request['account_ref'],$request['app_package'],$request['panel_reseller_id'],$request['recovery_hint_hash']]);
    }
}
