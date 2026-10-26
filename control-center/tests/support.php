<?php
declare(strict_types=1);
function supportCases(array &$tests,ControlCenter\Application $app,array $owner,array $alpha,array $beta,array $tenantA):void {
    $fixture=function(string $kind)use($app,$owner,$alpha,$tenantA):array {
        $c=$app->management->createCustomer($alpha,['tenant_id'=>$tenantA['id'],'name'=>'TEST support']);$d=$app->management->createDevice($alpha,['customer_id'=>$c['id'],'label'=>'TEST support']);$token=$app->commands->issueTestCredential($owner,$d['id']);$caps=['get_support_bundle','capture_app_screen'];$app->commands->checkIn($token,['capabilities'=>$caps],'127.0.0.83');$command=$app->commands->enqueue($alpha,$d['id'],['kind'=>$kind],bin2hex(random_bytes(16)));$delivery=$app->commands->checkIn($token,['capabilities'=>$caps],'127.0.0.83')['commands'][0];return [$d,$token,['command_id'=>$command['id'],'lease_token'=>$delivery['lease_token'],'profile_ref'=>str_repeat('a',64)]];
    };
    $png=static function(int $width=1,int $height=1):string {
        $chunk=static fn($type,$data)=>pack('N',strlen($data)).$type.$data.hash('crc32b',$type.$data,true);
        return "\x89PNG\r\n\x1a\n".$chunk('IHDR',pack('NNCCCCC',$width,$height,8,6,0,0,0)).$chunk('IDAT',gzcompress("\x00\x00\x00\x00\xff")).$chunk('IEND','');
    };
    $tests['private support bundles accept only typed diagnostics and fixed code history'] = function()use($app,$owner,$alpha,$beta,$fixture):void {
        [$d,$token,$ctx]=$fixture('get_support_bundle');
        $doc=['schema_version'=>1,'profile_ref'=>$ctx['profile_ref'],'snapshot'=>['schema_version'=>'1','profile_ref'=>$ctx['profile_ref'],'app_package'=>'com.arvio.tv.test','app_version'=>'2.0.112-test','sdk'=>'36','storage_total'=>'1000','storage_free'=>'900','channel_count'=>'4000','guide_state'=>'idle'],'events'=>[['code'=>'LIVE_RECOVERY','at_ms'=>time()*1000]]];
        $bad=$doc;$bad['events'][0]['code']='https://provider.test/password';problem(422,fn()=> $app->support->upload($token,$ctx+['kind'=>'bundle','document'=>$bad]));
        $bad=$doc;$bad['events'][0]['detail']='private password';problem(422,fn()=> $app->support->upload($token,$ctx+['kind'=>'bundle','document'=>$bad]));
        problem(422,fn()=> $app->commands->acknowledge($token,$ctx['command_id'],['status'=>'succeeded','lease_token'=>$ctx['lease_token']]));
        $saved=$app->support->upload($token,$ctx+['kind'=>'bundle','document'=>$doc]);$app->commands->acknowledge($token,$ctx['command_id'],['status'=>'succeeded','lease_token'=>$ctx['lease_token']]);
        $row=$app->db->one('SELECT payload_cipher FROM support_artifacts WHERE id=?',[$saved['id']]);check(!str_contains($row['payload_cipher'],'LIVE_RECOVERY'));same('application/json',$app->support->download($alpha,$d['id'],$saved['id'])['mime']);
        problem(404,fn()=> $app->support->download($beta,$d['id'],$saved['id']));$app->management->detachReseller($owner,$d['id']);problem(404,fn()=> $app->support->download($alpha,$d['id'],$saved['id']));same(1,count($app->support->listing($owner,$d['id'])['items']));
    };
    $tests['support panel images require consent static PNG bounds and no trailing content'] = function()use($app,$alpha,$fixture,$png):void {
        [$d,$token,$ctx]=$fixture('capture_app_screen');$image=$png();
        $body=$ctx+['kind'=>'panel','png_base64'=>base64_encode($image)];problem(422,fn()=> $app->support->upload($token,$body));
        same($image,ControlCenter\Support::png(base64_encode($image)));
        problem(422,fn()=> ControlCenter\Support::png(base64_encode($image.'private trailing data')));
        problem(422,fn()=> ControlCenter\Support::png(base64_encode($png(9999,9999))));
        problem(413,fn()=> ControlCenter\Support::png(str_repeat('a',175001)));
        $saved=$app->support->upload($token,$body+['consented'=>true]);same('image/png',$app->support->download($alpha,$d['id'],$saved['id'])['mime']);
        same($saved['id'],$app->support->listing($alpha,$d['id'])['current_items'][0]['id']);
        $app->db->query('UPDATE support_artifacts SET created_at=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 48 HOUR) WHERE id=?',[$saved['id']]);
        same([],$app->support->listing($alpha,$d['id'])['current_items']);same(1,count($app->support->listing($alpha,$d['id'])['items']));same('image/png',$app->support->download($alpha,$d['id'],$saved['id'])['mime']);
        $app->db->query('UPDATE support_artifacts SET expires_at=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 SECOND) WHERE id=?',[$saved['id']]);problem(404,fn()=> $app->support->download($alpha,$d['id'],$saved['id']));$app->support->maintain();same(0,count($app->support->listing($alpha,$d['id'])['items']));
    };
}
