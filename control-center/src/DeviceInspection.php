<?php
declare(strict_types=1);
namespace ControlCenter;

/** Shared read coordinator for browser and native operators. No separate credentials. */
final class DeviceInspection
{
    public const READS = ['get_settings','get_preferences','get_sources','get_category_layout','get_diagnostics'];
    public function __construct(private readonly Database $db,private readonly Management $management,private readonly Commands $commands) {}

    public function observe(array $actor,string $id,array $input):array
    {
        Input::keys($input,['section','scope']);
        $section=$input['section']??'overview';
        $priority=['overview'=>'get_settings','playback'=>'get_settings','preferences'=>'get_preferences',
            'sources'=>'get_sources','channels'=>'get_category_layout','support'=>'get_diagnostics','activity'=>'get_diagnostics','favourites'=>'get_settings'];
        if(!is_string($section)||!isset($priority[$section]))throw new Problem(422,'Unknown device section.');
        $scope=$input['scope']??[];if(!is_array($scope))throw new Problem(422,'Invalid inspection scope.');
        Input::keys($scope,['profile_ref','source_ref']);
        if($scope){EnrollmentProof::hex($scope['profile_ref']??null,64);EnrollmentProof::hex($scope['source_ref']??null,64);}
        return $this->db->transaction(function()use($actor,$id,$priority,$section,$scope):array {
            // Same device lock as command enqueue: two viewers never duplicate a read.
            $device=$this->management->device($actor,$id,true);
            $tenant=$this->management->tenant($actor,$device['tenant_id']);
            $this->commands->expire($id);
            $allowed=$device['policy']==='active'&&$tenant['status']==='active'&&$device['enrolled_at']!==null;
            $online=in_array($device['presence'],['online','idle'],true);
            $reads=self::READS;
            if($section==='support')$reads[]='get_support_bundle';
            if(!in_array('get_category_layout',$device['capabilities'],true))$reads[3]='get_categories';
            $first=$priority[$section];if($first==='get_category_layout')$first=$reads[3];
            $reads=array_values(array_unique(array_merge([$first],$reads)));
            $active=(int)$this->db->query("SELECT COUNT(*) FROM commands WHERE device_id=? AND status IN ('pending','delivered','running')",[$id])->fetchColumn();
            $sections=[];
            foreach($reads as $kind){
                $target=in_array($kind,['get_category_layout','get_categories'],true)?$scope:[];
                $scopeSql=$target?' AND JSON_UNQUOTE(JSON_EXTRACT(payload,\'$.profile_ref\'))=? AND JSON_UNQUOTE(JSON_EXTRACT(payload,\'$.source_ref\'))=?':" AND JSON_EXTRACT(payload,'$.source_ref') IS NULL";
                $args=array_merge([$id,$kind],$target?[$target['profile_ref'],$target['source_ref']]:[]);
                $pending=$this->db->one("SELECT id,status FROM commands WHERE device_id=? AND kind=? $scopeSql AND status IN ('pending','delivered','running') ORDER BY created_at DESC LIMIT 1",$args);
                $latest=$this->db->one("SELECT id,finished_at,TIMESTAMPDIFF(SECOND,finished_at,UTC_TIMESTAMP()) AS age FROM commands WHERE device_id=? AND kind=? $scopeSql AND status='succeeded' ORDER BY finished_at DESC,id DESC LIMIT 1",$args);
                $last=$this->db->one("SELECT status,TIMESTAMPDIFF(SECOND,created_at,UTC_TIMESTAMP()) AS age FROM commands WHERE device_id=? AND kind=? $scopeSql ORDER BY created_at DESC,id DESC LIMIT 1",$args);
                $supported=in_array($kind,$device['capabilities'],true);
                $ttl=in_array($kind,['get_category_layout','get_categories'],true)?90:30;
                // Mutations invalidate older reads so an explicit save is read back promptly.
                $mutation=$this->db->one("SELECT id FROM commands WHERE device_id=? AND status='succeeded' AND kind IN ('set_settings','set_live_buffer','apply_preferences','apply_sources','apply_category_layout','set_categories') AND finished_at>COALESCE(?, '1970-01-01') LIMIT 1",[$id,$latest['finished_at']??null]);
                $fresh=$latest&&(int)$latest['age']<$ttl&&!$mutation;
                $backoff=$last&&in_array($last['status'],['failed','expired','cancelled'],true)&&(int)$last['age']<120;
                if($allowed&&$online&&$supported&&!$fresh&&!$pending&&!$backoff&&$active<2){
                    $pending=$this->commands->enqueue($actor,$id,['kind'=>$kind,'payload'=>$target,'expires_seconds'=>60],'inspect-'.Database::id());
                    $active++;
                }
                $status=!$supported?'unsupported':(!$allowed?'restricted':(!$online?'offline':($pending?'syncing':($fresh?'current':($backoff?'unavailable':'waiting')))));
                $sections[$kind]=['state'=>$status,'updated_at'=>$latest['finished_at']??null,'snapshot_id'=>$latest['id']??null,'request_id'=>$pending['id']??null,'scope'=>(object)$target];
            }
            // Results are fetched through the existing scoped/masked snapshot endpoints.
            $lastChange=$this->db->one("SELECT id,finished_at FROM commands WHERE device_id=? AND status='succeeded' ORDER BY finished_at DESC,id DESC LIMIT 1",[$id]);
            return ['device'=>$device,'sections'=>$sections,'revision'=>$lastChange['id']??null,'retry_after'=>5,'server_time'=>gmdate('Y-m-d H:i:s')];
        });
    }
}
