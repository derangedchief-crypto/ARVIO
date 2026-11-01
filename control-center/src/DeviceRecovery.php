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
    /** Explicit owner association for legacy records; fresh signed enrollment
     * still performs reconnection and credential rotation. No policy is lifted. */
    private function legacyCandidate(string $id,bool $lock=false):array {
        if(!$this->enabled())throw new Problem(409,'Device recovery is not enabled.');
        $suffix=$lock?' FOR UPDATE':'';
        $r=$this->db->one('SELECT *,poll_expires_at>UTC_TIMESTAMP() AS delivery_valid FROM enrollment_requests WHERE id=?'.$suffix,[Input::id($id)]);
        if(!$r)throw new Problem(404,'Enrollment request not found.');
        if($r['status']!=='pending'||$r['recovery_state']!=='unidentified'||$r['device_id']!==null||!$r['delivery_valid']||!is_string($r['recovery_hint_hash'])||!preg_match('/^[a-f0-9]{64}$/D',$r['recovery_hint_hash']))throw new Problem(409,'This request is not an eligible live legacy recovery hold.');
        $matches=$this->db->all("SELECT device_id FROM enrollment_requests WHERE account_ref=? AND app_package=? AND status='approved' AND device_id IS NOT NULL".$suffix,[$r['account_ref'],$r['app_package']]);
        $ids=array_map(static fn($id)=>['device_id'=>$id],array_values(array_unique(array_column($matches,'device_id'))));
        if(count($ids)!==1)throw new Problem(409,'The historical device match is not unique.');
        $d=$this->db->one('SELECT d.*,t.status AS workspace_status,t.kind AS workspace_kind,TIMESTAMPDIFF(SECOND,d.last_seen_at,UTC_TIMESTAMP()) AS seen_age FROM devices d JOIN tenants t ON t.id=d.tenant_id WHERE d.id=?'.$suffix,[$ids[0]['device_id']]);
        if(!$d||$d['policy']!=='active'||$d['workspace_status']!=='active'||$d['is_test']||$d['app_package']!==$r['app_package']||$d['seen_age']===null||(int)$d['seen_age']<300)throw new Problem(409,'The previous device is restricted or recently active. No recovery association was changed.');
        $old=$this->db->one("SELECT * FROM enrollment_requests WHERE device_id=? AND key_fingerprint=? AND app_package=? AND status='approved' ORDER BY created_at DESC,id DESC LIMIT 1".$suffix,[$d['id'],$d['key_fingerprint'],$r['app_package']]);
        if(!$old||$old['account_ref']!==$r['account_ref']||empty($r['panel_reseller_id'])||empty($r['panel_customer_id'])||$old['panel_reseller_id']!==$r['panel_reseller_id']||$old['panel_customer_id']!==$r['panel_customer_id'])throw new Problem(409,'Verified account or panel identity differs.');
        $binding=$this->db->one('SELECT customer_id,tenant_id FROM managed_accounts WHERE account_ref=?'.$suffix,[$r['account_ref']]);
        $link=$this->db->one('SELECT tenant_id FROM panel_resellers WHERE panel_id=?'.$suffix,[$r['panel_reseller_id']]);
        if(!$binding||$binding['customer_id']!==$d['customer_id']||$binding['tenant_id']!==$d['tenant_id']||($d['workspace_kind']!=='owner'&&(!$link||$link['tenant_id']!==$d['tenant_id'])))throw new Problem(409,'Customer/workspace ownership differs.');
        if($this->db->one('SELECT device_id FROM device_recovery_hints WHERE device_id=? OR (account_ref=? AND app_package=? AND panel_reseller_id=? AND hint_hash=?)'.$suffix,[$d['id'],$r['account_ref'],$r['app_package'],$r['panel_reseller_id'],$r['recovery_hint_hash']]))throw new Problem(409,'A recovery association already exists; refresh and review instead of replacing it.');
        if($this->db->one('SELECT id FROM devices WHERE installation_id=?'.$suffix,[$r['installation_id']]))throw new Problem(409,'The new installation is already assigned.');
        $revision=$this->secrets->mac('legacy-recovery-review:v1\n'.json_encode([$r['id'],$r['account_ref'],$r['app_package'],$r['installation_id'],$r['key_fingerprint'],$r['recovery_hint_hash'],$r['panel_reseller_id'],$r['panel_customer_id'],$d['id'],$d['installation_id'],$d['key_fingerprint'],$d['policy'],$d['tenant_id'],$d['customer_id']],JSON_THROW_ON_ERROR));
        return ['request'=>$r,'device'=>$d,'revision'=>$revision];
    }
    public function previewLegacy(array $actor,string $id):array {
        Auth::owner($actor);$v=$this->legacyCandidate($id);$r=$v['request'];$d=$v['device'];
        $name=$this->db->one('SELECT username FROM verified_line_usernames WHERE account_ref=?',[$r['account_ref']]);
        return ['request_id'=>$r['id'],'device_id'=>$d['id'],'installation_id'=>$r['installation_id'],'app_package'=>$r['app_package'],'model'=>$r['model'],'previous_label'=>$d['label'],'previous_last_seen_at'=>$d['last_seen_at'],'line_username'=>$name['username']??null,'expected_revision'=>$v['revision']];
    }
    public function authorizeLegacy(array $actor,string $id,array $input):array {
        Auth::owner($actor);Input::keys($input,['expected_revision','confirm']);
        if(($input['confirm']??null)!==true)throw new Problem(422,'Explicit owner confirmation is required.');
        $expected=EnrollmentProof::hex($input['expected_revision']??null,64);
        $this->auth->limit('legacy-recovery-approval',$actor['id'],20,3600);
        return $this->db->transaction(function()use($actor,$id,$expected):array {
            $r=$this->db->one('SELECT account_ref,app_package FROM enrollment_requests WHERE id=?',[Input::id($id)]);
            if(!$r)throw new Problem(404,'Enrollment request not found.');
            $this->lockScope($r['account_ref'],$r['app_package']);
            // Current locking reads prevent stale approval after a policy change,
            // another owner review or a competing signed recovery attempt.
            $v=$this->legacyCandidate($id,true);$r=$v['request'];$d=$v['device'];
            if(!hash_equals($v['revision'],$expected))throw new Problem(409,'Recovery review is stale. Refresh and review again.');
            $this->db->query('INSERT INTO device_recovery_hints (device_id,account_ref,app_package,panel_reseller_id,hint_hash) VALUES (?,?,?,?,?)',[$d['id'],$r['account_ref'],$r['app_package'],$r['panel_reseller_id'],$r['recovery_hint_hash']]);
            $this->db->query("UPDATE enrollment_requests SET recovery_state='owner_authorized' WHERE id=?",[$id]);
            $this->auth->audit($actor,'device.recovery_owner_authorized',$d['id'],$d['tenant_id'],['request_id'=>$r['id'],'method'=>'explicit legacy recovery review; signed retry required']);
            return ['request_id'=>$id,'device_id'=>$d['id'],'state'=>'awaiting_signed_retry'];
        });
    }
}
