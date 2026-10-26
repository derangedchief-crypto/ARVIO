<?php
declare(strict_types=1);
namespace ControlCenter;

/** Public brand-scoped signup AUTHORIZATION policy, NOT proof of APK/device identity. */
final class TrialOffers
{
    public function __construct(private readonly Database $db,private readonly Config $config,private readonly Management $management,private readonly Auth $auth,private readonly Secrets $secrets,private readonly TrialPolicies $policies,private readonly BrandArtifacts $artifacts) {}
    private function deployed():bool {return $this->config->get('trial_public_signup_enabled',false)===true;}
    private function controlsReady():bool {return (bool)$this->db->one('SELECT name FROM schema_migrations WHERE name=?',['019_trial_global_control.sql']);}
    private function globalControl(bool $lock=false):array {
        if(!$this->controlsReady())return ['enabled'=>false,'revision'=>0,'upgrade_required'=>true];
        $row=$this->db->one('SELECT trial_offers_enabled,trial_control_revision FROM platform_settings WHERE id=1'.($lock?' FOR UPDATE':''));
        if(!$row)throw new Problem(503,'Global trial controls are unavailable.');return ['enabled'=>(bool)$row['trial_offers_enabled'],'revision'=>(int)$row['trial_control_revision'],'upgrade_required'=>false];
    }
    public function enabled(bool $current=false):bool {return $this->deployed()&&$this->globalControl($current)['enabled'];}
    public function globalSettings(array $actor):array {Auth::owner($actor);$state=$this->globalControl();return $state+['deployment_enabled'=>$this->deployed(),'effective_enabled'=>$this->deployed()&&$state['enabled']];}
    public function setGlobal(array $actor,array $input):array {
        Auth::owner($actor);Input::keys($input,['enabled','expected_revision']);if(!is_bool($input['enabled']??null))throw new Problem(422,'Choose a global trial state.');
        if(!$this->controlsReady())throw new Problem(503,'Apply the global trial controls database upgrade first.');
        return $this->db->transaction(function()use($actor,$input):array {$current=$this->globalControl(true);if($current['revision']!==Input::integer($input['expected_revision']??null,1,PHP_INT_MAX))throw new Problem(409,'Global trial controls changed; reload before saving.');
            $this->db->query('UPDATE platform_settings SET trial_offers_enabled=?,trial_control_revision=trial_control_revision+1 WHERE id=1',[(int)$input['enabled']]);$this->auth->audit($actor,'trial.global_gate_changed',null,null,['enabled'=>$input['enabled']]);return $this->globalSettings($actor);
        });
    }
    private function ready():void {if(!$this->db->one('SELECT name FROM schema_migrations WHERE name=?',['016_public_trial_signup.sql']))throw new Problem(503,'Apply the public-trial database upgrade first.');}
    public function dailyCap():int {return $this->config->number('trial_reseller_daily_limit',50,1,1000);}
    /** Private owner opt-in; no client-supplied role, package or limit grants this. */
    private function ownerUnlimited(string $brandId):bool {
        $approved=$this->config->get('trial_owner_unlimited_brand_id');if($approved===null)return false;
        if(!is_string($approved)||!preg_match('/^[a-f0-9]{32}$/D',$approved))throw new Problem(503,'Invalid owner trial policy.');
        return hash_equals($approved,$brandId)&&(bool)$this->db->one("SELECT b.id FROM app_brands b JOIN tenants t ON t.id=b.tenant_id JOIN panel_resellers r ON r.tenant_id=t.id WHERE b.id=? AND b.enabled=1 AND t.kind='owner' AND t.status='active'",[$brandId]);
    }
    private function pilotBrand():?string {
        $id=$this->config->get('trial_pilot_brand_id');if($id===null)return null;
        if(!is_string($id)||!preg_match('/^[a-f0-9]{32}$/D',$id))throw new Problem(503,'Invalid single-account pilot configuration.');return $id;
    }
    /** Called under the broker tenant lock, before reserving ANY new operation.
     * All outcomes consume the one pilot slot, including failed/uncertain work.
     * The original operation may still reconcile/retry; days/rebrands never reset it. */
    public function admitNewOperation(string $brandId):void {
        if($this->ownerUnlimited($brandId))return;
        $pilot=$this->pilotBrand();if($pilot===null)return;
        if($brandId!==$pilot)throw new Problem(409,'Only the owner-selected pilot brand is enabled.');
        if($this->db->one('SELECT id FROM trial_signup_operations WHERE brand_id=? LIMIT 1',[$pilot]))throw new Problem(429,'The single-account pilot is already reserved. Recover the original request or ask the owner to review it; do not create another account.');
    }
    private function snapshot(array $actor,string $tenantId):array {
        $p=$this->policies->get($actor,['tenant_id'=>$tenantId]);if(!$p['configuration_ready'])throw new Problem(409,'Review the current brand, worker and approved trial default first.');
        $brand=$this->db->one('SELECT * FROM app_brands WHERE tenant_id=?',[$tenantId]);$link=$this->db->one('SELECT panel_id FROM panel_resellers WHERE tenant_id=?',[$tenantId]);$channel=$this->config->get('trial_channel','production');if(!in_array($channel,['production','testing'],true))throw new Problem(503,'Invalid trial rollout channel.');
        $artifact=$this->artifacts->approved($brand['id'],$channel);$revision=[];
        if(!$artifact||$artifact['trial_protocol']<1||!preg_match('/^\d+\.\d+\.\d+\.([1-9][0-9]{0,4})(?:-test)?$/D',$artifact['version_name'],$revision)||(int)$revision[1]!== (int)$brand['version']||$artifact['package_name']!==$brand['package_name'].($channel==='testing'?'.test':''))throw new Problem(409,'A centrally approved current branded build supporting trials is required.');$plan=null;foreach($p['plans'] as $candidate)if($candidate['id']===(int)$p['default']['plan_id'])$plan=$candidate;
        if(!$plan||!$link)throw new Problem(409,'Verified trial offer is unavailable.');
        return ['brand_id'=>$brand['id'],'tenant_id'=>$tenantId,'brand_name'=>$brand['name'],'brand_version'=>(int)$brand['version'],'panel_reseller_id'=>(string)$link['panel_id'],'artifact_id'=>$artifact['id'],'grant_version'=>$p['grant']['version'],'default_revision'=>(int)$p['default']['revision'],'plan'=>$plan,'mode'=>'public_brand_signup','notice_version'=>1,'owner_unlimited'=>$this->ownerUnlimited($brand['id'])];
    }
    private function stamp(array $s,int $daily):string {return $this->secrets->mac("public-trial-offer-v1\n".json_encode([$s['brand_id'],$s['tenant_id'],$s['brand_version'],$s['panel_reseller_id'],$s['artifact_id'],$s['grant_version'],$s['default_revision'],$s['plan']['id'],$s['plan']['fingerprint'],$s['mode'],$s['notice_version'],$daily],JSON_UNESCAPED_SLASHES|JSON_THROW_ON_ERROR));}
    public function publish(array $actor,array $input):array {
        $this->ready();Input::keys($input,['tenant_id','expected_revision','enabled','daily_limit','confirmed_public_mode']);if(!is_bool($input['enabled']??null))throw new Problem(422,'Choose an offer publication state.');
        return $this->db->transaction(function()use($actor,$input):array {
            $t=$this->management->workspace($actor,$input['tenant_id']??null,true);$old=$this->db->one('SELECT * FROM trial_offer_publications WHERE tenant_id=? FOR UPDATE',[$t['id']]);
            $revision=$input['expected_revision']??0;if(!is_int($revision)||$revision<0||$revision>100000)throw new Problem(422,'Invalid offer revision. Reload trial settings before publishing.');if($revision!==(int)($old['revision']??0))throw new Problem(409,'Offer publication changed; reload it.');
            if(!$input['enabled']&&$old){$this->db->query('UPDATE trial_offer_publications SET enabled=0,revision=revision+1,updated_by=? WHERE tenant_id=?',[$actor['id'],$t['id']]);$this->auth->audit($actor,'trial.offer_unpublished',null,$t['id']);return ['revision'=>$revision+1,'published'=>false,'existing_trials_changed'=>false];}
            if(!$input['enabled'])return ['revision'=>0,'published'=>false,'existing_trials_changed'=>false];
            if(!$this->controlsReady())throw new Problem(503,'Apply the global trial controls database upgrade first.');
            // A reseller can save its own ON preference during a global pause,
            // but neither that preference nor this publication bypasses the gate.
            if(!$this->deployed()||($input['confirmed_public_mode']??null)!==true)throw new Problem(409,'Public signup rollout is off or has not been explicitly confirmed.');$s=$this->snapshot($actor,$t['id']);
            if($s['owner_unlimited'])$daily=0;else {$cap=$this->dailyCap();$daily=$input['daily_limit']??$cap;if(!is_int($daily)||$daily<1||$daily>$cap)throw new Problem(422,'Choose a whole-number daily trial limit from 1 to '.$cap.'.');}
            $pilot=$this->pilotBrand();if($pilot!==null&&$s['brand_id']!==$pilot&&!$s['owner_unlimited'])throw new Problem(409,'Only the owner-selected pilot brand may be published.');
            $this->db->query('INSERT INTO trial_offer_publications (tenant_id,brand_id,revision,enabled,policy_stamp,daily_limit,updated_by) VALUES (?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE revision=VALUES(revision),enabled=VALUES(enabled),policy_stamp=VALUES(policy_stamp),daily_limit=VALUES(daily_limit),updated_by=VALUES(updated_by)',[$t['id'],$s['brand_id'],$revision+1,(int)$input['enabled'],$this->stamp($s,$daily),$daily,$actor['id']]);$this->auth->audit($actor,'trial.offer_published',null,$t['id'],['revision'=>$revision+1,'daily_limit'=>$daily]);return ['revision'=>$revision+1,'published'=>$input['enabled'],'existing_trials_changed'=>false];
        });
    }
    public function admitted(string $brandId,bool $current=false):?array {
        if(!$this->enabled($current))return null;$this->ready();if(!preg_match('/^[a-f0-9]{32}$/D',$brandId))return null;
        $pilot=$this->pilotBrand();if($pilot!==null&&$pilot!==$brandId&&!$this->ownerUnlimited($brandId))return null;
        $pub=$this->db->one('SELECT * FROM trial_offer_publications WHERE brand_id=? AND enabled=1'.($current?' FOR UPDATE':''),[$brandId]);if(!$pub)return null;
        try{$s=$this->snapshot(['role'=>'owner','tenant_id'=>null],$pub['tenant_id']);}catch(Problem $e){if($e->status<500)return null;throw $e;}
        if(!hash_equals($pub['policy_stamp'],$this->stamp($s,(int)$pub['daily_limit'])))return null;
        if($s['owner_unlimited']?(int)$pub['daily_limit']!==0:(int)$pub['daily_limit']<1)return null;
        $s['policy_stamp']=$pub['policy_stamp'];$s['daily_limit']=$s['owner_unlimited']?0:min((int)$pub['daily_limit'],$this->dailyCap());return $s;
    }
    /** Pause new handoffs without deleting or re-creating the original request. */
    public function paused(string $brandId,bool $current=false):bool {
        if(!$this->enabled($current))return true;$pilot=$this->pilotBrand();if($pilot!==null&&$pilot!==$brandId&&!$this->ownerUnlimited($brandId))return true;
        return !$this->db->one('SELECT tenant_id FROM trial_offer_publications WHERE brand_id=? AND enabled=1'.($current?' FOR UPDATE':''),[$brandId]);
    }
    public function publicInfo(string $brandId):array {
        $s=$this->admitted($brandId);if(!$s)return ['available'=>false];return ['available'=>true,'offer_revision'=>hash('sha256',$s['policy_stamp']),'brand_name'=>$s['brand_name'],'duration'=>$s['plan']['duration'],'period'=>$s['plan']['period'],'max_connections'=>$s['plan']['max_connections'],'notice_version'=>1,'notice'=>'Your first name and phone number will be shared with this reseller for trial setup and follow-up. No phone verification or automated messages.'];
    }
    public function managerState(array $actor,array $input):array {
        $this->ready();Input::keys($input,['tenant_id']);$t=$this->management->workspace($actor,$input['tenant_id']??null);$row=$this->db->one('SELECT revision,enabled,daily_limit FROM trial_offer_publications WHERE tenant_id=?',[$t['id']]);$reason='';$s=null;
        try{$s=$this->snapshot($actor,$t['id']);}catch(Problem $e){$reason=$e->status<500?$e->getMessage():'Offer prerequisites need owner review.';}
        $pilot=$this->pilotBrand();$pilotStatus=null;
        $brand=$this->db->one('SELECT id FROM app_brands WHERE tenant_id=?',[$t['id']]);$unlimited=$brand&&$this->ownerUnlimited($brand['id']);
        if($pilot!==null&&!$unlimited){if(!$brand||$brand['id']!==$pilot){$s=null;$reason='Only the owner-selected single-account pilot brand is enabled.';}elseif($actor['role']==='owner'){$operation=$this->db->one('SELECT id,state FROM trial_signup_operations WHERE brand_id=? LIMIT 1',[$pilot]);$pilotStatus=['operation_limit'=>1,'remaining'=>$operation?0:1,'state'=>$operation['state']??null];}}
        $active=false;if($s&&$this->enabled())$active=$this->admitted($s['brand_id'])!==null;
        $global=$this->globalControl();return ['publication'=>$row,'rollout_enabled'=>$this->enabled(),'deployment_enabled'=>$this->deployed(),'global_enabled'=>$global['enabled'],'controls_upgrade_required'=>$global['upgrade_required'],'can_publish'=>$s!==null&&$this->deployed()&&!$global['upgrade_required'],'active'=>$active,'daily_cap'=>$unlimited?null:$this->dailyCap(),'owner_unlimited'=>$unlimited,'access_mode'=>'public_brand_signup','reason'=>$reason,'pilot'=>$pilotStatus];
    }
}
