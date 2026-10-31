<?php
declare(strict_types=1);
if(PHP_SAPI!=='cli'){http_response_code(404);exit;}
require dirname(__DIR__).'/src/bootstrap.php';
try{
    if(count($argv)!==3)throw new RuntimeException('Run the website catalogue connection helper.');
    $root=dirname(__DIR__);$config=new ControlCenter\Config($root);
    $connection=json_decode(file_get_contents($argv[2]),true,8,JSON_THROW_ON_ERROR);
    if(!is_array($connection))throw new RuntimeException('Invalid private connection document.');
    $values=ControlCenter\RenewalCatalogueConnection::configuration($config->values,$connection);
    $preview=new ControlCenter\Config($root,$values);$app=new ControlCenter\Application($preview);
    $policy=$app->renewalSettings->policy();
    if($policy['enabled']){
        $owner=$app->db->one("SELECT * FROM users WHERE role='owner' AND active=1 ORDER BY id LIMIT 1")??throw new RuntimeException('Active owner required.');
        $app->renewalSettings->setPolicy($owner,['enabled'=>false,'testing_only'=>$policy['testing_only']]);
    }
    $report=ControlCenter\RenewalCatalogueConnection::probe($app,$preview);
    $out="<?php\ndeclare(strict_types=1);\nreturn ".var_export($values,true).";\n";
    if(file_put_contents($argv[1],$out)!==strlen($out)||($check=require $argv[1])!==$values)throw new RuntimeException('Catalogue configuration could not be staged; original retained.');
    echo json_encode($report,JSON_PRETTY_PRINT|JSON_THROW_ON_ERROR)."\n";
}catch(Throwable $error){fwrite(STDERR,$error instanceof PDOException?'Database operation failed; check private server configuration.':($error instanceof ControlCenter\Problem||$error instanceof RuntimeException?$error->getMessage():'Catalogue connection check failed; original configuration retained.'));fwrite(STDERR,"\n");exit(1);}
