<?php
declare(strict_types=1);
// Synthetic local preview only. No live service or customer database is contacted.
if (PHP_SAPI !== 'cli') exit(1);
require dirname(__DIR__) . '/src/bootstrap.php';
use ControlCenter\Application;
use ControlCenter\Commands;
use ControlCenter\Config;

$source = dirname(__DIR__);
$base = '/tmp/opencode/control-center-preview-' . bin2hex(random_bytes(4));
umask(0077); mkdir($base,0700,true);
function copyPreview(string $source, string $dest): void {
    if (!is_dir($dest)) mkdir($dest,0700,true);
    foreach(new DirectoryIterator($source) as $item) { if($item->isDot())continue; $target=$dest.'/'.$item->getFilename(); if($item->isDir())copyPreview($item->getPathname(),$target); else copy($item->getPathname(),$target); }
}
foreach(['src','public','database'] as $dir)copyPreview($source.'/'.$dir,$base.'/'.$dir);
mkdir($base.'/storage',0700); mkdir($base.'/dev',0700);
copy($source.'/dev/router.php',$base.'/dev/router.php'); copy($source.'/VERSION',$base.'/VERSION');
$socket=getenv('CONTROL_TEST_SOCKET')?:'/tmp/opencode/control-center-dev/database.sock';
$user=getenv('CONTROL_TEST_USER')?:getenv('USER');
$name='control_demo_'.bin2hex(random_bytes(5));
$server=new PDO('mysql:unix_socket='.$socket.';charset=utf8mb4',$user,'',[PDO::ATTR_ERRMODE=>PDO::ERRMODE_EXCEPTION]);
$server->exec("CREATE DATABASE `$name` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
$port=(int)(getenv('CONTROL_PREVIEW_PORT')?:8186);
if($port<1024||$port>65535) throw new RuntimeException('Invalid local preview port');
$settings=['environment'=>'development','origin'=>'http://127.0.0.1:'.$port,'database'=>['dsn'=>"mysql:unix_socket=$socket;dbname=$name;charset=utf8mb4",'username'=>$user,'password'=>'']];
file_put_contents($base.'/config.php',"<?php\nreturn ".var_export($settings,true).";\n");
$config=new Config($base,$settings); $app=new Application($config);
$password=bin2hex(random_bytes(12));
$owner=$app->auth->bootstrap($config->secret('setup.key'),['name'=>'Local preview owner','email'=>'owner@preview.test','password'=>$password]);
$a=$app->management->createReseller($owner,['company'=>'DEMO · Northstar','name'=>'Demo reseller','email'=>'northstar@preview.test','password'=>bin2hex(random_bytes(12)),'device_limit'=>50]);
$b=$app->management->createReseller($owner,['company'=>'DEMO · Horizon','name'=>'Demo reseller','email'=>'horizon@preview.test','password'=>bin2hex(random_bytes(12)),'device_limit'=>25]);
$ownerTenant=$app->management->tenants($owner)[0];
$bindings=[];
foreach([[$ownerTenant,'TEST · Owner workspace','TEST simulator · Living room'],[$a,'TEST · Northstar customer','TEST simulator · Bedroom'],[$b,'TEST · Horizon customer','TEST simulator · Office']] as [$tenant,$customerName,$label]) {
    $customer=$app->management->createCustomer($owner,['tenant_id'=>$tenant['id'],'name'=>$customerName,'notes'=>'Synthetic local preview record only.']);
    $device=$app->management->createDevice($owner,['customer_id'=>$customer['id'],'label'=>$label,'notes'=>'No real Android device is connected.']);
    $token=$app->commands->issueTestCredential($owner,$device['id']);
    $bindings[]=['id'=>$device['id'],'token'=>$token];
}
$snapshot=['model'=>'LOCAL TEST SIMULATOR','os_version'=>'Synthetic — not Android','app_version'=>'development-simulator','activity_state'=>'active','capabilities'=>Commands::KINDS,'storage_total'=>17179869184,'storage_free'=>10737418240];
$app->commands->checkIn($bindings[0]['token'],$snapshot,'127.0.0.1');
$snapshot['activity_state']='idle'; $app->commands->checkIn($bindings[1]['token'],$snapshot,'127.0.0.1');
$app->db->query('UPDATE devices SET last_seen_at=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 20 MINUTE) WHERE id=?',[$bindings[2]['id']]);
// Explicit synthetic response for UI tests; not fetched from an Android device.
$settingsCommand=$app->commands->enqueue($owner,$bindings[0]['id'],['kind'=>'get_settings'],bin2hex(random_bytes(16)));
$settingsLease=$app->commands->checkIn($bindings[0]['token'],$snapshot,'127.0.0.1')['commands'][0];
$app->commands->acknowledge($bindings[0]['token'],$settingsCommand['id'],['status'=>'succeeded','lease_token'=>$settingsLease['lease_token'],'summary'=>'TEST SYNTHETIC SETTINGS SNAPSHOT — not from Android',
    'snapshot'=>['schema_version'=>'1','profile_ref'=>hash('sha256','SYNTHETIC PROFILE'),'settings_revision'=>hash('sha256','SYNTHETIC SETTINGS'),'sort_order'=>'provider','playlist_count'=>'1','enabled_playlist_count'=>'1','portal_count'=>'0','favourite_channel_count'=>'0','favourite_group_count'=>'0']]);
// A local synthetic issuer/request for exercising the OWNER review UI only.
// This is not evidence that a real activation worker or Android device connected.
$app->enrollment->setEnabled($owner,['enabled'=>true]);
$app->db->query('INSERT INTO panel_resellers (panel_id,tenant_id,username,linked_by) VALUES (?,?,?,?)',['700001',$ownerTenant['id'],'SYNTHETIC-preview-owner',$owner['id']]);
$private=openssl_pkey_new(['private_key_type'=>OPENSSL_KEYTYPE_EC,'curve_name'=>'prime256v1']);
$public=preg_replace('~-----[^\n]+-----|\s~','',openssl_pkey_get_details($private)['key']);
$fingerprint=hash('sha256',base64_decode($public)); $install=ControlCenter\Database::id();
$challenge=$app->enrollment->challenge(['installation_id'=>$install,'key_fingerprint'=>$fingerprint,'app_package'=>'com.arvio.tv.test'],'127.0.0.1')['challenge'];
$claim=['v'=>1,'iss'=>'extreme-activation','aud'=>$config->origin(),'sub'=>hash('sha256','SYNTHETIC PREVIEW '.$install),'hint'=>'…demo','install'=>$install,'key'=>$fingerprint,'package'=>'com.arvio.tv.test','challenge'=>hash('sha256',$challenge),'iat'=>time(),'exp'=>time()+60,'jti'=>ControlCenter\Database::id()];
$claim['panel']=['customer_id'=>'700001','reseller_id'=>'700001','reseller_username'=>'SYNTHETIC-preview-owner'];
$b64=static fn($value)=>rtrim(strtr(base64_encode($value),'+/','-_'),'=');
$payload=$b64(json_encode($claim,JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES|JSON_THROW_ON_ERROR));
$bridge=(new ControlCenter\EnrollmentProof($config,new ControlCenter\Secrets($config)))->bridgeSecret();
$proof='ec1.'.$payload.'.'.$b64(hash_hmac('sha256','proof-v1.'.$payload,hex2bin($bridge),true));
openssl_sign(ControlCenter\EnrollmentProof::signingText($challenge,$proof,$install,'com.arvio.tv.test',$fingerprint),$signature,$private,OPENSSL_ALGO_SHA256);
$used=(int)$app->db->query("SELECT COUNT(*) FROM devices WHERE tenant_id=? AND policy!='removed'",[$ownerTenant['id']])->fetchColumn();
$app->db->query('UPDATE tenants SET device_limit=? WHERE id=?',[max(1,$used),$ownerTenant['id']]);
$request=$app->enrollment->register(['challenge'=>$challenge,'proof'=>$proof,'public_key'=>$public,'signature'=>base64_encode($signature),'label'=>'TEST · Enrollment simulator','model'=>'SYNTHETIC ENROLLMENT FIXTURE','os_version'=>'Not a real Android device','app_version'=>'test-simulator'],'127.0.0.1');
$app->db->query('UPDATE tenants SET device_limit=100 WHERE id=?',[$ownerTenant['id']]);
$target=$app->db->one('SELECT id FROM customers WHERE tenant_id=? ORDER BY created_at,id LIMIT 1',[$ownerTenant['id']]);
file_put_contents($base.'/preview-credentials.json',json_encode(['email'=>'owner@preview.test','password'=>$password,'device_id'=>$bindings[0]['id'],'database'=>$name,
    'enrollment_request_id'=>$request['request_id'],'enrollment_customer_id'=>$target['id']],JSON_PRETTY_PRINT|JSON_THROW_ON_ERROR));
echo $base,"\n";
