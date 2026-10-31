<?php
declare(strict_types=1);
// Synthetic local preview only. No live service or customer database is contacted.
if (PHP_SAPI !== 'cli') exit(1);
require dirname(__DIR__) . '/src/bootstrap.php';
use ControlCenter\Application;
use ControlCenter\Commands;
use ControlCenter\Config;

$source = dirname(__DIR__);
$base = rtrim(getenv('CONTROL_TEST_TMPDIR') ?: '/tmp/opencode','/') . '/control-center-preview-' . bin2hex(random_bytes(4));
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
$settings=['environment'=>'development','origin'=>'http://127.0.0.1:'.$port,'development_brand_platform'=>['version'=>'2.0.115','code'=>431],'database'=>['dsn'=>"mysql:unix_socket=$socket;dbname=$name;charset=utf8mb4",'username'=>$user,'password'=>'']];
file_put_contents($base.'/config.php',"<?php\nreturn ".var_export($settings,true).";\n");
$config=new Config($base,$settings); $app=new Application($config);
$password=bin2hex(random_bytes(12));
$owner=$app->auth->bootstrap($config->secret('setup.key'),['name'=>'Local preview owner','email'=>'owner@preview.test','password'=>$password]);
$a=$app->management->createReseller($owner,['company'=>'DEMO · Northstar','name'=>'Demo reseller','email'=>'northstar@preview.test','password'=>bin2hex(random_bytes(12)),'device_limit'=>50]);
$b=$app->management->createReseller($owner,['company'=>'DEMO · Horizon','name'=>'Demo reseller','email'=>'horizon@preview.test','password'=>bin2hex(random_bytes(12)),'device_limit'=>25]);
$ownerTenant=$app->management->tenants($owner)[0];
$bindings=[];
foreach([[$ownerTenant,'TEST · Owner workspace','TEST simulator · Living room'],[$a,'TEST · Northstar customer','TEST simulator · Bedroom'],[$b,'TEST · Horizon customer','TEST simulator · Office'],[$ownerTenant,'TEST · Second owner device','TEST simulator · Second owner device']] as [$tenant,$customerName,$label]) {
    $customer=$app->management->createCustomer($owner,['tenant_id'=>$tenant['id'],'name'=>$customerName,'notes'=>'Synthetic local preview record only.']);
    $device=$app->management->createDevice($owner,['customer_id'=>$customer['id'],'label'=>$label,'notes'=>'No real Android device is connected.']);
    $token=$app->commands->issueTestCredential($owner,$device['id']);
    $bindings[]=['id'=>$device['id'],'token'=>$token];
}
$snapshot=['model'=>'LOCAL TEST SIMULATOR','os_version'=>'Synthetic — not Android','app_version'=>'development-simulator','activity_state'=>'active','capabilities'=>Commands::KINDS,'storage_total'=>17179869184,'storage_free'=>10737418240];
$app->commands->checkIn($bindings[0]['token'],$snapshot,'127.0.0.1');
$snapshot['activity_state']='idle'; $app->commands->checkIn($bindings[1]['token'],$snapshot,'127.0.0.1');
$favouriteDocument=['schema_version'=>1,'profile_ref'=>hash('sha256','SYNTHETIC FAVOURITE PROFILE'),'source_ref'=>hash('sha256','SYNTHETIC FAVOURITE SOURCE'),'revision'=>hash('sha256','SYNTHETIC FAVOURITE ORIGINAL'),'content_hash'=>hash('sha256','SYNTHETIC FAVOURITE CONTENT'),'reason'=>'automatic','entries'=>[
    ['kind'=>'channel','ref'=>hash('sha256','SYNTHETIC FAVOURITE 1'),'identity'=>'list_1:xtream:101','label'=>'TEST News Channel','available'=>true],
    ['kind'=>'channel','ref'=>hash('sha256','SYNTHETIC FAVOURITE 2'),'identity'=>'list_1:xtream:102','label'=>'TEST Sports Channel','available'=>true],
    ['kind'=>'group','ref'=>hash('sha256','SYNTHETIC FAVOURITE GROUP'),'identity'=>'TEST News','label'=>'TEST News','available'=>true]]];
$favouriteSaved=$app->favourites->capture($bindings[0]['token'],$favouriteDocument);
$emptyFavourites=$favouriteDocument;$emptyFavourites['entries']=[];$emptyFavourites['revision']=hash('sha256','SYNTHETIC EMPTY FAVOURITES');$emptyFavourites['content_hash']=hash('sha256','SYNTHETIC EMPTY CONTENT');
$app->favourites->capture($bindings[0]['token'],$emptyFavourites);
$app->db->query('UPDATE devices SET last_seen_at=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 20 MINUTE) WHERE id=?',[$bindings[2]['id']]);
// Explicit synthetic response for UI tests; not fetched from an Android device.
$settingsCommand=$app->commands->enqueue($owner,$bindings[0]['id'],['kind'=>'get_settings'],bin2hex(random_bytes(16)));
$settingsLease=$app->commands->checkIn($bindings[0]['token'],$snapshot,'127.0.0.1')['commands'][0];
$app->commands->acknowledge($bindings[0]['token'],$settingsCommand['id'],['status'=>'succeeded','lease_token'=>$settingsLease['lease_token'],'summary'=>'TEST SYNTHETIC SETTINGS SNAPSHOT — not from Android',
    'snapshot'=>['schema_version'=>'1','profile_ref'=>hash('sha256','SYNTHETIC PROFILE'),'settings_revision'=>hash('sha256','SYNTHETIC SETTINGS'),'sort_order'=>'provider','playlist_count'=>'1','enabled_playlist_count'=>'1','portal_count'=>'0','favourite_channel_count'=>'0','favourite_group_count'=>'0','live_prebuffer_seconds'=>'0','live_buffer_revision'=>hash('sha256','SYNTHETIC BUFFER')]]);
$sourceCommand=$app->commands->enqueue($owner,$bindings[0]['id'],['kind'=>'get_sources'],bin2hex(random_bytes(16)));
$sourceLease=$app->commands->checkIn($bindings[0]['token'],$snapshot,'127.0.0.1')['commands'][0];
$demoSourceUrl='https://synthetic.invalid/get.php?username=syntheticUser&password=synthetic-only';
$app->sources->capture($bindings[0]['token'],['command_id'=>$sourceCommand['id'],'lease_token'=>$sourceLease['lease_token'],'reason'=>'read','snapshot'=>['schema_version'=>1,'profile_ref'=>hash('sha256','SYNTHETIC PROFILE'),'revision'=>hash('sha256','SYNTHETIC SOURCE CONFIG'),'config'=>['m3uUrl'=>$demoSourceUrl,'epgUrl'=>'','playlists'=>[['id'=>'list_1','name'=>'TEST Main source','m3uUrl'=>$demoSourceUrl,'epgUrl'=>'','enabled'=>true,'epgUrls'=>[]]],'stalkerPortals'=>[],'sortOrder'=>'provider']]]);
$app->commands->acknowledge($bindings[0]['token'],$sourceCommand['id'],['status'=>'succeeded','lease_token'=>$sourceLease['lease_token'],'summary'=>'SYNTHETIC SOURCE READ — not Android']);
$preferenceCommand=$app->commands->enqueue($owner,$bindings[0]['id'],['kind'=>'get_preferences'],bin2hex(random_bytes(16)));
$preferenceLease=$app->commands->checkIn($bindings[0]['token'],$snapshot,'127.0.0.1')['commands'][0];
$preferenceValues=[];$preferenceOrigins=[];foreach(ControlCenter\Preferences::RULES as $key=>$choices){$preferenceValues[$key]=$choices[0];$preferenceOrigins[$key]='local';}
$app->preferences->capture($bindings[0]['token'],['command_id'=>$preferenceCommand['id'],'lease_token'=>$preferenceLease['lease_token'],'document'=>['schema_version'=>1,'profile_ref'=>hash('sha256','SYNTHETIC PROFILE'),'revision'=>hash('sha256','SYNTHETIC PREFERENCES'),'values'=>$preferenceValues,'origins'=>$preferenceOrigins,'base_values'=>[],'overrides'=>[],'base_preset_id'=>'','base_version'=>0]]);
$app->commands->acknowledge($bindings[0]['token'],$preferenceCommand['id'],['status'=>'succeeded','lease_token'=>$preferenceLease['lease_token'],'summary'=>'SYNTHETIC PREFERENCE READ — not Android']);
$categoryCommand=$app->commands->enqueue($owner,$bindings[0]['id'],['kind'=>'get_categories'],bin2hex(random_bytes(16)));
$categoryLease=$app->commands->checkIn($bindings[0]['token'],$snapshot,'127.0.0.1')['commands'][0];
$categorySource=hash('sha256','SYNTHETIC CATEGORY SOURCE');
$app->commands->acknowledge($bindings[0]['token'],$categoryCommand['id'],['status'=>'succeeded','lease_token'=>$categoryLease['lease_token'],'summary'=>'TEST SYNTHETIC CATEGORY PAGE — not from Android',
    'catalog'=>['schema_version'=>1,'profile_ref'=>hash('sha256','SYNTHETIC PROFILE'),'source_ref'=>$categorySource,'revision'=>hash('sha256','SYNTHETIC CATEGORY REVISION'),'page'=>1,'pages'=>1,'total'=>2,'order_editable'=>true,
        'sources'=>[['ref'=>$categorySource,'label'=>'Playlist 1','total'=>2]],'items'=>[
            ['ref'=>hash('sha256','SYNTHETIC CATEGORY 1'),'label'=>'TEST <img src=x onerror="window.__categoryInjected=1">','channels'=>20,'hidden'=>false,'position'=>1],
            ['ref'=>hash('sha256','SYNTHETIC CATEGORY 2'),'label'=>'TEST Sports','channels'=>30,'hidden'=>true,'position'=>2]]]]);
foreach([0,3] as $which) {
    $app->commands->checkIn($bindings[$which]['token'],$snapshot,'127.0.0.1');
    $sourceRef=hash('sha256','SYNTHETIC FULL SOURCE '.$which);$items=[];
    for($i=1;$i<=60;$i++) {
        $name=$i===1?'TEST <img src=x onerror="window.__categoryInjected=1">':($i===2?'TEST Sports':'TEST Category '.$i);
        if($which===3&&$i===2)$name='TEST Extra target category';
        $items[]=['ref'=>hash('sha256','SYNTHETIC FULL '.$which.' '.$i),'label'=>$i===3?'[redacted category label]':$name,'match_name'=>$i===3?null:$name,'channels'=>$i,'hidden'=>$i===2,'position'=>$i,'provider_position'=>$i];
    }
    $c=$app->commands->enqueue($owner,$bindings[$which]['id'],['kind'=>'get_category_layout'],bin2hex(random_bytes(16)));
    $delivery=$app->commands->checkIn($bindings[$which]['token'],$snapshot,'127.0.0.1')['commands'][0];
    $app->commands->acknowledge($bindings[$which]['token'],$c['id'],['status'=>'succeeded','lease_token'=>$delivery['lease_token'],'summary'=>'TEST SYNTHETIC COMPLETE LAYOUT — not from Android','catalog'=>[
        'schema_version'=>1,'profile_ref'=>hash('sha256','SYNTHETIC PROFILE'),'source_ref'=>$sourceRef,'revision'=>hash('sha256','SYNTHETIC FULL REVISION '.$which),'page'=>1,'pages'=>1,'page_size'=>2000,'total'=>60,'provider_order'=>true,'order_editable'=>true,
        'sources'=>[['ref'=>$sourceRef,'label'=>'Playlist 1','total'=>60]],'items'=>$items]]);
}
// A local synthetic issuer/request for exercising the OWNER review UI only.
// This is not evidence that a real activation worker or Android device connected.
$app->enrollment->setEnabled($owner,['enabled'=>true]);
$app->db->query('INSERT INTO panel_resellers (panel_id,tenant_id,username,linked_by) VALUES (?,?,?,?)',['700001',$ownerTenant['id'],'SYNTHETIC-preview-owner',$owner['id']]);
$brandPassword=bin2hex(random_bytes(20));$brandTenant=$app->management->createReseller($owner,['company'=>'TEST branded reseller','name'=>'TEST brand operator','email'=>'brand@preview.test','password'=>$brandPassword,'device_limit'=>5]);
$app->db->query('INSERT INTO panel_resellers (panel_id,tenant_id,username,linked_by) VALUES (?,?,?,?)',['700002',$brandTenant['id'],'SYNTHETIC-brand-reseller',$owner['id']]);
$private=openssl_pkey_new(['private_key_type'=>OPENSSL_KEYTYPE_EC,'curve_name'=>'prime256v1']);
$public=preg_replace('~-----[^\n]+-----|\s~','',openssl_pkey_get_details($private)['key']);
$fingerprint=hash('sha256',base64_decode($public)); $install=ControlCenter\Database::id();
$challenge=$app->enrollment->challenge(['installation_id'=>$install,'key_fingerprint'=>$fingerprint,'app_package'=>'com.arvio.tv.test'],'127.0.0.1')['challenge'];
$claim=['v'=>1,'iss'=>'extreme-activation','aud'=>$config->origin(),'sub'=>hash('sha256','SYNTHETIC PREVIEW '.$install),'hint'=>'…demo','install'=>$install,'key'=>$fingerprint,'package'=>'com.arvio.tv.test','challenge'=>hash('sha256',$challenge),'iat'=>time(),'exp'=>time()+60,'jti'=>ControlCenter\Database::id()];
$syntheticLineUsername='TEST_verified_line';
$claim['sub']=hash_hmac('sha256',"account-v1\n".$syntheticLineUsername,hex2bin((new ControlCenter\EnrollmentProof($config,new ControlCenter\Secrets($config)))->bridgeSecret()));
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
// Separate trial fixture: ALL catalog/receipt data are synthetic; no panel/worker call.
$trialPassword=bin2hex(random_bytes(20));$trialTenant=$app->management->createReseller($owner,['company'=>'TEST trial reseller','name'=>'TEST trial operator','email'=>'trial@preview.test','password'=>$trialPassword,'device_limit'=>5]);
$app->db->query('INSERT INTO panel_resellers (panel_id,tenant_id,username,linked_by) VALUES (?,?,?,?)',['700003',$trialTenant['id'],'SYNTHETIC-trial-reseller',$owner['id']]);
$trialActor=ControlCenter\Auth::publicUser($app->db->one('SELECT * FROM users WHERE tenant_id=?',[$trialTenant['id']]));$asset=$app->appBrands->upload($trialActor,['image'=>'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jSuoAAAAASUVORK5CYII=']);
$trialBrand=$app->appBrands->save($trialActor,['name'=>'TEST Trial TV','loading_asset'=>$asset['id'],'logo_asset'=>$asset['id'],'icon_asset'=>$asset['id']]);
$trialConfig=new Config($base,array_replace($config->values,['trial_worker'=>['shared_secret'=>str_repeat('a',64)]]));
$trialPlans=[['id'=>7,'name'=>'TEST 24-hour trial','duration'=>24,'period'=>'hours','max_connections'=>1,'credits'=>'0','bouquets'=>[10],'fingerprint'=>str_repeat('7',64)],['id'=>8,'name'=>'TEST 48-hour trial','duration'=>48,'period'=>'hours','max_connections'=>1,'credits'=>'1','bouquets'=>[10],'fingerprint'=>str_repeat('8',64)]];
$trialBridge=new ControlCenter\TrialWorker($trialConfig,fn()=>json_encode(['reseller_id'=>'700003','plans'=>$trialPlans]));$trialPolicy=new ControlCenter\TrialPolicies($app->db,$app->management,$app->auth,$trialBridge);
$trialCatalog=$trialPolicy->refresh($owner,['tenant_id'=>$trialTenant['id']]);$trialPolicy->grant($owner,['tenant_id'=>$trialTenant['id'],'expected_version'=>0,'catalog_id'=>$trialCatalog['catalog_id'],'allowed_plan_ids'=>[7],'enabled'=>true]);
$trialPolicy->setDefault($trialActor,['expected_revision'=>0,'grant_version'=>1,'plan_id'=>7,'enabled'=>true]);
$trialContext=['brand_id'=>$trialBrand['id'],'operation_id'=>ControlCenter\Database::id(),'device_ref'=>str_repeat('a',64),'first_name'=>'TEST Trial Contact','phone'=>'+15551234001','plan_id'=>7,'plan_fingerprint'=>str_repeat('7',64)];
$trialContact=(new ControlCenter\TrialContacts($app->db,$app->management,$app->auth,new ControlCenter\Secrets($config),$config,$trialBridge))->recordPrepared($trialContext,['state'=>'prepared','operation_id'=>$trialContext['operation_id'],'reseller_id'=>'700003','line_id'=>'999001','plan_id'=>7,'plan_fingerprint'=>str_repeat('7',64),'notes_recorded'=>true,'expires_at'=>time()+3600]);
$app->db->query('UPDATE trial_contacts SET expires_at=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 HOUR),trial_expires_at=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 HOUR) WHERE id=?',[$trialContact['id']]);
file_put_contents($base.'/preview-credentials.json',json_encode(['email'=>'owner@preview.test','password'=>$password,'brand_email'=>'brand@preview.test','brand_password'=>$brandPassword,'brand_tenant_id'=>$brandTenant['id'],'trial_email'=>'trial@preview.test','trial_password'=>$trialPassword,'trial_tenant_id'=>$trialTenant['id'],'trial_contact_id'=>$trialContact['id'],'device_id'=>$bindings[0]['id'],'database'=>$name,
    'enrollment_request_id'=>$request['request_id'],'enrollment_customer_id'=>$target['id'],'synthetic_enrollment_poll_token'=>$request['poll_token'],'synthetic_line_username'=>$syntheticLineUsername,'layout_target_id'=>$bindings[3]['id'],'reseller_device_id'=>$bindings[1]['id'],'synthetic_device_token'=>$bindings[0]['token'],'synthetic_favourites_backup_id'=>$favouriteSaved['backup_id']],JSON_PRETTY_PRINT|JSON_THROW_ON_ERROR));
echo $base,"\n";
