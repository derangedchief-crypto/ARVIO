<?php
declare(strict_types=1);
function sourceCases(array &$tests,ControlCenter\Application $app,array $owner,array $alpha,array $beta,array $tenantA):void {
    $fixture=function(bool $privateName=false,bool $privateSecondary=false)use($app,$owner,$alpha,$tenantA):array {
        $c=$app->management->createCustomer($alpha,['tenant_id'=>$tenantA['id'],'name'=>'TEST sources']);$d=$app->management->createDevice($alpha,['customer_id'=>$c['id'],'label'=>'TEST sources']);$token=$app->commands->issueTestCredential($owner,$d['id']);
        $caps=['get_sources','apply_sources'];$app->commands->checkIn($token,['capabilities'=>$caps],'127.0.0.81');
        $command=$app->commands->enqueue($alpha,$d['id'],['kind'=>'get_sources'],bin2hex(random_bytes(16)));$lease=$app->commands->checkIn($token,['capabilities'=>$caps],'127.0.0.81')['commands'][0]['lease_token'];
        $url='https://provider.test/get.php?username=fixture&password=private-secret';
        $doc=['schema_version'=>1,'profile_ref'=>str_repeat('a',64),'revision'=>str_repeat('b',64),'config'=>['m3uUrl'=>$url,'epgUrl'=>'','playlists'=>[['id'=>'list_1','name'=>'TEST Main','m3uUrl'=>$url,'epgUrl'=>'','enabled'=>true,'epgUrls'=>[]]],'stalkerPortals'=>[],'sortOrder'=>'provider']];
        if($privateName)$doc['config']['playlists'][0]['name']='private-secret';
        if($privateSecondary)$doc['config']['playlists'][]=['id'=>'secondary','name'=>'secondary-secret','m3uUrl'=>'https://secondary.test/get.php?username=secondary-user&password=secondary-secret','epgUrl'=>'','epgUrls'=>[],'enabled'=>true];
        $saved=$app->sources->capture($token,['command_id'=>$command['id'],'lease_token'=>$lease,'reason'=>'read','snapshot'=>$doc]);$app->commands->acknowledge($token,$command['id'],['status'=>'succeeded','lease_token'=>$lease]);
        return [$d,$token,$doc,$saved['id']];
    };
    $tests['private source snapshots are encrypted masked and tenant isolated'] = function()use($app,$alpha,$beta,$fixture):void {
        [$d,$token,$doc,$id]=$fixture();$view=$app->sources->view($alpha,$d['id'],$id);$text=json_encode($view);check(!str_contains($text,'private-secret'));check(!str_contains($text,'provider.test'));check(!str_contains($text,'list_1'));check(!isset($view['payload_cipher']));same(true,$view['entries'][0]['canonical']);
        $raw=$app->db->one('SELECT payload_cipher FROM source_snapshots WHERE id=?',[$id]);check(!str_contains($raw['payload_cipher'],'private-secret'));
        problem(404,fn()=> $app->sources->view($beta,$d['id'],$id));
    };
    $tests['full source drafts preserve canonical identity and scope device-only contexts to current lease'] = function()use($app,$alpha,$beta,$fixture):void {
        [$d,$token,$doc,$id]=$fixture();[$other,$otherToken]=$fixture();$entry=$app->sources->view($alpha,$d['id'],$id)['entries'][0];
        $rows=[['ref'=>$entry['ref'],'kind'=>'m3u','name'=>'TEST Renamed','enabled'=>true],['kind'=>'m3u','name'=>'TEST Extra','enabled'=>true,'xtream_host'=>'https://extra.test','xtream_username'=>'private-user','xtream_password'=>'private-password']];
        $preview=$app->sources->preview($alpha,$d['id'],['snapshot_id'=>$id,'entries'=>$rows]);check(!str_contains(json_encode($preview),'private-password'));same(2,count($preview['entries']));
        $bad=$rows;$bad[0]['replace_url']='https://different.test/list';problem(409,fn()=> $app->sources->preview($alpha,$d['id'],['snapshot_id'=>$id,'entries'=>$bad]));
        $bad=$rows;$bad[1]['replace_url']='file:///etc/passwd';unset($bad[1]['xtream_host'],$bad[1]['xtream_username'],$bad[1]['xtream_password']);problem(422,fn()=> $app->sources->preview($alpha,$d['id'],['snapshot_id'=>$id,'entries'=>$bad]));
        problem(404,fn()=> $app->sources->preview($beta,$d['id'],['snapshot_id'=>$id,'entries'=>$rows]));
        problem(409,fn()=> $app->commands->enqueue($alpha,$other['id'],['kind'=>'apply_sources','payload'=>['draft_id'=>$preview['id']]],bin2hex(random_bytes(16))));
        $command=$app->commands->enqueue($alpha,$d['id'],['kind'=>'apply_sources','payload'=>['draft_id'=>$preview['id']]],bin2hex(random_bytes(16)));$delivery=$app->commands->checkIn($token,['capabilities'=>['get_sources','apply_sources']],'127.0.0.81')['commands'][0];
        $ctx=['command_id'=>$command['id'],'lease_token'=>$delivery['lease_token']];problem(409,fn()=> $app->sources->context($otherToken,$ctx));
        $context=$app->sources->context($token,$ctx);same(2,count($context['target']['playlists']));check(str_contains($context['target']['playlists'][1]['m3uUrl'],'private-password'));
        $after=$doc;$after['config']=$context['target'];$after['revision']=str_repeat('c',64);
        problem(409,fn()=> $app->sources->capture($token,$ctx+['reason'=>'confirmation','snapshot'=>$after]));
        $safety=$app->sources->capture($token,$ctx+['reason'=>'pre_apply','snapshot'=>$doc]);check(isset($safety['id']));
        $app->sources->capture($token,$ctx+['reason'=>'confirmation','snapshot'=>$after]);$app->commands->acknowledge($token,$command['id'],['status'=>'succeeded','lease_token'=>$delivery['lease_token']]);
        $rollback=$app->sources->preview($alpha,$d['id'],['snapshot_id'=>$id,'rollback_id'=>$id]);same(1,count($rollback['entries']));
    };
    $tests['source previews reject stale baselines and preserve protected checkpoints beyond command retention'] = function()use($app,$alpha,$fixture):void {
        [$d,$token,$doc,$id]=$fixture();$view=$app->sources->view($alpha,$d['id'],$id);$row=$view['entries'][0];
        $app->db->query('UPDATE source_snapshots SET created_at=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 DAY) WHERE id=?',[$id]);problem(409,fn()=> $app->sources->preview($alpha,$d['id'],['snapshot_id'=>$id,'entries'=>[['ref'=>$row['ref'],'kind'=>'m3u','name'=>'TEST Main','enabled'=>true]]]));
        $app->db->query('DELETE FROM commands WHERE id=?',[$view['command_id']]);same($id,$app->sources->view($alpha,$d['id'],$id)['id']);
    };
    $tests['source labels containing actual credentials are masked without rewriting an unchanged private label'] = function()use($app,$alpha,$fixture):void {
        [$d,$token,$doc,$id]=$fixture(true);$view=$app->sources->view($alpha,$d['id'],$id);same('[private source label]',$view['entries'][0]['name']);
        $preview=$app->sources->preview($alpha,$d['id'],['snapshot_id'=>$id,'entries'=>[['ref'=>$view['entries'][0]['ref'],'kind'=>'m3u','name'=>$view['entries'][0]['name'],'enabled'=>true]]]);check(!str_contains(json_encode($preview),'private-secret'));
        $row=$app->db->one('SELECT payload_cipher FROM source_drafts WHERE id=?',[$preview['id']]);$plain=json_decode((new ControlCenter\Secrets($app->config))->decrypt($row['payload_cipher']),true,16,JSON_THROW_ON_ERROR);same('private-secret',$plain['playlists'][0]['name']);
    };
    $tests['replacing credentials cannot reveal a previously private supplementary source label'] = function()use($app,$alpha,$fixture):void {
        [$d,$token,$doc,$id]=$fixture(false,true);$view=$app->sources->view($alpha,$d['id'],$id);$rows=array_map(static fn($e)=>array_intersect_key($e,array_flip(['ref','kind','name','enabled'])),$view['entries']);same('[private source label]',$rows[1]['name']);$rows[1]['replace_url']='https://replacement.test/list.m3u';
        problem(422,fn()=> $app->sources->preview($alpha,$d['id'],['snapshot_id'=>$id,'entries'=>$rows]));$rows[1]['name']='TEST safe replacement label';$preview=$app->sources->preview($alpha,$d['id'],['snapshot_id'=>$id,'entries'=>$rows]);check(!str_contains(json_encode($preview),'secondary-secret'));same(true,$preview['entries'][1]['identity_changed']);
    };
}
