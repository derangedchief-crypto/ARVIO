<?php
declare(strict_types=1);
// Real MariaDB/MySQL tests, on a NEW random database via a local Unix socket only.
// Test passwords/tokens are synthetic; this directory is excluded from delivery ZIPs.
require dirname(__DIR__) . '/src/bootstrap.php';
ob_start();

use ControlCenter\Application;
use ControlCenter\Auth;
use ControlCenter\Commands;
use ControlCenter\Config;
use ControlCenter\Database;
use ControlCenter\Http;
use ControlCenter\Migrations;
use ControlCenter\Problem;
use ControlCenter\Secrets;

function check(bool $condition, string $message = 'Assertion failed'): void { if (!$condition) throw new RuntimeException($message); }
function same(mixed $expected, mixed $actual): void { check(serialize($expected) === serialize($actual), 'Expected ' . var_export($expected, true) . ', got ' . var_export($actual, true)); }
function problem(int $status, callable $work): void {
    try { $work(); } catch (Problem $e) { same($status, $e->status); return; }
    throw new RuntimeException("Expected HTTP policy error $status");
}
function copyTree(string $source, string $target): void {
    if (!is_dir($target)) mkdir($target, 0700, true);
    foreach (new DirectoryIterator($source) as $item) {
        if ($item->isDot()) continue;
        $dest = $target . '/' . $item->getFilename();
        if ($item->isDir()) copyTree($item->getPathname(), $dest); else copy($item->getPathname(), $dest);
    }
}

$root = dirname(__DIR__);
$socket = getenv('CONTROL_TEST_SOCKET') ?: '/tmp/opencode/control-center-dev/database.sock';
if (!str_starts_with($socket, '/') || !file_exists($socket)) throw new RuntimeException('Start the private dev MariaDB server first.');
$dbUser = getenv('CONTROL_TEST_USER') ?: getenv('USER');
$server = new PDO('mysql:unix_socket=' . $socket . ';charset=utf8mb4', $dbUser, '', [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION]);
$name = 'control_test_' . bin2hex(random_bytes(6));
$fixture = '/tmp/opencode/' . $name;
umask(0077);
mkdir($fixture, 0700, true);
foreach (['src','database','public'] as $directory) copyTree($root . '/' . $directory, $fixture . '/' . $directory);
mkdir($fixture.'/deploy',0700);copy($root.'/deploy/brand-build-timings.json',$fixture.'/deploy/brand-build-timings.json');
mkdir($fixture . '/storage', 0700); mkdir($fixture . '/dev', 0700);
copy($root . '/dev/router.php', $fixture . '/dev/router.php'); copy($root . '/VERSION', $fixture . '/VERSION');
$server->exec("CREATE DATABASE `$name` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
$settings = ['environment' => 'testing', 'origin' => 'http://127.0.0.1:9876','development_brand_platform'=>['version'=>'2.0.115','code'=>431], 'database' => ['dsn' => "mysql:unix_socket=$socket;dbname=$name;charset=utf8mb4", 'username' => $dbUser, 'password' => '']];
$config = new Config($fixture, $settings);
$app = new Application($config);
$password = 'Synthetic-Test-Password-42!';
$owner = $app->auth->bootstrap($config->secret('setup.key'), ['name' => 'Test owner', 'email' => 'owner@example.test', 'password' => $password]);
$tenantA = $app->management->createReseller($owner, ['company' => 'Reseller Alpha', 'name' => 'Alpha', 'email' => 'alpha@example.test', 'password' => $password]);
$tenantB = $app->management->createReseller($owner, ['company' => 'Reseller Beta', 'name' => 'Beta', 'email' => 'beta@example.test', 'password' => $password]);
$alphaSession = $app->auth->login(['email' => 'alpha@example.test', 'password' => $password], '127.0.0.2');
$betaSession = $app->auth->login(['email' => 'beta@example.test', 'password' => $password], '127.0.0.3');
$alpha = $alphaSession['user']; $beta = $betaSession['user'];
$tests = [];
$tests['schema migrations are repeatable and version checked'] = function () use ($app, $config): void { same([], (new Migrations($app->db, $config))->apply()); check((new Migrations($app->db, $config))->installed()); };
$tests['owner setup cannot create a second owner'] = function () use ($app, $config, $password): void { problem(409, fn() => $app->auth->bootstrap($config->secret('setup.key'), ['name'=>'Other','email'=>'other@example.test','password'=>$password])); same(1, (int) $app->db->query("SELECT COUNT(*) FROM users WHERE role = 'owner'")->fetchColumn()); };
$tests['invalid setup key is rejected before database mutation'] = function () use ($app, $password): void { problem(403, fn() => $app->auth->bootstrap(str_repeat('0',64), ['name'=>'Other','email'=>'other@example.test','password'=>$password])); };
$tests['passwords and session tokens are never stored in plaintext'] = function () use ($app, $password, $alphaSession): void { $row = $app->db->one("SELECT password_hash FROM users WHERE email = 'alpha@example.test'"); check($row['password_hash'] !== $password && password_verify($password, $row['password_hash'])); same(0, (int) $app->db->query('SELECT COUNT(*) FROM sessions WHERE token_hash = ?', [$alphaSession['token']])->fetchColumn()); check(!isset($alphaSession['user']['password_hash'])); };
$tests['invalid credentials use generic authentication failure'] = function () use ($app): void { problem(401, fn() => $app->auth->login(['email'=>'missing@example.test','password'=>'not-the-password'], '127.0.0.4')); problem(401, fn() => $app->auth->login(['email'=>'alpha@example.test','password'=>'not-the-password'], '127.0.0.4')); };
$tests['CSRF is bound to the current session'] = function () use ($app, $alphaSession, $betaSession): void { $app->auth->verifyCsrf($alphaSession['token'], $alphaSession['csrf']); problem(403, fn() => $app->auth->verifyCsrf($alphaSession['token'], $betaSession['csrf'])); };
$tests['rate limiting rejects excess attempts'] = function () use ($app): void { $app->auth->limit('fixture','unique-rate',2,60); $app->auth->limit('fixture','unique-rate',2,60); problem(429, fn() => $app->auth->limit('fixture','unique-rate',2,60)); };
$tests['resellers cannot create resellers or read other tenant details'] = function () use ($app, $alpha, $tenantB): void { problem(403, fn() => $app->management->createReseller($alpha, [])); problem(404, fn() => $app->management->tenant($alpha, $tenantB['id'])); same(1, count($app->management->tenants($alpha))); };
$makeCustomer = fn(array $actor, string $tenant) => $app->management->createCustomer($actor, ['tenant_id'=>$tenant,'name'=>'Customer ' . bin2hex(random_bytes(3)), 'notes'=>'Private notes']);
$makeDevice = function (array $actor, string $tenant) use ($app, $makeCustomer): array { $customer = $makeCustomer($actor, $tenant); return $app->management->createDevice($actor, ['customer_id'=>$customer['id'], 'label'=>'Living room']); };
$tests['device search supports Unicode labels alongside ASCII IDs without crossing tenants'] = function()use($app,$alpha,$beta,$tenantA,$makeCustomer):void {
    $c=$makeCustomer($alpha,$tenantA['id']);$label='TEST · Télévision 日本語';$d=$app->management->createDevice($alpha,['customer_id'=>$c['id'],'label'=>$label]);
    same(1,$app->management->listing($alpha,'devices',['search'=>$label])['total']);same(1,$app->management->listing($alpha,'devices',['search'=>$d['id']])['total']);same(0,$app->management->listing($beta,'devices',['search'=>$label])['total']);
};
$snapshot = ['model'=>'LOCAL TEST SIMULATOR','os_version'=>'Synthetic','app_version'=>'test-client','activity_state'=>'active','capabilities'=>Commands::KINDS,'storage_total'=>100000,'storage_free'=>40000];
$enrolled = function (array $actor, string $tenant) use ($app, $owner, $makeDevice, $snapshot): array { $device = $makeDevice($actor, $tenant); $token = $app->commands->issueTestCredential($owner, $device['id']); $app->commands->checkIn($token, $snapshot, '127.0.0.1'); return [$app->management->device($actor,$device['id']), $token]; };
$tests['customer and device tenant boundaries are enforced'] = function () use ($app, $alpha, $beta, $tenantA, $makeCustomer): void { $customer = $makeCustomer($alpha, $tenantA['id']); problem(404, fn() => $app->management->customer($beta, $customer['id'])); problem(404, fn() => $app->management->updateCustomer($beta, $customer['id'], ['notes'=>'stolen'])); problem(404, fn() => $app->management->createDevice($beta, ['customer_id'=>$customer['id'],'label'=>'intruder'])); };
$tests['mass assignment cannot override roles or trusted device state'] = function () use ($app, $alpha, $tenantA, $makeCustomer): void { problem(422, fn() => $app->management->createCustomer($alpha, ['tenant_id'=>$tenantA['id'],'name'=>'X','role'=>'owner'])); $customer=$makeCustomer($alpha,$tenantA['id']); problem(422, fn() => $app->management->createDevice($alpha,['customer_id'=>$customer['id'],'label'=>'X','is_test'=>false,'capabilities'=>Commands::KINDS])); };
$tests['database composite foreign key rejects cross-tenant assignment'] = function () use ($app, $alpha, $tenantA, $tenantB, $makeCustomer): void { $customer=$makeCustomer($alpha,$tenantA['id']); try { $id=Database::id(); $app->db->query("INSERT INTO devices (id,tenant_id,customer_id,installation_id,label,notes,capabilities) VALUES (?,?,?,?,?,'','[]')",[$id,$tenantB['id'],$customer['id'],$id,'Invalid']); } catch (PDOException $e) { same('23000',$e->getCode()); return; } throw new RuntimeException('Foreign key did not reject cross-tenant device'); };
$tests['pagination is bounded and tenant scoped'] = function () use ($app,$alpha,$beta,$tenantA,$makeCustomer): void { for($i=0;$i<26;$i++) $makeCustomer($alpha,$tenantA['id']); $first=$app->management->listing($alpha,'customers',['page'=>'1']); $next=$app->management->listing($alpha,'customers',['page'=>'2']); same(25,count($first['items'])); check(count($next['items'])>=1); foreach($first['items'] as $c) same($tenantA['id'],$c['tenant_id']); foreach($app->management->listing($beta,'customers',[])['items'] as $c) check($c['tenant_id']!==$tenantA['id']); problem(422,fn()=> $app->management->listing($alpha,'customers',['page'=>'1 OR 1=1'])); };
$tests['pending device is not presented as online or capable'] = function () use ($app,$alpha,$tenantA,$makeDevice): void { $d=$makeDevice($alpha,$tenantA['id']); same('not_enrolled',$d['presence']); same([], $d['capabilities']); problem(409,fn()=> $app->commands->enqueue($alpha,$d['id'],['kind'=>'refresh_playlist'],Database::id())); };
$tests['device allocation is enforced inside provisioning transaction'] = function () use ($app,$owner,$alpha,$tenantA,$makeDevice): void { $used=(int)$app->db->query("SELECT COUNT(*) FROM devices WHERE tenant_id=? AND policy!='removed'",[$tenantA['id']])->fetchColumn(); $app->management->updateTenant($owner,$tenantA['id'],['status'=>'active','device_limit'=>max(1,$used)]); try { problem(409,fn()=> $makeDevice($alpha,$tenantA['id'])); } finally { $app->management->updateTenant($owner,$tenantA['id'],['status'=>'active','device_limit'=>100]); } };
$tests['device detail and command history cannot cross reseller boundary'] = function () use ($app,$alpha,$beta,$tenantA,$makeDevice): void { $d=$makeDevice($alpha,$tenantA['id']); problem(404,fn()=> $app->management->device($beta,$d['id'])); problem(404,fn()=> $app->commands->listing($beta,$d['id'],[])); problem(404,fn()=> $app->commands->enqueue($beta,$d['id'],['kind'=>'refresh_playlist'],Database::id())); };
$tests['device tokens are unique, hashed and revocable'] = function () use ($app,$owner,$alpha,$tenantA,$enrolled): void { [$d,$token]=$enrolled($alpha,$tenantA['id']); same(0,(int)$app->db->query('SELECT COUNT(*) FROM device_credentials WHERE token_hash=?',[$token])->fetchColumn()); $replacement=$app->commands->issueTestCredential($owner,$d['id']); check($replacement!==$token); problem(401,fn()=> $app->commands->deviceIdentity($token)); same($d['id'],$app->commands->deviceIdentity($replacement)['id']); };
$tests['test enrollment is impossible in production configuration'] = function () use ($fixture,$settings,$owner,$app,$alpha,$tenantA,$makeDevice): void { $production=new Application(new Config($fixture,array_replace($settings,['environment'=>'production','origin'=>'https://control.fecxbrfzku.net']))); $d=$makeDevice($alpha,$tenantA['id']); problem(403,fn()=> $production->commands->issueTestCredential($owner,$d['id'])); };
$tests['check-in validates data and returns central owner update policy'] = function () use ($app,$alpha,$tenantA,$enrolled,$snapshot): void { [$d,$token]=$enrolled($alpha,$tenantA['id']); $result=$app->commands->checkIn($token,$snapshot,'127.0.0.1'); same('platform_owner',$result['update_policy']['managed_by']); problem(422,fn()=> $app->commands->checkIn($token,array_replace($snapshot,['capabilities'=>['remote_shell']]),'127.0.0.1')); problem(422,fn()=> $app->commands->checkIn($token,array_replace($snapshot,['storage_free'=>100001]),'127.0.0.1')); problem(422,fn()=> $app->commands->checkIn($token,array_replace($snapshot,['tenant_id'=>'other']),'127.0.0.1')); };
$tests['online and idle status derive from server last-seen, not arbitrary client time'] = function () use ($app,$alpha,$tenantA,$enrolled,$snapshot): void { [$d,$token]=$enrolled($alpha,$tenantA['id']); same('online',$d['presence']); $app->commands->checkIn($token,array_replace($snapshot,['activity_state'=>'idle']),'127.0.0.1'); same('idle',$app->management->device($alpha,$d['id'])['presence']); $app->db->query('UPDATE devices SET last_seen_at=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 10 MINUTE) WHERE id=?',[$d['id']]); same('offline',$app->management->device($alpha,$d['id'])['presence']); };
$tests['command allowlist rejects shell and arbitrary payload fields'] = function () use ($app,$alpha,$tenantA,$enrolled): void { [$d]=$enrolled($alpha,$tenantA['id']); problem(422,fn()=> $app->commands->enqueue($alpha,$d['id'],['kind'=>'shell','payload'=>['command'=>'id']],Database::id())); problem(422,fn()=> $app->commands->enqueue($alpha,$d['id'],['kind'=>'clear_cache','payload'=>['scope'=>'all_app_data']],Database::id())); problem(422,fn()=> $app->commands->enqueue($alpha,$d['id'],['kind'=>'refresh_playlist','update_url'=>'https://evil.test/'],Database::id())); };
$tests['idempotent retries enqueue once and reject key reuse for other actions'] = function () use ($app,$alpha,$tenantA,$enrolled): void { [$d]=$enrolled($alpha,$tenantA['id']); $key=Database::id(); $a=$app->commands->enqueue($alpha,$d['id'],['kind'=>'refresh_playlist'],$key); $b=$app->commands->enqueue($alpha,$d['id'],['kind'=>'refresh_playlist'],$key); same($a['id'],$b['id']); same(1,(int)$app->db->query('SELECT COUNT(*) FROM commands WHERE id=?',[$a['id']])->fetchColumn()); problem(409,fn()=> $app->commands->enqueue($alpha,$d['id'],['kind'=>'reload_epg'],$key)); };
$tests['clear-cache command is scoped to expendable cache only'] = function () use ($app,$alpha,$tenantA,$enrolled): void { [$d]=$enrolled($alpha,$tenantA['id']); $c=$app->commands->enqueue($alpha,$d['id'],['kind'=>'clear_cache'],Database::id()); same(['scope'=>'expendable_only'],(array)$c['payload']); check(!isset($c['lease_hash'],$c['request_hash'])); };
$tests['unsupported capability is not silently queued'] = function () use ($app,$alpha,$tenantA,$enrolled,$snapshot): void { [$d,$token]=$enrolled($alpha,$tenantA['id']); $app->commands->checkIn($token,array_replace($snapshot,['capabilities'=>[]]),'127.0.0.1'); problem(409,fn()=> $app->commands->enqueue($alpha,$d['id'],['kind'=>'restart_app'],Database::id())); };
$tests['queue delivery leases prevent duplicate delivery before timeout'] = function () use ($app,$alpha,$tenantA,$enrolled,$snapshot): void { [$d,$token]=$enrolled($alpha,$tenantA['id']); $c=$app->commands->enqueue($alpha,$d['id'],['kind'=>'reload_epg'],Database::id()); $first=$app->commands->checkIn($token,$snapshot,'127.0.0.1')['commands']; same($c['id'],$first[0]['id']); same([],$app->commands->checkIn($token,$snapshot,'127.0.0.1')['commands']); same('delivered',$app->commands->listing($alpha,$d['id'],[])['items'][0]['status']); };
$tests['running and completed acknowledgments are duplicate safe and redacted'] = function () use ($app,$alpha,$tenantA,$enrolled,$snapshot): void { [$d,$token]=$enrolled($alpha,$tenantA['id']); $c=$app->commands->enqueue($alpha,$d['id'],['kind'=>'reload_epg'],Database::id()); $lease=$app->commands->checkIn($token,$snapshot,'127.0.0.1')['commands'][0]['lease_token']; same('running',$app->commands->acknowledge($token,$c['id'],['lease_token'=>$lease,'status'=>'running'])['status']); $done=$app->commands->acknowledge($token,$c['id'],['lease_token'=>$lease,'status'=>'succeeded','summary'=>'url https://provider.test/?password=abc token=secret-value']); same('succeeded',$done['status']); check(!str_contains($done['result']['summary'],'secret-value')&&!str_contains($done['result']['summary'],'provider.test')); same($done,$app->commands->acknowledge($token,$c['id'],['lease_token'=>$lease,'status'=>'succeeded'])); };
$tests['another device cannot acknowledge a leased command'] = function () use ($app,$alpha,$beta,$tenantA,$tenantB,$enrolled,$snapshot): void { [$d,$token]=$enrolled($alpha,$tenantA['id']); [, $other]=$enrolled($beta,$tenantB['id']); $c=$app->commands->enqueue($alpha,$d['id'],['kind'=>'reload_epg'],Database::id()); $lease=$app->commands->checkIn($token,$snapshot,'127.0.0.1')['commands'][0]['lease_token']; problem(404,fn()=> $app->commands->acknowledge($other,$c['id'],['lease_token'=>$lease,'status'=>'succeeded'])); problem(409,fn()=> $app->commands->acknowledge($token,$c['id'],['lease_token'=>str_repeat('0',64),'status'=>'succeeded'])); };
$tests['lease retry rotates proof and rejects stale acknowledgments'] = function () use ($app,$alpha,$tenantA,$enrolled,$snapshot): void { [$d,$token]=$enrolled($alpha,$tenantA['id']); $c=$app->commands->enqueue($alpha,$d['id'],['kind'=>'reload_epg'],Database::id()); $old=$app->commands->checkIn($token,$snapshot,'127.0.0.1')['commands'][0]['lease_token']; $app->db->query('UPDATE commands SET lease_until=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 SECOND) WHERE id=?',[$c['id']]); $new=$app->commands->checkIn($token,$snapshot,'127.0.0.1')['commands'][0]['lease_token']; check($new!==$old); problem(409,fn()=> $app->commands->acknowledge($token,$c['id'],['lease_token'=>$old,'status'=>'succeeded'])); same('succeeded',$app->commands->acknowledge($token,$c['id'],['lease_token'=>$new,'status'=>'succeeded'])['status']); };
$tests['expired commands cannot be delivered'] = function () use ($app,$alpha,$tenantA,$enrolled,$snapshot): void { [$d,$token]=$enrolled($alpha,$tenantA['id']); $c=$app->commands->enqueue($alpha,$d['id'],['kind'=>'reload_epg'],Database::id()); $app->db->query('UPDATE commands SET expires_at=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 SECOND) WHERE id=?',[$c['id']]); same([],$app->commands->checkIn($token,$snapshot,'127.0.0.1')['commands']); same('expired',$app->commands->listing($alpha,$d['id'],[])['items'][0]['status']); };
$tests['suspension cancels pending work but retains device identity for policy recovery'] = function () use ($app,$alpha,$tenantA,$enrolled,$snapshot): void { [$d,$token]=$enrolled($alpha,$tenantA['id']); $app->commands->enqueue($alpha,$d['id'],['kind'=>'reload_epg'],Database::id()); $app->management->updateDevice($alpha,$d['id'],['policy'=>'suspended']); $response=$app->commands->checkIn($token,$snapshot,'127.0.0.1'); same('suspended',$response['policy']); same([],$response['commands']); same('cancelled',$app->commands->listing($alpha,$d['id'],[])['items'][0]['status']); $app->management->updateDevice($alpha,$d['id'],['policy'=>'active']); same('active',$app->commands->checkIn($token,$snapshot,'127.0.0.1')['policy']); };
$tests['only owner can block/remove and reseller cannot reactivate owner block'] = function () use ($app,$owner,$alpha,$tenantA,$enrolled): void { [$d,$token]=$enrolled($alpha,$tenantA['id']); problem(403,fn()=> $app->management->updateDevice($alpha,$d['id'],['policy'=>'blocked'])); $app->management->updateDevice($owner,$d['id'],['policy'=>'blocked']); problem(403,fn()=> $app->management->updateDevice($alpha,$d['id'],['policy'=>'active'])); $app->management->updateDevice($owner,$d['id'],['policy'=>'removed']); problem(401,fn()=> $app->commands->deviceIdentity($token)); problem(409,fn()=> $app->management->updateDevice($owner,$d['id'],['policy'=>'active'])); };
$tests['reseller suspension revokes browser sessions and returns suspended device policy'] = function () use ($app,$owner,$alpha,$alphaSession,$tenantA,$enrolled,$snapshot): void { [, $token]=$enrolled($alpha,$tenantA['id']); $app->management->updateTenant($owner,$tenantA['id'],['status'=>'suspended','device_limit'=>100]); try { problem(401,fn()=> $app->auth->user($alphaSession['token'])); same('suspended',$app->commands->checkIn($token,$snapshot,'127.0.0.1')['policy']); } finally { $app->management->updateTenant($owner,$tenantA['id'],['status'=>'active','device_limit'=>100]); } };
$tests['audit listing remains tenant scoped and excludes stored credentials'] = function () use ($app,$alpha,$tenantA,$tenantB): void { foreach($app->management->audit($alpha,[])['items'] as $event) { check(!str_contains(json_encode($event),$tenantB['id'])); check(!isset($event['password_hash'],$event['token_hash'])); } same(0,(int)$app->db->query('SELECT COUNT(*) FROM audit_events WHERE metadata LIKE ?', ['%Synthetic-Test-Password%'])->fetchColumn()); };
$tests['authenticated encryption rejects tampering and persists with the same private key'] = function () use ($config): void { $secrets=new Secrets($config); $encoded=$secrets->encrypt('private-fixture'); check(!str_contains($encoded,'private-fixture')); same('private-fixture',$secrets->decrypt($encoded)); $bad=substr($encoded,0,-4).'AAAA'; try { $secrets->decrypt($bad); } catch(RuntimeException) { return; } throw new RuntimeException('Tampered ciphertext accepted'); };
$tests['private key permissions are owner-only'] = function () use ($config): void { $config->secret('application.key'); same(0600,fileperms($config->privateFile('application.key')) & 0777); same($config->secret('application.key'),$config->secret('application.key')); };
$tests['production HTTPS policy does not trust spoofed forwarded headers'] = function () use ($fixture,$settings): void { $saved=$_SERVER; try { $_SERVER=['HTTP_HOST'=>'control.fecxbrfzku.net','DOCUMENT_ROOT'=>$fixture.'/public','SERVER_PORT'=>80,'HTTP_X_FORWARDED_PROTO'=>'https']; $c=new Config($fixture,array_replace($settings,['environment'=>'production','origin'=>'https://control.fecxbrfzku.net'])); problem(503,fn()=> Http::guard($c)); $_SERVER['HTTPS']='on'; $_SERVER['SERVER_PORT']=443; Http::guard($c); $_SERVER['DOCUMENT_ROOT']=$fixture; problem(503,fn()=> Http::guard($c)); } finally { $_SERVER=$saved; } };

require __DIR__ . '/http.php';
$http = new HttpFixture($fixture, $settings);
httpCases($http, $tests, $password, $app, $owner, $alpha, $beta, $tenantA, $tenantB, $makeDevice);
require __DIR__ . '/operations.php';
operationalCases($tests,$app,$config,$server,$name,$socket,$dbUser,$fixture,$password,$owner,$alpha,$tenantA,$makeDevice,$enrolled,$snapshot);
require __DIR__ . '/installation.php';
installationCases($tests,$app,$config,$server,$name,$fixture,$password,$owner);
require __DIR__ . '/enrollment.php';
enrollmentCases($tests,$app,$config,$owner,$alpha,$beta,$tenantA,$tenantB,$makeCustomer,$http,$password);
require __DIR__.'/settings-presets.php';
settingsCases($tests,$app,$owner,$alpha,$beta,$tenantA,$tenantB,$http,$password);
require __DIR__.'/categories.php';
categoryCases($tests,$app,$owner,$alpha,$beta,$tenantA,$http);
require __DIR__.'/category-layouts.php';
layoutCases($tests,$app,$owner,$alpha,$beta,$tenantA,$tenantB,$http,$password);
require __DIR__.'/favourites.php';
favouriteCases($tests,$app,$owner,$alpha,$beta,$tenantA,$http);
require __DIR__.'/sources.php';
sourceCases($tests,$app,$owner,$alpha,$beta,$tenantA);
require __DIR__.'/preferences.php';
preferenceCases($tests,$app,$owner,$alpha,$beta,$tenantA,$tenantB);
require __DIR__.'/support.php';
supportCases($tests,$app,$owner,$alpha,$beta,$tenantA);
require __DIR__.'/advanced-http.php';
advancedHttpCases($tests,$app,$owner,$alpha,$tenantA,$http);
require __DIR__.'/device-profiles.php';
deviceProfileCases($tests,$app,$owner,$alpha,$beta,$tenantA,$tenantB,$http);
require __DIR__.'/app-brands.php';
appBrandCases($tests,$app,$owner,$alpha,$beta,$tenantA,$tenantB,$http,$password);
require __DIR__.'/trials.php';
trialCases($tests,$app,$config,$owner,$alpha,$beta,$tenantA,$tenantB,$http,$password);
require __DIR__.'/public-trial-signup.php';
publicTrialCases($tests,$app,$config,$owner,$alpha,$beta,$tenantB,$http);
require __DIR__.'/brand-builder.php';
brandBuilderCases($tests,$app,$config,$owner,$http);
require __DIR__.'/brand-downloads.php';
brandDownloadCases($tests,$app,$config,$owner,$http);
require __DIR__.'/brand-main-updates.php';
brandMainUpdateCases($tests,$app,$config,$owner);
require __DIR__.'/owner-trials.php';
ownerTrialCases($tests,$app,$config,$owner,$alpha);
require __DIR__.'/owner-workspace.php';
ownerWorkspaceCases($tests,$app,$config,$owner,$alpha,$tenantA,$http,$password);
require __DIR__.'/trial-controls.php';
trialControlCases($tests,$app,$config,$owner,$http,$password);
require __DIR__.'/build-progress.php';
buildProgressCases($tests,$app,$config,$owner,$alpha);
require __DIR__.'/production-brands.php';
productionBrandCases($tests,$app,$config,$owner);
require __DIR__.'/owner-unlimited.php';
ownerUnlimitedCases($tests,$app,$config,$owner,$alpha);
require __DIR__.'/simple-trials.php';
simpleTrialCases($tests,$app,$config,$owner,$alpha,$http,$password);
require __DIR__.'/global-trials.php';
globalTrialCases($tests,$app,$config,$owner,$http,$password);
require __DIR__.'/current-activity.php';
currentActivityCases($tests,$app,$owner,$alpha,$beta,$tenantA,$enrolled);
require __DIR__.'/module-licences.php';
moduleLicenceCases($tests,$app,$owner,$alpha,$beta,$tenantA,$http,$password,$fixture);
require __DIR__.'/primary-access.php';
primaryAccessCases($tests,$app,$config,$owner);
require __DIR__.'/device-recovery.php';
deviceRecoveryCases($tests,$app,$config,$owner,$beta,$fixture);
require __DIR__.'/rampex-contract.php';
rampexContractCases($tests);
$passed=0; $failures=[]; $started=microtime(true);
try {
    foreach($tests as $label=>$test) {
        try { $test(); $passed++; echo "PASS $label\n"; }
        catch(Throwable $error) { $failures[]=['test'=>$label,'type'=>get_class($error),'message'=>$error->getMessage()]; echo "FAIL $label: ",$error->getMessage(),"\n"; }
    }
    $report=['database_version'=>$server->query('SELECT VERSION()')->fetchColumn(),'passed'=>$passed,'failed'=>count($failures),'failures'=>$failures,'seconds'=>round(microtime(true)-$started,2)];
    if(!is_dir($root.'/test-results')) mkdir($root.'/test-results',0700);
    file_put_contents($root.'/test-results/integration.json',json_encode($report,JSON_PRETTY_PRINT|JSON_THROW_ON_ERROR));
    echo json_encode($report,JSON_PRETTY_PRINT|JSON_THROW_ON_ERROR),"\n";
} finally {
    $http->stop();
    if (!preg_match('/^control_test_[a-f0-9]{12}$/D',$name)) throw new RuntimeException('Refusing unexpected database cleanup name');
    $server->exec("DROP DATABASE `$name`");
    // Keep private fixture/logs under /tmp/opencode for debugging; never package them.
}
exit($failures ? 1 : 0);
