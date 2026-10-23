<?php
declare(strict_types=1);
namespace ControlCenter;

/** Public brand-scoped signup AUTHORIZATION policy, NOT proof of APK/device identity. */
final class TrialOffers
{
    public function __construct(private readonly Database $db,private readonly Config $config,private readonly Management $management,private readonly Auth $auth,private readonly Secrets $secrets,private readonly TrialPolicies $policies,private readonly BrandArtifacts $artifacts) {}
    public function enabled():bool {return $this->config->get('trial_public_signup_enabled',false)===true;}
    private function ready():void {if(!$this->db->one('SELECT name FROM schema_migrations WHERE name=?',['016_public_trial_signup.sql']))throw new Problem(503,'Apply the public-trial database upgrade first.');}
    public function dailyCap():int {return $this->config->number('trial_reseller_daily_limit',50,1,1000);}
    private function snapshot(array $actor,string $tenantId):array {
        $p=$this->policies->get($actor,['tenant_id'=>$tenantId]);if(!$p['configuration_ready'])throw new Problem(409,'Review the current brand, worker and approved trial default first.');
        $brand=$this->db->one('SELECT * FROM app_brands WHERE tenant_id=?',[$tenantId]);$link=$this->db->one('SELECT panel_id FROM panel_resellers WHERE tenant_id=?',[$tenantId]);$channel=$this->config->get('trial_channel','production');if(!in_array($channel,['production','testing'],true))throw new Problem(503,'Invalid trial rollout channel.');
        $artifact=$this->artifacts->approved($brand['id'],$channel);$revision=[];
        if(!$artifact||$artifact['trial_protocol']<1||!preg_match('/^\d+\.\d+\.\d+\.([1-9][0-9]{0,4})(?:-test)?$/D',$artifact['version_name'],$revision)||(int)$revision[1]!== (int)$brand['version']||$artifact['package_name']!==$brand['package_name'].($channel==='testing'?'.test':''))throw new Problem(409,'A centrally approved current branded build supporting trials is required.');$plan=null;foreach($p['plans'] as $candidate)if($candidate['id']===(int)$p['default']['plan_id'])$plan=$candidate;
        if(!$plan||!$link)throw new Problem(409,'Verified trial offer is unavailable.');
        return ['brand_id'=>$brand['id'],'tenant_id'=>$tenantId,'brand_name'=>$brand['name'],'brand_version'=>(int)$brand['version'],'panel_reseller_id'=>(string)$link['panel_id'],'artifact_id'=>$artifact['id'],'grant_version'=>$p['grant']['version'],'default_revision'=>(int)$p['default']['revision'],'plan'=>$plan,'mode'=>'public_brand_signup','notice_version'=>1];
    }
    private function stamp(array $s,int $daily):string {return $this->secrets->mac("public-trial-offer-v1\n".json_encode([$s['brand_id'],$s['tenant_id'],$s['brand_version'],$s['panel_reseller_id'],$s['artifact_id'],$s['grant_version'],$s['default_revision'],$s['plan']['id'],$s['plan']['fingerprint'],$s['mode'],$s['notice_version'],$daily],JSON_UNESCAPED_SLASHES|JSON_THROW_ON_ERROR));}
    public function publish(array $actor,array $input):array {
        $this->ready();Input::keys($input,['tenant_id','expected_revision','enabled','daily_limit','confirmed_public_mode']);if(!is_bool($input['enabled']??null))throw new Problem(422,'Choose an offer publication state.');
        return $this->db->transaction(function()use($actor,$input):array {
            $t=$this->management->tenant($actor,Input::id($input['tenant_id']??$actor['tenant_id']),true);$old=$this->db->one('SELECT * FROM trial_offer_publications WHERE tenant_id=? FOR UPDATE',[$t['id']]);$revision=Input::integer($input['expected_revision']??0,0,100000);if($revision!==(int)($old['revision']??0))throw new Problem(409,'Offer publication changed; reload it.');
            if(!$input['enabled']&&$old){$this->db->query('UPDATE trial_offer_publications SET enabled=0,revision=revision+1,updated_by=? WHERE tenant_id=?',[$actor['id'],$t['id']]);$this->auth->audit($actor,'trial.offer_unpublished',null,$t['id']);return ['revision'=>$revision+1,'published'=>false,'existing_trials_changed'=>false];}
            if(!$input['enabled'])return ['revision'=>0,'published'=>false,'existing_trials_changed'=>false];
            if(!$this->enabled()||($input['confirmed_public_mode']??null)!==true)throw new Problem(409,'Public signup rollout is off or has not been explicitly confirmed.');$daily=Input::integer($input['daily_limit']??$this->dailyCap(),1,$this->dailyCap());$s=$this->snapshot($actor,$t['id']);
            $this->db->query('INSERT INTO trial_offer_publications (tenant_id,brand_id,revision,enabled,policy_stamp,daily_limit,updated_by) VALUES (?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE revision=VALUES(revision),enabled=VALUES(enabled),policy_stamp=VALUES(policy_stamp),daily_limit=VALUES(daily_limit),updated_by=VALUES(updated_by)',[$t['id'],$s['brand_id'],$revision+1,(int)$input['enabled'],$this->stamp($s,$daily),$daily,$actor['id']]);$this->auth->audit($actor,'trial.offer_published',null,$t['id'],['revision'=>$revision+1,'daily_limit'=>$daily]);return ['revision'=>$revision+1,'published'=>$input['enabled'],'existing_trials_changed'=>false];
        });
    }
    public function admitted(string $brandId):?array {
        if(!$this->enabled())return null;$this->ready();if(!preg_match('/^[a-f0-9]{32}$/D',$brandId))return null;
        $pub=$this->db->one('SELECT * FROM trial_offer_publications WHERE brand_id=? AND enabled=1',[$brandId]);if(!$pub)return null;
        try{$s=$this->snapshot(['role'=>'owner','tenant_id'=>null],$pub['tenant_id']);}catch(Problem $e){if($e->status<500)return null;throw $e;}
        if(!hash_equals($pub['policy_stamp'],$this->stamp($s,(int)$pub['daily_limit'])))return null;
        $s['policy_stamp']=$pub['policy_stamp'];$s['daily_limit']=min((int)$pub['daily_limit'],$this->dailyCap());return $s;
    }
    public function publicInfo(string $brandId):array {
        $s=$this->admitted($brandId);if(!$s)return ['available'=>false];return ['available'=>true,'offer_revision'=>hash('sha256',$s['policy_stamp']),'brand_name'=>$s['brand_name'],'duration'=>$s['plan']['duration'],'period'=>$s['plan']['period'],'max_connections'=>$s['plan']['max_connections'],'notice_version'=>1,'notice'=>'Your first name and phone number will be shared with this reseller for trial setup and follow-up. No phone verification or automated messages.'];
    }
    public function managerState(array $actor,array $input):array {
        $this->ready();Input::keys($input,['tenant_id']);$t=$this->management->tenant($actor,Input::id($input['tenant_id']??$actor['tenant_id']));$row=$this->db->one('SELECT revision,enabled,daily_limit FROM trial_offer_publications WHERE tenant_id=?',[$t['id']]);$reason='';$s=null;
        try{$s=$this->snapshot($actor,$t['id']);}catch(Problem $e){$reason=$e->status<500?$e->getMessage():'Offer prerequisites need owner review.';}
        $active=false;if($s&&$this->enabled())$active=$this->admitted($s['brand_id'])!==null;
        return ['publication'=>$row,'rollout_enabled'=>$this->enabled(),'can_publish'=>$s!==null&&$this->enabled(),'active'=>$active,'daily_cap'=>$this->dailyCap(),'access_mode'=>'public_brand_signup','reason'=>$reason];
    }
}
