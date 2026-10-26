<?php
declare(strict_types=1);
namespace ControlCenter;

/** Prepared trial policy only: never enables a public provisioning route by itself. */
final class TrialPolicies
{
    public function __construct(private readonly Database $db,private readonly Management $management,private readonly Auth $auth,private readonly TrialWorker $worker) {}
    private function ready():void {if(!$this->db->one('SELECT name FROM schema_migrations WHERE name=?',['015_trial_policies_contacts.sql']))throw new Problem(503,'Apply the trial-policy database upgrade first.');}
    private function tenant(array $actor,?string $id,bool $lock=false):array {$this->ready();$t=$this->management->workspace($actor,$id,$lock);if($t['kind']==='owner')Auth::owner($actor);elseif($t['kind']!=='reseller')throw new Problem(409,'Choose an owner or reseller workspace.');return $t;}
    private function link(string $tenant):?array {return $this->db->one('SELECT panel_id FROM panel_resellers WHERE tenant_id=?',[$tenant]);}
    private function catalog(string $tenant):?array {
        $c=$this->db->one('SELECT * FROM trial_plan_catalogs WHERE tenant_id=?',[$tenant]);if($c)$c['plans']=json_decode($c['plans'],true,12,JSON_THROW_ON_ERROR);return $c;
    }
    private function fresh(?array $catalog,?array $link):bool {return $catalog&&$link&&(string)$catalog['panel_reseller_id']===(string)$link['panel_id']&&strtotime($catalog['expires_at'].' UTC')>time();}
    public function get(array $actor,array $input):array {
        Input::keys($input,['tenant_id']);$t=$this->tenant($actor,$input['tenant_id']??null);$catalog=$this->catalog($t['id']);$link=$this->link($t['id']);$fresh=$this->fresh($catalog,$link);
        $grant=$this->db->one('SELECT * FROM trial_plan_grants WHERE tenant_id=?',[$t['id']]);$allowed=$grant?json_decode($grant['allowed_plans'],true,8,JSON_THROW_ON_ERROR):[];
        $default=$this->db->one('SELECT * FROM trial_reseller_defaults WHERE tenant_id=?',[$t['id']]);$plans=array_values(array_filter($catalog['plans']??[],static fn($p)=>isset($allowed[(string)$p['id']])&&hash_equals($allowed[(string)$p['id']],$p['fingerprint'])));
        $selected=null;foreach($plans as $plan)if($default&&(int)$default['plan_id']===$plan['id']&&hash_equals($default['plan_fingerprint'],$plan['fingerprint']))$selected=$plan;
        $brand=$this->db->one('SELECT enabled FROM app_brands WHERE tenant_id=?',[$t['id']]);
        $ready=$this->worker->configured()&&$t['status']==='active'&&$fresh&&$grant&&$grant['enabled']&&$default&&$default['enabled']&&(int)$grant['version']===(int)$default['grant_version']&&$selected&&$brand&&$brand['enabled'];
        return ['tenant_id'=>$t['id'],'grant'=>$grant?['version'=>(int)$grant['version'],'enabled'=>(bool)$grant['enabled'],'allowed_plan_ids'=>array_map('intval',array_keys($allowed))]:null,
            'default'=>$default,'plans'=>$plans,'catalog'=>$actor['role']==='owner'?$catalog:null,'catalog_fresh'=>$fresh,'worker_configured'=>$this->worker->configured(),
            'configuration_ready'=>(bool)$ready,'live_offer_enabled'=>false,'offer_status'=>'Plan/default settings do not publish a trial offer. Public signup needs separate owner rollout approval and explicit offer publication.'];
    }
    public function refresh(array $actor,array $input):array {
        Auth::owner($actor);return $this->refreshWorkspace($actor,$input);
    }
    /** Internal simple-setup reader: only the authenticated workspace, no grants,
     * provisioning or publication. Never authorizes changed plan terms. */
    public function ensureCurrent(array $actor,string $tenantId,bool $ahead=false):void {
        $t=$this->tenant($actor,$tenantId);if(!$this->worker->configured()||!$this->link($t['id'])||$t['status']!=='active')return;
        $catalog=$this->catalog($t['id']);if(!$this->fresh($catalog,$this->link($t['id']))||($ahead&&strtotime($catalog['expires_at'].' UTC')<time()+3600))$this->refreshWorkspace($actor,['tenant_id'=>$t['id']],true);
    }
    private function refreshWorkspace(array $actor,array $input,bool $automatic=false):array {
        Input::keys($input,['tenant_id']);$t=$this->tenant($actor,$input['tenant_id']??null);$link=$this->link($t['id']);if(!$link||$t['status']!=='active')throw new Problem(409,'Link this workspace to an active panel reseller first.');
        if($automatic&&$this->db->one('SELECT name FROM schema_migrations WHERE name=?',['022_simple_reseller_trials.sql'])){
            if($this->db->pdo->inTransaction())throw new \LogicException('Metadata refresh must not hold a trial transaction.');
            $this->db->transaction(function()use($t):void {
                $this->db->one('SELECT id FROM tenants WHERE id=? FOR UPDATE',[$t['id']]);$row=$this->db->one('SELECT retry_after FROM trial_catalog_refreshes WHERE tenant_id=? FOR UPDATE',[$t['id']]);
                if($row&&strtotime($row['retry_after'].' UTC')>time())throw new Problem(503,'Package check is retrying shortly.');
                $this->db->query('INSERT INTO trial_catalog_refreshes (tenant_id,retry_after) VALUES (?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 60 SECOND)) ON DUPLICATE KEY UPDATE retry_after=VALUES(retry_after)',[$t['id']]);
            });
        }else $this->auth->limit($automatic?'trial-catalog-auto':'trial-catalog-refresh',$automatic?$t['id']:$actor['id'],$automatic?3:20,3600);
        $plans=$this->worker->catalog((string)$link['panel_id']);
        return $this->db->transaction(function()use($actor,$t,$link,$plans,$automatic):array {
            $current=$this->tenant($actor,$t['id'],true);$nowLink=$this->link($t['id']);if($current['status']!=='active'||!$nowLink||(string)$nowLink['panel_id']!==(string)$link['panel_id'])throw new Problem(409,'Reseller ownership changed; refresh again.');
            $id=Database::id();$this->db->query('INSERT INTO trial_plan_catalogs (tenant_id,catalog_id,panel_reseller_id,plans,expires_at) VALUES (?,?,?,?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 DAY)) ON DUPLICATE KEY UPDATE catalog_id=VALUES(catalog_id),panel_reseller_id=VALUES(panel_reseller_id),plans=VALUES(plans),fetched_at=UTC_TIMESTAMP(),expires_at=VALUES(expires_at)',[$t['id'],$id,$link['panel_id'],json_encode($plans,JSON_UNESCAPED_UNICODE|JSON_THROW_ON_ERROR)]);
            if($automatic&&$this->db->one('SELECT name FROM schema_migrations WHERE name=?',['022_simple_reseller_trials.sql']))$this->db->query('UPDATE trial_catalog_refreshes SET retry_after=UTC_TIMESTAMP() WHERE tenant_id=?',[$t['id']]);
            $this->auth->audit($automatic?null:$actor,$automatic?'trial.catalog_refreshed_auto':'trial.catalog_refreshed',$id,$t['id'],['plans'=>count($plans)]);return ['catalog_id'=>$id,'plans'=>count($plans),'accounts_created'=>false];
        });
    }
    public function grant(array $actor,array $input):array {
        Auth::owner($actor);Input::keys($input,['tenant_id','expected_version','catalog_id','allowed_plan_ids','enabled']);if(!is_bool($input['enabled']??null))throw new Problem(422,'Choose an admin trial-policy state.');
        $ids=$input['allowed_plan_ids']??null;if(!is_array($ids)||!array_is_list($ids)||count($ids)>20||count(array_unique($ids,SORT_REGULAR))!==count($ids))throw new Problem(422,'Choose up to 20 unique trial plans.');foreach($ids as $id)Input::integer($id,1,2147483647);
        if($input['enabled']&&!$ids)throw new Problem(422,'Allow at least one trial plan first.');
        return $this->db->transaction(function()use($actor,$input,$ids):array {
            $t=$this->tenant($actor,$input['tenant_id']??null,true);$old=$this->db->one('SELECT * FROM trial_plan_grants WHERE tenant_id=? FOR UPDATE',[$t['id']]);$version=Input::integer($input['expected_version']??0,0,100000);if($version!==(int)($old['version']??0))throw new Problem(409,'Admin trial policy changed; reload it.');
            $catalog=$this->catalog($t['id']);$allowed=[];
            if($ids){
                if($t['status']==='active'&&$this->fresh($catalog,$this->link($t['id']))&&($input['catalog_id']??null)===$catalog['catalog_id']){$byId=array_column($catalog['plans'],null,'id');foreach($ids as $id){if(!isset($byId[$id]))throw new Problem(422,'Only current worker trial plans may be allowed.');$allowed[(string)$id]=$byId[$id]['fingerprint'];}}
                elseif(!$input['enabled']&&$old){$previous=json_decode($old['allowed_plans'],true,8,JSON_THROW_ON_ERROR);foreach($ids as $id){if(!isset($previous[(string)$id]))throw new Problem(409,'Refresh the worker catalog before adding plans.');$allowed[(string)$id]=$previous[(string)$id];}}
                else throw new Problem(409,'Refresh and review the current worker trial catalog first.');
            }
            $version++;$this->db->query('INSERT INTO trial_plan_grants (tenant_id,version,enabled,allowed_plans,updated_by) VALUES (?,?,?,?,?) ON DUPLICATE KEY UPDATE version=VALUES(version),enabled=VALUES(enabled),allowed_plans=VALUES(allowed_plans),updated_by=VALUES(updated_by)',[$t['id'],$version,(int)$input['enabled'],json_encode((object)$allowed,JSON_THROW_ON_ERROR),$actor['id']]);
            $this->auth->audit($actor,'trial.admin_policy_saved',null,$t['id'],['version'=>$version,'enabled'=>$input['enabled']]);return ['version'=>$version,'live_offer_enabled'=>false,'existing_trials_changed'=>false];
        });
    }
    public function setDefault(array $actor,array $input):array {
        Input::keys($input,['tenant_id','expected_revision','grant_version','plan_id','enabled']);if(!is_bool($input['enabled']??null))throw new Problem(422,'Choose a default-policy state.');
        return $this->db->transaction(function()use($actor,$input):array {
            $t=$this->tenant($actor,$input['tenant_id']??null,true);$old=$this->db->one('SELECT * FROM trial_reseller_defaults WHERE tenant_id=? FOR UPDATE',[$t['id']]);$revision=Input::integer($input['expected_revision']??0,0,100000);if($revision!==(int)($old['revision']??0))throw new Problem(409,'Reseller default changed; reload it.');
            if(!$input['enabled']&&$old){$this->db->query('UPDATE trial_reseller_defaults SET enabled=0,revision=revision+1,updated_by=? WHERE tenant_id=?',[$actor['id'],$t['id']]);$this->auth->audit($actor,'trial.default_disabled',null,$t['id']);return ['revision'=>$revision+1,'live_offer_enabled'=>false];}
            $policy=$this->get($actor,['tenant_id'=>$t['id']]);$grant=$policy['grant'];$planId=Input::integer($input['plan_id']??null,1,2147483647);$selected=null;foreach($policy['plans'] as $plan)if($plan['id']===$planId)$selected=$plan;
            if($t['status']!=='active'||!$policy['catalog_fresh']||!$grant||!$grant['enabled']||$grant['version']!==Input::integer($input['grant_version']??null,1,100000)||!$selected)throw new Problem(409,'Choose a currently approved trial plan; admin changes require review.');
            $revision++;$this->db->query('INSERT INTO trial_reseller_defaults (tenant_id,revision,grant_version,plan_id,plan_fingerprint,enabled,updated_by) VALUES (?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE revision=VALUES(revision),grant_version=VALUES(grant_version),plan_id=VALUES(plan_id),plan_fingerprint=VALUES(plan_fingerprint),enabled=VALUES(enabled),updated_by=VALUES(updated_by)',[$t['id'],$revision,$grant['version'],$planId,$selected['fingerprint'],(int)$input['enabled'],$actor['id']]);
            $this->auth->audit($actor,'trial.default_saved',null,$t['id'],['revision'=>$revision,'plan_id'=>$planId]);return ['revision'=>$revision,'live_offer_enabled'=>false,'existing_trials_changed'=>false];
        });
    }
}
