<?php
declare(strict_types=1);
// Install this exact fixed command as the SSH account's forced command. Never
// evaluate SSH_ORIGINAL_COMMAND, shell fragments, filenames or command arguments.
if(PHP_SAPI!=='cli'){http_response_code(404);exit;}
require dirname(__DIR__).'/src/bootstrap.php';
try {
    $raw=stream_get_contents(STDIN,16385);if(strlen($raw)>16384)throw new RuntimeException('Oversized builder command.');
    $request=json_decode($raw,true,15,JSON_THROW_ON_ERROR);if(!is_array($request))throw new RuntimeException('Invalid builder command.');\ControlCenter\Input::keys($request,['action','input']);
    if(!is_array($request['input']??null)||!in_array($request['action']??null,['claim','heartbeat','prepared','authorize','finish','fail','reconcile'],true))throw new RuntimeException('Unsupported builder command.');
    $config=new \ControlCenter\Config(dirname(__DIR__));$app=new \ControlCenter\Application($config);
    $result=$app->brandBuilder->{$request['action']}($request['input']);echo json_encode($result,JSON_UNESCAPED_SLASHES|JSON_UNESCAPED_UNICODE|JSON_THROW_ON_ERROR)."\n";
}catch(Throwable $error){fwrite(STDERR,"Central builder command refused or failed. Review private policy/job state.\n");exit(1);}
