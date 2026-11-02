<?php
declare(strict_types=1);
namespace ControlCenter;

/** Workspace-scoped review; an allow binds to an existing device, never adds one. */
final class RecoveryHolds {
    public function __construct(private readonly Database $db,private readonly Config $config,private readonly Auth $auth,private readonly Management $management) {}
    private function scope(array $actor,array &$args,string $column='a.tenant_id'):string {
        if($actor['role']==='owner')return '1=1';
        if($actor['role']!=='reseller'||empty($actor['tenant_id']))throw new Problem(403,'Access denied.');
        $args[]=$actor['tenant_id'];return "$column=?";
    }
    public function listing(array $actor,array $input):array {
        Input::keys($input,['page','restricted_page']);$page=Input::page($input['page']??1);$offset=($page-1)*25;
        $args=[];$scope=$this->scope($actor,$args);
        $from="FROM enrollment_requests r JOIN managed_accounts a ON a.account_ref=r.account_ref LEFT JOIN verified_line_usernames u ON u.account_ref=r.account_ref WHERE $scope AND r.status='pending' AND r.recovery_state IS NOT NULL";
        $total=(int)$this->db->query("SELECT COUNT(*) $from",$args)->fetchColumn();
        $items=$this->db->all("SELECT r.id,r.installation_id,r.label,r.model,r.app_package,r.app_version,r.recovery_state,r.last_request_at,u.username AS line_username $from ORDER BY r.last_request_at DESC,r.id LIMIT 25 OFFSET $offset",$args);
        $args=[];$scope=$this->scope($actor,$args,'d.tenant_id');
        $restrictedPage=Input::page($input['restricted_page']??1);$restrictedOffset=($restrictedPage-1)*25;
        $restrictedTotal=(int)$this->db->query("SELECT COUNT(*) FROM devices d WHERE $scope AND d.policy IN ('blocked','suspended','removed')",$args)->fetchColumn();
        $restricted=$this->db->all("SELECT d.id,d.label,d.model,d.policy,d.app_version FROM devices d WHERE $scope AND d.policy IN ('blocked','suspended','removed') ORDER BY d.last_seen_at DESC,d.id LIMIT 25 OFFSET $restrictedOffset",$args);
        return ['items'=>$items,'restricted_devices'=>$restricted,'restricted_pagination'=>['page'=>$restrictedPage,'pages'=>max(1,(int)ceil($restrictedTotal/25)),'total'=>$restrictedTotal],'page'=>$page,'pages'=>max(1,(int)ceil($total/25)),'total'=>$total];
    }
    private function review(array $actor,string $id):array {
        $args=[Input::id($id)];$scope=$this->scope($actor,$args);
        $r=$this->db->one("SELECT r.*,a.tenant_id AS bound_tenant,a.customer_id AS bound_customer,t.status AS workspace_status FROM enrollment_requests r JOIN managed_accounts a ON a.account_ref=r.account_ref JOIN tenants t ON t.id=a.tenant_id WHERE r.id=? AND $scope",$args);
        if(!$r)throw new Problem(404,'Recovery hold not found.');
        if($r['status']!=='pending'||empty($r['recovery_state']))throw new Problem(409,'This request is no longer pending recovery.');
        $devices=$this->db->all("SELECT DISTINCT d.id,d.label,d.model,d.policy,d.installation_id,d.key_fingerprint,d.app_package,d.last_seen_at,d.reseller_detached,h.hint_hash FROM devices d JOIN enrollment_requests e ON e.device_id=d.id LEFT JOIN device_recovery_hints h ON h.device_id=d.id WHERE e.account_ref=? AND e.status='approved' AND d.customer_id=? AND d.tenant_id=? AND d.app_package=? ORDER BY d.id",[$r['account_ref'],$r['bound_customer'],$r['bound_tenant'],$r['app_package']]);
        $revision=(new Secrets($this->config))->mac('recovery-hold-review-v1\n'.json_encode([$r['id'],$r['installation_id'],$r['key_fingerprint'],$r['recovery_hint_hash'],$r['recovery_state'],$r['panel_reseller_id'],$r['panel_customer_id'],$r['bound_tenant'],$r['bound_customer'],$r['workspace_status'],$devices],JSON_THROW_ON_ERROR));
        return ['request'=>$r,'devices'=>$devices,'revision'=>$revision];
    }
    public function preview(array $actor,string $id):array {
        $v=$this->review($actor,$id);$r=$v['request'];
        return ['request_id'=>$r['id'],'model'=>$r['model'],'installation_id'=>$r['installation_id'],'expected_revision'=>$v['revision'],'devices'=>array_map(static fn($d)=>['id'=>$d['id'],'label'=>$d['label'],'model'=>$d['model'],'policy'=>$d['policy'],'last_seen_at'=>$d['last_seen_at'],'hint_matches'=>$d['hint_hash']!==null&&$d['hint_hash']===$r['recovery_hint_hash']],$v['devices'])];
    }
    public function decide(array $actor,string $id,array $input):array {
        Input::keys($input,['action','device_id','expected_revision','confirm']);
        if(($input['confirm']??null)!==true||!in_array($input['action']??null,['allow','reject'],true))throw new Problem(422,'Explicit allow or reject confirmation is required.');
        $expected=EnrollmentProof::hex($input['expected_revision']??null,64);
        $initial=$this->review($actor,$id);
        $recovery=new DeviceRecovery($this->db,$this->config,new Secrets($this->config),$this->auth);
        return $this->db->transaction(function()use($actor,$id,$input,$expected,$initial,$recovery):array {
            $recovery->lockScope($initial['request']['account_ref'],$initial['request']['app_package']);
            // Serialize with policy changes, registration, owner review and other decisions.
            $this->db->one('SELECT id FROM tenants WHERE id=? FOR UPDATE',[$initial['request']['bound_tenant']]);
            $this->db->one('SELECT id FROM enrollment_requests WHERE id=? FOR UPDATE',[$id]);
            foreach($initial['devices'] as $d)$this->db->one('SELECT id FROM devices WHERE id=? FOR UPDATE',[$d['id']]);
            $v=$this->review($actor,$id);$r=$v['request'];
            if(!hash_equals($v['revision'],$expected))throw new Problem(409,'Recovery review changed. Refresh and review again.');
            if($input['action']==='reject'){
                $this->db->query("UPDATE enrollment_requests SET status='rejected',reviewed_by=? WHERE id=?",[$actor['id'],$id]);
                $this->auth->audit($actor,'recovery_hold.rejected',$id,$r['bound_tenant']);
                return ['state'=>'rejected'];
            }
            if(!$recovery->enabled()||$r['workspace_status']!=='active')throw new Problem(409,'Recovery or workspace is not active.');
            $target=Input::id($input['device_id']??null);$device=null;
            foreach($v['devices'] as $d)if($d['id']===$target)$device=$d;
            if(!$device)throw new Problem(409,'Select an existing device belonging to this account and app.');
            if($device['policy']!=='active')throw new Problem(409,'Reactivate the existing device through its policy controls first; owner blocks cannot be bypassed by enrollment.');
            if($actor['role']!=='owner'&&!empty($device['reseller_detached']))throw new Problem(404,'Recovery hold not found.');
            $valid=$this->db->one("SELECT id FROM enrollment_requests WHERE id=? AND poll_expires_at>UTC_TIMESTAMP() AND last_request_at>DATE_SUB(UTC_TIMESTAMP(),INTERVAL 15 MINUTE)",[$id]);
            if(!$valid||empty($r['recovery_hint_hash']))throw new Problem(409,'Open the app and retry to provide a fresh signed recovery request.');
            $old=$this->db->one("SELECT * FROM enrollment_requests WHERE device_id=? AND key_fingerprint=? AND app_package=? AND status='approved' ORDER BY created_at DESC,id DESC LIMIT 1",[$target,$device['key_fingerprint'],$r['app_package']]);
            if(!$old||empty($r['panel_reseller_id'])||empty($r['panel_customer_id'])||$old['account_ref']!==$r['account_ref']||$old['panel_reseller_id']!==$r['panel_reseller_id']||$old['panel_customer_id']!==$r['panel_customer_id'])throw new Problem(409,'Verified account ownership differs.');
            $link=$this->db->one('SELECT tenant_id FROM panel_resellers WHERE panel_id=?',[$r['panel_reseller_id']]);
            if($actor['role']!=='owner'&&(!$link||$link['tenant_id']!==$r['bound_tenant']))throw new Problem(409,'Verified reseller link changed.');
            $busy=$this->db->one('SELECT id FROM devices WHERE id=? AND last_seen_at>DATE_SUB(UTC_TIMESTAMP(),INTERVAL 5 MINUTE)',[$target]);
            if($busy)throw new Problem(409,'The selected device is still checking in. Do not replace a working TV.');
            if($device['hint_hash']!==null&&$device['hint_hash']!==$r['recovery_hint_hash'])throw new Problem(409,'This request has a different known device hint. It cannot replace that device.');
            $conflict=$this->db->one('SELECT device_id FROM device_recovery_hints WHERE account_ref=? AND app_package=? AND panel_reseller_id=? AND hint_hash=? AND device_id<>?',[$r['account_ref'],$r['app_package'],$r['panel_reseller_id'],$r['recovery_hint_hash'],$target]);
            if($conflict)throw new Problem(409,'More than one device may match; owner review is required.');
            if($device['hint_hash']===null)$this->db->query('INSERT INTO device_recovery_hints (device_id,account_ref,app_package,panel_reseller_id,hint_hash) VALUES (?,?,?,?,?)',[$target,$r['account_ref'],$r['app_package'],$r['panel_reseller_id'],$r['recovery_hint_hash']]);
            $this->db->query("UPDATE enrollment_requests SET recovery_state='owner_authorized',reviewed_by=? WHERE id=?",[$actor['id'],$id]);
            $this->auth->audit($actor,'recovery_hold.allowed',$id,$r['bound_tenant'],['device_id'=>$target,'requires_fresh_signed_retry'=>true]);
            return ['state'=>'awaiting_signed_retry','device_id'=>$target];
        });
    }
}
