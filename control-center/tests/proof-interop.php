<?php
declare(strict_types=1);
require dirname(__DIR__).'/src/bootstrap.php';
if(PHP_SAPI!=='cli')exit(1);
$root=getenv('CONTROL_INTEROP_ROOT');
if(!$root||!str_starts_with($root,'/tmp/opencode/'))exit(2);
if(!is_dir($root.'/storage'))mkdir($root.'/storage',0700,true);
if(!is_file($root.'/storage/application.key')) { file_put_contents($root.'/storage/application.key',str_repeat('ab',32)); chmod($root.'/storage/application.key',0600); }
$config=new ControlCenter\Config($root,['origin'=>'https://control.example.test','environment'=>'testing']);
$proof=new ControlCenter\EnrollmentProof($config,new ControlCenter\Secrets($config));
if(($argv[1]??'')==='key') { echo $proof->bridgeSecret(); exit; }
if(($argv[1]??'')==='bridge-signature') {
    $input=json_decode(stream_get_contents(STDIN,4096),true,8,JSON_THROW_ON_ERROR);
    echo ControlCenter\PanelBridge::signature($proof->bridgeSecret(),$input['domain'],$input['timestamp'],$input['nonce'],$input['payload']);exit;
}
$token=trim(stream_get_contents(STDIN,4097));
$claim=$proof->verify($token,1000);
echo json_encode(['issuer'=>$claim['iss'],'sub'=>$claim['sub'],'install'=>$claim['install'],'key'=>$claim['key'],'package'=>$claim['package'],'challenge'=>$claim['challenge'],'panel'=>$claim['panel']??null],JSON_THROW_ON_ERROR);
