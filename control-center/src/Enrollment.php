<?php
declare(strict_types=1);
namespace ControlCenter;

final class Enrollment
{
    private readonly EnrollmentProof $proofs;
    public function __construct(private readonly Database $db, private readonly Config $config, private readonly Auth $auth,
        private readonly Management $management, private readonly Secrets $secrets)
    { $this->proofs = new EnrollmentProof($config,$secrets); }

    private function enabled(bool $requireEnabled = true): bool
    {
        try { $row=$this->db->one('SELECT enrollment_enabled FROM platform_settings WHERE id=1'); }
        catch(\PDOException $e) { if(($e->errorInfo[1]??null)===1146)throw new Problem(503,'The owner must apply the control-center database upgrade first.'); throw $e; }
        $enabled=(bool)($row['enrollment_enabled']??false);
        if($requireEnabled&&!$enabled) throw new Problem(503,'Device enrollment is not enabled by the owner yet.');
        return $enabled;
    }
    public function settings(array $actor): array
    {
        Auth::owner($actor);
        return ['enabled'=>$this->enabled(false),'audience'=>$this->config->origin(),'worker_endpoint'=>'https://activationworker.extremeiptv.net/management/enrollment-proof'];
    }
    public function setEnabled(array $actor, array $input): array
    {
        Auth::owner($actor); Input::keys($input,['enabled']);
        if(!is_bool($input['enabled']??null)) throw new Problem(422,'Invalid enrollment setting.');
        $this->enabled(false);
        $this->db->transaction(function () use($actor,$input):void {
            $this->db->query('UPDATE platform_settings SET enrollment_enabled=? WHERE id=1',[(int)$input['enabled']]);
            $this->auth->audit($actor,'enrollment.setting_changed',null,null,['enabled'=>$input['enabled']]);
        });
        return $this->settings($actor);
    }
    public function bridgeConfiguration(array $actor): array
    {
        Auth::owner($actor);
        $this->auth->audit($actor,'enrollment.bridge_configuration_downloaded',null,null);
        return ['filename'=>'management-bridge.env','content'=>"MANAGEMENT_CONTROL_ORIGIN=".$this->config->origin()."\nMANAGEMENT_ENROLLMENT_SECRET=".$this->proofs->bridgeSecret()."\n"];
    }
    public function challenge(array $input, string $ip): array
    {
        $enabled=$this->enabled(false);
        Input::keys($input,['schema_version','installation_id','key_fingerprint','app_package']);
        Input::integer($input['schema_version']??1,1,1);
        $install=EnrollmentProof::hex($input['installation_id']??null,32);
        $key=EnrollmentProof::hex($input['key_fingerprint']??null,64);
        $package=EnrollmentProof::package($input['app_package']??null);
        if(!$enabled&&!$this->db->one("SELECT r.id FROM enrollment_requests r JOIN devices d ON d.id=r.device_id WHERE r.installation_id=? AND r.key_fingerprint=? AND r.app_package=? AND r.status='approved' AND d.policy!='removed'",[$install,$key,$package])) {
            throw new Problem(503,'New device enrollment is paused by the owner.');
        }
        $this->auth->limit('enrollment-challenge',$ip,120,3600);
        $nonce=bin2hex(random_bytes(32));
        $this->db->query('INSERT INTO enrollment_challenges (nonce_hash,installation_id,key_fingerprint,app_package,expires_at) VALUES (?,?,?,?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 2 MINUTE))',[hash('sha256',$nonce),$install,$key,$package]);
        return ['schema_version'=>1,'challenge'=>$nonce,'expires_in'=>120];
    }

    public function register(array $input, string $ip): array
    {
        $enabled=$this->enabled(false);
        Input::keys($input,['schema_version','challenge','proof','public_key','signature','label','model','os_version','app_version']);
        Input::integer($input['schema_version']??1,1,1);
        $nonce=EnrollmentProof::hex($input['challenge']??null,64);
        $token=Input::text($input['proof']??null,'enrollment proof',4096);
        $claim=$this->proofs->verify($token);
        $encoded=Input::text($input['public_key']??null,'public key',256);
        $key=EnrollmentProof::publicKey($encoded);
        if(!hash_equals($key['fingerprint'],$claim['key'])||!hash_equals(hash('sha256',$nonce),$claim['challenge'])) throw new Problem(403,'Enrollment proof is bound to a different device/challenge.');
        EnrollmentProof::verifyDeviceSignature($key,Input::text($input['signature']??null,'signature',128),
            EnrollmentProof::signingText($nonce,$token,$claim['install'],$claim['package'],$claim['key']));
        $label=Input::text($input['label']??'Android device','device label',120);
        $model=Input::text($input['model']??'','model',120,false);
        $os=Input::text($input['os_version']??'','OS version',80,false);
        $version=Input::text($input['app_version']??'','app version',40,false);
        $this->auth->limit('enrollment-register-ip',$ip,120,3600);
        $this->auth->limit('enrollment-register-account',$claim['sub'],20,3600);
        return $this->db->transaction(function () use($nonce,$claim,$encoded,$label,$model,$os,$version,$enabled):array {
            if(!$enabled&&!$this->db->one("SELECT r.id FROM enrollment_requests r JOIN devices d ON d.id=r.device_id WHERE r.installation_id=? AND r.key_fingerprint=? AND r.account_ref=? AND r.app_package=? AND r.status='approved' AND d.policy!='removed'",[$claim['install'],$claim['key'],$claim['sub'],$claim['package']])) {
                throw new Problem(503,'New device enrollment is paused by the owner.');
            }
            $nonceHash=hash('sha256',$nonce);
            $challenge=$this->db->one('SELECT * FROM enrollment_challenges WHERE nonce_hash=? AND expires_at>UTC_TIMESTAMP() FOR UPDATE',[$nonceHash]);
            if(!$challenge||$challenge['installation_id']!==$claim['install']||$challenge['key_fingerprint']!==$claim['key']||$challenge['app_package']!==$claim['package']) throw new Problem(403,'Enrollment challenge expired or does not match.');
            if($challenge['used_at']!==null) {
                $used=$this->db->one('SELECT nonce_hash FROM enrollment_proofs WHERE proof_id=?',[$claim['jti']]);
                if(!$used||$used['nonce_hash']!==$nonceHash||!$challenge['request_id']) throw new Problem(409,'Enrollment challenge was already used. Request another challenge.');
                $existing=$this->db->one('SELECT * FROM enrollment_requests WHERE id=?',[$challenge['request_id']]);
                if(!$existing||$existing['poll_cipher']===null) throw new Problem(409,'Enrollment delivery was already confirmed. Request a new challenge.');
                return $this->registrationResponse($existing);
            }
            if($this->db->one('SELECT proof_id FROM enrollment_proofs WHERE proof_id=?',[$claim['jti']])) throw new Problem(409,'Enrollment proof was already consumed.');
            $this->db->query('INSERT INTO enrollment_proofs (proof_id,nonce_hash) VALUES (?,?)',[$claim['jti'],$nonceHash]);
            $candidate=Database::id(); $poll=bin2hex(random_bytes(32));
            $this->db->query("INSERT INTO enrollment_requests (id,installation_id,key_fingerprint,public_key,account_ref,account_hint,app_package,label,model,os_version,app_version,poll_hash,poll_cipher,poll_expires_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 7 DAY)) ON DUPLICATE KEY UPDATE id=id",
                [$candidate,$claim['install'],$claim['key'],$encoded,$claim['sub'],$claim['hint'],$claim['package'],$label,$model,$os,$version,hash('sha256',$poll),$this->secrets->encrypt($poll)]);
            $row=$this->db->one('SELECT *, (poll_expires_at<=UTC_TIMESTAMP()) AS poll_expired, (credential_expires_at<=UTC_TIMESTAMP()) AS credential_expired FROM enrollment_requests WHERE installation_id=? AND key_fingerprint=? AND account_ref=? AND app_package=? FOR UPDATE',[$claim['install'],$claim['key'],$claim['sub'],$claim['package']]);
            if($row['id']!==$candidate&&($row['poll_cipher']===null||$row['poll_expired'])) {
                $this->db->query('UPDATE enrollment_requests SET poll_hash=?,poll_cipher=?,poll_expires_at=DATE_ADD(UTC_TIMESTAMP(),INTERVAL 7 DAY),confirmed_at=NULL WHERE id=?',[hash('sha256',$poll),$this->secrets->encrypt($poll),$row['id']]);
            }
            $this->db->query('UPDATE enrollment_requests SET label=?,model=?,os_version=?,app_version=?,last_request_at=UTC_TIMESTAMP() WHERE id=?',[$label,$model,$os,$version,$row['id']]);
            if($row['status']==='approved'&&$row['device_id']) {
                $device=$this->db->one('SELECT d.*,a.customer_id AS bound_customer FROM devices d JOIN managed_accounts a ON a.account_ref=? WHERE d.id=? FOR UPDATE',[$row['account_ref'],$row['device_id']]);
                if($device&&$device['policy']!=='removed'&&$device['customer_id']===$device['bound_customer']&&$device['key_fingerprint']===$row['key_fingerprint']&&$device['app_package']===$row['app_package']) {
                    if($row['credential_cipher']===null||$row['confirmed_at']!==null||$row['credential_expired']) $this->mintCredential($row['id'],$device['id']);
                } else {
                    // Removal never silently reactivates a device. A new proof
                    // makes a review request, preserving the existing app login.
                    $this->db->query("UPDATE enrollment_requests SET status='pending',credential_cipher=NULL,reviewed_by=NULL WHERE id=?",[$row['id']]);
                }
            }
            $this->db->query('UPDATE enrollment_challenges SET used_at=UTC_TIMESTAMP(),request_id=? WHERE nonce_hash=?',[$row['id'],$nonceHash]);
            if($row['id']===$candidate) $this->auth->audit(null,'enrollment.requested',$row['id'],null,['app_package'=>$claim['package']]);
            return $this->registrationResponse($this->db->one('SELECT * FROM enrollment_requests WHERE id=?',[$row['id']]));
        });
    }
    private function registrationResponse(array $row): array
    {
        return ['schema_version'=>1,'request_id'=>$row['id'],'installation_id'=>$row['installation_id'],
            'poll_token'=>$this->secrets->decrypt($row['poll_cipher']),'status'=>$row['status']];
    }
    private function mintCredential(string $requestId, string $deviceId): void
    {
        $token=bin2hex(random_bytes(32));
        $days=$this->config->number('device_credential_days',90,7,365);
        $this->db->query('UPDATE device_credentials SET revoked_at=UTC_TIMESTAMP() WHERE device_id=? AND revoked_at IS NULL',[$deviceId]);
        $this->db->query('INSERT INTO device_credentials (token_hash,device_id,expires_at) VALUES (?,?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL ? DAY))',[hash('sha256',$token),$deviceId,$days]);
        $expires=$this->db->one('SELECT expires_at FROM device_credentials WHERE token_hash=?',[hash('sha256',$token)])['expires_at'];
        $this->db->query('UPDATE enrollment_requests SET credential_cipher=?,credential_expires_at=?,confirmed_at=NULL WHERE id=?',[$this->secrets->encrypt($token),$expires,$requestId]);
    }
    public function poll(array $input, string $ip, bool $confirm=false): array
    {
        Input::keys($input,['schema_version','request_id','poll_token']); Input::integer($input['schema_version']??1,1,1);
        $id=Input::id($input['request_id']??null); $token=EnrollmentProof::hex($input['poll_token']??null,64);
        $this->auth->limit('enrollment-poll-ip',$ip,300,60);
        return $this->db->transaction(function () use($id,$token,$confirm):array {
            $row=$this->db->one('SELECT * FROM enrollment_requests WHERE id=? AND poll_expires_at>UTC_TIMESTAMP() FOR UPDATE',[$id]);
            if(!$row||!hash_equals($row['poll_hash'],hash('sha256',$token))) throw new Problem(401,'Enrollment delivery expired or is not authorized.');
            if($confirm) {
                if($row['status']!=='approved'||!$row['device_id']) throw new Problem(409,'Enrollment has not been approved.');
                $this->db->query('UPDATE enrollment_requests SET credential_cipher=NULL,poll_cipher=NULL,confirmed_at=COALESCE(confirmed_at,UTC_TIMESTAMP()) WHERE id=?',[$id]);
                return ['schema_version'=>1,'confirmed'=>true,'device_id'=>$row['device_id']];
            }
            $response=['schema_version'=>1,'status'=>$row['status'],'request_id'=>$id,'installation_id'=>$row['installation_id'],'poll_after_seconds'=>60];
            if($row['status']==='approved') {
                if($row['credential_cipher']===null) throw new Problem(409,'Enrollment delivery was already confirmed.');
                $device=$this->db->one('SELECT policy FROM devices WHERE id=?',[$row['device_id']]);
                if(!$device||$device['policy']==='removed') throw new Problem(409,'Device was removed; a new owner approval is required.');
                $response+=['device_id'=>$row['device_id'],'credential'=>$this->secrets->decrypt($row['credential_cipher']),'credential_expires_at'=>$row['credential_expires_at']];
            }
            return $response;
        });
    }
    public function listing(array $actor, array $input): array
    {
        Auth::owner($actor); $this->enabled(false);
        Input::keys($input,['page','status']); $page=Input::page($input['page']??1); $offset=($page-1)*25;
        $status=$input['status']??'pending'; if(!in_array($status,['pending','approved','rejected'],true)) throw new Problem(422,'Invalid enrollment status.');
        $total=(int)$this->db->query('SELECT COUNT(*) FROM enrollment_requests WHERE status=?',[$status])->fetchColumn();
        $rows=$this->db->all("SELECT id,installation_id,key_fingerprint,account_hint,app_package,label,model,os_version,app_version,status,device_id,created_at,last_request_at,confirmed_at FROM enrollment_requests WHERE status=? ORDER BY last_request_at DESC,id LIMIT 25 OFFSET $offset",[$status]);
        return ['items'=>$rows,'page'=>$page,'pages'=>max(1,(int)ceil($total/25)),'total'=>$total];
    }
    public function approve(array $actor, string $id, array $input): array
    {
        Auth::owner($actor); $this->enabled(); Input::keys($input,['customer_id','device_id','label']);
        $customer=$this->management->customer($actor,Input::id($input['customer_id']??null));
        return $this->db->transaction(function () use($actor,$id,$input,$customer):array {
            $row=$this->db->one('SELECT * FROM enrollment_requests WHERE id=? FOR UPDATE',[Input::id($id)]);
            if(!$row) throw new Problem(404,'Record not found.');
            if($row['status']==='approved'&&$row['device_id']) {
                $existing=$this->management->device($actor,$row['device_id']);
                if($existing['customer_id']!==$customer['id']) throw new Problem(409,'This enrollment is already assigned to another customer.');
                return $existing;
            }
            $tenant=$this->management->tenant($actor,$customer['tenant_id'],true);
            if($tenant['status']!=='active') throw new Problem(409,'This reseller is suspended.');
            $binding=$this->db->one('SELECT * FROM managed_accounts WHERE account_ref=? FOR UPDATE',[$row['account_ref']]);
            if($binding&&$binding['customer_id']!==$customer['id']) throw new Problem(409,'This verified account is assigned to another customer. An owner-reviewed transfer is required.');
            if(!$binding) $this->db->query('INSERT INTO managed_accounts (account_ref,customer_id,tenant_id,account_hint) VALUES (?,?,?,?)',[$row['account_ref'],$customer['id'],$tenant['id'],$row['account_hint']]);
            $existing=isset($input['device_id'])&&$input['device_id']!=='' ? $this->management->device($actor,Input::id($input['device_id']),true)
                : $this->db->one('SELECT * FROM devices WHERE installation_id=? FOR UPDATE',[$row['installation_id']]);
            if($existing) {
                $reserved=str_starts_with($existing['installation_id'],'pending:')&&$existing['enrolled_at']===null;
                if($existing['customer_id']!==$customer['id']||(!$reserved&&$existing['installation_id']!==$row['installation_id'])
                    ||(!$reserved&&$existing['key_fingerprint']!==$row['key_fingerprint']&&$existing['policy']!=='removed')) throw new Problem(409,'The selected device does not match this approved identity/customer.');
            }
            $addsSlot=!$existing||$existing['policy']==='removed';
            $used=(int)$this->db->query("SELECT COUNT(*) FROM devices WHERE tenant_id=? AND policy!='removed'",[$tenant['id']])->fetchColumn();
            if($addsSlot&&$used>=(int)$tenant['device_limit']) throw new Problem(409,'Device allocation reached.');
            $deviceId=$existing['id']??Database::id();
            $label=Input::text($input['label']??$existing['label']??$row['label'],'device label',120);
            if(!$existing) $this->db->query("INSERT INTO devices (id,tenant_id,customer_id,installation_id,label,notes,capabilities) VALUES (?,?,?,?,?,'','[]')",[$deviceId,$tenant['id'],$customer['id'],$row['installation_id'],$label]);
            $policy=$existing&&$existing['policy']!=='removed' ? $existing['policy'] : 'active';
            $this->db->query('UPDATE devices SET installation_id=?,key_fingerprint=?,app_package=?,label=?,model=?,os_version=?,app_version=?,policy=?,is_test=0,enrolled_at=COALESCE(enrolled_at,UTC_TIMESTAMP()) WHERE id=?',
                [$row['installation_id'],$row['key_fingerprint'],$row['app_package'],$label,$row['model'],$row['os_version'],$row['app_version'],$policy,$deviceId]);
            $this->db->query("UPDATE enrollment_requests SET status='approved',device_id=?,reviewed_by=? WHERE id=?",[$deviceId,$actor['id'],$id]);
            $this->mintCredential($id,$deviceId);
            $this->auth->audit($actor,'enrollment.approved',$id,$tenant['id'],['device_id'=>$deviceId]);
            return $this->management->device($actor,$deviceId);
        });
    }
    public function reject(array $actor, string $id): array
    {
        Auth::owner($actor); $this->enabled(false);
        return $this->db->transaction(function () use($actor,$id):array {
            $row=$this->db->one('SELECT status FROM enrollment_requests WHERE id=? FOR UPDATE',[Input::id($id)]);
            if(!$row)throw new Problem(404,'Record not found.');
            if($row['status']==='approved')throw new Problem(409,'Manage an approved device through its policy controls.');
            $this->db->query("UPDATE enrollment_requests SET status='rejected',reviewed_by=? WHERE id=?",[$actor['id'],$id]);
            $this->auth->audit($actor,'enrollment.rejected',$id,null);
            return ['status'=>'rejected'];
        });
    }
    public function maintain(): void
    {
        $this->db->query('DELETE FROM enrollment_challenges WHERE expires_at<DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 DAY) LIMIT 1000');
        $this->db->query('DELETE FROM enrollment_proofs WHERE created_at<DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 DAY) LIMIT 1000');
        $this->db->query('UPDATE enrollment_requests SET poll_cipher=NULL,credential_cipher=NULL WHERE poll_expires_at<=UTC_TIMESTAMP() AND (poll_cipher IS NOT NULL OR credential_cipher IS NOT NULL)');
        $days=$this->config->number('retention_days',90,7,365);
        $this->db->query("DELETE FROM enrollment_requests WHERE status IN ('pending','rejected') AND poll_expires_at<DATE_SUB(UTC_TIMESTAMP(),INTERVAL $days DAY) LIMIT 1000");
    }
}
