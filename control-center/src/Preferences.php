<?php
declare(strict_types=1);
namespace ControlCenter;

/** Device/profile overlays; no arbitrary Android preference keys. */
final class Preferences
{
    public const RULES=[
        'default_audio_language'=>['Auto (Original)','English','French','Spanish','German','Italian','Portuguese'],
        'default_subtitle'=>['Off','English','French','Spanish','German','Italian','Portuguese'],
        'subtitle_size'=>['Small','Medium','Large'],'clock_format'=>['12h','24h'],
        'auto_play_next'=>['false','true'],'show_loading_stats'=>['false','true'],'spoiler_blur'=>['false','true'],
        'smooth_scrolling'=>['false','true'],'trailer_auto_play'=>['false','true'],'volume_boost_db'=>['0','3','6']
    ];
    public function __construct(private readonly Database $db,private readonly Management $management,private readonly Commands $commands,private readonly Auth $auth) {}
    public static function values(mixed $input,bool $local=false):array {
        if(!is_array($input)||($input!==[]&&array_is_list($input))||array_diff(array_keys($input),array_keys(self::RULES)))throw new Problem(422,'Unknown managed preference field.');
        foreach($input as $key=>$value)if(!is_string($value)||!in_array($value,array_merge(self::RULES[$key],$local?['local_custom']:[]),true))throw new Problem(422,'Unsupported managed preference value.');return $input;
    }
    public static function document(mixed $doc):array {
        if(!is_array($doc)||!is_int($doc['base_version']??null))throw new Problem(422,'Invalid managed preferences document/version.');
        Input::keys($doc,['schema_version','profile_ref','revision','values','origins','base_values','overrides','base_preset_id','base_version']);
        if(($doc['schema_version']??null)!==1)throw new Problem(422,'Invalid managed preferences schema.');EnrollmentProof::hex($doc['profile_ref']??null,64);EnrollmentProof::hex($doc['revision']??null,64);
        $doc['values']=self::values($doc['values']??null,true);$doc['base_values']=self::values($doc['base_values']??null);$doc['overrides']=self::values($doc['overrides']??null);
        if(array_diff(array_keys(self::RULES),array_keys($doc['values']))||!is_array($doc['origins']??null)||array_diff(array_keys(self::RULES),array_keys($doc['origins']))||array_diff(array_keys($doc['origins']),array_keys(self::RULES)))throw new Problem(422,'Incomplete effective preferences.');
        foreach($doc['origins'] as $origin)if(!in_array($origin,['local','base_preset','device_profile_override'],true))throw new Problem(422,'Invalid preference origin.');
        if(($doc['base_preset_id']??null)==='') {if(($doc['base_version']??null)!==0||$doc['base_values']!==[])throw new Problem(422,'Invalid empty base preset.');}
        else {Input::id($doc['base_preset_id']??null);Input::integer($doc['base_version']??null,1,100000);}
        return $doc;
    }
    private function profile(array $actor,string $id):array {$row=$this->db->one('SELECT * FROM preference_profiles WHERE id=?',[Input::id($id)]);if(!$row)throw new Problem(404,'Settings profile not found.');$this->management->tenant($actor,$row['tenant_id']);return $row;}
    public function profiles(array $actor,array $input):array {
        Input::keys($input,['tenant_id','page','search']);$page=Input::page($input['page']??1);$offset=($page-1)*25;$args=[];$where='p.archived=0';
        if($actor['role']!=='owner'||!empty($input['tenant_id'])){$tenant=$this->management->tenant($actor,Input::id($input['tenant_id']??$actor['tenant_id']));$where.=' AND p.tenant_id=?';$args[]=$tenant['id'];}
        $search=Input::text($input['search']??'','search',80,false);if($search!==''){$where.=' AND LOCATE(?,p.name)>0';$args[]=$search;}
        $total=(int)$this->db->query("SELECT COUNT(*) FROM preference_profiles p WHERE $where",$args)->fetchColumn();$rows=$this->db->all("SELECT p.id,p.tenant_id,p.name,p.version,p.values_json,p.updated_at,t.name AS tenant_name FROM preference_profiles p JOIN tenants t ON t.id=p.tenant_id WHERE $where ORDER BY p.updated_at DESC,p.id LIMIT 25 OFFSET $offset",$args);foreach($rows as &$row){$row['values']=json_decode($row['values_json'],true,8,JSON_THROW_ON_ERROR);unset($row['values_json']);}unset($row);
        return ['items'=>$rows,'rules'=>self::RULES,'page'=>$page,'pages'=>max(1,(int)ceil($total/25)),'total'=>$total];
    }
    public function save(array $actor,?string $id,array $input):array {
        Input::keys($input,['tenant_id','name','values','expected_version','archived']);$name=Input::text($input['name']??null,'settings profile name',80);$values=self::values($input['values']??null);$archived=$input['archived']??false;if(!is_bool($archived))throw new Problem(422,'Invalid archive status.');
        return $this->db->transaction(function()use($actor,$id,$input,$name,$values,$archived):array {
            if($id){$old=$this->profile($actor,$id);$tenant=$this->management->tenant($actor,$old['tenant_id'],true);$old=$this->db->one('SELECT * FROM preference_profiles WHERE id=? FOR UPDATE',[$id]);if((int)$old['version']!==Input::integer($input['expected_version']??null,1,99999))throw new Problem(409,'Settings profile changed; refresh first.');$version=(int)$old['version']+1;$this->db->query('UPDATE preference_profiles SET name=?,values_json=?,version=?,archived=? WHERE id=?',[$name,json_encode((object)$values,JSON_THROW_ON_ERROR),$version,(int)$archived,$id]);}
            else {$tenant=$this->management->tenant($actor,Input::id($input['tenant_id']??$actor['tenant_id']),true);if((int)$this->db->query('SELECT COUNT(*) FROM preference_profiles WHERE tenant_id=? AND archived=0',[$tenant['id']])->fetchColumn()>=100)throw new Problem(409,'Archive an unused settings profile first (100/workspace).');$id=Database::id();$version=1;$this->db->query('INSERT INTO preference_profiles (id,tenant_id,name,values_json,created_by) VALUES (?,?,?,?,?)',[$id,$tenant['id'],$name,json_encode((object)$values,JSON_THROW_ON_ERROR),$actor['id']]);}
            if($tenant['status']!=='active')throw new Problem(409,'Workspace is suspended.');$this->auth->audit($actor,'preferences.profile_saved',$id,$tenant['id'],['version'=>$version,'archived'=>$archived]);return ['id'=>$id,'version'=>$version];
        });
    }
    public function capture(string $token,array $input):array {
        Input::keys($input,['command_id','lease_token','document']);[$d,$c]=$this->commands->leasedDeviceCommand($token,$input['command_id']??'',$input['lease_token']??'',['get_preferences','apply_preferences']);$this->auth->limit('preferences-upload',$d['id'],20,60);$doc=self::document($input['document']??[]);
        if($c['kind']==='apply_preferences') {$p=json_decode($c['payload'],true,16,JSON_THROW_ON_ERROR);$draft=$this->draft($d['id'],$p['draft_id']);$target=json_decode($draft['target'],true,16,JSON_THROW_ON_ERROR);if($doc['profile_ref']!==$draft['profile_ref']||$doc['base_values']!=$target['base_values']||$doc['overrides']!=$target['overrides']||$doc['base_preset_id']!==$target['base_preset_id']||$doc['base_version']!==$target['base_version'])throw new Problem(409,'Preference confirmation differs from reviewed policy.');}
        if($c['kind']==='apply_preferences')foreach(array_replace($target['base_values'],$target['overrides']) as $key=>$value)if($doc['values'][$key]!==$value||$doc['origins'][$key]!== (array_key_exists($key,$target['overrides'])?'device_profile_override':'base_preset'))throw new Problem(409,'Effective preference changed before confirmation; read it again.');
        $this->db->query('INSERT INTO preference_snapshots (command_id,device_id,profile_ref,revision,document) VALUES (?,?,?,?,?) ON DUPLICATE KEY UPDATE command_id=VALUES(command_id)',[$c['id'],$d['id'],$doc['profile_ref'],$doc['revision'],json_encode($doc,JSON_THROW_ON_ERROR)]);
        $keep=$this->db->all('SELECT command_id FROM preference_snapshots WHERE device_id=? ORDER BY created_at DESC,command_id DESC LIMIT 5',[$d['id']]);$ids=array_column($keep,'command_id');if($ids){$marks=implode(',',array_fill(0,count($ids),'?'));$this->db->query("DELETE FROM preference_snapshots WHERE device_id=? AND command_id NOT IN ($marks)",array_merge([$d['id']],$ids));}return ['stored'=>true];
    }
    public function latest(array $actor,string $device):array {$this->management->device($actor,$device);$row=$this->db->one('SELECT command_id,document,created_at FROM preference_snapshots WHERE device_id=? ORDER BY created_at DESC,command_id DESC LIMIT 1',[$device]);if($row)$row['document']=json_decode($row['document'],true,16,JSON_THROW_ON_ERROR);return ['snapshot'=>$row,'rules'=>self::RULES];}
    private function draft(string $device,string $id):array {$row=$this->db->one('SELECT * FROM preference_drafts WHERE id=? AND device_id=? AND expires_at>UTC_TIMESTAMP()',[Input::id($id),$device]);if(!$row)throw new Problem(409,'Settings preview expired or belongs to another device.');return $row;}
    public function preview(array $actor,string $device,array $input):array {
        Input::keys($input,['snapshot_id','base_preset_id','overrides']);$d=$this->management->device($actor,$device);$row=$this->db->one('SELECT * FROM preference_snapshots WHERE command_id=? AND device_id=? AND created_at>DATE_SUB(UTC_TIMESTAMP(),INTERVAL 10 MINUTE)',[Input::id($input['snapshot_id']??null),$device]);if(!$row)throw new Problem(409,'Read fresh expanded settings from the app first.');
        $doc=json_decode($row['document'],true,16,JSON_THROW_ON_ERROR);$base=[];$version=0;$preset=$input['base_preset_id']??'';if($preset!==''){$p=$this->profile($actor,Input::id($preset));if($p['tenant_id']!==$d['tenant_id']||$p['archived'])throw new Problem(409,'Choose an active settings profile in this device workspace.');$base=json_decode($p['values_json'],true,8,JSON_THROW_ON_ERROR);$version=(int)$p['version'];}
        $overrides=self::values($input['overrides']??null);$target=['base_values'=>(object)$base,'overrides'=>(object)$overrides,'base_preset_id'=>$preset,'base_version'=>$version];$effective=array_replace($base,$overrides);$changes=[];foreach(self::RULES as $key=>$choices)$changes[]=['field'=>$key,'current'=>$doc['values'][$key],'next'=>$effective[$key]??'Retain newer local value, otherwise restore underlying local/Cloud preference','origin'=>isset($overrides[$key])?'device_profile_override':(isset($base[$key])?'base_preset':'local')];
        $this->auth->limit('preference-preview',$actor['id'],30,60);if((int)$this->db->query('SELECT COUNT(*) FROM preference_drafts WHERE device_id=? AND expires_at>UTC_TIMESTAMP()',[$device])->fetchColumn()>=10)throw new Problem(409,'Ten settings previews already active; wait for expiry.');$id=Database::id();$this->db->query('INSERT INTO preference_drafts (id,device_id,profile_ref,expected_revision,target,expires_at) VALUES (?,?,?,?,?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 10 MINUTE))',[$id,$device,$row['profile_ref'],$row['revision'],json_encode($target,JSON_THROW_ON_ERROR)]);return ['id'=>$id,'changes'=>$changes,'warning'=>'Device/profile overrides win over the selected base profile. Unmanaged fields stay local; reset restores the underlying choice only if it has not since changed locally. UI/audio observers may apply immediately; no forced player restart.'];
    }
    public function context(string $token,array $input):array {Input::keys($input,['command_id','lease_token']);[$d,$c]=$this->commands->leasedDeviceCommand($token,$input['command_id']??'',$input['lease_token']??'',['apply_preferences']);$p=json_decode($c['payload'],true,16,JSON_THROW_ON_ERROR);$draft=$this->draft($d['id'],$p['draft_id']);$target=json_decode($draft['target'],true,16,JSON_THROW_ON_ERROR);$target['base_values']=(object)$target['base_values'];$target['overrides']=(object)$target['overrides'];return ['profile_ref'=>$draft['profile_ref'],'expected_revision'=>$draft['expected_revision'],'target'=>$target];}
    public function maintain():void {$this->db->query('DELETE FROM preference_drafts WHERE expires_at<DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 DAY)');$this->db->query('DELETE FROM preference_snapshots WHERE created_at<DATE_SUB(UTC_TIMESTAMP(),INTERVAL 7 DAY)');$this->db->query('DELETE FROM preference_profiles WHERE archived=1 AND updated_at<DATE_SUB(UTC_TIMESTAMP(),INTERVAL 90 DAY)');}
}
