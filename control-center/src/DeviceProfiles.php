<?php
declare(strict_types=1);
namespace ControlCenter;

/** Explicit workspace policies orchestrate existing leased operations. No new app privilege. */
final class DeviceProfiles
{
    private const ACTIVE=['waiting','running'];
    public function __construct(private readonly Database $db,private readonly Management $management,private readonly Commands $commands,private readonly Auth $auth) {}
    public static function installed(Database $db):bool {return (bool)$db->one('SELECT name FROM schema_migrations WHERE name=?',['013_device_setup_profiles.sql']);}
    private function ready():void {if(!self::installed($this->db))throw new Problem(503,'Apply the device-profile database upgrade first.');}
    private function profile(array $actor,string $id):array {
        $this->ready();$row=$this->db->one('SELECT * FROM device_setup_profiles WHERE id=?',[Input::id($id)]);if(!$row)throw new Problem(404,'Device profile not found.');$this->management->tenant($actor,$row['tenant_id']);$row['definition']=json_decode($row['definition'],true,20,JSON_THROW_ON_ERROR);return $row;
    }
    public function listing(array $actor,array $input):array {
        $this->ready();Input::keys($input,['tenant_id','page','search']);$args=[];$where='p.archived=0';
        if($actor['role']!=='owner'||!empty($input['tenant_id'])){$tenant=$this->management->tenant($actor,Input::id($input['tenant_id']??$actor['tenant_id']));$where.=' AND p.tenant_id=?';$args[]=$tenant['id'];}
        $search=Input::text($input['search']??'','search',80,false);if($search!==''){$where.=' AND LOCATE(?,p.name)>0';$args[]=$search;}$page=Input::page($input['page']??1);$offset=($page-1)*25;
        $total=(int)$this->db->query("SELECT COUNT(*) FROM device_setup_profiles p WHERE $where",$args)->fetchColumn();$rows=$this->db->all("SELECT p.*,t.name AS tenant_name FROM device_setup_profiles p JOIN tenants t ON t.id=p.tenant_id WHERE $where ORDER BY p.updated_at DESC,p.id LIMIT 25 OFFSET $offset",$args);foreach($rows as &$row)$row['definition']=json_decode($row['definition'],true,20,JSON_THROW_ON_ERROR);unset($row);return ['items'=>$rows,'page'=>$page,'pages'=>max(1,(int)ceil($total/25)),'total'=>$total];
    }
    public function get(array $actor,string $id):array {return $this->profile($actor,$id);}
    private function definition(string $tenant,array $input):array {
        $layout=null;$settings=null;
        if(!empty($input['layout_template_id'])){$row=$this->db->one('SELECT * FROM category_layout_templates WHERE id=? AND tenant_id=? AND archived=0',[Input::id($input['layout_template_id']),$tenant]);if(!$row)throw new Problem(404,'Choose a layout in this workspace.');if((int)$row['version']!==Input::integer($input['layout_version']??null,1,100000))throw new Problem(409,'Layout changed; reload before saving the recipe.');$layout=['id'=>$row['id'],'version'=>(int)$row['version'],'name'=>$row['name'],'entries'=>json_decode($row['entries'],true,16,JSON_THROW_ON_ERROR)];}
        if(!empty($input['settings_profile_id'])){$row=$this->db->one('SELECT * FROM preference_profiles WHERE id=? AND tenant_id=? AND archived=0',[Input::id($input['settings_profile_id']),$tenant]);if(!$row)throw new Problem(404,'Choose a settings profile in this workspace.');if((int)$row['version']!==Input::integer($input['settings_version']??null,1,100000))throw new Problem(409,'Settings profile changed; reload before saving the recipe.');$settings=['id'=>$row['id'],'version'=>(int)$row['version'],'name'=>$row['name'],'values'=>Preferences::values(json_decode($row['values_json'],true,8,JSON_THROW_ON_ERROR))];}
        $sort=$input['sort_order']??null;$buffer=$input['prebuffer_seconds']??null;
        if($sort!==null&&!in_array($sort,['provider','name','number'],true))throw new Problem(422,'Invalid sorting choice.');if($buffer!==null&&!in_array($buffer,['0','2','5','10'],true))throw new Problem(422,'Invalid Live TV buffer.');
        if(!$layout&&!$settings&&$sort===null&&$buffer===null)throw new Problem(422,'Choose at least one supported setup item.');return ['layout'=>$layout,'settings'=>$settings,'sort_order'=>$sort,'prebuffer_seconds'=>$buffer];
    }
    public function save(array $actor,?string $id,array $input):array {
        $this->ready();Input::keys($input,['tenant_id','name','layout_template_id','layout_version','settings_profile_id','settings_version','sort_order','prebuffer_seconds','expected_version','archived']);$name=Input::text($input['name']??null,'device profile name',80);$archive=$input['archived']??false;if(!is_bool($archive))throw new Problem(422,'Invalid archive flag.');
        return $this->db->transaction(function()use($actor,$id,$input,$name,$archive):array {
            $old=$id?$this->profile($actor,$id):null;$tenant=$this->management->tenant($actor,$old['tenant_id']??Input::id($input['tenant_id']??$actor['tenant_id']),true);if($tenant['status']!=='active')throw new Problem(409,'Workspace is suspended.');
            if($old){$current=$this->db->one('SELECT * FROM device_setup_profiles WHERE id=? FOR UPDATE',[$id]);if((int)$current['version']!==Input::integer($input['expected_version']??null,1,99999))throw new Problem(409,'Device profile changed; reload it first.');$version=(int)$current['version']+1;}
            else {if((int)$this->db->query('SELECT COUNT(*) FROM device_setup_profiles WHERE tenant_id=? AND archived=0',[$tenant['id']])->fetchColumn()>=100)throw new Problem(409,'Archive an unused device profile first (100/workspace).');$id=Database::id();$version=1;}
            $definition=$archive&&$old?$old['definition']:$this->definition($tenant['id'],$input);$json=json_encode($definition,JSON_UNESCAPED_UNICODE|JSON_THROW_ON_ERROR);
            if($old)$this->db->query('UPDATE device_setup_profiles SET name=?,definition=?,version=?,archived=? WHERE id=?',[$name,$json,$version,(int)$archive,$id]);else $this->db->query('INSERT INTO device_setup_profiles (id,tenant_id,name,version,definition,created_by) VALUES (?,?,?,?,?,?)',[$id,$tenant['id'],$name,$version,$json,$actor['id']]);
            $this->db->query('INSERT INTO device_setup_versions (profile_id,version,name,definition) VALUES (?,?,?,?)',[$id,$version,$name,$json]);
            if($archive)$this->db->query('UPDATE device_setup_defaults SET enabled=0,revision=revision+1 WHERE profile_id=? AND tenant_id=?',[$id,$tenant['id']]);
            $this->auth->audit($actor,'setup.profile_saved',$id,$tenant['id'],['version'=>$version,'archived'=>$archive]);return ['id'=>$id,'version'=>$version];
        });
    }
    public function policy(array $actor,string $tenantId):array {$this->ready();$tenant=$this->management->tenant($actor,Input::id($tenantId));$p=$this->db->one('SELECT d.*,v.name FROM device_setup_defaults d JOIN device_setup_versions v ON v.profile_id=d.profile_id AND v.version=d.profile_version WHERE d.tenant_id=?',[$tenant['id']]);return ['policy'=>$p,'tenant'=>$tenant];}
    public function setPolicy(array $actor,array $input):array {
        $this->ready();Input::keys($input,['tenant_id','profile_id','profile_version','enabled','expected_revision']);if(!is_bool($input['enabled']??null))throw new Problem(422,'Choose whether automatic setup is enabled.');
        return $this->db->transaction(function()use($actor,$input):array {
            $tenant=$this->management->tenant($actor,Input::id($input['tenant_id']??$actor['tenant_id']),true);$profile=$this->profile($actor,Input::id($input['profile_id']??null));$version=Input::integer($input['profile_version']??null,1,100000);if($tenant['status']!=='active')throw new Problem(409,'Workspace suspended.');
            $prior=$this->db->one('SELECT * FROM device_setup_defaults WHERE tenant_id=? FOR UPDATE',[$tenant['id']]);$revision=Input::integer($input['expected_revision']??0,0,PHP_INT_MAX);if($revision!==(int)($prior['revision']??0))throw new Problem(409,'Default policy changed; reload it first.');
            $retainPinned=$prior&&$prior['profile_id']===$profile['id']&&(int)$prior['profile_version']===$version;
            if($profile['tenant_id']!==$tenant['id']||($input['enabled']&&$profile['archived'])||(!$retainPinned&&($profile['archived']||$version!==(int)$profile['version'])))throw new Problem(409,'Choose an active device profile in this workspace; existing pins can be retained.');
            if($input['enabled']&&!$this->db->one('SELECT panel_id FROM panel_resellers WHERE tenant_id=?',[$tenant['id']]))throw new Problem(409,'Link this workspace to a verified panel reseller before enabling defaults.');
            $this->db->query('INSERT INTO device_setup_defaults (tenant_id,profile_id,profile_version,enabled,revision,set_by) VALUES (?,?,?,?,?,?) ON DUPLICATE KEY UPDATE profile_id=VALUES(profile_id),profile_version=VALUES(profile_version),enabled=VALUES(enabled),revision=VALUES(revision),set_by=VALUES(set_by),updated_at=CURRENT_TIMESTAMP(6)',[$tenant['id'],$profile['id'],$version,(int)$input['enabled'],$revision+1,$actor['id']]);
            if(!$input['enabled'])foreach($this->db->all("SELECT * FROM device_setup_jobs WHERE tenant_id=? AND automatic=1 AND status IN ('waiting','running')",[$tenant['id']]) as $job)$this->stopJob($job,'paused','Automatic setup disabled; applied stages are not undone');
            $this->auth->audit($actor,'setup.default_changed',$profile['id'],$tenant['id'],['version'=>$version,'enabled'=>$input['enabled']]);return ['revision'=>$revision+1,'enabled'=>$input['enabled'],'existing_devices_changed'=>false];
        });
    }
    /** Invoked ONLY from a successful first verified admission, never renewals. */
    public static function admit(Database $db,string $device):?string {
        if(!self::installed($db))return null;
        $row=$db->one("SELECT d.id,d.tenant_id,p.profile_id,p.profile_version,p.set_by FROM devices d JOIN device_setup_defaults p ON p.tenant_id=d.tenant_id JOIN tenants t ON t.id=d.tenant_id JOIN panel_resellers r ON r.tenant_id=d.tenant_id WHERE d.id=? AND p.enabled=1 AND t.status='active' AND d.reseller_detached=0 AND d.policy='active' FOR UPDATE",[$device]);if(!$row)return null;
        $id=Database::id();$added=$db->query("INSERT IGNORE INTO device_setup_jobs (id,device_id,admission_device_id,tenant_id,profile_id,profile_version,actor_id,automatic,state,expires_at) VALUES (?,?,?,?,?,?,?,1,'{}',DATE_ADD(UTC_TIMESTAMP(),INTERVAL 2 DAY))",[$id,$device,$device,$row['tenant_id'],$row['profile_id'],$row['profile_version'],$row['set_by']])->rowCount();return $added?$id:null;
    }
    public static function assignmentChanged(Database $db,string $device):void {if(self::installed($db))$db->query("UPDATE device_setup_jobs SET status='cancelled',message='Device assignment changed; automatic/manual setup stopped' WHERE device_id=? AND status IN ('waiting','running','paused')",[$device]);}
    public function jobs(array $actor,array $input):array {
        $this->ready();Input::keys($input,['tenant_id','device_id','page']);$args=[];$where='1=1';if($actor['role']!=='owner'){if(!empty($input['tenant_id']))$this->management->tenant($actor,Input::id($input['tenant_id']));$where.=' AND d.tenant_id=? AND j.tenant_id=?';array_push($args,$actor['tenant_id'],$actor['tenant_id']);}elseif(!empty($input['tenant_id'])){$t=$this->management->tenant($actor,Input::id($input['tenant_id']));$where.=' AND j.tenant_id=?';$args[]=$t['id'];}
        if(!empty($input['device_id'])){$d=$this->management->device($actor,Input::id($input['device_id']));$where.=' AND j.device_id=?';$args[]=$d['id'];}$page=Input::page($input['page']??1);$offset=($page-1)*20;
        $total=(int)$this->db->query("SELECT COUNT(*) FROM device_setup_jobs j JOIN devices d ON d.id=j.device_id WHERE $where",$args)->fetchColumn();$rows=$this->db->all("SELECT j.*,v.name AS profile_name,d.label AS device_label FROM device_setup_jobs j JOIN devices d ON d.id=j.device_id JOIN device_setup_versions v ON v.profile_id=j.profile_id AND v.version=j.profile_version WHERE $where ORDER BY j.created_at DESC,j.id DESC LIMIT 20 OFFSET $offset",$args);
        foreach($rows as &$row){$state=json_decode($row['state'],true,20,JSON_THROW_ON_ERROR);unset($row['state']);$row['results']=$state['results']??[];$row['notes']=$state['notes']??[];}unset($row);return ['items'=>$rows,'page'=>$page,'pages'=>max(1,(int)ceil($total/20)),'total'=>$total];
    }
    public function applyExisting(array $actor,string $device,array $input,string $key):array {
        $this->ready();Input::keys($input,['profile_id','profile_version','confirmed']);if(($input['confirmed']??null)!==true)throw new Problem(422,'Review and confirm this existing-device setup.');EnrollmentProof::hex($key,32);
        $this->auth->limit('setup-manual',$actor['id'],20,60);
        return $this->db->transaction(function()use($actor,$device,$input,$key):array {
            $d=$this->management->device($actor,$device,true);if(!$d['enrolled_at']||$d['policy']!=='active')throw new Problem(409,'An enrolled app with active access is required before setup.');$p=$this->profile($actor,Input::id($input['profile_id']??null));if($p['tenant_id']!==$d['tenant_id']||$p['archived']||(int)$p['version']!==Input::integer($input['profile_version']??null,1,100000))throw new Problem(409,'Profile is unavailable or changed.');
            $existing=$this->db->one('SELECT * FROM device_setup_jobs WHERE id=? FOR UPDATE',[$key]);if($existing){if($existing['device_id']!==$device||$existing['profile_id']!==$p['id']||(int)$existing['profile_version']!==(int)$p['version'])throw new Problem(409,'Request key already used.');return ['id'=>$key];}
            if($this->db->one("SELECT id FROM device_setup_jobs WHERE device_id=? AND status IN ('waiting','running','paused') FOR UPDATE",[$device]))throw new Problem(409,'Review/cancel the outstanding setup before starting another.');
            $this->db->query("INSERT INTO device_setup_jobs (id,device_id,tenant_id,profile_id,profile_version,actor_id,automatic,state,expires_at) VALUES (?,?,?,?,?,?,0,'{}',DATE_ADD(UTC_TIMESTAMP(),INTERVAL 2 DAY))",[$key,$device,$d['tenant_id'],$p['id'],$p['version'],$actor['id']]);$this->auth->audit($actor,'setup.manual_requested',$key,$d['tenant_id']);return ['id'=>$key];
        });
    }
    private function stopJob(array $job,string $status,string $message):void {
        if(!$this->db->pdo->inTransaction()){$this->db->transaction(fn()=> $this->stopJob($job,$status,$message));return;}
        $job=$this->db->one('SELECT * FROM device_setup_jobs WHERE id=? FOR UPDATE',[$job['id']]);if(!$job||in_array($job['status'],['complete','complete_with_notes'],true))return;
        $state=json_decode($job['state'],true,20,JSON_THROW_ON_ERROR);foreach($state['commands']??[] as $id)$this->db->query("UPDATE commands SET status='cancelled',finished_at=UTC_TIMESTAMP(),lease_hash=NULL,lease_until=NULL WHERE id=? AND device_id=? AND status IN ('pending','delivered')",[$id,$job['device_id']]);
        $this->db->query('UPDATE device_setup_jobs SET status=?,message=? WHERE id=?',[$status,$message,$job['id']]);
    }
    public function control(array $actor,string $id,array $input):array {
        $this->ready();Input::keys($input,['action']);$job=$this->db->one('SELECT * FROM device_setup_jobs WHERE id=?',[Input::id($id)]);if(!$job)throw new Problem(404,'Setup not found.');$d=$this->management->device($actor,$job['device_id']);if($actor['role']!=='owner'&&$job['tenant_id']!==$d['tenant_id'])throw new Problem(404,'Setup not found.');
        if(!$job['automatic']&&$actor['role']!=='owner'&&$job['actor_id']!==$actor['id'])throw new Problem(403,'Only the requester or owner may control this manual setup.');
        if(($input['action']??null)==='cancel'&&!in_array($job['status'],['complete','complete_with_notes'],true)){$this->stopJob($job,'cancelled','Cancelled; already applied/running native work is not undone');}
        elseif(($input['action']??null)==='resume'&&$job['status']==='paused'&&strtotime($job['expires_at'].' UTC')>time()){
            // Reuse a still-pending stage's original command/baseline; do not silently
            // refresh revisions after a conflict. Cancelled write stages need review.
            $state=json_decode($job['state'],true,20,JSON_THROW_ON_ERROR);$current=$state['commands'][$job['stage']]??null;if($current&&$this->db->one("SELECT id FROM commands WHERE id=? AND status='cancelled'",[$current]))throw new Problem(409,'A stage was cancelled. Start a separately reviewed setup with fresh reads.');
            $this->db->query("UPDATE device_setup_jobs SET status='waiting',actor_id=?,next_at=UTC_TIMESTAMP() WHERE id=?",[$actor['id'],$id]);
        }else throw new Problem(409,'Choose Cancel, or resume a paused setup. Conflicts require a separately reviewed apply.');$this->auth->audit($actor,'setup.job_controlled',$id,$d['tenant_id'],['action'=>$input['action']]);return ['id'=>$id];
    }
    public function detail(array $actor,string $id):array {
        $this->ready();$job=$this->db->one('SELECT j.*,v.definition,v.name AS profile_name FROM device_setup_jobs j JOIN device_setup_versions v ON v.profile_id=j.profile_id AND v.version=j.profile_version WHERE j.id=?',[Input::id($id)]);if(!$job)throw new Problem(404,'Setup not found.');$this->management->device($actor,$job['device_id']);if($actor['role']!=='owner'&&$job['tenant_id']!==$actor['tenant_id'])throw new Problem(404,'Setup not found.');$state=json_decode($job['state'],true,20,JSON_THROW_ON_ERROR);$job['stages']=$this->stages(json_decode($job['definition'],true,20,JSON_THROW_ON_ERROR));$job['results']=$state['results']??[];$job['notes']=$state['notes']??[];$job['stage_status']=[];foreach($state['commands']??[] as $stage=>$command){$row=$this->db->one('SELECT status FROM commands WHERE id=? AND device_id=?',[$command,$job['device_id']]);if($row)$job['stage_status'][$stage]=$row['status'];}unset($job['state'],$job['definition']);return $job;
    }
    public static function matchLayout(array $catalog,array $entries):array {
        $byName=[];$ambiguous=[];$unsafe=[];foreach($catalog['items'] as $row){$name=$row['match_name'];if($name===null){if(isset($row['label']))$unsafe[$row['label']]=true;continue;}if(isset($byName[$name]))$ambiguous[]=$name;else $byName[$name]=$row;}
        if($ambiguous)throw new Problem(409,'Ambiguous category names require review; no layout guessed.');$chosen=[];$missing=[];$hidden=[];
        foreach($entries as $entry){if(isset($unsafe[$entry['name']]))throw new Problem(409,'A requested category has no unambiguous full name; review it before applying.');$row=$byName[$entry['name']]??null;if(!$row){$missing[]=$entry['name'];continue;}$chosen[$row['ref']]=true;if($entry['hidden'])$hidden[]=$row['ref'];}
        if(!$chosen)throw new Problem(409,'No exact layout names match; review the catalogue.');$order=array_keys($chosen);
        foreach($catalog['items'] as $row)if(!isset($chosen[$row['ref']])){$order[]=$row['ref'];if($row['hidden'])$hidden[]=$row['ref'];}
        if(count($hidden)>=count($order))throw new Problem(409,'This setup would hide every category; review the layout first.');
        return ['payload'=>['profile_ref'=>$catalog['profile_ref'],'source_ref'=>$catalog['source_ref'],'expected_revision'=>$catalog['revision'],'order'=>$order,'hidden'=>$hidden,'provider_order'=>false],'missing'=>$missing];
    }
    private function stages(array $def):array {
        $stages=['read_settings'];if($def['settings'])$stages[]='read_preferences';if($def['layout'])$stages[]='read_layout';
        if($def['settings'])$stages[]='write_preferences';if($def['sort_order']!==null)$stages[]='write_sort';if($def['prebuffer_seconds']!==null)$stages[]='write_buffer';if($def['layout'])array_push($stages,'write_layout','confirm_layout');return $stages;
    }
    private function kind(string $stage):string {return match($stage){'read_settings'=>'get_settings','read_preferences'=>'get_preferences','read_layout','confirm_layout'=>'get_category_layout','write_preferences'=>'apply_preferences','write_sort'=>'set_settings','write_buffer'=>'set_live_buffer','write_layout'=>'apply_category_layout'};}
    private function actor(array $job):?array {$u=$this->db->one('SELECT * FROM users WHERE id=? AND active=1',[$job['actor_id']]);if(!$u||($u['role']!=='owner'&&$u['tenant_id']!==$job['tenant_id']))return null;return Auth::publicUser($u);}
    private function queueStage(array $actor,array $job,array &$state,array $def):string {
        $stage=$job['stage'];$payload=[];
        if($stage==='write_sort')$payload=['profile_ref'=>$state['profile_ref'],'expected_revision'=>$state['settings']['settings_revision'],'sort_order'=>$def['sort_order']];
        if($stage==='write_buffer')$payload=['profile_ref'=>$state['profile_ref'],'expected_revision'=>$state['settings']['live_buffer_revision'],'prebuffer_seconds'=>$def['prebuffer_seconds']];
        if($stage==='write_layout')$payload=$state['layout_plan'];
        if($stage==='confirm_layout')$payload=['profile_ref'=>$state['profile_ref'],'source_ref'=>$state['layout_plan']['source_ref']];
        if($stage==='write_preferences') {
            $before=$state['preferences'];$pinned=$def['settings'];$draft=Database::id();$target=['base_values'=>(object)$pinned['values'],'overrides'=>(object)$before['overrides'],'base_preset_id'=>$pinned['id'],'base_version'=>$pinned['version']];
            $this->db->query('INSERT INTO preference_drafts (id,device_id,profile_ref,expected_revision,target,expires_at) VALUES (?,?,?,?,?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 10 MINUTE))',[$draft,$job['device_id'],$state['profile_ref'],$before['revision'],json_encode($target,JSON_THROW_ON_ERROR)]);$payload=['draft_id'=>$draft];
        }
        $attempt=(int)($state['attempts'][$stage]??0);$key='setup-'.$job['id'].'-'.str_replace('_','-',$stage).'-'.$attempt;
        $command=$this->commands->enqueue($actor,$job['device_id'],['kind'=>$this->kind($stage),'payload'=>$payload,'expires_seconds'=>600],$key);$state['commands'][$stage]=$command['id'];return $command['id'];
    }
    private function consume(array $job,array &$state,array $def,array $command):void {
        $stage=$job['stage'];$result=$command['result'];
        if($stage==='read_settings') {$s=$result['snapshot']??null;if(!$s||($def['sort_order']!==null&&!isset($s['settings_revision']))||($def['prebuffer_seconds']!==null&&!isset($s['live_buffer_revision'])))throw new Problem(409,'App settings are incomplete; update the app before setup.');$state['profile_ref']=$s['profile_ref'];$state['settings']=$s;}
        elseif($stage==='read_preferences') {$row=$this->db->one('SELECT document FROM preference_snapshots WHERE command_id=? AND device_id=?',[$command['id'],$job['device_id']]);if(!$row)throw new Problem(409,'Settings read expired; review setup.');$doc=json_decode($row['document'],true,16,JSON_THROW_ON_ERROR);if($doc['profile_ref']!==$state['profile_ref'])throw new Problem(409,'Active app profile changed; review setup.');$state['preferences']=$doc;}
        elseif(in_array($stage,['read_layout','confirm_layout'],true)) {
            $row=$this->db->one('SELECT catalog FROM category_layout_snapshots WHERE command_id=? AND device_id=?',[$command['id'],$job['device_id']]);if(!$row)throw new Problem(409,'Catalogue read expired; review setup.');$catalog=json_decode($row['catalog'],true,20,JSON_THROW_ON_ERROR);if($catalog['profile_ref']!==$state['profile_ref'])throw new Problem(409,'Active app profile changed; review setup.');
            if($stage==='read_layout') {$matched=self::matchLayout($catalog,$def['layout']['entries']);$state['layout_plan']=$matched['payload'];$state['notes']=$matched['missing'];}
            else {$plan=$state['layout_plan'];$order=array_column($catalog['items'],'ref');$hidden=array_column(array_values(array_filter($catalog['items'],static fn($r)=>$r['hidden'])),'ref');$expected=$plan['hidden'];sort($hidden);sort($expected);if($catalog['source_ref']!==$plan['source_ref']||$order!==$plan['order']||$hidden!==$expected||$catalog['provider_order']!==false)throw new Problem(409,'Layout confirmation changed; do not overwrite it automatically.');}
        } elseif(in_array($stage,['write_sort','write_buffer'],true)) {
            $s=$result['snapshot']??[];if(($s['profile_ref']??null)!==$state['profile_ref']||($stage==='write_sort'&&($s['sort_order']??null)!==$def['sort_order'])||($stage==='write_buffer'&&(string)($s['live_prebuffer_seconds']??'')!==$def['prebuffer_seconds']))throw new Problem(409,'Setting confirmation did not match the pinned recipe.');
        }
        $state['results'][$stage]=['status'=>'confirmed','command_id'=>$command['id']];
    }
    public function tickDevice(string $device):void {
        if(!self::installed($this->db))return;
        foreach($this->db->all("SELECT id FROM device_setup_jobs WHERE device_id=? AND status IN ('waiting','running') AND next_at<=UTC_TIMESTAMP() ORDER BY created_at LIMIT 2",[$device]) as $row)$this->advance($row['id']);
    }
    public function afterCommand(string $device,string $command):void {
        if(!self::installed($this->db))return;
        foreach($this->db->all("SELECT id FROM device_setup_jobs WHERE device_id=? AND status IN ('waiting','running') AND JSON_UNQUOTE(JSON_EXTRACT(state,CONCAT('$.commands.',stage)))=?",[$device,$command]) as $row)$this->advance($row['id']);
    }
    public function pending(string $device):bool {return self::installed($this->db)&&(bool)$this->db->one("SELECT id FROM device_setup_jobs WHERE device_id=? AND status IN ('waiting','running') LIMIT 1",[$device]);}
    private function advance(string $id):void {
        // Each progression owns its transaction; fresh permission/policy reads must
        // not use an earlier repeatable-read snapshot. Never change a caller's TX.
        if($this->db->pdo->inTransaction())return;
        $this->db->pdo->exec('SET TRANSACTION ISOLATION LEVEL READ COMMITTED');
        try {$this->db->transaction(function()use($id):void {
            $pre=$this->db->one('SELECT device_id FROM device_setup_jobs WHERE id=?',[$id]);if(!$pre)return;
            // Same lock order as explicit commands/unassignment: device, then job.
            $rawDevice=$this->db->one('SELECT id,tenant_id,reseller_detached FROM devices WHERE id=? FOR UPDATE',[$pre['device_id']]);$job=$this->db->one("SELECT * FROM device_setup_jobs WHERE id=? AND status IN ('waiting','running') FOR UPDATE",[$id]);if(!$job)return;
            if(!$rawDevice||$rawDevice['tenant_id']!==$job['tenant_id']||($job['automatic']&&$rawDevice['reseller_detached'])){$this->stopJob($job,'cancelled','Device assignment changed; reseller defaults stopped.');return;}
            if(strtotime($job['expires_at'].' UTC')<=time()){$this->stopJob($job,'expired','Setup expired; completed/running changes are not undone. Review before retrying.');return;}
            $actor=$this->actor($job);if(!$actor){$this->stopJob($job,'paused','Setup publisher is inactive; an authorized operator must review it.');return;}
            $d=$this->management->device($actor,$job['device_id'],true);$tenant=$this->management->tenant($actor,$job['tenant_id']);
            if($d['tenant_id']!==$job['tenant_id']||($job['automatic']&&$d['reseller_detached'])){$this->stopJob($job,'cancelled','Device assignment changed; reseller defaults stopped.');return;}
            if($d['policy']!=='active'||$tenant['status']!=='active'){$this->stopJob($job,'paused','App/workspace access is restricted; setup paused.');return;}
            if($job['automatic']){$policy=$this->db->one('SELECT enabled FROM device_setup_defaults WHERE tenant_id=?',[$job['tenant_id']]);$link=$this->db->one('SELECT panel_id FROM panel_resellers WHERE tenant_id=?',[$job['tenant_id']]);if(!$policy||!$policy['enabled']||!$link){$this->stopJob($job,'paused','Automatic setup disabled or reseller link unavailable.');return;}}
            $version=$this->db->one('SELECT definition FROM device_setup_versions WHERE profile_id=? AND version=?',[$job['profile_id'],$job['profile_version']]);$def=json_decode($version['definition'],true,20,JSON_THROW_ON_ERROR);$stages=$this->stages($def);$state=json_decode($job['state'],true,20,JSON_THROW_ON_ERROR);
            $needed=array_unique(array_map(fn($s)=>$this->kind($s),$stages));if(array_diff($needed,$d['capabilities'])){$this->db->query("UPDATE device_setup_jobs SET message='Waiting for a compatible app to open; no changes sent',next_at=DATE_ADD(UTC_TIMESTAMP(),INTERVAL 60 SECOND) WHERE id=?",[$id]);return;}
            $this->commands->expire($d['id']);$commandId=$state['commands'][$job['stage']]??null;
            if($commandId) {
                $raw=$this->db->one('SELECT * FROM commands WHERE id=? AND device_id=?',[$commandId,$d['id']]);if(!$raw)throw new Problem(409,'Stage history unavailable; review actual state.');$command=Commands::publicCommand($raw);
                if(in_array($command['status'],['pending','delivered','running'],true)){$this->db->query("UPDATE device_setup_jobs SET status='running',message='Waiting for the app to confirm this stage',next_at=DATE_ADD(UTC_TIMESTAMP(),INTERVAL 15 SECOND) WHERE id=?",[$id]);return;}
                if($command['status']!=='succeeded') {
                    $attempt=(int)($state['attempts'][$job['stage']]??0);
                    if($job['stage']==='read_layout'&&$attempt<3){$state['attempts'][$job['stage']]=$attempt+1;unset($state['commands'][$job['stage']]);$this->db->query("UPDATE device_setup_jobs SET status='waiting',message='Waiting for a ready catalogue; open Live TV in the app',state=?,next_at=DATE_ADD(UTC_TIMESTAMP(),INTERVAL ? SECOND) WHERE id=?",[json_encode($state,JSON_THROW_ON_ERROR),[60,300,900][$attempt],$id]);return;}
                    throw new Problem(409,'Stage failed, conflicted or expired; inspect the device before a new apply.');
                }
                $this->consume($job,$state,$def,$command);$index=array_search($job['stage'],$stages,true)+1;
                if($index>=count($stages)){$this->db->query("UPDATE device_setup_jobs SET status=?,message=?,state=? WHERE id=?",[empty($state['notes'])?'complete':'complete_with_notes',empty($state['notes'])?'Every required stage confirmed':'Confirmed; some template names were absent and target-only categories were kept',json_encode($state,JSON_THROW_ON_ERROR),$id]);return;}
                $job['stage']=$stages[$index];
            }
            $this->queueStage($actor,$job,$state,$def);$this->db->query("UPDATE device_setup_jobs SET status='running',stage=?,message='Request sent; waiting for device confirmation',state=?,next_at=DATE_ADD(UTC_TIMESTAMP(),INTERVAL 15 SECOND) WHERE id=?",[$job['stage'],json_encode($state,JSON_THROW_ON_ERROR),$id]);
        });}catch(\Throwable $error){
            if(!($error instanceof Problem))error_log('Device setup stage failed ('.get_class($error).'); inspect private server diagnostics.');
            $message=$error instanceof Problem&&$error->status===409?$error->getMessage():'Setup needs review; no automatic overwrite or hidden retry performed.';
            // Never serialize exception/SQL/provider details into a support/UI message.
            $this->db->query("UPDATE device_setup_jobs SET status='needs_review',message=? WHERE id=? AND status IN ('waiting','running')",[mb_substr($message,0,250),$id]);
        }
    }
    public function maintain():void {
        foreach($this->db->all("SELECT * FROM device_setup_jobs WHERE status='paused' AND expires_at<=UTC_TIMESTAMP() LIMIT 100") as $job)$this->stopJob($job,'expired','Paused setup expired; review before starting a new apply.');
        foreach($this->db->all("SELECT id FROM device_setup_jobs WHERE status IN ('waiting','running') AND next_at<=UTC_TIMESTAMP() ORDER BY next_at LIMIT 100") as $row)$this->advance($row['id']);
        $this->db->query("DELETE FROM device_setup_jobs WHERE automatic=0 AND status IN ('complete','complete_with_notes','cancelled','expired','needs_review') AND updated_at<DATE_SUB(UTC_TIMESTAMP(),INTERVAL 90 DAY) LIMIT 1000");
        $this->db->query("DELETE v FROM device_setup_versions v JOIN device_setup_profiles p ON p.id=v.profile_id WHERE v.version+20<p.version AND NOT EXISTS (SELECT 1 FROM device_setup_defaults d WHERE d.profile_id=v.profile_id AND d.profile_version=v.version) AND NOT EXISTS (SELECT 1 FROM device_setup_jobs j WHERE j.profile_id=v.profile_id AND j.profile_version=v.version)");
    }
}
