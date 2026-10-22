<?php
declare(strict_types=1);

function categoryCases(array &$tests,ControlCenter\Application $app,array $owner,array $alpha,array $beta,array $tenantA,HttpFixture $http):void
{
    $catalog=static function():array {
        return ['schema_version'=>1,'profile_ref'=>str_repeat('a',64),'source_ref'=>str_repeat('b',64),'revision'=>str_repeat('c',64),'page'=>1,'pages'=>1,'total'=>2,'order_editable'=>true,
            'sources'=>[['ref'=>str_repeat('b',64),'label'=>'Playlist 1','total'=>2]],'items'=>[
                ['ref'=>str_repeat('d',64),'label'=>'TEST News','channels'=>20,'hidden'=>false,'position'=>1],
                ['ref'=>str_repeat('e',64),'label'=>'TEST Sports','channels'=>30,'hidden'=>true,'position'=>2]]];
    };
    $device=static function(array $caps)use($app,$owner,$alpha,$tenantA):array {
        $c=$app->management->createCustomer($alpha,['tenant_id'=>$tenantA['id'],'name'=>'TEST categories']);$d=$app->management->createDevice($alpha,['customer_id'=>$c['id'],'label'=>'TEST category fixture']);
        $token=$app->commands->issueTestCredential($owner,$d['id']);$app->commands->checkIn($token,['capabilities'=>$caps],'127.0.0.71');return [$d,$token];
    };
    $tests['category commands accept only opaque scoped references and supported operations'] = function():void {
        same([],ControlCenter\CommandCategories::payload('get_categories',[]));
        foreach([['source_ref'=>str_repeat('a',64)],['profile_ref'=>str_repeat('a',64)],['page'=>41],['page'=>1.5],['url'=>'https://forbidden.test']] as $bad)problem(422,fn()=> ControlCenter\CommandCategories::payload('get_categories',$bad));
        $base=['profile_ref'=>str_repeat('a',64),'source_ref'=>str_repeat('b',64),'expected_revision'=>str_repeat('c',64),'category_ref'=>str_repeat('d',64),'operation'=>'hide'];
        same($base,ControlCenter\CommandCategories::payload('set_categories',$base));
        foreach([['operation'=>'delete_profile'],['source_ref'=>'https://forbidden.test'],['playlist_url'=>'https://forbidden.test'],['operation'=>'reset_order']] as $extra)problem(422,fn()=> ControlCenter\CommandCategories::payload('set_categories',array_replace($base,$extra)));
        unset($base['category_ref']);$base['operation']='reset_order';same($base,ControlCenter\CommandCategories::payload('set_categories',$base));
    };
    $tests['category snapshots are closed paged metadata and redact source-like labels'] = function()use($catalog):void {
        $valid=$catalog();same(2,ControlCenter\CommandCategories::catalog('get_categories',$valid)['total']);
        $bad=$valid;$bad['sources'][0]['url']='https://forbidden.test';problem(422,fn()=> ControlCenter\CommandCategories::catalog('get_categories',$bad));
        $bad=$valid;$bad['items'][0]['password']='forbidden';problem(422,fn()=> ControlCenter\CommandCategories::catalog('get_categories',$bad));
        $bad=$valid;$bad['items'][1]['ref']=$bad['items'][0]['ref'];problem(422,fn()=> ControlCenter\CommandCategories::catalog('get_categories',$bad));
        $bad=$valid;$bad['items'][0]['hidden']='false';problem(422,fn()=> ControlCenter\CommandCategories::catalog('get_categories',$bad));
        $bad=$valid;$bad['items'][0]['position']=9;problem(422,fn()=> ControlCenter\CommandCategories::catalog('get_categories',$bad));
        $bad=$valid;$bad['page']=2;problem(422,fn()=> ControlCenter\CommandCategories::catalog('get_categories',$bad));
        $bad=$valid;$bad['source_ref']=str_repeat('f',64);problem(422,fn()=> ControlCenter\CommandCategories::catalog('get_categories',$bad));
        $bad=$valid;$bad['items'][0]['label']='https://provider.test/path?password=synthetic-private';$redacted=ControlCenter\CommandCategories::catalog('get_categories',$bad);
        check(!str_contains(json_encode($redacted),'synthetic-private'));same('[redacted category label]',$redacted['items'][0]['label']);
        problem(422,fn()=> ControlCenter\CommandCategories::catalog('set_categories',$valid));
    };
    $tests['category read uploads require a valid current lease and match the requested source/profile'] = function()use($app,$owner,$alpha,$beta,$device,$catalog):void {
        [$d,$token]=$device(['get_categories','set_categories']);$data=$catalog();
        $c=$app->commands->enqueue($alpha,$d['id'],['kind'=>'get_categories','payload'=>['profile_ref'=>$data['profile_ref'],'source_ref'=>$data['source_ref'],'page'=>1]],bin2hex(random_bytes(16)));
        $delivery=$app->commands->checkIn($token,['capabilities'=>['get_categories','set_categories']],'127.0.0.71')['commands'][0];
        $input=['status'=>'succeeded','lease_token'=>$delivery['lease_token'],'catalog'=>$data];
        $bad=$input;$bad['lease_token']=str_repeat('0',64);problem(409,fn()=> $app->commands->acknowledge($token,$c['id'],$bad));
        $bad=$input;$bad['catalog']['profile_ref']=str_repeat('f',64);problem(422,fn()=> $app->commands->acknowledge($token,$c['id'],$bad));
        $missing=$input;unset($missing['catalog']);problem(422,fn()=> $app->commands->acknowledge($token,$c['id'],$missing));
        $r=$app->commands->acknowledge($token,$c['id'],$input);same('TEST News',$r['result']['catalog']['items'][0]['label']);
        problem(404,fn()=> $app->commands->listing($beta,$d['id'],[]));
        same('TEST Sports',$app->commands->listing($owner,$d['id'],[])['items'][0]['result']['catalog']['items'][1]['label']);
    };
    $tests['category writes are capability-gated and cannot claim a fabricated new read snapshot'] = function()use($app,$alpha,$beta,$device,$catalog):void {
        [$d,$token]=$device(['get_categories']);$data=$catalog();$payload=['profile_ref'=>$data['profile_ref'],'source_ref'=>$data['source_ref'],'expected_revision'=>$data['revision'],'category_ref'=>$data['items'][0]['ref'],'operation'=>'hide'];
        problem(409,fn()=> $app->commands->enqueue($alpha,$d['id'],['kind'=>'set_categories','payload'=>$payload],bin2hex(random_bytes(16))));
        $app->commands->checkIn($token,['capabilities'=>['get_categories','set_categories']],'127.0.0.71');
        problem(404,fn()=> $app->commands->enqueue($beta,$d['id'],['kind'=>'set_categories','payload'=>$payload],bin2hex(random_bytes(16))));
        $c=$app->commands->enqueue($alpha,$d['id'],['kind'=>'set_categories','payload'=>$payload],bin2hex(random_bytes(16)));
        $delivery=$app->commands->checkIn($token,['capabilities'=>['get_categories','set_categories']],'127.0.0.71')['commands'][0];
        problem(422,fn()=> $app->commands->acknowledge($token,$c['id'],['status'=>'succeeded','lease_token'=>$delivery['lease_token'],'catalog'=>$data]));
        $r=$app->commands->acknowledge($token,$c['id'],['status'=>'succeeded','lease_token'=>$delivery['lease_token'],'summary'=>'TEST mutation receipt only; request a fresh read']);
        same('succeeded',$r['status']);check(!isset($r['result']['catalog']));
    };
    $tests['category read chronology uses server receive precision and idempotent acknowledgments keep it unchanged'] = function()use($app,$alpha,$device,$catalog):void {
        [$d,$token]=$device(['get_categories']);$receipts=[];
        for($i=0;$i<2;$i++) {
            $c=$app->commands->enqueue($alpha,$d['id'],['kind'=>'get_categories'],bin2hex(random_bytes(16)));
            $delivery=$app->commands->checkIn($token,['capabilities'=>['get_categories']],'127.0.0.71')['commands'][0];
            $input=['status'=>'succeeded','lease_token'=>$delivery['lease_token'],'catalog'=>$catalog()];
            $r=$app->commands->acknowledge($token,$c['id'],$input);$receipts[]=$r['result']['received_at_us'];
            same($r['result']['received_at_us'],$app->commands->acknowledge($token,$c['id'],$input)['result']['received_at_us']);
        }
        check($receipts[1]>$receipts[0]);
    };
    $tests['worst-case bounded Unicode category pages fit the real HTTP upload limit'] = function()use($app,$alpha,$device,$catalog,$http):void {
        [$d,$token]=$device(['get_categories']);$data=$catalog();$data['total']=50;$data['sources']=[];$data['items']=[];
        for($i=1;$i<=32;$i++)$data['sources'][]=['ref'=>hash('sha256','source-'.$i),'label'=>'Playlist '.$i,'total'=>$i===1?50:0];$data['source_ref']=$data['sources'][0]['ref'];
        for($i=1;$i<=50;$i++)$data['items'][]=['ref'=>hash('sha256','category-'.$i),'label'=>str_repeat('😀',80),'channels'=>10000000,'hidden'=>false,'position'=>$i];
        ControlCenter\CommandCategories::catalog('get_categories',$data);
        $c=$app->commands->enqueue($alpha,$d['id'],['kind'=>'get_categories'],bin2hex(random_bytes(16)));$delivery=$app->commands->checkIn($token,['capabilities'=>['get_categories']],'127.0.0.71')['commands'][0];
        $body=['status'=>'succeeded','lease_token'=>$delivery['lease_token'],'catalog'=>$data];check(strlen(json_encode($body,JSON_UNESCAPED_UNICODE))<32768);
        $r=$http->request('POST','/api/device/commands/'.$c['id'].'/ack',$body,['Authorization'=>'Bearer '.$token]);same(200,$r['status']);same(50,count($r['body']['result']['catalog']['items']));check(strlen($r['raw'])<65536,'Response must also fit the Android management client limit.');
    };
}
