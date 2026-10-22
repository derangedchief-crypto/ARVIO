<?php
declare(strict_types=1);

/** Launches an isolated copy of the application; never writes config.php
 * into the developer's checkout or connects to the user's aaPanel website. */
final class HttpFixture
{
    public readonly string $origin;
    private mixed $process;
    private array $pipes = [];
    public function __construct(private readonly string $root, array $settings)
    {
        $probe = stream_socket_server('tcp://127.0.0.1:0', $code, $message);
        if (!$probe) throw new RuntimeException('Unable to reserve local test port');
        $port = (int) substr(strrchr(stream_socket_get_name($probe, false), ':'), 1);
        fclose($probe);
        $this->origin = 'http://127.0.0.1:' . $port;
        $settings['origin'] = $this->origin;
        file_put_contents($root . '/config.php', "<?php\nreturn " . var_export($settings, true) . ";\n");
        chmod($root . '/config.php', 0600);
        $args = [PHP_BINARY, '-n', '-d', 'extension_dir=' . ini_get('extension_dir')];
        foreach (['ctype','fileinfo','mbstring','pdo','mysqlnd','pdo_mysql','curl'] as $extension) array_push($args, '-d', 'extension=' . $extension);
        array_push($args, '-d', 'date.timezone=UTC', '-S', '127.0.0.1:' . $port, '-t', $root . '/public', $root . '/dev/router.php');
        $this->process = proc_open($args, [0 => ['file','/dev/null','r'], 1 => ['file',$root.'/http.log','a'], 2 => ['file',$root.'/http.log','a']], $this->pipes, $root);
        if (!is_resource($this->process)) throw new RuntimeException('Could not start test HTTP server');
        $ready = false;
        for ($i = 0; $i < 50; $i++) {
            $connection = @stream_socket_client('tcp://127.0.0.1:' . $port, $code, $message, 0.1);
            if ($connection) { fclose($connection); $ready = true; break; }
            usleep(50000);
        }
        if (!$ready) throw new RuntimeException('Local PHP server did not become ready');
    }
    public function request(string $method, string $path, mixed $body = null, array $headers = [], string $identity = 'anonymous'): array
    {
        $curl = curl_init($this->origin . $path);
        $responseHeaders = [];
        $lines = ['Accept: application/json'];
        if ($method !== 'GET') $lines = array_merge($lines, ['Origin: ' . $this->origin, 'Content-Type: application/json']);
        foreach ($headers as $key => $value) {
            $lines = array_values(array_filter($lines, static fn($line) => !str_starts_with(strtolower($line), strtolower($key) . ':')));
            $lines[] = $key . ': ' . $value;
        }
        $cookies = $this->root . '/cookies-' . $identity;
        curl_setopt_array($curl, [CURLOPT_RETURNTRANSFER=>true, CURLOPT_CUSTOMREQUEST=>$method, CURLOPT_HTTPHEADER=>$lines,
            CURLOPT_TIMEOUT=>10, CURLOPT_COOKIEFILE=>$cookies, CURLOPT_COOKIEJAR=>$cookies,
            CURLOPT_HEADERFUNCTION=>static function ($handle, string $line) use (&$responseHeaders): int {
                $parts = explode(':', $line, 2); if (count($parts) === 2) $responseHeaders[strtolower($parts[0])][] = trim($parts[1]); return strlen($line);
            }]);
        if ($method !== 'GET') curl_setopt($curl, CURLOPT_POSTFIELDS, json_encode($body ?? new stdClass(), JSON_THROW_ON_ERROR | JSON_UNESCAPED_UNICODE));
        $raw = curl_exec($curl);
        if ($raw === false) throw new RuntimeException('HTTP fixture failed: ' . curl_error($curl));
        $status = curl_getinfo($curl, CURLINFO_RESPONSE_CODE);
        curl_close($curl);
        return ['status'=>$status, 'headers'=>$responseHeaders, 'raw'=>$raw, 'body'=>json_decode($raw, true)];
    }
    public function stop(): void { if (is_resource($this->process)) { proc_terminate($this->process); proc_close($this->process); $this->process = null; } }
    public function __destruct() { $this->stop(); }
}

function httpCases(HttpFixture $http, array &$tests, string $password, ControlCenter\Application $app, array $owner, array $alpha, array $beta, array $tenantA, array $tenantB, callable $makeDevice): void
{
    $sessions=[];
    $tests['HTTP health reports only installation/version, never secrets'] = function () use ($http): void { $r=$http->request('GET','/api/status'); same(200,$r['status']); same(true,$r['body']['installed']); check(!isset($r['body']['database'],$r['body']['setup_key'])); };
    $tests['HTTP login issues HttpOnly SameSite cookie without exposing session token in JSON'] = function () use ($http,$password,&$sessions): void {
        foreach(['owner','alpha','beta'] as $user) { $r=$http->request('POST','/api/login',['email'=>$user.'@example.test','password'=>$password],[],$user); same(200,$r['status']); check(!isset($r['body']['token'])); $sessions[$user]=$r['body']; $cookie=implode(';',$r['headers']['set-cookie']??[]); check(str_contains(strtolower($cookie),'httponly')&&str_contains(strtolower($cookie),'samesite=strict')); }
    };
    $tests['HTTP unauthenticated data access is denied'] = function () use ($http): void { same(401,$http->request('GET','/api/devices')['status']); same(401,$http->request('GET','/api/tenants')['status']); };
    $tests['HTTP security headers disallow framing, external scripts and response caching'] = function () use ($http): void { $r=$http->request('GET','/'); same(200,$r['status']); same(['DENY'],$r['headers']['x-frame-options']); check(str_contains($r['headers']['content-security-policy'][0],"frame-ancestors 'none'")); check(str_contains($r['headers']['cache-control'][0],'no-store')); check(!isset($r['headers']['access-control-allow-origin'])); };
    $tests['HTTP private config, keys and SQL files are not downloadable'] = function () use ($http,$app): void { foreach(['/config.php','/config.local.php','/storage/application.key','/storage/setup.key','/database/migrations/001_foundation.sql','/src/Auth.php','/dev/router.php','/../config.php'] as $path) { $r=$http->request('GET',$path); check($r['status']!==200,'Private path returned 200: '.$path); check(!str_contains($r['raw'],$app->config->secret('application.key'))); check(!str_contains($r['raw'],'<?php')); } };
    $tests['HTTP Host spoofing and cross-origin writes are rejected'] = function () use ($http,&$sessions): void { same(400,$http->request('GET','/api/me',null,['Host'=>'attacker.example.test'],'owner')['status']); same(403,$http->request('POST','/api/customers',['name'=>'No'],['Origin'=>'https://attacker.test','X-CSRF-Token'=>$sessions['owner']['csrf']],'owner')['status']); };
    $tests['HTTP JSON content-type and CSRF are mandatory for browser writes'] = function () use ($http,&$sessions): void { same(415,$http->request('POST','/api/customers',[],['Content-Type'=>'text/plain'],'owner')['status']); same(403,$http->request('POST','/api/customers',[],[],'owner')['status']); same(403,$http->request('POST','/api/customers',[],['X-CSRF-Token'=>$sessions['alpha']['csrf']],'owner')['status']); };
    $tests['HTTP valid owner/reseller reads are scoped by authenticated session'] = function () use ($http,$tenantA): void { $r=$http->request('GET','/api/tenants',null,[],'alpha'); same(200,$r['status']); same(1,count($r['body']['items'])); same($tenantA['id'],$r['body']['items'][0]['id']); same(200,$http->request('GET','/api/overview',null,[],'owner')['status']); };
    $tests['HTTP reseller cannot create reseller accounts or suspend another workspace'] = function () use ($http,&$sessions,$tenantB): void { $headers=['X-CSRF-Token'=>$sessions['alpha']['csrf']]; same(403,$http->request('POST','/api/resellers',['company'=>'No'],$headers,'alpha')['status']); same(403,$http->request('POST','/api/tenants/'.$tenantB['id'],['status'=>'suspended','device_limit'=>100],$headers,'alpha')['status']); };
    $tests['HTTP customer creation rejects unknown fields and cross-tenant ownership'] = function () use ($http,&$sessions,$tenantA,$tenantB): void { $headers=['X-CSRF-Token'=>$sessions['alpha']['csrf']]; same(404,$http->request('POST','/api/customers',['tenant_id'=>$tenantB['id'],'name'=>'No'],$headers,'alpha')['status']); same(422,$http->request('POST','/api/customers',['name'=>'No','role'=>'owner'],$headers,'alpha')['status']); $r=$http->request('POST','/api/customers',['name'=>'HTTP-owned customer'],$headers,'alpha'); same(201,$r['status']); same($tenantA['id'],$r['body']['tenant_id']); same(404,$http->request('GET','/api/customers/'.$r['body']['id'],null,[],'beta')['status']); };
    $tests['HTTP pagination rejects array/injection values and prevents foreign tenant filtering'] = function () use ($http,$tenantB): void { same(422,$http->request('GET','/api/devices?page%5B%5D=1',null,[],'alpha')['status']); same(422,$http->request('GET','/api/devices?page=1%20OR%201=1',null,[],'alpha')['status']); same(404,$http->request('GET','/api/devices?tenant_id='.$tenantB['id'],null,[],'alpha')['status']); };
    $tests['HTTP oversize writes are rejected before storing customer data'] = function () use ($http,&$sessions): void { same(413,$http->request('POST','/api/customers',['name'=>'Too big','notes'=>str_repeat('x',40000)],['X-CSRF-Token'=>$sessions['alpha']['csrf']],'alpha')['status']); };
    $tests['HTTP device credentials are not interchangeable with browser sessions'] = function () use ($http): void { same(401,$http->request('POST','/api/device/check-in',[],[],'owner')['status']); };
    $tests['HTTP production enrollment and test-token issuance endpoints do not exist yet'] = function () use ($http,&$sessions): void { same(404,$http->request('POST','/api/device/test-enroll',[],['X-CSRF-Token'=>$sessions['owner']['csrf']],'owner')['status']); same(404,$http->request('POST','/api/device/enroll',[],['X-CSRF-Token'=>$sessions['owner']['csrf']],'owner')['status']); };
    $tests['HTTP command endpoint applies ownership and idempotency requirements'] = function () use ($http,&$sessions,$app,$owner,$alpha,$tenantA,$makeDevice): void {
        $d=$makeDevice($alpha,$tenantA['id']); $token=$app->commands->issueTestCredential($owner,$d['id']);
        $r=$http->request('POST','/api/device/check-in',['model'=>'LOCAL TEST SIMULATOR','capabilities'=>['request_checkin']],['Authorization'=>'Bearer '.$token]); same(200,$r['status']);
        $path='/api/devices/'.$d['id'].'/commands'; $body=['kind'=>'request_checkin']; $headers=['X-CSRF-Token'=>$sessions['alpha']['csrf']];
        same(422,$http->request('POST',$path,$body,$headers,'alpha')['status']);
        $headers['Idempotency-Key']=ControlCenter\Database::id(); $a=$http->request('POST',$path,$body,$headers,'alpha'); $b=$http->request('POST',$path,$body,$headers,'alpha'); same(201,$a['status']); same($a['body']['id'],$b['body']['id']);
        same(404,$http->request('GET',$path,null,[],'beta')['status']);
        $delivered=$http->request('POST','/api/device/check-in',['capabilities'=>['request_checkin']],['Authorization'=>'Bearer '.$token]); same(200,$delivered['status']);
        $commands=$delivered['body']['commands']; same($a['body']['id'],$commands[0]['id']);
        $raw=json_decode($delivered['raw']); check($raw->commands[0]->payload instanceof stdClass,'Empty payload must be a JSON object');
        $ack=$http->request('POST','/api/device/commands/'.$a['body']['id'].'/ack',['lease_token'=>$commands[0]['lease_token'],'status'=>'succeeded','summary'=>'Test completed'],['Authorization'=>'Bearer '.$token]); same(200,$ack['status']); same('succeeded',$ack['body']['status']);
    };
    $tests['HTTP changing password revokes all sessions without deleting customer/device data'] = function () use ($http,&$sessions,$app,$tenantB,$password): void {
        $before=(int)$app->db->query('SELECT COUNT(*) FROM customers WHERE tenant_id=?',[$tenantB['id']])->fetchColumn();
        $r=$http->request('POST','/api/password',['current_password'=>$password,'new_password'=>'Replacement-Test-Password-43!'],['X-CSRF-Token'=>$sessions['beta']['csrf']],'beta'); same(200,$r['status']); same(401,$http->request('GET','/api/me',null,[],'beta')['status']);
        same($before,(int)$app->db->query('SELECT COUNT(*) FROM customers WHERE tenant_id=?',[$tenantB['id']])->fetchColumn());
    };
    $tests['HTTP logout invalidates current session'] = function () use ($http,&$sessions): void { same(200,$http->request('POST','/api/logout',new stdClass(),['X-CSRF-Token'=>$sessions['owner']['csrf']],'owner')['status']); same(401,$http->request('GET','/api/me',null,[],'owner')['status']); };
}
