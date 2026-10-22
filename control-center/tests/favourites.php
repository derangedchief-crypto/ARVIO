<?php
declare(strict_types=1);

function favouriteCases(array &$tests,ControlCenter\Application $app,array $owner,array $alpha,array $beta,array $tenantA,HttpFixture $http):void
{
    $fixture=static function()use($app,$owner,$alpha,$tenantA):array {$c=$app->management->createCustomer($alpha,['tenant_id'=>$tenantA['id'],'name'=>'TEST favourites']);$d=$app->management->createDevice($alpha,['customer_id'=>$c['id'],'label'=>'TEST recovery']);$token=$app->commands->issueTestCredential($owner,$d['id']);$app->commands->checkIn($token,['capabilities'=>['backup_favourites','preview_favourites_restore','restore_favourites']],'127.0.0.91');return [$d,$token];};
    $snapshot=static function(string $suffix='1'):array {return ['schema_version'=>1,'profile_ref'=>str_repeat('a',64),'source_ref'=>str_repeat('b',64),'revision'=>hash('sha256','rev'.$suffix),'content_hash'=>hash('sha256','content'.$suffix),'entries'=>[
        ['kind'=>'channel','ref'=>hash('sha256','one'.$suffix),'identity'=>'list_1:xtream:'.$suffix,'label'=>'TEST Channel '.$suffix,'available'=>true],
        ['kind'=>'group','ref'=>hash('sha256','group'.$suffix),'identity'=>'TEST News','label'=>'TEST News','available'=>true]],'reason'=>'automatic'];};
    $preview=static function(string $backupId,array $doc,array $missing=[]):array {return ['schema_version'=>1,'backup_id'=>$backupId,'profile_ref'=>$doc['profile_ref'],'source_ref'=>$doc['source_ref'],'expected_revision'=>str_repeat('d',64),'missing_refs'=>$missing,
        'matched_channels'=>in_array($doc['entries'][0]['ref'],$missing,true)?0:1,'matched_groups'=>in_array($doc['entries'][1]['ref'],$missing,true)?0:1,'current_channels'=>0,'current_groups'=>0,'backup_channels'=>1,'backup_groups'=>1];};
    $tests['nonempty favourites versions are encrypted protected and never replaced by customer emptiness'] = function()use($app,$owner,$alpha,$beta,$fixture,$snapshot):void {
        [$d,$token]=$fixture();$doc=$snapshot();$saved=$app->favourites->capture($token,$doc);same(true,$saved['stored']);same(true,$saved['protected']);
        $row=$app->db->one('SELECT * FROM favourites_backups WHERE id=?',[$saved['backup_id']]);check(!str_contains($row['payload_cipher'],'list_1:xtream'));check(!str_contains($row['payload_cipher'],'TEST Channel'));
        $empty=$doc;$empty['entries']=[];$empty['revision']=str_repeat('e',64);$empty['content_hash']=str_repeat('f',64);
        same(false,$app->favourites->capture($token,$empty)['stored']);$list=$app->favourites->listing($alpha,$d['id'],[]);same(1,$list['total']);same(0,$list['live'][0]['channel_count']);same(0,$list['live'][0]['group_count']);
        $view=$app->favourites->view($alpha,$d['id'],$saved['backup_id']);same(2,count($view['entries']));check(!isset($view['entries'][0]['identity'],$view['payload_cipher']));
        problem(404,fn()=> $app->favourites->view($beta,$d['id'],$saved['backup_id']));
        same($saved['backup_id'],$app->favourites->capture($token,$doc)['backup_id']);same(1,$app->favourites->listing($alpha,$d['id'],[])['total']);
    };
    $tests['recovery previews are scope and lease bound; missing entries prohibit destructive replace'] = function()use($app,$alpha,$beta,$fixture,$snapshot,$preview):void {
        [$d,$token]=$fixture();$doc=$snapshot();$saved=$app->favourites->capture($token,$doc);$id=$saved['backup_id'];
        $c=$app->favourites->queuePreview($alpha,$d['id'],$id,bin2hex(random_bytes(16)));$delivery=$app->commands->checkIn($token,['capabilities'=>['preview_favourites_restore','restore_favourites']],'127.0.0.91')['commands'][0];
        $p=$preview($id,$doc,[$doc['entries'][0]['ref']]);
        $bad=$p;$bad['backup_id']=str_repeat('0',32);problem(422,fn()=> $app->commands->acknowledge($token,$c['id'],['status'=>'succeeded','lease_token'=>$delivery['lease_token'],'favourites_preview'=>$bad]));
        $ack=$app->commands->acknowledge($token,$c['id'],['status'=>'succeeded','lease_token'=>$delivery['lease_token'],'favourites_preview'=>$p]);check(!isset($ack['result']['favourites_preview']['missing_refs']));same(1,$ack['result']['favourites_preview']['missing_count']);
        problem(409,fn()=> $app->favourites->queueRestore($alpha,$d['id'],$id,['preview_id'=>$c['id'],'mode'=>'replace'],bin2hex(random_bytes(16))));
        $restore=$app->favourites->queueRestore($alpha,$d['id'],$id,['preview_id'=>$c['id'],'mode'=>'merge'],bin2hex(random_bytes(16)));same('restore_favourites',$restore['kind']);
        $context=$app->favourites->context($token,['preview_id'=>$c['id']]);same('list_1:xtream:1',$context['backup']['entries'][0]['identity']);same(1,count($context['preview']['missing_refs']));
        problem(404,fn()=> $app->favourites->preview($beta,$d['id'],$id));
        $app->db->query('UPDATE favourites_restore_previews SET expires_at=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 SECOND) WHERE command_id=?',[$c['id']]);
        problem(409,fn()=> $app->favourites->context($token,['preview_id'=>$c['id']]));
    };
    $tests['device credentials cannot read another devices backup and removed reseller loses all recovery access'] = function()use($app,$owner,$alpha,$beta,$fixture,$snapshot):void {
        [$d,$token]=$fixture();[$other,$otherToken]=$fixture();$saved=$app->favourites->capture($token,$snapshot());
        problem(404,fn()=> $app->favourites->context($otherToken,['backup_id'=>$saved['backup_id']]));
        problem(404,fn()=> $app->commands->enqueue($alpha,$other['id'],['kind'=>'preview_favourites_restore','payload'=>['backup_id'=>$saved['backup_id']]],bin2hex(random_bytes(16))));
        $app->management->detachReseller($owner,$d['id']);problem(404,fn()=> $app->favourites->listing($alpha,$d['id'],[]));same(1,$app->favourites->listing($owner,$d['id'],[])['total']);
    };
    $tests['backup protection limits and retention cannot auto-delete protected good versions'] = function()use($app,$alpha,$fixture,$snapshot):void {
        [$d,$token]=$fixture();$first=$app->favourites->capture($token,$snapshot('1'));$saved=[$first['backup_id']];
        for($i=2;$i<=6;$i++){$doc=$snapshot((string)$i);$doc['reason']='manual';$r=$app->favourites->capture($token,$doc);$saved[]=$r['backup_id'];if($i<=5)$app->favourites->protect($alpha,$d['id'],$r['backup_id'],['protected'=>true,'label'=>'TEST approved']);}
        problem(409,fn()=> $app->favourites->protect($alpha,$d['id'],$saved[5],['protected'=>true]));
        $app->db->query('UPDATE favourites_backups SET last_seen_at=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 366 DAY) WHERE device_id=?',[$d['id']]);$app->favourites->maintain();
        check($app->db->one('SELECT id FROM favourites_backups WHERE id=?',[$first['backup_id']])!==null);same(5,$app->favourites->listing($alpha,$d['id'],[])['total']);
    };
    $tests['favourites HTTP capture and recovery routes authenticate and protect mutations from CSRF'] = function()use($app,$alpha,$beta,$fixture,$snapshot,$http):void {
        [$d,$token]=$fixture();$doc=$snapshot();same(401,$http->request('POST','/api/device/favourites/capture',$doc)['status']);
        $r=$http->request('POST','/api/device/favourites/capture',$doc,['Authorization'=>'Bearer '.$token]);same(200,$r['status']);$id=$r['body']['backup_id'];
        $a=$http->request('GET','/api/me',null,[],'settings-alpha')['body'];$b=$http->request('GET','/api/me',null,[],'settings-beta')['body'];
        same(404,$http->request('GET','/api/devices/'.$d['id'].'/favourites',null,[],'settings-beta')['status']);
        same(403,$http->request('POST','/api/devices/'.$d['id'].'/favourites/backups/'.$id.'/protect',['protected'=>false],[],'settings-alpha')['status']);
        same(200,$http->request('POST','/api/devices/'.$d['id'].'/favourites/backups/'.$id.'/protect',['protected'=>true,'label'=>'HTTP protected'],['X-CSRF-Token'=>$a['csrf']],'settings-alpha')['status']);
        $view=$http->request('GET','/api/devices/'.$d['id'].'/favourites/backups/'.$id,null,[],'settings-alpha');same(200,$view['status']);check(!str_contains($view['raw'],'list_1:xtream:1'));check(!str_contains($view['raw'],'payload_cipher'));
    };
}
