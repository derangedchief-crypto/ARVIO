<?php
declare(strict_types=1);
namespace ControlCenter;

/** Global package approval; verified resellers own their app switch and quota. */
final class TrialSelfService
{
    public function __construct(private readonly Database $db,private readonly Management $management,private readonly Auth $auth,private readonly TrialPolicies $policies,private readonly TrialOffers $offers) {}
    private function number(mixed $n,string $name,int $min=0,int $max=100000):int {if(!is_int($n)||$n<$min||$n>$max)throw new Problem(422,'Invalid '.$name.'. Reload trial settings and try again.');return $n;}
    private function refresh(array $actor,string $tenant):void {try{$this->policies->ensureCurrent($actor,$tenant);}catch(\Throwable){/* Cached readiness fails closed and explains the missing package check. */}}
    private function ownerSpace():array {return $this->db->one("SELECT id FROM tenants WHERE kind='owner' ORDER BY id LIMIT 1")??throw new Problem(409,'The owner workspace is missing.');}
    public function packages(array $actor):array {
        Auth::owner($actor);$space=$this->ownerSpace();$this->refresh($actor,$space['id']);return $this->packageView($actor,$space['id']);
    }
    private function packageView(array $actor,string $tenant):array {
        $p=$this->policies->get($actor,['tenant_id'=>$tenant]);$g=$this->policies->globalApproval();
        return ['revision'=>(int)($g['revision']??0),'allowed_plan_ids'=>$g?array_map('intval',array_keys($g['allowed_plans'])):[],'packages'=>$p['catalog']['plans']??[],'catalog_fresh'=>$p['catalog_fresh'],'worker_configured'=>$p['worker_configured']];
    }
    public function savePackages(array $actor,array $input):array {
        Auth::owner($actor);Input::keys($input,['expected_revision','allowed_plan_ids','plan_fingerprints']);$space=$this->ownerSpace();$this->refresh($actor,$space['id']);
        return $this->db->transaction(function()use($actor,$input,$space):array {
            $g=$this->policies->globalApproval();$revision=$this->number($input['expected_revision']??null,'global package revision');if(!$g||$revision!==(int)$g['revision'])throw new Problem(409,'Global package approvals changed. Reload before saving.');
            $p=$this->policies->get($actor,['tenant_id'=>$space['id']]);if(!$p['catalog_fresh'])throw new Problem(409,'Current packages could not be checked. Reload the page to retry.');
            $ids=$input['allowed_plan_ids']??[];$fingerprints=$input['plan_fingerprints']??[];if(!is_array($ids)||!array_is_list($ids)||count($ids)>20||count(array_unique($ids,SORT_REGULAR))!==count($ids)||!is_array($fingerprints))throw new Problem(422,'Choose up to 20 different trial packages.');
            $catalog=array_column($p['catalog']['plans']??[],null,'id');$terms=[];foreach($ids as $id){$this->number($id,'package',1,2147483647);if(!isset($catalog[$id])||!is_string($fingerprints[$id]??null)||!hash_equals($catalog[$id]['fingerprint'],$fingerprints[$id]))throw new Problem(409,'Package terms changed. Reload and review the current details.');$terms[(string)$id]=$catalog[$id]['fingerprint'];}
            $old=$g['allowed_plans'];ksort($old);ksort($terms);if($old!==$terms){$this->db->query('UPDATE trial_global_packages SET allowed_plans=?,revision=revision+1,updated_by=? WHERE id=1',[json_encode((object)$terms,JSON_THROW_ON_ERROR),$actor['id']]);$this->auth->audit($actor,'trial.global_packages_saved',null,null,['packages'=>count($terms)]);}
            return $this->packageView($actor,$space['id']);
        });
    }
    public function status(array $actor,array $input):array {
        Input::keys($input,['tenant_id']);$t=$this->management->workspace($actor,$input['tenant_id']??null);$this->refresh($actor,$t['id']);return $this->view($actor,$t);
    }
    private function view(array $actor,array $t):array {
        $p=$this->policies->get($actor,['tenant_id'=>$t['id']]);$o=$this->offers->managerState($actor,['tenant_id'=>$t['id']]);$link=$this->db->one('SELECT panel_id FROM panel_resellers WHERE tenant_id=?',[$t['id']]);$build=$this->offers->buildReadiness($t['id']);$blockers=[];
        if($t['status']!=='active')$blockers[]='This workspace is suspended.';
        if(!$link)$blockers[]='Link this workspace to its panel reseller in Workspaces first.';
        if(!$p['worker_configured']||!$o['deployment_enabled'])$blockers[]='The server trial connection needs admin setup.';
        if($link&&!$p['catalog_fresh'])$blockers[]='Current packages could not be checked. Reload shortly to retry.';
        if(!$p['plans'])$blockers[]='No globally approved trial packages are available for this panel account.';
        if(!$build['ready'])$blockers[]=$build['reason'];
        if($p['plans']&&(!$p['default']||!$p['default']['enabled']||($t['kind']==='reseller'&&!($p['default']['global_opt_in']??false))))$blockers[]='Choose your trial package and daily limit, then save your app settings.';
        $daily=(int)(($p['default']['global_opt_in']??false)?$p['default']['daily_limit']:($o['publication']['daily_limit']??50));if($daily<1||$daily>1000)$daily=50;
        $selected=$p['default']?(int)$p['default']['plan_id']:null;$term=null;foreach($p['plans'] as $plan)if($plan['id']===$selected)$term=$plan;
        if($selected!==null&&!$term)$blockers[]='Your selected package changed or is no longer globally approved. Choose an approved package.';
        return ['tenant_id'=>$t['id'],'workspace_kind'=>$t['kind'],'global_model'=>true,'active'=>$o['active'],'status'=>$o['active']?'Trials are on':(!$o['global_enabled']?'Paused globally':($blockers?'Not ready':'Trials are off')),'blockers'=>array_values(array_unique($blockers)),
            'global_enabled'=>$o['global_enabled'],'worker_configured'=>$p['worker_configured'],'owner_unlimited'=>$o['owner_unlimited'],'plans'=>$p['plans'],'daily_max'=>1000,
            'settings'=>['enabled'=>(bool)($o['publication']['enabled']??false),'plan_id'=>$selected,'plan_fingerprint'=>$term['fingerprint']??null,'daily_limit'=>$o['owner_unlimited']?null:$daily,'default_revision'=>(int)($p['default']['revision']??0),'offer_revision'=>(int)($o['publication']['revision']??0)]];
    }
    public function saveApp(array $actor,array $input):array {
        Input::keys($input,['tenant_id','expected_default_revision','expected_offer_revision','enabled','plan_id','plan_fingerprint','daily_limit','confirmed_public_mode']);if(!is_bool($input['enabled']??null))throw new Problem(422,'Choose whether app trials are on or off.');
        $t=$this->management->workspace($actor,$input['tenant_id']??null);if($actor['role']==='owner'&&$t['kind']==='reseller')throw new Problem(403,'Resellers manage their own app switch and daily limit.');$pub=$this->db->one('SELECT enabled FROM trial_offer_publications WHERE tenant_id=?',[$t['id']]);$pause=!$input['enabled']&&($pub['enabled']??false);
        if(!$pause&&($input['enabled']||isset($input['plan_id'])))$this->refresh($actor,$t['id']);
        return $this->db->transaction(function()use($actor,$input,$t):array {
            $this->management->workspace($actor,$t['id'],true);$pub=$this->db->one('SELECT revision,enabled FROM trial_offer_publications WHERE tenant_id=? FOR UPDATE',[$t['id']]);$expected=$this->number($input['expected_offer_revision']??null,'app trial revision');if($expected!==(int)($pub['revision']??0))throw new Problem(409,'App trial settings changed. Reload before saving.');
            $unlimited=$this->offers->managerState($actor,['tenant_id'=>$t['id']])['owner_unlimited'];$pause=!$input['enabled']&&($pub['enabled']??false);$daily=null;
            if(!$pause&&($input['enabled']||isset($input['plan_id']))){
                if($input['enabled']&&($input['confirmed_public_mode']??null)!==true)throw new Problem(422,'Confirm sharing trial contact details with this reseller.');
                $p=$this->policies->get($actor,['tenant_id'=>$t['id']]);$id=$this->number($input['plan_id']??null,'trial package',1,2147483647);$plan=null;foreach($p['plans'] as $candidate)if($candidate['id']===$id)$plan=$candidate;
                if(!$plan||!is_string($input['plan_fingerprint']??null)||!hash_equals($plan['fingerprint'],$input['plan_fingerprint']))throw new Problem(409,'This package changed or is not globally approved for your panel account. Reload and choose an approved package.');
                $daily=$unlimited?0:$this->number($input['daily_limit']??null,'daily limit (choose 1–1000)',1,1000);$revision=$this->number($input['expected_default_revision']??null,'package-settings revision');if($revision!==(int)($p['default']['revision']??0))throw new Problem(409,'Your trial package or daily limit changed. Reload before saving.');
                $same=$p['default']&&$p['default']['enabled']&&(int)$p['default']['plan_id']===$id&&hash_equals($p['default']['plan_fingerprint'],$plan['fingerprint']);
                if(!$same){$this->policies->setDefault($actor,['tenant_id'=>$t['id'],'expected_revision'=>$revision,'grant_version'=>$p['grant']['version'],'plan_id'=>$id,'enabled'=>true]);$revision++;}
                if(!$p['default']||!($p['default']['global_opt_in']??false)||(int)($p['default']['daily_limit']??50)!==($unlimited?50:$daily))$this->db->query('UPDATE trial_reseller_defaults SET global_opt_in=1,daily_limit=?,revision=revision+1 WHERE tenant_id=?',[$unlimited?50:$daily,$t['id']]);
            }
            $view=$this->view($actor,$t);if($input['enabled']&&!$this->offers->managerState($actor,['tenant_id'=>$t['id']])['can_publish'])throw new Problem(409,$view['blockers'][0]??'Check the app trial prerequisites.');
            $limit=$unlimited?0:($daily??$view['settings']['daily_limit']);$this->offers->publish($actor,['tenant_id'=>$t['id'],'expected_revision'=>$expected,'enabled'=>$input['enabled'],'daily_limit'=>$limit,'confirmed_public_mode'=>$input['enabled']]);return $this->view($actor,$t);
        });
    }
}
