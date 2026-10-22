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
} finally {
    if(is_file($root.'/config.php'))unlink($root.'/config.php');
    rmdir($root);
}
