<?php
declare(strict_types=1);

function deviceInspectionCases(array &$tests,ControlCenter\Application $app,array $owner,array $alpha,array $beta,array $tenantA,callable $enrolled):void {
    $inspect=new ControlCenter\DeviceInspection($app->db,$app->management,$app->commands);
    $tests['online device filtering includes connected idle but excludes offline and unenrolled records']=function()use($app,$alpha,$beta,$tenantA,$enrolled):void {
        $ids=[];
        foreach(['active','idle','offline','unenrolled'] as $kind){
            [$d]=$enrolled($alpha,$tenantA['id']);$ids[$kind]=$d['id'];
            $app->db->query("UPDATE devices SET label=?,activity_state=?,last_seen_at=UTC_TIMESTAMP() WHERE id=?",['ONLINE-FILTER-FIXTURE-'.$kind,$kind==='idle'?'idle':'active',$d['id']]);
        }
        $app->db->query('UPDATE devices SET last_seen_at=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 HOUR) WHERE id=?',[$ids['offline']]);
        $app->db->query('UPDATE devices SET enrolled_at=NULL WHERE id=?',[$ids['unenrolled']]);
        $filter=['search'=>'ONLINE-FILTER-FIXTURE-','presence'=>'online','visibility'=>'all'];
        $result=$app->management->listing($alpha,'devices',$filter);
        same(2,$result['total']);same(1,$result['pages']);$found=array_column($result['items'],'presence','id');same('online',$found[$ids['active']]);same('idle',$found[$ids['idle']]);
        $all=$app->management->listing($alpha,'devices',['presence'=>'online']);same($all['total'],(int)$app->management->overview($alpha)['connected']);
        same([],$app->management->listing($alpha,'devices',$filter+['page'=>2])['items']);
        same(0,$app->management->listing($beta,'devices',$filter)['total']);
        problem(422,fn()=>$app->management->listing($alpha,'devices',['presence'=>'invalid']));
    };
    $tests['automatic inspection deduplicates viewers and bounds queued reads']=function()use($app,$inspect,$owner,$alpha,$tenantA,$enrolled):void {
        [$d]=$enrolled($alpha,$tenantA['id']);$id=$d['id'];
        $app->db->query("UPDATE devices SET last_seen_at=UTC_TIMESTAMP(),activity_state='active',capabilities=? WHERE id=?",[json_encode(ControlCenter\DeviceInspection::READS),$id]);
        $a=$inspect->observe($alpha,$id,['section'=>'playback']);$b=$inspect->observe($owner,$id,['section'=>'sources']);
        same(2,(int)$app->db->query('SELECT COUNT(*) FROM commands WHERE device_id=?',[$id])->fetchColumn());
        same($a['sections']['get_settings']['request_id'],$b['sections']['get_settings']['request_id']);
        same('syncing',$a['sections']['get_settings']['state']);
        $app->db->query("UPDATE commands SET status='succeeded',finished_at=UTC_TIMESTAMP() WHERE device_id=?",[$id]);
        $c=$inspect->observe($alpha,$id,['section'=>'sources']);same('current',$c['sections']['get_settings']['state']);
        same(4,(int)$app->db->query('SELECT COUNT(*) FROM commands WHERE device_id=?',[$id])->fetchColumn());
    };
    $tests['inspection respects tenant isolation offline state and read allowlist']=function()use($app,$inspect,$alpha,$beta,$tenantA,$enrolled):void {
        [$d]=$enrolled($alpha,$tenantA['id']);$id=$d['id'];
        $app->db->query('UPDATE devices SET last_seen_at=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 HOUR),capabilities=? WHERE id=?',[json_encode(ControlCenter\DeviceInspection::READS),$id]);
        $r=$inspect->observe($alpha,$id,[]);same('offline',$r['sections']['get_settings']['state']);
        same(0,(int)$app->db->query('SELECT COUNT(*) FROM commands WHERE device_id=?',[$id])->fetchColumn());
        problem(404,fn()=>$inspect->observe($beta,$id,[]));
        problem(422,fn()=>$inspect->observe($alpha,$id,['section'=>'restart_app']));
        problem(422,fn()=>$inspect->observe($alpha,$id,['scope'=>['source_ref'=>'bad']]));
    };
    $tests['inspection keeps source snapshots separate and reuses the selected source read']=function()use($app,$inspect,$alpha,$tenantA,$enrolled):void {
        [$d]=$enrolled($alpha,$tenantA['id']);$id=$d['id'];
        $app->db->query("UPDATE devices SET last_seen_at=UTC_TIMESTAMP(),activity_state='active',capabilities=? WHERE id=?",[json_encode(['get_category_layout']),$id]);
        $a=['profile_ref'=>str_repeat('a',64),'source_ref'=>str_repeat('b',64)];
        $b=['profile_ref'=>str_repeat('a',64),'source_ref'=>str_repeat('c',64)];
        $first=$inspect->observe($alpha,$id,['section'=>'channels','scope'=>$a]);
        $firstId=$first['sections']['get_category_layout']['request_id'];
        $app->db->query("UPDATE commands SET status='succeeded',finished_at=UTC_TIMESTAMP() WHERE id=?",[$firstId]);
        $second=$inspect->observe($alpha,$id,['section'=>'channels','scope'=>$b]);
        same(null,$second['sections']['get_category_layout']['snapshot_id']);
        $again=$inspect->observe($alpha,$id,['section'=>'channels','scope'=>$b]);
        same($second['sections']['get_category_layout']['request_id'],$again['sections']['get_category_layout']['request_id']);
        same($firstId,$inspect->observe($alpha,$id,['section'=>'channels','scope'=>$a])['sections']['get_category_layout']['snapshot_id']);
        same(2,(int)$app->db->query('SELECT COUNT(*) FROM commands WHERE device_id=?',[$id])->fetchColumn());
    };
    $tests['automatic inspection backs off failures and never reads restricted devices']=function()use($app,$inspect,$alpha,$tenantA,$enrolled):void {
        [$d]=$enrolled($alpha,$tenantA['id']);$id=$d['id'];
        $app->db->query("UPDATE devices SET last_seen_at=UTC_TIMESTAMP(),activity_state='active',capabilities=? WHERE id=?",[json_encode(['get_settings']),$id]);
        $inspect->observe($alpha,$id,[]);
        $app->db->query("UPDATE commands SET status='failed',finished_at=UTC_TIMESTAMP() WHERE device_id=?",[$id]);
        same('unavailable',$inspect->observe($alpha,$id,[])['sections']['get_settings']['state']);
        same(1,(int)$app->db->query('SELECT COUNT(*) FROM commands WHERE device_id=?',[$id])->fetchColumn());
        $app->db->query("UPDATE devices SET policy='suspended' WHERE id=?",[$id]);
        same('restricted',$inspect->observe($alpha,$id,[])['sections']['get_settings']['state']);
    };
}
