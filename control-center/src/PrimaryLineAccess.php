<?php
declare(strict_types=1);
namespace ControlCenter;

/** Resolve ONLY server-owned enrollment -> panel line; never playlist ordering. */
final class PrimaryLineAccess {
    public function __construct(private readonly Database $db,private readonly Config $config,private readonly ?PanelBridge $bridge=null) {}
    public function device(array $device):array {
        $device=$this->db->one('SELECT * FROM devices WHERE id=?',[$device['id']])??[];
        if(!$device)return ['valid'=>false,'seconds'=>0,'reason'=>'primary_unverified'];
        // Owner-issued synthetic simulator credentials are unavailable in production.
        // This isolates the existing localhost service/browser fixtures from a live panel.
        if($this->bridge===null&&$this->config->local()&&($device['is_test']??false))return ['valid'=>true,'seconds'=>900,'reason'=>null];
        $binding=$this->db->one("SELECT r.account_ref,p.panel_id,p.reseller_id FROM enrollment_requests r JOIN panel_customers p ON p.account_ref=r.account_ref WHERE r.device_id=? AND r.key_fingerprint=? AND r.app_package=? AND r.status='approved' ORDER BY r.created_at DESC,r.id DESC LIMIT 1",[$device['id'],$device['key_fingerprint']??null,$device['app_package']??null]);
        if(!$binding)return ['valid'=>false,'seconds'=>0,'reason'=>'primary_unverified'];
        $began=microtime(true);$bridge=$this->bridge??new PanelBridge(new EnrollmentProof($this->config,new Secrets($this->config)));
        $r=$bridge->primaryLine($binding['panel_id'],$binding['account_ref']);
        $seconds=max(0,$r['valid_for_seconds']-(int)ceil(microtime(true)-$began));
        $valid=$r['identity_valid']&&$r['active']&&$seconds>0&&$r['reseller_id']===$binding['reseller_id'];
        return ['valid'=>$valid,'seconds'=>$valid?$seconds:0,'reason'=>$valid?null:(!$r['identity_valid']?'primary_unverified':(!$r['active']||$seconds<=0?'primary_invalid':'primary_unverified'))];
    }
}
