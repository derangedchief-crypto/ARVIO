<?php
declare(strict_types=1);

function installationCases(array &$tests, ControlCenter\Application $app, ControlCenter\Config $config, PDO $server, string $name, string $fixture, string $password, array $owner): void
{
    $tests['database preflight rejects versions without the required constraint baseline'] = function (): void {
        foreach(['10.6.22-MariaDB','10.11.14-MariaDB','5.5.5-10.6.22-MariaDB','8.0.16','8.4.8'] as $version) check(ControlCenter\Database::supportedVersion($version));
        foreach(['10.5.28-MariaDB','5.7.44','8.0.15','unknown'] as $version) check(!ControlCenter\Database::supportedVersion($version));
    };
    $tests['diagnostic summary redaction handles quoted and multiword credentials'] = function (): void {
        foreach(['password: secret with spaces','"password":"quoted secret"',"token='quoted token value'",'Authorization: Bearer abc.def.ghi','activation_code=123456','https://provider.test/play?username=private&password=value'] as $value) {
            $redacted=ControlCenter\Secrets::redact($value);
            foreach(['secret with spaces','quoted secret','quoted token value','abc.def.ghi','123456','provider.test','password=value'] as $secret) check(!str_contains($redacted,$secret),'Unredacted secret in summary');
        }
    };
    $tests['missing application key fails closed rather than generating a replacement'] = function () use ($app,$config): void {
        $file=$config->privateFile('application.key'); $original=file_get_contents($file);
        unlink($file);
        try { problem(503,fn()=> new ControlCenter\Application($config)); check(!is_file($file),'Missing key was silently regenerated'); }
        finally { file_put_contents($file,$original); chmod($file,0600); }
    };
    $tests['CLI owner recovery revokes sessions while preserving customers and private key'] = function () use ($app,$config,$owner,$password): void {
        $session=$app->auth->login(['email'=>$owner['email'],'password'=>$password],'127.0.0.31');
        $count=(int)$app->db->query('SELECT COUNT(*) FROM customers')->fetchColumn(); $key=$config->secret('application.key');
        $new='Recovered-Test-Owner-Password-44!';
        try {
            $app->auth->recoverOwner($new);
            problem(401,fn()=> $app->auth->user($session['token']));
            same($owner['id'],$app->auth->login(['email'=>$owner['email'],'password'=>$new],'127.0.0.31')['user']['id']);
            same($count,(int)$app->db->query('SELECT COUNT(*) FROM customers')->fetchColumn()); same($key,$config->secret('application.key'));
        } finally { $app->auth->recoverOwner($password); }
    };
    $tests['unrelated populated database is refused without adding or deleting tables'] = function () use ($server,$name,$fixture,$config,$password): void {
        $dbName=$name.'_unrelated'; $server->exec("CREATE DATABASE `$dbName` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        try {
            $settings=$config->values; $settings['database']['dsn']=str_replace('dbname='.$name.';','dbname='.$dbName.';',$settings['database']['dsn']);
            $c=new ControlCenter\Config($fixture,$settings); $a=new ControlCenter\Application($c);
            $a->db->query('CREATE TABLE unrelated_customer_data (id INT PRIMARY KEY, value VARCHAR(40))');
            $a->db->query("INSERT INTO unrelated_customer_data VALUES (1,'keep me')");
            problem(409,fn()=> $a->auth->bootstrap($c->secret('setup.key'),['name'=>'Owner','email'=>'other@example.test','password'=>$password]));
            same(['unrelated_customer_data'],$a->db->query('SHOW TABLES')->fetchAll(PDO::FETCH_COLUMN));
            same('keep me',$a->db->query('SELECT value FROM unrelated_customer_data WHERE id=1')->fetchColumn());
        } finally { $server->exec("DROP DATABASE `$dbName`"); }
    };
    $tests['fresh website setup creates one owner without exposing its setup key'] = function () use ($server,$name,$fixture,$config,$password): void {
        $dbName=$name.'_install'; $server->exec("CREATE DATABASE `$dbName` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        $http=null;
        try {
            $fresh=$fixture.'/fresh-install'; mkdir($fresh,0700);
            foreach(['src','public','database'] as $dir) copyTree($config->root.'/'.$dir,$fresh.'/'.$dir);
            mkdir($fresh.'/storage',0700); mkdir($fresh.'/dev',0700);
            copy($config->root.'/dev/router.php',$fresh.'/dev/router.php'); copy($config->root.'/VERSION',$fresh.'/VERSION');
            $settings=$config->values; $settings['database']['dsn']=str_replace('dbname='.$name.';','dbname='.$dbName.';',$settings['database']['dsn']);
            $http=new HttpFixture($fresh,$settings); $c=new ControlCenter\Config($fresh,$settings);
            $r=$http->request('GET','/api/status'); same(200,$r['status']); same(false,$r['body']['installed']); check(!isset($r['body']['setup_key']));
            $body=['setup_key'=>str_repeat('0',64),'name'=>'Fresh owner','email'=>'fresh@example.test','password'=>$password];
            same(403,$http->request('POST','/api/setup',$body)['status']);
            $body['setup_key']=$c->secret('setup.key');
            same(201,$http->request('POST','/api/setup',$body)['status']);
            same(409,$http->request('POST','/api/setup',$body)['status']);
            same(true,$http->request('GET','/api/status')['body']['installed']);
            same(200,$http->request('POST','/api/login',['email'=>'fresh@example.test','password'=>$password],[],'fresh')['status']);
            check(is_file($fresh.'/storage/application.key'));
        } finally { if($http)$http->stop(); $server->exec("DROP DATABASE `$dbName`"); }
    };
}
