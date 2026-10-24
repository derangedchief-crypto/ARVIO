<?php
declare(strict_types=1);
// Filename/loader regression checks: no database or preview servers required.
require dirname(__DIR__) . '/src/bootstrap.php';

use ControlCenter\Config;

function verify(bool $condition, string $label): void
{
    if (!$condition) throw new RuntimeException($label);
    echo 'PASS ', $label, "\n";
}
umask(0077);
$root = '/tmp/opencode/control-config-' . bin2hex(random_bytes(6));
mkdir($root,0700);
try {
    $stub = dirname(__DIR__) . '/tools/config.stub.php';
    copy($stub,$root.'/config.php');
    $config = new Config($root);
    verify($config->get('database')['username'] === 'sql_control_fecxbrfzku_net', 'config.php loads the prefilled database identifier');
    verify(!$config->configured(), 'blank production password shows the edit-config.php screen without connecting');
    $settings = $config->values;
    $settings['database']['password'] = 'synthetic-config-test-only';
    file_put_contents($root.'/config.php',"<?php return ".var_export($settings,true).';');
    verify((new Config($root))->configured(), 'filled config.php is the only required application configuration');
    verify(!file_exists($root.'/config.local.php') && !file_exists($root.'/config.example.php'), 'no local/example config file is generated');
    unlink($root.'/config.php');
    verify(!(new Config($root))->configured(), 'missing config.php remains safely unconfigured');
    $settings['environment']='testing'; $settings['origin']='http://127.0.0.1:9999'; $settings['database']['password']='';
    verify((new Config($root,$settings))->configured(), 'local socket test configuration remains supported');
    $reader=new Config($root,$settings);$key=$reader->secret('application.key');$path=$root.'/storage/application.key';
    verify((fileperms($path)&0777)===0600,'initial key creation remains owner-only');
    chmod($path,0400);clearstatcache(true,$path);
    verify($reader->secret('application.key')===$key&&(fileperms($path)&0777)===0400,'existing original key needs no write access or chmod');
    $secrets=new ControlCenter\Secrets($reader);$encrypted=$secrets->encrypt('synthetic-private-value');
    verify($secrets->decrypt($encrypted)==='synthetic-private-value','encryption and MAC readers use the same read-only original key');
    chmod($path,0640);clearstatcache(true,$path);$reader->secret('application.key',false);clearstatcache(true,$path);
    verify((fileperms($path)&0777)===0640,'reading preserves operator-provisioned shared read permissions');
    chmod($path,0600);file_put_contents($path,'');$refused=false;
    try{$reader->secret('application.key');}catch(ControlCenter\Problem $error){$refused=$error->status===503;}
    verify($refused&&file_get_contents($path)==='','empty existing keys fail closed and are never regenerated');
} finally {
    if(is_file($root.'/config.php'))unlink($root.'/config.php');
    if(is_file($root.'/storage/application.key'))unlink($root.'/storage/application.key');
    if(is_dir($root.'/storage'))rmdir($root.'/storage');
    rmdir($root);
}
