<?php
declare(strict_types=1);
if(PHP_SAPI!=='cli'){http_response_code(404);exit;}
require dirname(__DIR__).'/src/bootstrap.php';
try{
    if(count($argv)!==3)throw new RuntimeException('Use the standalone renewal-worker connection script.');
    $root=dirname(__DIR__);$config=new ControlCenter\Config($root);$connection=json_decode(file_get_contents($argv[2]),true,8,JSON_THROW_ON_ERROR);
    if(!is_array($connection))throw new RuntimeException('Invalid private connection document.');
    $values=ControlCenter\RenewalWorkerConnection::configuration($config->values,$connection);$preview=new ControlCenter\Config($root,$values);$app=new ControlCenter\Application($preview);
    $report=ControlCenter\RenewalWorkerConnection::probe($app,$preview);
    $out="<?php\ndeclare(strict_types=1);\nreturn ".var_export($values,true).";\n";
    if(file_put_contents($argv[1],$out)!==strlen($out)||($check=require $argv[1])!==$values)throw new RuntimeException('Connection could not be staged; original retained.');
    echo json_encode($report,JSON_PRETTY_PRINT|JSON_THROW_ON_ERROR)."\n";
}catch(Throwable $error){fwrite(STDERR,$error instanceof PDOException?'Database operation failed; private values omitted.':($error instanceof ControlCenter\Problem?$error->getMessage():'Standalone worker check failed; private values omitted.'));fwrite(STDERR,"\n");exit(1);}
