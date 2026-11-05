<?php
declare(strict_types=1);

function liveConnectionCases(array &$tests,ControlCenter\Application $app,ControlCenter\Config $config):void {
    $tests['live notifications wait for outer commit and discard rolled-back savepoints']=function()use($app):void {
        $events=[];$db=$app->db;
        $db->transaction(function()use($db,&$events):void {
            $db->afterCommit('first',function()use(&$events){$events[]='first';});
            try{$db->transaction(function()use($db,&$events):void {
                $db->afterCommit('first',function()use(&$events){$events[]='wrong replacement';});
                $db->afterCommit('rolled-back',function()use(&$events){$events[]='rolled-back';});
                throw new RuntimeException('rollback');
            });}catch(RuntimeException){}
            $db->transaction(function()use($db,&$events):void {$db->afterCommit('second',function()use(&$events){$events[]='second';});});
            same([],$events);
        });
        same(['first','second'],$events);
        try{$db->transaction(function()use($db,&$events):void {$db->afterCommit('never',function()use(&$events){$events[]='never';});throw new RuntimeException('rollback');});}catch(RuntimeException){}
        $db->transaction(fn()=>null);same(['first','second'],$events);
    };
    $tests['notification failure cannot turn committed mutation into failed request']=function()use($app):void {
        same('saved',$app->db->transaction(function()use($app){$app->db->afterCommit('unavailable',fn()=>throw new RuntimeException('synthetic relay outage'));return 'saved';}));
    };
    $tests['live tickets are short-lived device scoped and contain no app credential']=function()use($config):void {
        $file=$config->root.'/storage/live-connection.key';
        check(!file_exists($file));$live=new ControlCenter\LiveConnection($config);
        same(['schema_version'=>1,'enabled'=>false],$live->ticket([]));
        $key=bin2hex(random_bytes(32));file_put_contents($file,$key);chmod($file,0600);
        try {
            $d=['id'=>str_repeat('a',32),'tenant_id'=>str_repeat('b',32),'credential'=>'never transmit this'];
            $result=$live->ticket($d);same(true,$result['enabled']);same($d['id'],$result['device_id']);
            [$body,$signature]=explode('.',$result['ticket']);
            same(hash_hmac('sha256',$body,hex2bin($key)),$signature);
            $claims=json_decode(base64_decode(strtr($body,'-_','+/')),true,16,JSON_THROW_ON_ERROR);
            same($d['id'],$claims['device']);same($d['tenant_id'],$claims['tenant']);
            check($claims['exp']>time()&&$claims['exp']<=time()+300);check(!str_contains(json_encode($claims),'credential'));
            check($live->ticket($d)['ticket']!==$result['ticket']);
            file_put_contents($file,'broken');problem(503,fn()=>$live->ticket($d));
        } finally {unlink($file);}
    };
    $tests['live hints target only affected device or workspace and exclude reads and acknowledgments']=function():void {
        $d=str_repeat('a',32);$t=str_repeat('b',32);$command=str_repeat('c',32);
        same(['device'=>$d],ControlCenter\LiveConnection::target('command.queued',$command,$t,['device_id'=>$d]));
        same(['device'=>$d],ControlCenter\LiveConnection::target('device.updated',$d,$t,[]));
        same(['tenant'=>$t],ControlCenter\LiveConnection::target('reseller.updated',$t,$t,[]));
        same(null,ControlCenter\LiveConnection::target('support.artifact_downloaded',$command,$t,[]));
        same(null,ControlCenter\LiveConnection::target('command.succeeded',$command,$t,['device_id'=>$d]));
        same(null,ControlCenter\LiveConnection::target('command.queued',$command,$t,[]));
    };
}
