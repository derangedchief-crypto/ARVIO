<?php
declare(strict_types=1);
namespace ControlCenter;

/** Contact PII is encrypted, tenant-scoped and separate from minimal eligibility history. */
final class TrialContacts
{
    public function __construct(private readonly Database $db,private readonly Management $management,private readonly Auth $auth,private readonly Secrets $secrets,private readonly Config $config,private readonly TrialWorker $worker) {}
    private function ready():void {if(!$this->db->one('SELECT name FROM schema_migrations WHERE name=?',['015_trial_policies_contacts.sql']))throw new Problem(503,'Apply the trial-contact database upgrade first.');}
    public static function contact(array $input):array {
        Input::keys($input,['first_name','phone']);$name=Input::text($input['first_name']??null,'first name',60);
        if(!preg_match('/^[\p{L}\p{M}][\p{L}\p{M} \x{2019}\x{0027}-]*$/uD',$name))throw new Problem(422,'Enter a first name without markup or newlines.');$name=preg_replace('/ +/u',' ',$name);
        $phone=Input::text($input['phone']??null,'phone number',50);if(preg_match('/[\r\n]/u',$phone))throw new Problem(422,'Invalid phone number.');$phone=preg_replace('/[ ().-]/','',$phone);if(str_starts_with($phone,'00'))$phone='+'.substr($phone,2);
        if(!preg_match('/^\+[1-9][0-9]{7,14}$/D',$phone))throw new Problem(422,'Include the international country code in the phone number.');return ['first_name'=>$name,'phone'=>$phone,'phone_verified'=>false];
    }
    /** Internal trusted broker receipt ingestion ONLY. Deliberately has NO public HTTP route.
     * Broker supplies a previously admitted operation, not app claims. Recording an
     * already-created receipt survives later offer suspension and grants no app access. */
    public function recordPrepared(array $context,array $receipt):array {
        $this->ready();Input::keys($context,['brand_id','operation_id','device_ref','first_name','phone','plan_id','plan_fingerprint']);
        Input::keys($receipt,['state','operation_id','reseller_id','line_id','plan_id','plan_fingerprint','notes_recorded','expires_at']);
        $id=Input::id($context['operation_id']??null);$brandId=Input::id($context['brand_id']??null);$device=$context['device_ref']??null;$fingerprint=$context['plan_fingerprint']??null;
        if(!is_string($device)||!preg_match('/^[a-f0-9]{64}$/D',$device)||!is_string($fingerprint)||!preg_match('/^[a-f0-9]{64}$/D',$fingerprint))throw new Problem(422,'Invalid verified trial context.');
        $plan=Input::integer($context['plan_id']??null,1,2147483647);$contact=self::contact(['first_name'=>$context['first_name']??null,'phone'=>$context['phone']??null]);
        $line=$receipt['line_id']??null;$reseller=$receipt['reseller_id']??null;
        if(($receipt['state']??null)!=='prepared'||($receipt['operation_id']??null)!==$id||($receipt['notes_recorded']??null)!==true||($receipt['plan_id']??null)!==$plan||($receipt['plan_fingerprint']??null)!==$fingerprint||!is_string($line)||!preg_match('/^[1-9][0-9]{0,18}$/D',$line)||!is_string($reseller))throw new Problem(409,'Worker has not confirmed this trial contact.');
        $expires=$receipt['expires_at']??null;if($expires!==null)Input::integer($expires,1,4102444800);
        return $this->db->transaction(function()use($id,$brandId,$device,$plan,$contact,$line,$reseller,$expires):array {
            $before=$this->db->one('SELECT tenant_id FROM app_brands WHERE id=?',[$brandId]);if(!$before)throw new Problem(404,'Verified brand not found.');$tenant=$this->db->one('SELECT * FROM tenants WHERE id=? FOR UPDATE',[$before['tenant_id']]);$brand=$this->db->one('SELECT * FROM app_brands WHERE id=? FOR UPDATE',[$brandId]);$link=$this->db->one('SELECT panel_id FROM panel_resellers WHERE tenant_id=?',[$before['tenant_id']]);
            if(!$brand||$brand['tenant_id']!==$before['tenant_id']||!$tenant||!$link||(string)$link['panel_id']!==$reseller)throw new Problem(409,'Trial ownership changed; contact needs review.');
            $customer=$this->secrets->mac("trial-customer-v1\n".$tenant['id']."\n".$contact['phone']);$old=$this->db->one('SELECT * FROM trial_contacts WHERE operation_id=? FOR UPDATE',[$id]);
            if($old){if($old['tenant_id']!==$tenant['id']||$old['brand_id']!==$brandId||$old['customer_ref']!==$customer||$old['device_ref']!==$device||$old['panel_line_id']!==$line||(int)$old['plan_id']!==$plan)throw new Problem(409,'Trial receipt does not match its original contact.');return ['id'=>$old['id']];}
            if($this->db->one('SELECT id FROM trial_contacts WHERE tenant_id=? AND customer_ref=? AND device_ref=? FOR UPDATE',[$tenant['id'],$customer,$device]))throw new Problem(409,'This customer/device already has trial history with this reseller.');
            $keepDays=$this->config->number('trial_contact_retention_days',90,7,365);$until=($expires??time())+$keepDays*86400;$contactId=Database::id();$cipher=$until>time()?$this->secrets->encrypt(json_encode(['v'=>1,'tenant_id'=>$tenant['id'],'contact_id'=>$contactId,'contact'=>$contact],JSON_UNESCAPED_UNICODE|JSON_THROW_ON_ERROR)):null;
            $expiry=$expires?gmdate('Y-m-d H:i:s',$expires):null;$this->db->query('INSERT INTO trial_contacts (id,operation_id,tenant_id,brand_id,customer_ref,device_ref,contact_cipher,panel_line_id,plan_id,state,expires_at,trial_expires_at,contact_retention_until,status_checked_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,UTC_TIMESTAMP())',[$contactId,$id,$tenant['id'],$brandId,$customer,$device,$cipher,$line,$plan,'prepared',$expiry,$expiry,gmdate('Y-m-d H:i:s',$until)]);
            $this->auth->audit(null,'trial.contact_recorded',$contactId,$tenant['id']);return ['id'=>$contactId];
        });
    }
    private function publicRow(array $row,bool $full):array {
        $contact=null;if($row['contact_cipher']!==null&&strtotime($row['contact_retention_until'].' UTC')>time()){
            $envelope=json_decode($this->secrets->decrypt($row['contact_cipher']),true,8,JSON_THROW_ON_ERROR);
            if(!is_array($envelope)||($envelope['v']??null)!==1||($envelope['tenant_id']??null)!==$row['tenant_id']||($envelope['contact_id']??null)!==$row['id']||!is_array($envelope['contact']??null))throw new Problem(503,'Trial contact integrity check failed.');
            $contact=self::contact(['first_name'=>$envelope['contact']['first_name']??null,'phone'=>$envelope['contact']['phone']??null]);
        }
        $state=in_array($row['state'],['prepared','active'],true)&&$row['expires_at']!==null&&strtotime($row['expires_at'].' UTC')<=time()?'expired':$row['state'];
        return ['id'=>$row['id'],'first_name'=>$contact['first_name']??'Contact removed','phone'=>$contact?($full?$contact['phone']:'••••'.substr($contact['phone'],-4)):null,'phone_verified'=>false,'contact_available'=>$contact!==null,'state'=>$state,'expires_at'=>$row['expires_at'],'trial_expires_at'=>$row['trial_expires_at'],'created_at'=>$row['created_at'],'last_verified_at'=>$row['status_checked_at'],'contact_retention_until'=>$row['contact_retention_until'],'plan_id'=>(int)$row['plan_id']];
    }
    public function listing(array $actor,array $input):array {
        $this->ready();Input::keys($input,['tenant_id','page']);$t=$this->management->tenant($actor,Input::id($input['tenant_id']??$actor['tenant_id']));$page=Input::page($input['page']??1);$offset=($page-1)*25;
        $total=(int)$this->db->query('SELECT COUNT(*) FROM trial_contacts WHERE tenant_id=?',[$t['id']])->fetchColumn();$rows=$this->db->all("SELECT * FROM trial_contacts WHERE tenant_id=? ORDER BY created_at DESC,id LIMIT 25 OFFSET $offset",[$t['id']]);
        return ['items'=>array_map(fn($r)=>$this->publicRow($r,false),$rows),'page'=>$page,'pages'=>max(1,(int)ceil($total/25)),'total'=>$total,'retention_days'=>$this->config->number('trial_contact_retention_days',90,7,365)];
    }
    private function row(array $actor,string $id,bool $lock=false):array {$this->ready();$row=$this->db->one('SELECT * FROM trial_contacts WHERE id=?'.($lock?' FOR UPDATE':''),[Input::id($id)]);if(!$row)throw new Problem(404,'Contact not found.');$this->management->tenant($actor,$row['tenant_id']);return $row;}
    public function detail(array $actor,string $id):array {$row=$this->row($actor,$id);$result=$this->publicRow($row,true);$this->auth->audit($actor,'trial.contact_viewed',$id,$row['tenant_id']);return $result;}
    public function redact(array $actor,string $id,array $input):array {
        Input::keys($input,['confirmed']);if(($input['confirmed']??null)!==true)throw new Problem(422,'Confirm contact removal. Panel notes are not changed by this action.');
        return $this->db->transaction(function()use($actor,$id):array {$row=$this->row($actor,$id,true);$this->db->query('UPDATE trial_contacts SET contact_cipher=NULL WHERE id=?',[$id]);$this->auth->audit($actor,'trial.contact_redacted',$id,$row['tenant_id']);return ['id'=>$id,'contact_removed'=>true,'eligibility_reset'=>false,'panel_notes_changed'=>false];});
    }
    public function refresh(array $actor,string $id,array $input):array {
        Input::keys($input,[]);$before=$this->row($actor,$id);$this->auth->limit('trial-contact-status',$actor['id'],30,60);$link=$this->db->one('SELECT panel_id FROM panel_resellers WHERE tenant_id=?',[$before['tenant_id']]);if(!$link)throw new Problem(409,'The verified reseller link is unavailable.');
        $status=$this->worker->status($before['operation_id'],(string)$link['panel_id']);if($status['line_id']!==$before['panel_line_id'])throw new Problem(409,'Trial line identity changed; review before updating.');
        return $this->db->transaction(function()use($actor,$id,$before,$link,$status):array {
            $this->management->tenant($actor,$before['tenant_id'],true);$row=$this->row($actor,$id,true);$current=$this->db->one('SELECT panel_id FROM panel_resellers WHERE tenant_id=?',[$before['tenant_id']]);if(!$current||(string)$current['panel_id']!==(string)$link['panel_id'])throw new Problem(409,'Reseller assignment changed; status not saved.');
            if($status['checked_at_us']<=(int)$row['panel_status_revision_us'])return $this->detail($actor,$id);
            $expiry=$status['state']==='unavailable'?$row['expires_at']:($status['expires_at']?gmdate('Y-m-d H:i:s',$status['expires_at']):null);$original=$row['trial_expires_at'];$until=$row['contact_retention_until'];
            if($original===null&&$expiry!==null&&in_array($status['state'],['active','expired','restricted'],true)){$original=$expiry;$keep=$this->config->number('trial_contact_retention_days',90,7,365);$until=gmdate('Y-m-d H:i:s',max(strtotime($until.' UTC'),strtotime($expiry.' UTC')+$keep*86400));}
            $this->db->query('UPDATE trial_contacts SET state=?,expires_at=?,trial_expires_at=?,contact_retention_until=?,panel_status_revision_us=?,status_checked_at=UTC_TIMESTAMP() WHERE id=?',[$status['state'],$expiry,$original,$until,$status['checked_at_us'],$id]);$this->auth->audit($actor,'trial.status_refreshed',$id,$row['tenant_id'],['state'=>$status['state']]);return $this->detail($actor,$id);
        });
    }
    public function maintain():void {$this->db->query('UPDATE trial_contacts SET contact_cipher=NULL WHERE contact_cipher IS NOT NULL AND contact_retention_until<=UTC_TIMESTAMP() LIMIT 1000');}
}
