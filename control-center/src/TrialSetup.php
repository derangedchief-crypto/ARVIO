<?php
declare(strict_types=1);
namespace ControlCenter;

/** Website-only orchestration. Choosing a plan and switching ON is one atomic
 * action; it never provisions a line or changes another workspace's preference. */
final class TrialSetup
{
    public function __construct(private readonly Database $db,private readonly Management $management,private readonly Auth $auth,private readonly TrialPolicies $policies,private readonly TrialOffers $offers) {}
    private function ready():void {if(!$this->offers->simpleReady())throw new Problem(503,'Apply the website trial-settings upgrade first.');}
    private function number(mixed $value,string $field,int $min=0,int $max=100000):int {if(!is_int($value)||$value<$min||$value>$max)throw new Problem(422,'Invalid '.$field.'. Reload trial settings and try again.');return $value;}
    private function refresh(array $actor,string $tenant):string {try{$this->policies->ensureCurrent($actor,$tenant);return '';}catch(\Throwable){return 'Could not check current packages. Try again; no new trial will be created.';}}
    public function status(array $actor,array $input):array {
        $this->ready();Input::keys($input,['tenant_id']);$t=$this->management->workspace($actor,$input['tenant_id']??null);$refresh=$this->refresh($actor,$t['id']);
        return $this->view($actor,$t,$refresh);
    }
    private function view(array $actor,array $t,string $refresh=''):array {
        $p=$this->policies->get($actor,['tenant_id'=>$t['id']]);$o=$this->offers->managerState($actor,['tenant_id'=>$t['id']]);$g=$this->db->one('SELECT * FROM trial_plan_grants WHERE tenant_id=?',[$t['id']]);$link=$this->db->one('SELECT panel_id FROM panel_resellers WHERE tenant_id=?',[$t['id']]);
        $allowed=$o['owner_unlimited']||($g&&(bool)$g['enabled']&&(bool)$g['live_enabled']);$blockers=[];
        if($t['status']!=='active')$blockers[]='This workspace is suspended. Ask the admin to reactivate it.';
        if(!$link)$blockers[]='Link this workspace to its panel reseller in Workspaces first.';
        if(!$p['worker_configured']||!$o['deployment_enabled'])$blockers[]='The server trial connection needs admin setup.';
        if($link&&!$p['catalog_fresh'])$blockers[]=$refresh?:'Current packages could not be checked. Reload this page to retry.';
        if(!$allowed)$blockers[]=$actor['role']==='owner'?'Turn on Allow trials, choose packages and save trial access.':'Your admin has not allowed trials for this app yet.';
        if($allowed&&!$p['plans'])$blockers[]='No approved packages are available. The admin must review the current packages.';
        if($allowed&&$p['plans']&&!$p['configuration_ready'])$blockers[]='Choose an approved trial package and save your trial settings.';
        $build=$this->offers->buildReadiness($t['id']);if(!$build['ready'])$blockers[]=$build['reason'];
        if($o['pilot'])$blockers[]='This app still has a restricted pilot policy; ask the admin to review it.';
        if(!$blockers&&$o['global_enabled']&&!$o['active']&&($o['publication']['enabled']??false))$blockers[]='Save trial settings once to confirm the current package.';
        $selected=$p['default']?(int)$p['default']['plan_id']:null;$term=null;foreach($p['plans'] as $plan)if($plan['id']===$selected)$term=$plan;
        return ['tenant_id'=>$t['id'],'workspace_kind'=>$t['kind'],'active'=>$o['active'],'status'=>$o['active']?'Trials are on':(!$o['global_enabled']?'Paused by admin':($blockers?'Not ready':'Trials are off')),'blockers'=>array_values(array_unique($blockers)),
            'global_enabled'=>$o['global_enabled'],'worker_configured'=>$p['worker_configured'],'admin_allowed'=>(bool)$allowed,'owner_unlimited'=>$o['owner_unlimited'],'daily_limit'=>$o['owner_unlimited']?null:($g&&$g['live_enabled']?(int)$g['daily_cap']:$o['daily_cap']),
            'plans'=>$p['plans'],'settings'=>['enabled'=>(bool)($o['publication']['enabled']??false),'plan_id'=>$selected,'plan_fingerprint'=>$term['fingerprint']??null,'default_revision'=>(int)($p['default']['revision']??0),'offer_revision'=>(int)($o['publication']['revision']??0)],
            'admin'=>$actor['role']==='owner'?['allowed'=>(bool)$allowed,'revision'=>(int)($g['setup_revision']??0),'allowed_plan_ids'=>$p['grant']['allowed_plan_ids']??[],'daily_limit'=>$o['owner_unlimited']?null:(int)($g['daily_cap']??50),'packages'=>$p['catalog']['plans']??[]]:null];
    }
    public function saveAdmin(array $actor,array $input):array {
        Auth::owner($actor);$this->ready();Input::keys($input,['tenant_id','expected_revision','allowed','allowed_plan_ids','plan_fingerprints','daily_limit']);if(!is_bool($input['allowed']??null))throw new Problem(422,'Choose whether this reseller may offer trials.');
        $t=$this->management->workspace($actor,$input['tenant_id']??null);if($input['allowed'])$this->refresh($actor,$t['id']);
        return $this->db->transaction(function()use($actor,$input,$t):array {
            $this->management->workspace($actor,$t['id'],true);$g=$this->db->one('SELECT * FROM trial_plan_grants WHERE tenant_id=? FOR UPDATE',[$t['id']]);$revision=$this->number($input['expected_revision']??null,'trial-access revision');if($revision!==(int)($g['setup_revision']??0))throw new Problem(409,'Trial access changed. Reload before saving.');
            // OFF is always available, even with a worker outage, stale catalogue
            // or an unfinished numeric/package edit. Preserve the saved terms.
            if(!$input['allowed']){
                if(!$g)$this->policies->grant($actor,['tenant_id'=>$t['id'],'expected_version'=>0,'allowed_plan_ids'=>[],'enabled'=>false]);
                elseif($g['enabled'])$this->db->query('UPDATE trial_plan_grants SET enabled=0,version=version+1,updated_by=? WHERE tenant_id=?',[$actor['id'],$t['id']]);
                $this->db->query('UPDATE trial_plan_grants SET live_enabled=0,setup_revision=setup_revision+1 WHERE tenant_id=?',[$t['id']]);$this->auth->audit($actor,'trial.access_saved',null,$t['id'],['allowed'=>false]);return $this->view($actor,$t);
            }
            $p=$this->policies->get($actor,['tenant_id'=>$t['id']]);$ids=$input['allowed_plan_ids']??[];$fingerprints=$input['plan_fingerprints']??[];
            if(!$p['catalog_fresh'])throw new Problem(409,'Current packages could not be checked. Use Reload status before enabling trials.');
            if(!is_array($ids)||!array_is_list($ids)||count($ids)>20||count(array_unique($ids,SORT_REGULAR))!==count($ids)||!is_array($fingerprints))throw new Problem(422,'Choose up to 20 different trial packages.');
            $terms=[];$byId=array_column($p['catalog']['plans']??[],null,'id');foreach($ids as $id){$this->number($id,'package',1,2147483647);if(!isset($byId[$id])||!is_string($fingerprints[$id]??null)||!hash_equals($byId[$id]['fingerprint'],$fingerprints[$id]))throw new Problem(409,'Package details changed. Reload and review them before saving.');$terms[(string)$id]=$byId[$id]['fingerprint'];}
            if($input['allowed']&&!$ids)throw new Problem(422,'Choose at least one package before allowing trials.');
            $limit=$this->number($input['daily_limit']??50,'daily trial limit',1,1000);$oldTerms=$g?json_decode($g['allowed_plans'],true,8,JSON_THROW_ON_ERROR):[];ksort($terms);ksort($oldTerms);
            $oldDefault=$this->db->one('SELECT * FROM trial_reseller_defaults WHERE tenant_id=? FOR UPDATE',[$t['id']]);$oldPub=$this->db->one('SELECT * FROM trial_offer_publications WHERE tenant_id=? FOR UPDATE',[$t['id']]);
            if(!$g||(bool)$g['enabled']!==$input['allowed']||$oldTerms!==$terms)$this->policies->grant($actor,['tenant_id'=>$t['id'],'expected_version'=>(int)($g['version']??0),'catalog_id'=>$p['catalog']['catalog_id']??null,'allowed_plan_ids'=>$ids,'enabled'=>$input['allowed']]);
            $current=$this->db->one('SELECT version FROM trial_plan_grants WHERE tenant_id=?',[$t['id']]);
            $this->db->query('UPDATE trial_plan_grants SET live_enabled=?,daily_cap=?,setup_revision=setup_revision+1 WHERE tenant_id=?',[(int)$input['allowed'],$limit,$t['id']]);
            // Adding/removing OTHER packages or toggling permission must not force
            // the reseller to reselect unchanged, already-reviewed default terms.
            if($oldDefault&&isset($terms[(string)$oldDefault['plan_id']])&&hash_equals($oldDefault['plan_fingerprint'],$terms[(string)$oldDefault['plan_id']])&&(int)$oldDefault['grant_version']!==(int)$current['version'])$this->db->query('UPDATE trial_reseller_defaults SET grant_version=?,revision=revision+1 WHERE tenant_id=?',[$current['version'],$t['id']]);
            $new=$this->view($actor,$t);
            if($input['allowed']&&$oldPub&&$oldPub['enabled']&&$new['settings']['plan_fingerprint']&&$this->offers->managerState($actor,['tenant_id'=>$t['id']])['can_publish'])$this->offers->publish($actor,['tenant_id'=>$t['id'],'expected_revision'=>(int)$oldPub['revision'],'enabled'=>true,'daily_limit'=>$new['owner_unlimited']?0:$limit,'confirmed_public_mode'=>true]);
            $this->auth->audit($actor,'trial.access_saved',null,$t['id'],['allowed'=>$input['allowed'],'daily_limit'=>$new['owner_unlimited']?0:$limit]);return $this->view($actor,$t);
        });
    }
    public function saveApp(array $actor,array $input):array {
        $this->ready();Input::keys($input,['tenant_id','expected_default_revision','expected_offer_revision','enabled','plan_id','plan_fingerprint','confirmed_public_mode']);if(!is_bool($input['enabled']??null))throw new Problem(422,'Choose whether trials are on or off.');
        $t=$this->management->workspace($actor,$input['tenant_id']??null);$previous=$this->db->one('SELECT enabled FROM trial_offer_publications WHERE tenant_id=?',[$t['id']]);if($input['enabled']||(isset($input['plan_id'])&&!($previous['enabled']??false)))$this->refresh($actor,$t['id']);
        return $this->db->transaction(function()use($actor,$input,$t):array {
            $this->management->workspace($actor,$t['id'],true);$pub=$this->db->one('SELECT revision,enabled FROM trial_offer_publications WHERE tenant_id=? FOR UPDATE',[$t['id']]);$expected=$this->number($input['expected_offer_revision']??null,'app trial revision');if($expected!==(int)($pub['revision']??0))throw new Problem(409,'App trial settings changed. Reload before saving.');
            if($input['enabled']&&($input['confirmed_public_mode']??null)!==true)throw new Problem(422,'Confirm sharing trial contact details with this reseller.');
            // Existing owner offers keep their legacy approval on upgrade. An
            // explicit save in this new flow adopts update-stable terms without
            // changing Unlimited or authorizing any reseller.
            if($input['enabled']&&$this->offers->managerState($actor,['tenant_id'=>$t['id']])['owner_unlimited'])$this->db->query('UPDATE trial_plan_grants SET live_enabled=1,setup_revision=setup_revision+1 WHERE tenant_id=? AND live_enabled=0',[$t['id']]);
            if($input['enabled']||(isset($input['plan_id'])&&!($pub['enabled']??false))){
                $view=$this->view($actor,$t);if(!$view['admin_allowed'])throw new Problem(409,'Your admin must allow trials for this app first.');$p=$this->policies->get($actor,['tenant_id'=>$t['id']]);$id=$this->number($input['plan_id']??null,'trial package',1,2147483647);$plan=null;foreach($p['plans'] as $candidate)if($candidate['id']===$id)$plan=$candidate;
                if(!$plan||!is_string($input['plan_fingerprint']??null)||!hash_equals($plan['fingerprint'],$input['plan_fingerprint']))throw new Problem(409,'This package changed or is no longer approved. Reload and choose an approved package.');
                $same=$p['default']&&$p['default']['enabled']&&(int)$p['default']['plan_id']===$id&&hash_equals($p['default']['plan_fingerprint'],$plan['fingerprint'])&&(int)$p['default']['grant_version']===$p['grant']['version'];
                $revision=$this->number($input['expected_default_revision']??null,'selected-package revision');if($revision!==(int)($p['default']['revision']??0)&&!$same)throw new Problem(409,'The selected package changed. Reload before saving.');
                if(!$same)$this->policies->setDefault($actor,['tenant_id'=>$t['id'],'expected_revision'=>$revision,'grant_version'=>$p['grant']['version'],'plan_id'=>$id,'enabled'=>true]);
            }
            $view=$this->view($actor,$t);if($input['enabled']&&!$this->offers->managerState($actor,['tenant_id'=>$t['id']])['can_publish'])throw new Problem(409,$view['blockers'][0]??'Build and publish this workspace’s branded APK first.');
            $this->offers->publish($actor,['tenant_id'=>$t['id'],'expected_revision'=>$expected,'enabled'=>$input['enabled'],'daily_limit'=>$view['owner_unlimited']?0:$view['daily_limit'],'confirmed_public_mode'=>$input['enabled']]);return $this->view($actor,$t);
        });
    }
    public function maintain():void {
        if(!$this->offers->simpleReady())return;$owner=$this->db->one("SELECT id,role,tenant_id FROM users WHERE role='owner' AND active=1 ORDER BY id LIMIT 1");if(!$owner)return;
        $rows=$this->offers->globalReady()?$this->db->all("SELECT p.tenant_id FROM trial_offer_publications p JOIN tenants t ON t.id=p.tenant_id JOIN panel_resellers r ON r.tenant_id=t.id LEFT JOIN trial_plan_catalogs c ON c.tenant_id=t.id WHERE p.enabled=1 AND t.status='active' AND (c.expires_at IS NULL OR c.expires_at<DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR)) ORDER BY c.expires_at LIMIT 5"):$this->db->all("SELECT g.tenant_id FROM trial_plan_grants g JOIN tenants t ON t.id=g.tenant_id JOIN panel_resellers r ON r.tenant_id=t.id LEFT JOIN trial_plan_catalogs c ON c.tenant_id=t.id WHERE g.enabled=1 AND t.status='active' AND (g.live_enabled=1 OR t.kind='owner') AND (c.expires_at IS NULL OR c.expires_at<DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR)) ORDER BY c.expires_at LIMIT 5");
        foreach($rows as $row)try{$this->policies->ensureCurrent($owner,$row['tenant_id'],true);}catch(\Throwable){/* Fail closed and surface the exact readiness state in the UI. */}
    }
}
