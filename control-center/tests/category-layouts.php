<?php
declare(strict_types=1);

function layoutCases(array &$tests,ControlCenter\Application $app,array $owner,array $alpha,array $beta,array $tenantA,array $tenantB,HttpFixture $http,string $password):void
{
    $fixture=static function()use($app,$owner,$alpha,$tenantA):array {
        $c=$app->management->createCustomer($alpha,['tenant_id'=>$tenantA['id'],'name'=>'TEST whole layout']);$d=$app->management->createDevice($alpha,['customer_id'=>$c['id'],'label'=>'TEST batch editor']);$token=$app->commands->issueTestCredential($owner,$d['id']);
        $app->commands->checkIn($token,['capabilities'=>['get_category_layout','apply_category_layout','request_checkin']],'127.0.0.81');return [$d,$token];
    };
    $catalog=static function(int $count=60):array {
        $data=['schema_version'=>1,'profile_ref'=>str_repeat('a',64),'source_ref'=>str_repeat('b',64),'revision'=>str_repeat('c',64),'page'=>1,'pages'=>1,'page_size'=>2000,'total'=>$count,'provider_order'=>true,'order_editable'=>true,
            'sources'=>[['ref'=>str_repeat('b',64),'label'=>'Playlist 1','total'=>$count]],'items'=>[]];
        for($i=1;$i<=$count;$i++)$data['items'][]=['ref'=>hash('sha256','layout-'.$i),'label'=>'TEST Category '.$i,'match_name'=>'TEST Category '.$i,'channels'=>$i,'hidden'=>false,'position'=>$i,'provider_position'=>$i];
        return $data;
    };
    $read=static function(array $d,string $token,array $data)use($app,$alpha):array {
        $c=$app->commands->enqueue($alpha,$d['id'],['kind'=>'get_category_layout'],bin2hex(random_bytes(16)));
        $delivery=$app->commands->checkIn($token,['capabilities'=>['get_category_layout','apply_category_layout','request_checkin']],'127.0.0.81')['commands'][0];
        return $app->commands->acknowledge($token,$c['id'],['status'=>'succeeded','lease_token'=>$delivery['lease_token'],'catalog'=>$data]);
    };
    $tests['complete layout snapshots are separate bounded private caches, not huge command-history responses'] = function()use($app,$owner,$alpha,$beta,$fixture,$catalog,$read):void {
        [$d,$token]=$fixture();$r=$read($d,$token,$catalog());check(!isset($r['result']['catalog']));same(60,$r['result']['layout_summary']['total']);
        $state=$app->layouts->device($alpha,$d['id'],[]);same(60,count($state['catalog']['items']));
        $meta=$app->layouts->device($alpha,$d['id'],['metadata'=>'1']);same(null,$meta['catalog']);check(strlen(json_encode($meta))<10000);
        problem(404,fn()=> $app->layouts->device($beta,$d['id'],[]));
        for($i=0;$i<3;$i++)$read($d,$token,$catalog());
        same(3,(int)$app->db->query('SELECT COUNT(*) FROM category_layout_snapshots WHERE device_id=?',[$d['id']])->fetchColumn());
        $app->management->detachReseller($owner,$d['id']);problem(404,fn()=> $app->layouts->device($alpha,$d['id'],[]));same(60,$app->layouts->device($owner,$d['id'],[])['catalog']['total']);
    };
    $tests['one complete push is idempotent, exclusive on delivery and summarized in history'] = function()use($app,$alpha,$beta,$fixture,$catalog):void {
        [$d,$token]=$fixture();$data=$catalog(2000);$refs=array_column($data['items'],'ref');$payload=['profile_ref'=>$data['profile_ref'],'source_ref'=>$data['source_ref'],'expected_revision'=>$data['revision'],'order'=>array_reverse($refs),'hidden'=>$refs,'provider_order'=>false];
        $key=bin2hex(random_bytes(16));$c=$app->commands->enqueue($alpha,$d['id'],['kind'=>'apply_category_layout','payload'=>$payload],$key);$again=$app->commands->enqueue($alpha,$d['id'],['kind'=>'apply_category_layout','payload'=>$payload],$key);same($c['id'],$again['id']);
        check(!isset($c['payload']->order));same(2000,$c['payload']->order_count);
        problem(404,fn()=> $app->commands->enqueue($beta,$d['id'],['kind'=>'apply_category_layout','payload'=>$payload],bin2hex(random_bytes(16))));
        $normal=$app->commands->enqueue($alpha,$d['id'],['kind'=>'request_checkin'],bin2hex(random_bytes(16)));
        $app->db->query('UPDATE commands SET created_at=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 2 SECOND) WHERE id=?',[$c['id']]);
        $deliver=$app->commands->checkIn($token,['capabilities'=>['get_category_layout','apply_category_layout','request_checkin']],'127.0.0.81');same(1,count($deliver['commands']));same($c['id'],$deliver['commands'][0]['id']);same(2000,count($deliver['commands'][0]['payload']->order));check(strlen(json_encode($deliver))<524288);
        same(15,$deliver['heartbeat_seconds']);
        $app->commands->acknowledge($token,$c['id'],['status'=>'succeeded','lease_token'=>$deliver['commands'][0]['lease_token'],'summary'=>'TEST one atomic layout receipt']);
        same($normal['id'],$app->commands->checkIn($token,['capabilities'=>['request_checkin']],'127.0.0.81')['commands'][0]['id']);
        check(strlen(json_encode($app->commands->listing($alpha,$d['id'],[])))<20000);
    };
    $tests['layout arrays reject duplicates partial uploads unknown fields and unsafe template names'] = function()use($catalog):void {
        $data=$catalog(2);$refs=array_column($data['items'],'ref');$p=['profile_ref'=>$data['profile_ref'],'source_ref'=>$data['source_ref'],'expected_revision'=>$data['revision'],'order'=>$refs,'hidden'=>[]];
        foreach([['order'=>[$refs[0],$refs[0]]],['hidden'=>[str_repeat('f',64)]],['order'=>[]],['source_url'=>'https://forbidden.test'],['provider_order'=>null]] as $bad)problem(422,fn()=> ControlCenter\CommandCategories::payload('apply_category_layout',array_replace($p,$bad)));
        $bad=$data;$bad['items'][0]['match_name']='https://forbidden.test';problem(422,fn()=> ControlCenter\CommandCategories::catalog('get_category_layout',$bad));
        $bad=$data;$bad['items'][1]['provider_position']=1;problem(422,fn()=> ControlCenter\CommandCategories::catalog('get_category_layout',$bad));
        $bad=$data;$bad['items']=array_slice($bad['items'],0,1);problem(422,fn()=> ControlCenter\CommandCategories::catalog('get_category_layout',$bad));
    };
    $tests['large full layouts fit authenticated HTTP limits and ACK only small metadata'] = function()use($app,$alpha,$fixture,$catalog,$http):void {
        [$d,$token]=$fixture();$data=$catalog(2000);
        foreach($data['items'] as $i=>&$item){$name=str_repeat('😀',76).str_pad((string)$i,4,'0',STR_PAD_LEFT);$item['label']=$name;$item['match_name']=$name;}unset($item);
        $c=$app->commands->enqueue($alpha,$d['id'],['kind'=>'get_category_layout'],bin2hex(random_bytes(16)));$delivery=$app->commands->checkIn($token,['capabilities'=>['get_category_layout']],'127.0.0.81')['commands'][0];
        $body=['status'=>'succeeded','lease_token'=>$delivery['lease_token'],'catalog'=>$data];$bytes=strlen(json_encode($body,JSON_UNESCAPED_UNICODE));check($bytes>1000000&&$bytes<2097152);
        same(401,$http->request('POST','/api/device/commands/'.$c['id'].'/ack',$body)['status']);
        $result=$http->request('POST','/api/device/commands/'.$c['id'].'/ack',$body,['Authorization'=>'Bearer '.$token]);same(200,$result['status']);check(strlen($result['raw'])<65536);check(!isset($result['body']['result']['catalog']));
        same(2000,$app->layouts->device($alpha,$d['id'],[])['catalog']['total']);
    };
    $tests['portable templates are tenant scoped versioned and never contain device refs'] = function()use($app,$owner,$alpha,$beta,$tenantA,$tenantB):void {
        $entries=[['name'=>'TEST News','hidden'=>true],['name'=>'TEST Sports','hidden'=>false]];
        $t=$app->layouts->create($alpha,['name'=>'TEST Template <img src=x>','entries'=>$entries]);$full=$app->layouts->get($alpha,$t['id']);same($tenantA['id'],$full['tenant_id']);same($entries,$full['entries']);
        problem(404,fn()=> $app->layouts->get($beta,$t['id']));problem(404,fn()=> $app->layouts->change($beta,$t['id'],['expected_version'=>1,'name'=>'No']));
        problem(404,fn()=> $app->layouts->listing($beta,['tenant_id'=>$tenantA['id']]));
        problem(422,fn()=> $app->layouts->create($alpha,['name'=>'Bad','entries'=>[['name'=>'password=private','hidden'=>true]]]));
        problem(422,fn()=> $app->layouts->create($alpha,['name'=>'Bad','entries'=>[$entries[0],$entries[0]]]));
        $changed=$app->layouts->change($alpha,$t['id'],['expected_version'=>1,'name'=>'Changed']);same(2,$changed['version']);
        problem(409,fn()=> $app->layouts->change($alpha,$t['id'],['expected_version'=>1,'entries'=>$entries]));
        $copy=$app->layouts->create($owner,['tenant_id'=>$tenantB['id'],'name'=>'Explicit owner copy','entries'=>$full['entries']]);same($entries,$app->layouts->get($beta,$copy['id'])['entries']);
        $app->layouts->change($alpha,$t['id'],['expected_version'=>2],true);problem(404,fn()=> $app->layouts->get($alpha,$t['id']));
    };
    $tests['layout/template HTTP writes require CSRF and never reveal another devices snapshot'] = function()use($app,$owner,$alpha,$beta,$tenantA,$tenantB,$fixture,$catalog,$read,$http,$password):void {
        [$d,$token]=$fixture();$r=$read($d,$token,$catalog(2));
        // Reuse current authenticated synthetic fixtures to avoid login throttles.
        $a=$http->request('GET','/api/me',null,[],'settings-alpha')['body'];$b=$http->request('GET','/api/me',null,[],'settings-beta')['body'];
        same(404,$http->request('GET','/api/devices/'.$d['id'].'/category-layout',null,[],'settings-beta')['status']);
        same(200,$http->request('GET','/api/devices/'.$d['id'].'/category-layout?command_id='.$r['id'],null,[],'settings-alpha')['status']);
        $data=$catalog(2);$payload=['profile_ref'=>$data['profile_ref'],'source_ref'=>$data['source_ref'],'expected_revision'=>$data['revision'],'order'=>array_column($data['items'],'ref'),'hidden'=>[]];
        same(403,$http->request('POST','/api/devices/'.$d['id'].'/category-layout',$payload,['Idempotency-Key'=>bin2hex(random_bytes(16))],'settings-alpha')['status']);
        same(201,$http->request('POST','/api/devices/'.$d['id'].'/category-layout',$payload,['X-CSRF-Token'=>$a['csrf'],'Idempotency-Key'=>bin2hex(random_bytes(16))],'settings-alpha')['status']);
        same(403,$http->request('POST','/api/category-layout-templates',['name'=>'CSRF','entries'=>[['name'=>'TEST','hidden'=>false]]],[],'settings-alpha')['status']);
        $t=$http->request('POST','/api/category-layout-templates',['name'=>'HTTP template','entries'=>[['name'=>'TEST','hidden'=>false]]],['X-CSRF-Token'=>$a['csrf']],'settings-alpha');same(201,$t['status']);
        same(404,$http->request('GET','/api/category-layout-templates/'.$t['body']['id'],null,[],'settings-beta')['status']);
        same(404,$http->request('POST','/api/category-layout-templates/'.$t['body']['id'].'/archive',['expected_version'=>1],['X-CSRF-Token'=>$b['csrf']],'settings-beta')['status']);
    };
}
