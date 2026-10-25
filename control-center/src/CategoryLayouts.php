<?php
declare(strict_types=1);
namespace ControlCenter;

final class CategoryLayouts
{
    public function __construct(private readonly Database $db,private readonly Management $management,private readonly Auth $auth,private readonly Config $config) {}
    public function device(array $actor,string $id,array $input): array
    {
        Input::keys($input,['metadata','command_id','watch_id']);$this->management->device($actor,$id);
        if(isset($input['metadata'])&&!in_array($input['metadata'],['0','1'],true))throw new Problem(422,'Invalid layout metadata option.');$metadata=($input['metadata']??'0')==='1';
        $args=[$id];$where='device_id=?';if(isset($input['command_id'])){$where.=' AND command_id=?';$args[]=Input::id($input['command_id']);}
        $row=$this->db->one('SELECT command_id,profile_ref,source_ref,revision,total,received_at_us'.($metadata?'':',catalog')." FROM category_layout_snapshots WHERE $where ORDER BY received_at_us DESC LIMIT 1",$args);
        $catalog=$row&&!$metadata?json_decode($row['catalog'],true,16,JSON_THROW_ON_ERROR):null;if($row)unset($row['catalog']);
        $this->db->query("UPDATE commands SET status='expired',finished_at=UTC_TIMESTAMP() WHERE device_id=? AND status IN ('pending','delivered','running') AND expires_at<=UTC_TIMESTAMP()",[$id]);
        $commands=$this->db->all("SELECT * FROM commands WHERE device_id=? AND kind IN ('get_category_layout','apply_category_layout') ORDER BY created_at DESC,id DESC LIMIT 10",[$id]);
        if(isset($input['watch_id'])) {
            $watch=$this->db->one("SELECT * FROM commands WHERE id=? AND device_id=? AND kind IN ('get_category_layout','apply_category_layout')",[Input::id($input['watch_id']),$id]);if(!$watch)throw new Problem(404,'Command not found.');
            if(!in_array($watch['id'],array_column($commands,'id'),true))$commands[]=$watch;
        }
        return ['snapshot'=>$row,'catalog'=>$catalog,'commands'=>array_map(Commands::publicCommand(...),$commands)];
    }
    private static function entries(mixed $entries): array
    {
        if(!is_array($entries)||!array_is_list($entries)||!$entries||count($entries)>2000)throw new Problem(422,'A template needs 1–2000 named categories.');
        $seen=[];
        foreach($entries as &$entry) {
            if(!is_array($entry))throw new Problem(422,'Invalid template category.');Input::keys($entry,['name','hidden']);
            $name=CommandCategories::portableName($entry['name']??null);if(isset($seen[$name])||!is_bool($entry['hidden']??null))throw new Problem(422,'Duplicate/invalid template category.');
            $seen[$name]=true;$entry=['name'=>$name,'hidden'=>$entry['hidden']];
        }
        unset($entry);return $entries;
    }
    public function listing(array $actor,array $input): array
    {
        Input::keys($input,['tenant_id','page','search']);$args=[];$where='p.archived=0';
        if($actor['role']!=='owner'||!empty($input['tenant_id'])){$tenant=$this->management->tenant($actor,Input::id($input['tenant_id']??$actor['tenant_id']));$where.=' AND p.tenant_id=?';$args[]=$tenant['id'];}
        $search=Input::text($input['search']??'','search',80,false);if($search!==''){$where.=' AND LOCATE(?,p.name)>0';$args[]=$search;}
        $page=Input::page($input['page']??1);$offset=($page-1)*25;$total=(int)$this->db->query("SELECT COUNT(*) FROM category_layout_templates p WHERE $where",$args)->fetchColumn();
        return ['items'=>$this->db->all("SELECT p.id,p.tenant_id,p.name,p.version,p.updated_at,JSON_LENGTH(p.entries) AS category_count,t.name AS tenant_name FROM category_layout_templates p JOIN tenants t ON t.id=p.tenant_id WHERE $where ORDER BY p.updated_at DESC,p.id LIMIT 25 OFFSET $offset",$args),'page'=>$page,'pages'=>max(1,(int)ceil($total/25)),'total'=>$total];
    }
    public function get(array $actor,string $id): array
    {
        $row=$this->db->one('SELECT * FROM category_layout_templates WHERE id=? AND archived=0',[Input::id($id)]);if(!$row)throw new Problem(404,'Record not found.');
        $this->management->tenant($actor,$row['tenant_id']);$row['entries']=json_decode($row['entries'],true,16,JSON_THROW_ON_ERROR);return $row;
    }
    public function create(array $actor,array $input): array
    {
        Input::keys($input,['tenant_id','name','entries']);$tenantId=$this->management->workspace($actor,$input['tenant_id']??null)['id'];$name=Input::text($input['name']??null,'template name',80);$entries=self::entries($input['entries']??null);
        return $this->db->transaction(function()use($actor,$tenantId,$name,$entries):array {
            $tenant=$this->management->tenant($actor,$tenantId,true);if($tenant['status']!=='active')throw new Problem(409,'Workspace suspended.');
            if((int)$this->db->query('SELECT COUNT(*) FROM category_layout_templates WHERE tenant_id=? AND archived=0',[$tenantId])->fetchColumn()>=100)throw new Problem(409,'Archive an unused layout template first (100 active per workspace).');
            $id=Database::id();$this->db->query('INSERT INTO category_layout_templates (id,tenant_id,name,entries,created_by) VALUES (?,?,?,?,?)',[$id,$tenantId,$name,json_encode($entries,JSON_UNESCAPED_UNICODE|JSON_THROW_ON_ERROR),$actor['id']]);
            $this->auth->audit($actor,'layout.template_created',$id,$tenantId,['categories'=>count($entries)]);return ['id'=>$id,'version'=>1];
        });
    }
    public function change(array $actor,string $id,array $input,bool $archive=false): array
    {
        Input::keys($input,$archive?['expected_version']:['expected_version','name','entries']);$version=Input::integer($input['expected_version']??null,1,PHP_INT_MAX);
        return $this->db->transaction(function()use($actor,$id,$input,$archive,$version):array {
            $row=$this->db->one('SELECT * FROM category_layout_templates WHERE id=? AND archived=0 FOR UPDATE',[Input::id($id)]);if(!$row)throw new Problem(404,'Record not found.');
            $tenant=$this->management->tenant($actor,$row['tenant_id']);if($tenant['status']!=='active')throw new Problem(409,'Workspace suspended.');
            if((int)$row['version']!==$version)throw new Problem(409,'Template changed elsewhere. Reload before replacing it.');
            $name=Input::text($input['name']??$row['name'],'template name',80);$entries=array_key_exists('entries',$input)?self::entries($input['entries']):json_decode($row['entries'],true,16,JSON_THROW_ON_ERROR);
            $this->db->query('UPDATE category_layout_templates SET name=?,entries=?,version=version+1,archived=?,updated_at=UTC_TIMESTAMP() WHERE id=?',[$name,json_encode($entries,JSON_UNESCAPED_UNICODE|JSON_THROW_ON_ERROR),(int)$archive,$id]);
            $this->auth->audit($actor,$archive?'layout.template_archived':'layout.template_updated',$id,$row['tenant_id']);return ['id'=>$id,'version'=>$version+1];
        });
    }
    public function maintain():void {$days=$this->config->number('retention_days',90,7,365);$this->db->query("DELETE FROM category_layout_templates WHERE archived=1 AND updated_at<DATE_SUB(UTC_TIMESTAMP(),INTERVAL $days DAY) LIMIT 1000");}
}
