<?php
declare(strict_types=1);
function currentActivityCases(array &$tests,ControlCenter\Application $app,array $owner,array $alpha,array $beta,array $tenantA,callable $enrolled):void {
    $command=function(string $device,string $kind,string $status,int $hours=0)use($app,$alpha,$tenantA):string {
        $id=ControlCenter\Database::id();
        $app->db->query("INSERT INTO commands (id,device_id,tenant_id,requested_by,idempotency_key,request_hash,kind,payload,status,created_at,finished_at,expires_at) VALUES (?,?,?,?,?,?,?,'{}',?,DATE_SUB(UTC_TIMESTAMP(),INTERVAL ? HOUR),DATE_SUB(UTC_TIMESTAMP(),INTERVAL ? HOUR),DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR))",[$id,$device,$tenantA['id'],$alpha['id'],ControlCenter\Database::id(),str_repeat('a',64),$kind,$status,$hours,$hours]);
        return $id;
    };
    $tests['current commands exclude old logs and retain full scoped history']=function()use($app,$alpha,$beta,$tenantA,$enrolled,$command):void {
        [$d]=$enrolled($alpha,$tenantA['id']);$old=$command($d['id'],'get_sources','failed',48);
        same(null,$app->commands->currentState($alpha,$d['id'])['current_request']);
        $recent=$command($d['id'],'get_sources','succeeded',1);same($recent,$app->commands->currentState($alpha,$d['id'])['current_request']['id']);
        same(1,$app->commands->listing($alpha,$d['id'],['view'=>'current'])['total']);same(2,$app->commands->listing($alpha,$d['id'],[])['total']);
        same($recent,$app->sources->listing($alpha,$d['id'])['current_request']['id']);same(null,$app->favourites->listing($alpha,$d['id'],[])['current_request']);
        problem(404,fn()=> $app->commands->listing($beta,$d['id'],['view'=>'current']));problem(422,fn()=> $app->commands->listing($alpha,$d['id'],['view'=>'invalid']));
        same('failed',$app->commands->get($alpha,$d['id'],$old)['status']);
    };
    $tests['active work takes priority regardless of age and paginates without hiding requests']=function()use($app,$alpha,$tenantA,$enrolled,$command):void {
        [$d]=$enrolled($alpha,$tenantA['id']);$command($d['id'],'get_sources','succeeded');
        for($i=0;$i<7;$i++)$command($d['id'],'get_sources','pending',48);
        $a=$app->commands->listing($alpha,$d['id'],['view'=>'current']);$b=$app->commands->listing($alpha,$d['id'],['view'=>'current','page'=>2]);
        same(7,$a['active_count']);same(2,$a['pages']);same(5,count($a['items']));same(2,count($b['items']));same(7,count(array_unique(array_column(array_merge($a['items'],$b['items']),'id'))));
        same('pending',$app->sources->listing($alpha,$d['id'])['current_request']['status']);
        $app->db->query("UPDATE commands SET expires_at=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 SECOND) WHERE device_id=? AND status='pending'",[$d['id']]);
        same(0,$app->commands->currentState($alpha,$d['id'])['active_count']);
    };
    $tests['current activity feed filters old events without deleting audit history']=function()use($app,$owner,$alpha,$beta,$tenantA):void {
        $id=ControlCenter\Database::id();$app->auth->audit($alpha,'test.old_activity',$id,$tenantA['id'],[]);
        $app->db->query('UPDATE audit_events SET created_at=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 48 HOUR) WHERE resource_id=?',[$id]);
        $current=$app->management->audit($alpha,['view'=>'current']);$history=$app->management->audit($alpha,[]);check($history['total']>$current['total']);
        foreach($current['items'] as $r)check($r['resource_id']!==$id);
        problem(404,fn()=> $app->management->audit($beta,['view'=>'current','tenant_id'=>$tenantA['id']]));
        same(1,(int)$app->db->query('SELECT COUNT(*) FROM audit_events WHERE resource_id=?',[$id])->fetchColumn());
    };
    $tests['current setup list keeps actionable work but not obsolete completed jobs']=function()use($app,$alpha,$beta,$tenantA,$enrolled):void {
        [$d]=$enrolled($alpha,$tenantA['id']);$p=$app->deviceProfiles->save($alpha,null,['name'=>'TEST current setup','prebuffer_seconds'=>'2']);
        $job=$app->deviceProfiles->applyExisting($alpha,$d['id'],['profile_id'=>$p['id'],'profile_version'=>1,'confirmed'=>true],ControlCenter\Database::id());
        $app->db->query("UPDATE device_setup_jobs SET status='complete',updated_at=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 48 HOUR) WHERE id=?",[$job['id']]);
        same(0,$app->deviceProfiles->jobs($alpha,['device_id'=>$d['id'],'view'=>'current'])['total']);same(1,$app->deviceProfiles->jobs($alpha,['device_id'=>$d['id']])['total']);
        foreach(['paused','needs_review','running','waiting'] as $status){$app->db->query('UPDATE device_setup_jobs SET status=?,updated_at=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 48 HOUR) WHERE id=?',[$status,$job['id']]);same(1,$app->deviceProfiles->jobs($alpha,['device_id'=>$d['id'],'view'=>'current'])['total']);}
        problem(404,fn()=> $app->deviceProfiles->jobs($beta,['device_id'=>$d['id'],'view'=>'current']));
    };
}
