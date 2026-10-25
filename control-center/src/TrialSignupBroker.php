<?php
declare(strict_types=1);
namespace ControlCenter;

/** Anonymous public offer signup. Sessions prove key possession, NOT APK identity. */
final class TrialSignupBroker
{
    public function __construct(private readonly Database $db,private readonly Config $config,private readonly Secrets $secrets,private readonly Auth $auth,private readonly TrialOffers $offers,private readonly TrialPublicSessions $sessions,private readonly TrialBudgets $budgets,private readonly TrialWorker $worker,private readonly TrialContacts $contacts) {}
    private function seal(string $purpose,array $job,array $data):string {return $this->secrets->encrypt(json_encode(['purpose'=>$purpose,'id'=>$job['id'],'tenant_id'=>$job['tenant_id'],'brand_id'=>$job['brand_id'],'key_hash'=>$job['client_key_hash'],'data'=>$data],JSON_UNESCAPED_UNICODE|JSON_THROW_ON_ERROR));}
    private function open(string $purpose,array $job,string $cipher):array {
        $e=json_decode($this->secrets->decrypt($cipher),true,15,JSON_THROW_ON_ERROR);if(($e['purpose']??null)!==$purpose||($e['id']??null)!==$job['id']||($e['tenant_id']??null)!==$job['tenant_id']||($e['brand_id']??null)!==$job['brand_id']||($e['key_hash']??null)!==$job['client_key_hash']||!is_array($e['data']??null))throw new Problem(503,'Trial operation integrity check failed.');return $e['data'];
    }
    public function submit(array $input):array {
        $session=$this->sessions->prove($input);if(($input['notice_accepted']??null)!==true)throw new Problem(422,'Confirm sharing your name and phone for trial setup and follow-up.');
        $contact=TrialContacts::contact(['first_name'=>$input['first_name']??null,'phone'=>$input['phone']??null]);if(!hash_equals(TrialPublicSessions::requestHash($contact),$input['request_hash']))throw new Problem(422,'Contact details changed; start a fresh signed request.');
        $id=$this->budgets->locked($session,fn()=> $this->db->transaction(function()use($session,$contact):string {
            $this->db->one('SELECT id FROM tenants WHERE id=? FOR UPDATE',[$session['tenant_id']]);$s=$this->db->one('SELECT * FROM trial_public_sessions WHERE token_hash=? AND expires_at>UTC_TIMESTAMP() FOR UPDATE',[$session['token_hash']]);if(!$s)throw new Problem(401,'Trial session expired.');
            $existing=$this->db->one('SELECT id FROM trial_signup_operations WHERE id=?',[$s['resume_operation_id']??$s['operation_id']]);if($existing)return $existing['id'];
            $offer=$this->offers->admitted($s['brand_id'],true);if(!$offer||$offer['policy_stamp']!==$s['policy_stamp'])throw new Problem(409,'Offer changed; review it before starting a new request.');
            $eligibility=$this->secrets->mac("trial-eligibility-v1\n".$offer['panel_reseller_id']."\n".$contact['phone']."\n".$s['device_ref']);$old=$this->db->one('SELECT * FROM trial_signup_operations WHERE eligibility_key=? FOR UPDATE',[$eligibility]);
            if($old){if(!hash_equals($old['client_key_hash'],$s['client_key_hash']))throw new Problem(409,'This customer/device has already requested or used a trial with this reseller.');$this->db->query('UPDATE trial_public_sessions SET resume_operation_id=? WHERE token_hash=?',[$old['id'],$s['token_hash']]);return $old['id'];}
            $this->offers->admitNewOperation($s['brand_id']);
            $job=['id'=>$s['operation_id'],'tenant_id'=>$s['tenant_id'],'brand_id'=>$s['brand_id'],'client_key_hash'=>$s['client_key_hash']];$spec=['operation_id'=>$job['id'],'brand_id'=>$s['brand_id'],'reseller_id'=>$offer['panel_reseller_id'],'device_ref'=>$s['device_ref'],'package_id'=>$offer['plan']['id'],'plan_fingerprint'=>$offer['plan']['fingerprint'],'first_name'=>$contact['first_name'],'phone'=>$contact['phone']];
            $context=['spec'=>$spec,'policy_stamp'=>$offer['policy_stamp'],'notice_version'=>1];$cipher=$this->seal('trial-request-v1',$job,$context);$days=$this->config->number('trial_contact_retention_days',90,7,365);
            $this->db->query('INSERT INTO trial_signup_operations (id,tenant_id,brand_id,session_hash,eligibility_key,client_key_hash,request_cipher,contact_retention_until) VALUES (?,?,?,?,?,?,?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL ? DAY))',[$job['id'],$job['tenant_id'],$job['brand_id'],$s['token_hash'],$eligibility,$s['client_key_hash'],$cipher,$days]);$this->budgets->reserve($job,$offer,$s);$this->auth->audit(null,'trial.signup_reserved',$job['id'],$job['tenant_id']);return $job['id'];
        }));
        $this->process($id);return $this->status($input);
    }
    public function status(array $input):array {
        $s=$this->sessions->prove($input);$job=$this->db->one('SELECT * FROM trial_signup_operations WHERE id=?',[$s['resume_operation_id']??$s['operation_id']]);if(!$job)return ['state'=>'not_submitted'];
        if($job['tenant_id']!==$s['tenant_id']||$job['brand_id']!==$s['brand_id']||!hash_equals($job['client_key_hash'],$s['client_key_hash']))throw new Problem(401,'Trial result does not belong to this session key.');
        // A signed poll may advance only this already-admitted operation once its
        // retry lease is due. Recovery must not depend solely on a working cron.
        if(in_array($job['state'],['reserved','sending','retry_wait','registration_pending'],true)&&strtotime($job['next_attempt_at'].' UTC')<=time()){
            $this->process($job['id']);$job=$this->db->one('SELECT * FROM trial_signup_operations WHERE id=?',[$job['id']]);
        }
        $result=['state'=>$job['state'],'operation_id'=>$job['id']];
        if(in_array($job['state'],['reserved','sending','retry_wait','registration_pending'],true)&&$this->offers->paused($job['brand_id']))$result['paused']=true;
        if($job['state']==='prepared'){
            if($job['activation_cipher']===null||$job['activation_until']===null||strtotime($job['activation_until'].' UTC')<=time())return ['state'=>'already_created','operation_id'=>$job['id']];
            $data=$this->open('trial-activation-v1',$job,$job['activation_cipher']);$result['activation_code']=$data['activation_code'];$result['expires_at']=$data['expires_at'];
        }
        return $result;
    }
    public function acknowledge(array $input):array {
        $s=$this->sessions->prove($input);return $this->db->transaction(function()use($s):array {
            $job=$this->db->one('SELECT * FROM trial_signup_operations WHERE id=? FOR UPDATE',[$s['resume_operation_id']??$s['operation_id']]);if(!$job||!hash_equals($job['client_key_hash'],$s['client_key_hash'])||$job['tenant_id']!==$s['tenant_id']||$job['brand_id']!==$s['brand_id'])throw new Problem(401,'Invalid trial acknowledgment.');
            if(!in_array($job['state'],['prepared','delivered'],true))throw new Problem(409,'Trial result is not ready.');$this->db->query("UPDATE trial_signup_operations SET state='delivered',activation_cipher=NULL,activation_until=NULL WHERE id=?",[$job['id']]);return ['state'=>'delivered','operation_id'=>$job['id']];
        });
    }
    public function process(string $id):void {
        $before=$this->db->one('SELECT tenant_id FROM trial_signup_operations WHERE id=?',[$id]);if(!$before)return;
        $request=$this->db->transaction(function()use($id,$before):?array {
            $this->db->one('SELECT id FROM tenants WHERE id=? FOR UPDATE',[$before['tenant_id']]);$job=$this->db->one('SELECT * FROM trial_signup_operations WHERE id=? FOR UPDATE',[$id]);
            if(!$job||!in_array($job['state'],['reserved','sending','retry_wait','registration_pending'],true)||strtotime($job['next_attempt_at'].' UTC')>time()||$job['request_cipher']===null)return null;
            if($job['tenant_id']!==$before['tenant_id'])throw new Problem(409,'Trial ownership changed; review this operation.');
            // Current/locking reads serialize the dispatch decision against the
            // admin and reseller switches. A dispatch already authorized here
            // may finish; a later OFF cannot recall a remote panel request.
            if($this->offers->paused($job['brand_id'],true))return null;
            if((int)$job['attempts']>=3){$this->db->query("UPDATE trial_signup_operations SET state='needs_review' WHERE id=?",[$id]);return null;}
            $ctx=$this->open('trial-request-v1',$job,$job['request_cipher']);$offer=$this->offers->admitted($job['brand_id'],true);
            if(!$offer||$offer['policy_stamp']!==$ctx['policy_stamp']){
                if((int)$job['attempts']===0){$this->db->query("UPDATE trial_signup_operations SET state='cancelled',eligibility_key=NULL,request_cipher=NULL WHERE id=?",[$id]);$this->db->query('UPDATE trial_signup_budgets SET counted=0 WHERE operation_id=?',[$id]);}
                else $this->db->query("UPDATE trial_signup_operations SET state='needs_review' WHERE id=?",[$id]);return null;
            }
            $this->db->query("UPDATE trial_signup_operations SET state='sending',attempts=attempts+1,next_attempt_at=DATE_ADD(UTC_TIMESTAMP(),INTERVAL 60 SECOND) WHERE id=?",[$id]);return ['job'=>$job,'context'=>$ctx];
        });
        if(!$request)return;$job=$request['job'];$ctx=$request['context'];
        try{$reply=$this->worker->provision($ctx['spec']);}catch(\Throwable){$this->db->query("UPDATE trial_signup_operations SET state='retry_wait',next_attempt_at=DATE_ADD(UTC_TIMESTAMP(),INTERVAL 60 SECOND) WHERE id=? AND state='sending'",[$id]);return;}
        $this->db->transaction(function()use($id,$job,$ctx,$reply):void {
            $this->db->one('SELECT id FROM tenants WHERE id=? FOR UPDATE',[$job['tenant_id']]);$current=$this->db->one('SELECT * FROM trial_signup_operations WHERE id=? FOR UPDATE',[$id]);if(!$current||$current['state']!=='sending')return;
            if($reply['state']==='failed'){$this->db->query("UPDATE trial_signup_operations SET state='failed',eligibility_key=NULL,request_cipher=NULL WHERE id=?",[$id]);$this->db->query('UPDATE trial_signup_budgets SET counted=0 WHERE operation_id=?',[$id]);return;}
            if($reply['state']!=='prepared'){$this->db->query('UPDATE trial_signup_operations SET state=?,panel_line_id=?,next_attempt_at=DATE_ADD(UTC_TIMESTAMP(),INTERVAL 60 SECOND) WHERE id=?',[$reply['state'],$reply['line_id'],$id]);return;}
            $spec=$ctx['spec'];$contactContext=['brand_id'=>$job['brand_id'],'operation_id'=>$id,'device_ref'=>$spec['device_ref'],'first_name'=>$spec['first_name'],'phone'=>$spec['phone'],'plan_id'=>$spec['package_id'],'plan_fingerprint'=>$spec['plan_fingerprint']];$receipt=$reply;unset($receipt['activation_code']);
            $this->contacts->recordPrepared($contactContext,$receipt);$cipher=$this->seal('trial-activation-v1',$current,['activation_code'=>$reply['activation_code'],'expires_at'=>$reply['expires_at']]);$this->db->query("UPDATE trial_signup_operations SET state='prepared',panel_line_id=?,activation_cipher=?,activation_until=DATE_ADD(UTC_TIMESTAMP(),INTERVAL 15 MINUTE),request_cipher=NULL WHERE id=?",[$reply['line_id'],$cipher,$id]);$this->auth->audit(null,'trial.signup_prepared',$id,$job['tenant_id']);
        });
    }
    public function maintain():void {
        if($this->offers->enabled())foreach($this->db->all("SELECT j.id FROM trial_signup_operations j JOIN trial_offer_publications p ON p.brand_id=j.brand_id AND p.enabled=1 WHERE j.state IN ('reserved','sending','retry_wait','registration_pending') AND j.next_attempt_at<=UTC_TIMESTAMP() ORDER BY j.next_attempt_at LIMIT 5") as $r)$this->process($r['id']);
        $this->db->query('UPDATE trial_signup_operations SET activation_cipher=NULL WHERE activation_cipher IS NOT NULL AND activation_until<=UTC_TIMESTAMP() LIMIT 1000');
        $this->db->query("UPDATE trial_signup_operations SET request_cipher=NULL,state=IF(state IN ('reserved','sending','retry_wait','registration_pending'),'needs_review',state) WHERE request_cipher IS NOT NULL AND contact_retention_until<=UTC_TIMESTAMP() LIMIT 1000");
        $this->db->query('DELETE s FROM trial_public_sessions s WHERE s.expires_at<DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 DAY) AND NOT EXISTS(SELECT 1 FROM trial_signup_operations j WHERE j.session_hash=s.token_hash)');
    }
}
