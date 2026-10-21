<?php
declare(strict_types=1);

function testPhpArguments(string $file): array
{
    $args = [PHP_BINARY, '-n', '-d', 'extension_dir=' . ini_get('extension_dir')];
    foreach (['ctype','fileinfo','mbstring','pdo','mysqlnd','pdo_mysql','curl'] as $extension) array_push($args, '-d', 'extension=' . $extension);
    $args[] = $file;
    return $args;
}
function concurrentOperations(string $fixture, array $input): array
{
    $processes = [];
    for ($i=0; $i<2; $i++) {
        $pipes=[];
        $process=proc_open(testPhpArguments($fixture.'/tests/concurrency-worker.php'), [0=>['pipe','r'],1=>['pipe','w'],2=>['file',$fixture.'/workers.log','a']],$pipes,$fixture);
        if (!is_resource($process)) throw new RuntimeException('Could not start concurrency fixture');
        fwrite($pipes[0], json_encode($input,JSON_THROW_ON_ERROR)); fclose($pipes[0]);
        $processes[]=[$process,$pipes[1]];
    }
    $results=[];
    foreach($processes as [$process,$stdout]) {
        $raw=stream_get_contents($stdout); fclose($stdout); $status=proc_close($process);
        if($status!==0) throw new RuntimeException('Concurrency fixture failed: '. $raw);
        $results[]=json_decode($raw,true,16,JSON_THROW_ON_ERROR);
    }
    return $results;
}

function operationalCases(array &$tests, ControlCenter\Application $app, ControlCenter\Config $config, PDO $server, string $name, string $socket, string $dbUser, string $fixture, string $password, array $owner, array $alpha, array $tenantA, callable $makeDevice, callable $enrolled, array $snapshot): void
{
    mkdir($fixture.'/tests',0700);
    copy(__DIR__.'/concurrency-worker.php',$fixture.'/tests/concurrency-worker.php');
    $tests['device contract rejects unsupported schema before executing or acknowledging commands'] = function () use ($app,$alpha,$tenantA,$enrolled,$snapshot): void {
        [$device,$token]=$enrolled($alpha,$tenantA['id']);
        $command=$app->commands->enqueue($alpha,$device['id'],['kind'=>'request_checkin'],ControlCenter\Database::id());
        problem(422,fn()=> $app->commands->checkIn($token,array_replace($snapshot,['schema_version'=>2]),'127.0.0.1'));
        same('pending',$app->commands->listing($alpha,$device['id'],[])['items'][0]['status']);
        $response=$app->commands->checkIn($token,array_replace($snapshot,['schema_version'=>1]),'127.0.0.1'); same(1,$response['schema_version']);
        $lease=$response['commands'][0]['lease_token'];
        problem(422,fn()=> $app->commands->acknowledge($token,$command['id'],['schema_version'=>2,'lease_token'=>$lease,'status'=>'succeeded']));
        same('succeeded',$app->commands->acknowledge($token,$command['id'],['schema_version'=>1,'lease_token'=>$lease,'status'=>'succeeded'])['status']);
    };
    $tests['reseller pagination does not impose a silent 200-account ceiling'] = function () use ($app,$owner,$alpha): void {
        for($i=0;$i<27;$i++) $app->db->query("INSERT INTO tenants (id,name,kind) VALUES (?,?,'reseller')",[ControlCenter\Database::id(),'Paging fixture '.$i]);
        $first=$app->management->tenantListing($owner,[]); $second=$app->management->tenantListing($owner,['page'=>2]);
        same(25,count($first['items'])); check($first['total']>=30&&count($second['items'])>0); same(1,$app->management->tenantListing($alpha,[])['total']);
    };
    $tests['concurrent provisioning cannot exceed reseller allocation'] = function () use ($app,$owner,$password,$fixture): void {
        $email='race-'.ControlCenter\Database::id().'@example.test';
        $tenant=$app->management->createReseller($owner,['company'=>'Race fixture','name'=>'Race','email'=>$email,'password'=>$password,'device_limit'=>1]);
        $actor=$app->auth->login(['email'=>$email,'password'=>$password],'127.0.0.20')['user'];
        $customer=$app->management->createCustomer($actor,['name'=>'Race customer']);
        $results=concurrentOperations($fixture,['operation'=>'provision','actor'=>$actor,'customer_id'=>$customer['id']]);
        $statuses=array_column($results,'status'); sort($statuses); same([201,409],$statuses);
        same(1,(int)$app->db->query('SELECT COUNT(*) FROM devices WHERE tenant_id=?',[$tenant['id']])->fetchColumn());
    };
    $tests['concurrent duplicate command requests create one queued action'] = function () use ($app,$alpha,$tenantA,$enrolled,$fixture): void {
        [$device]=$enrolled($alpha,$tenantA['id']);
        $results=concurrentOperations($fixture,['operation'=>'enqueue','actor'=>$alpha,'device_id'=>$device['id'],'key'=>ControlCenter\Database::id()]);
        same(201,$results[0]['status']); same(201,$results[1]['status']); same($results[0]['id'],$results[1]['id']);
        same(1,(int)$app->db->query('SELECT COUNT(*) FROM commands WHERE device_id=?',[$device['id']])->fetchColumn());
    };
    $tests['delivery attempts are bounded and exhausted leases expire'] = function () use ($app,$alpha,$tenantA,$enrolled,$snapshot): void {
        [$device,$token]=$enrolled($alpha,$tenantA['id']);
        $command=$app->commands->enqueue($alpha,$device['id'],['kind'=>'reload_epg'],ControlCenter\Database::id());
        for($i=0;$i<3;$i++) { same(1,count($app->commands->checkIn($token,$snapshot,'127.0.0.1')['commands'])); $app->db->query('UPDATE commands SET lease_until=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 SECOND) WHERE id=?',[$command['id']]); }
        same([],$app->commands->checkIn($token,$snapshot,'127.0.0.1')['commands']);
        same('expired',$app->commands->listing($alpha,$device['id'],[])['items'][0]['status']);
    };
    $tests['modified applied migrations are rejected without erasing records'] = function () use ($app,$config,$fixture): void {
        $file=$fixture.'/database/migrations/001_foundation.sql'; $original=file_get_contents($file);
        $count=(int)$app->db->query('SELECT COUNT(*) FROM customers')->fetchColumn();
        try { file_put_contents($file,$original."\n-- tampered fixture\n"); problem(503,fn()=> (new ControlCenter\Migrations($app->db,$config))->apply()); }
        finally { file_put_contents($file,$original); }
        same($count,(int)$app->db->query('SELECT COUNT(*) FROM customers')->fetchColumn());
    };
    $tests['retention removes only expired sessions and old final records'] = function () use ($app,$alpha,$tenantA,$enrolled): void {
        [$device]=$enrolled($alpha,$tenantA['id']); $pending=$app->commands->enqueue($alpha,$device['id'],['kind'=>'reload_epg'],ControlCenter\Database::id());
        $old=$app->commands->enqueue($alpha,$device['id'],['kind'=>'request_checkin'],ControlCenter\Database::id());
        $app->db->query("UPDATE commands SET status='failed',finished_at=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 100 DAY) WHERE id=?",[$old['id']]);
        $app->db->query('INSERT INTO sessions (token_hash,user_id,expires_at) VALUES (?,?,DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 SECOND))',[hash('sha256','expired synthetic token'),$alpha['id']]);
        $result=$app->maintain(); check($result['old_commands_removed']>=1); check($result['expired_sessions_removed']>=1);
        check($app->db->one('SELECT id FROM commands WHERE id=?',[$pending['id']])!==null);
        same(null,$app->db->one('SELECT id FROM commands WHERE id=?',[$old['id']]));
    };
    $tests['remote SQL rejects an unavailable verification CA before attempting a connection'] = function () use ($fixture): void {
        $bad=new ControlCenter\Config($fixture,['database'=>['dsn'=>'mysql:host=not-a-real-db.invalid;port=3306;dbname=fixture;charset=utf8mb4','username'=>'fixture','password'=>'not-real','ssl_ca'=>$fixture.'/missing-ca.pem']]);
        problem(503,fn()=> new ControlCenter\Database($bad));
    };
    $tests['database backup restores schema, records, migrations and encrypted data with original key'] = function () use ($app,$server,$name,$socket,$dbUser,$fixture,$config): void {
        $tools=getenv('CONTROL_TOOLS_ROOT')?:'/tmp/opencode/control-center-tools/root';
        $dump=$tools.'/usr/bin/mariadb-dump'; $client=$tools.'/usr/bin/mariadb';
        if(!is_executable($dump)||!is_executable($client)) throw new RuntimeException('Install the user-space mariadb-client package for the real restore test');
        $cipher=(new ControlCenter\Secrets($config))->encrypt('synthetic-provider-password');
        $app->db->query("INSERT INTO audit_events (action,metadata) VALUES ('fixture.backup',?)",[json_encode(['cipher'=>$cipher],JSON_THROW_ON_ERROR)]);
        $file=$fixture.'/private-backup.sql';
        $environment=array_merge(getenv(),['LD_LIBRARY_PATH'=>$tools.'/usr/lib/x86_64-linux-gnu']);
        $pipes=[]; $process=proc_open([$dump,'--no-defaults','--socket='.$socket,'--user='.$dbUser,'--single-transaction','--skip-comments',$name],
            [0=>['file','/dev/null','r'],1=>['file',$file,'w'],2=>['file',$fixture.'/backup.log','a']],$pipes,null,$environment);
        if(!is_resource($process)||proc_close($process)!==0) throw new RuntimeException('Database dump failed');
        check(!str_contains(file_get_contents($file),'synthetic-provider-password'));
        check(!str_contains(file_get_contents($file),$config->secret('application.key')));
        $restore=$name.'_restore';
        $server->exec("CREATE DATABASE `$restore` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        try {
            $process=proc_open([$client,'--no-defaults','--socket='.$socket,'--user='.$dbUser,$restore],
                [0=>['file',$file,'r'],1=>['file',$fixture.'/restore.log','a'],2=>['file',$fixture.'/restore.log','a']],$pipes,null,$environment);
            if(!is_resource($process)||proc_close($process)!==0) throw new RuntimeException('Database restore failed');
            $settings=$config->values; $settings['database']['dsn']=str_replace('dbname='.$name.';','dbname='.$restore.';',$settings['database']['dsn']);
            $restoredConfig=new ControlCenter\Config($fixture,$settings); $restored=new ControlCenter\Application($restoredConfig);
            same([], (new ControlCenter\Migrations($restored->db,$restoredConfig))->apply());
            foreach(['tenants','users','customers','devices','commands'] as $table) same((int)$app->db->query("SELECT COUNT(*) FROM $table")->fetchColumn(),(int)$restored->db->query("SELECT COUNT(*) FROM $table")->fetchColumn());
            $row=$restored->db->one("SELECT metadata FROM audit_events WHERE action='fixture.backup' ORDER BY id DESC LIMIT 1");
            same('synthetic-provider-password',(new ControlCenter\Secrets($restoredConfig))->decrypt(json_decode($row['metadata'],true)['cipher']));
        } finally { $server->exec("DROP DATABASE `$restore`"); }
    };
}
