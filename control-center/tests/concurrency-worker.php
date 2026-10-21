<?php
declare(strict_types=1);
if (PHP_SAPI !== 'cli') exit(1);
require dirname(__DIR__) . '/src/bootstrap.php';
$input = json_decode(file_get_contents('php://stdin'), true, 16, JSON_THROW_ON_ERROR);
$app = new ControlCenter\Application(new ControlCenter\Config(dirname(__DIR__)));
if (!$app->config->local() || !str_contains($app->config->get('database')['dsn'], 'dbname=control_test_')) exit(2);
try {
    $result = $input['operation'] === 'provision'
        ? $app->management->createDevice($input['actor'], ['customer_id'=>$input['customer_id'], 'label'=>'Concurrent fixture'])
        : $app->commands->enqueue($input['actor'], $input['device_id'], ['kind'=>'request_checkin'], $input['key']);
    echo json_encode(['status'=>201,'id'=>$result['id']], JSON_THROW_ON_ERROR);
} catch (ControlCenter\Problem $error) { echo json_encode(['status'=>$error->status]); }
