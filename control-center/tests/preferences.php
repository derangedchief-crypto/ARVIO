<?php
declare(strict_types=1);
function preferenceCases(array &$tests,ControlCenter\Application $app,array $owner,array $alpha,array $beta,array $tenantA,array $tenantB):void {
    $fixture=function()use($app,$owner,$alpha,$tenantA):array {
        $c=$app->management->createCustomer($alpha,['tenant_id'=>$tenantA['id'],'name'=>'TEST preferences']);$d=$app->management->createDevice($alpha,['customer_id'=>$c['id'],'label'=>'TEST preferences']);$token=$app->commands->issueTestCredential($owner,$d['id']);$caps=['get_preferences','apply_preferences'];$app->commands->checkIn($token,['capabilities'=>$caps],'127.0.0.82');
        $command=$app->commands->enqueue($alpha,$d['id'],['kind'=>'get_preferences'],bin2hex(random_bytes(16)));$lease=$app->commands->checkIn($token,['capabilities'=>$caps],'127.0.0.82')['commands'][0]['lease_token'];
        $values=[];$origins=[];foreach(ControlCenter\Preferences::RULES as $key=>$choices){$values[$key]=$choices[0];$origins[$key]='local';}
        $doc=['schema_version'=>1,'profile_ref'=>str_repeat('a',64),'revision'=>str_repeat('b',64),'values'=>$values,'origins'=>$origins,'base_values'=>[],'overrides'=>[],'base_preset_id'=>'','base_version'=>0];
        $app->preferences->capture($token,['command_id'=>$command['id'],'lease_token'=>$lease,'document'=>$doc]);$app->commands->acknowledge($token,$command['id'],['status'=>'succeeded','lease_token'=>$lease]);return [$d,$token,$doc,$command['id']];
    };
    $tests['broader settings profiles are versioned scoped and reject arbitrary preference keys'] = function()use($app,$alpha,$beta,$tenantA):void {
        $p=$app->preferences->save($alpha,null,['tenant_id'=>$tenantA['id'],'name'=>'TEST broad profile','values'=>['clock_format'=>'24h','default_subtitle'=>'English']]);same(1,$p['version']);
        problem(404,fn()=> $app->preferences->save($beta,$p['id'],['name'=>'No','values'=>[],'expected_version'=>1]));
        problem(422,fn()=> $app->preferences->save($alpha,null,['name'=>'No','values'=>['update_url'=>'https://other.test']]));
        $next=$app->preferences->save($alpha,$p['id'],['name'=>'TEST broad v2','values'=>['clock_format'=>'12h'],'expected_version'=>1]);same(2,$next['version']);
        problem(409,fn()=> $app->preferences->save($alpha,$p['id'],['name'=>'Stale','values'=>[],'expected_version'=>1]));
    };
    $tests['effective settings capture preview and confirmed override commands remain device and tenant scoped'] = function()use($app,$owner,$alpha,$beta,$tenantA,$tenantB,$fixture):void {
        [$d,$token,$doc,$snapshot]=$fixture();[$other,$otherToken]=$fixture();
        $p=$app->preferences->save($alpha,null,['name'=>'TEST apply base','values'=>['clock_format'=>'24h']]);
        $preview=$app->preferences->preview($alpha,$d['id'],['snapshot_id'=>$snapshot,'base_preset_id'=>$p['id'],'overrides'=>['clock_format'=>'12h','auto_play_next'=>'false']]);same('device_profile_override',$preview['changes'][3]['origin']);
        problem(404,fn()=> $app->preferences->latest($beta,$d['id']));
        problem(409,fn()=> $app->commands->enqueue($alpha,$other['id'],['kind'=>'apply_preferences','payload'=>['draft_id'=>$preview['id']]],bin2hex(random_bytes(16))));
        $command=$app->commands->enqueue($alpha,$d['id'],['kind'=>'apply_preferences','payload'=>['draft_id'=>$preview['id']]],bin2hex(random_bytes(16)));$delivery=$app->commands->checkIn($token,['capabilities'=>['get_preferences','apply_preferences']],'127.0.0.82')['commands'][0];$ctx=['command_id'=>$command['id'],'lease_token'=>$delivery['lease_token']];
        problem(409,fn()=> $app->preferences->context($otherToken,$ctx));$context=$app->preferences->context($token,$ctx);same('12h',((array)$context['target']['overrides'])['clock_format']);
        problem(422,fn()=> $app->commands->acknowledge($token,$command['id'],['status'=>'succeeded','lease_token'=>$delivery['lease_token']]));
        $after=$doc;$after['base_values']=['clock_format'=>'24h'];$after['overrides']=['clock_format'=>'12h','auto_play_next'=>'false'];$after['base_preset_id']=$p['id'];$after['base_version']=1;$after['values']['clock_format']='12h';$after['origins']['clock_format']='device_profile_override';$after['origins']['auto_play_next']='device_profile_override';
        $app->preferences->capture($token,$ctx+['document'=>$after]);$app->commands->acknowledge($token,$command['id'],['status'=>'succeeded','lease_token'=>$delivery['lease_token']]);
        $app->management->detachReseller($owner,$d['id']);problem(404,fn()=> $app->preferences->latest($alpha,$d['id']));check($app->preferences->latest($owner,$d['id'])['snapshot']!==null);
    };
    $tests['expanded settings snapshots are closed and reject raw secrets and malformed origins'] = function():void {
        problem(422,fn()=> ControlCenter\Preferences::values(['default_subtitle'=>'private-password']));problem(422,fn()=> ControlCenter\Preferences::values(['source_url'=>'https://private.test']));
    };
}
