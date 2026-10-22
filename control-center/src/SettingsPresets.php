<?php
declare(strict_types=1);
namespace ControlCenter;

/** Presets never contain source URLs, credentials, app-update settings or scripts. */
final class SettingsPresets
{
    public function __construct(private readonly Database $db,private readonly Management $management,private readonly Auth $auth,private readonly Config $config) {}
    private static function sort(mixed $value): string
    { if(!in_array($value,['provider','name','number'],true))throw new Problem(422,'Unsupported channel sorting.'); return $value; }
    public function listing(array $actor,array $input): array
    {
        Input::keys($input,['tenant_id','page','search']);$args=[];$where='p.archived=0';
        if($actor['role']!=='owner'||!empty($input['tenant_id'])) {
            $tenant=$this->management->tenant($actor,Input::id($input['tenant_id']??$actor['tenant_id']));$where.=' AND p.tenant_id=?';$args[]=$tenant['id'];
        }
        $search=Input::text($input['search']??'','search',80,false);
        if($search!==''){$where.=' AND LOCATE(?,p.name)>0';$args[]=$search;}
        $page=Input::page($input['page']??1);$offset=($page-1)*25;
        $total=(int)$this->db->query("SELECT COUNT(*) FROM settings_presets p WHERE $where",$args)->fetchColumn();
        return ['items'=>$this->db->all("SELECT p.*,t.name AS tenant_name FROM settings_presets p JOIN tenants t ON t.id=p.tenant_id WHERE $where ORDER BY p.updated_at DESC,p.id LIMIT 25 OFFSET $offset",$args),'page'=>$page,'pages'=>max(1,(int)ceil($total/25)),'total'=>$total];
    }
    public function create(array $actor,array $input): array
    {
        Input::keys($input,['tenant_id','name','sort_order']);$tenantId=Input::id($input['tenant_id']??$actor['tenant_id']);
        $name=Input::text($input['name']??null,'preset name',80);$sort=self::sort($input['sort_order']??null);
        return $this->db->transaction(function()use($actor,$tenantId,$name,$sort):array {
            $tenant=$this->management->tenant($actor,$tenantId,true);if($tenant['status']!=='active')throw new Problem(409,'This workspace is suspended.');
            if((int)$this->db->query('SELECT COUNT(*) FROM settings_presets WHERE tenant_id=? AND archived=0',[$tenantId])->fetchColumn()>=100)throw new Problem(409,'Archive an unused preset first (100 active presets per workspace).');
            $id=Database::id();$this->db->query('INSERT INTO settings_presets (id,tenant_id,name,sort_order,created_by) VALUES (?,?,?,?,?)',[$id,$tenantId,$name,$sort,$actor['id']]);
            $this->auth->audit($actor,'settings.preset_created',$id,$tenantId);return ['id'=>$id,'tenant_id'=>$tenantId,'name'=>$name,'sort_order'=>$sort];
        });
    }
    public function change(array $actor,string $id,array $input,bool $archive=false): array
    {
        Input::keys($input,$archive?[]:['name','sort_order']);
        return $this->db->transaction(function()use($actor,$id,$input,$archive):array {
            $row=$this->db->one('SELECT * FROM settings_presets WHERE id=? AND archived=0 FOR UPDATE',[Input::id($id)]);
            if(!$row)throw new Problem(404,'Record not found.');$tenant=$this->management->tenant($actor,$row['tenant_id']);
            if($tenant['status']!=='active')throw new Problem(409,'This workspace is suspended.');
            $name=$archive?$row['name']:Input::text($input['name']??$row['name'],'preset name',80);$sort=$archive?$row['sort_order']:self::sort($input['sort_order']??$row['sort_order']);
            $this->db->query('UPDATE settings_presets SET name=?,sort_order=?,archived=?,updated_at=UTC_TIMESTAMP() WHERE id=?',[$name,$sort,(int)$archive,$id]);
            $this->auth->audit($actor,$archive?'settings.preset_archived':'settings.preset_updated',$id,$row['tenant_id']);return ['id'=>$id,'archived'=>$archive];
        });
    }
    public function maintain(): void
    {
        $days=$this->config->number('retention_days',90,7,365);$this->db->query("DELETE FROM settings_presets WHERE archived=1 AND updated_at<DATE_SUB(UTC_TIMESTAMP(),INTERVAL $days DAY) LIMIT 1000");
    }
}
