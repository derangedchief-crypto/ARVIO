<?php
declare(strict_types=1);
if (PHP_SAPI !== 'cli') exit(1);
require dirname(__DIR__) . '/src/bootstrap.php';
$input = json_decode(file_get_contents('php://stdin'), true, 16, JSON_THROW_ON_ERROR);
$app = new ControlCenter\Application(new ControlCenter\Config(dirname(__DIR__)));
if (!$app->config->local() || !str_contains($app->config->get('database')['dsn'], 'dbname=control_test_')) exit(2);
try {
    if($input['operation']==='recovery-register'){
        $r=$app->enrollment->register($input['body'],'127.0.0.94');
        echo json_encode(['status'=>200,'enrollment_status'=>$r['status'],'request_id'=>$r['request_id'],'recovery_state'=>$r['recovery_state']??null],JSON_THROW_ON_ERROR);exit;
    }
    if($input['operation']==='module-redeem') {
        $result=$app->moduleLicences->redeem($input['actor'],['key'=>$input['key']]);
        echo json_encode(['status'=>200,'id'=>$result['licence']['id'],'expires_at'=>$result['licence']['expires_at'],'revision'=>$result['licence']['revision']],JSON_THROW_ON_ERROR);exit;
    }
    $result = $input['operation'] === 'provision'
        ? $app->management->createDevice($input['actor'], ['customer_id'=>$input['customer_id'], 'label'=>'Concurrent fixture'])
        : $app->commands->enqueue($input['actor'], $input['device_id'], ['kind'=>'request_checkin'], $input['key']);
    echo json_encode(['status'=>201,'id'=>$result['id']], JSON_THROW_ON_ERROR);
} catch (ControlCenter\Problem $error) { echo json_encode(['status'=>$error->status]); }
