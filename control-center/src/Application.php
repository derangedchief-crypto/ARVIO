<?php
declare(strict_types=1);
namespace ControlCenter;

final class Application
{
    public readonly Database $db;
    public readonly Auth $auth;
    public readonly Management $management;
    public readonly Commands $commands;
    public readonly Enrollment $enrollment;
    public function __construct(public readonly Config $config)
    {
        $this->db = new Database($config);
        if ((new Migrations($this->db, $config))->installed()) $config->secret('application.key', false);
        $this->auth = new Auth($this->db, $config, new Secrets($config));
        $this->management = new Management($this->db, $config, $this->auth);
        $this->commands = new Commands($this->db, $config, $this->auth, $this->management);
        $this->enrollment = new Enrollment($this->db,$config,$this->auth,$this->management,new Secrets($config));
    }

    public function serve(string $method, string $path): never
    {
        $migrations = new Migrations($this->db, $this->config);
        if ($method === 'GET' && $path === '/api/status') {
            $installed = $migrations->installed();
            if (!$installed) $this->config->secret('setup.key');
            Http::json(['configured' => true, 'installed' => $installed, 'version' => trim(file_get_contents($this->config->root . '/VERSION'))]);
        }
        if ($method === 'POST' && $path === '/api/setup') {
            Http::sameOrigin($this->config);
            $input = Http::body();
            Input::keys($input, ['setup_key', 'name', 'email', 'password']);
            $key = $input['setup_key'] ?? '';
            if (!is_string($key) || strlen($key) !== 64) throw new Problem(403, 'Invalid setup key.');
            unset($input['setup_key']);
            $this->auth->bootstrap($key, $input);
            Http::json(['ok' => true], 201);
        }
        if (!$migrations->installed()) throw new Problem(503, 'Owner setup is required.');
        if ($method === 'POST' && $path === '/api/login') {
            Http::sameOrigin($this->config);
            $result = $this->auth->login(Http::body(), $_SERVER['REMOTE_ADDR'] ?? 'unknown');
            Http::cookie($this->config, $result['token']);
            unset($result['token']);
            Http::json($result);
        }
        if($method==='POST'&&$path==='/api/device/enrollment/challenge') Http::json($this->enrollment->challenge(Http::body(),$_SERVER['REMOTE_ADDR']??''));
        if($method==='POST'&&$path==='/api/device/enrollment/register') Http::json($this->enrollment->register(Http::body(),$_SERVER['REMOTE_ADDR']??''),201);
        if($method==='POST'&&$path==='/api/device/enrollment/poll') Http::json($this->enrollment->poll(Http::body(),$_SERVER['REMOTE_ADDR']??''));
        if($method==='POST'&&$path==='/api/device/enrollment/confirm') Http::json($this->enrollment->poll(Http::body(),$_SERVER['REMOTE_ADDR']??'',true));
        if ($method === 'POST' && $path === '/api/device/check-in') {
            $credential=Http::bearer();
            try { Http::json($this->commands->checkIn($credential, Http::body(), $_SERVER['REMOTE_ADDR'] ?? '')); }
            catch(Problem $error) {
                if($error->status===401) {
                    $notice=$this->commands->removalNotice($credential);
                    if($notice!==null) Http::json($notice);
                }
                throw $error;
            }
        }
        if ($method === 'POST' && preg_match('~^/api/device/commands/([a-f0-9]{32})/ack$~D', $path, $match)) {
            Http::json($this->commands->acknowledge(Http::bearer(), $match[1], Http::body()));
        }
        $token = $_COOKIE[Http::COOKIE] ?? '';
        if (!is_string($token)) throw new Problem(401, 'Please sign in.');
        $actor = $this->auth->user($token);
        $this->auth->limit('web-session', $actor['id'], 300, 60);
        if ($method !== 'GET') {
            Http::sameOrigin($this->config);
            $this->auth->verifyCsrf($token, $_SERVER['HTTP_X_CSRF_TOKEN'] ?? '');
        }
        if ($method === 'GET' && $path === '/api/me') Http::json(['user' => $actor, 'csrf' => $this->auth->csrf($token)]);
        if($method==='GET'&&$path==='/api/system/status') { Auth::owner($actor); Http::json(['pending_migrations'=>$migrations->pending()]); }
        if($method==='POST'&&$path==='/api/system/migrate') {
            Auth::owner($actor); Input::keys(Http::body(),[]);
            $applied=$migrations->apply(); $this->auth->audit($actor,'system.database_upgraded',null,null,['migrations'=>$applied]);
            Http::json(['applied'=>$applied]);
        }
        if($method==='GET'&&$path==='/api/enrollments/settings') Http::json($this->enrollment->settings($actor));
        if($method==='POST'&&$path==='/api/enrollments/settings') Http::json($this->enrollment->setEnabled($actor,Http::body()));
        if($method==='POST'&&$path==='/api/enrollments/bridge-configuration') { Input::keys(Http::body(),[]); Http::json($this->enrollment->bridgeConfiguration($actor)); }
        if($method==='GET'&&$path==='/api/enrollments') Http::json($this->enrollment->listing($actor,$_GET));
        if($method==='POST'&&preg_match('~^/api/enrollments/([a-f0-9]{32})/approve$~D',$path,$match)) Http::json($this->enrollment->approve($actor,$match[1],Http::body()));
        if($method==='POST'&&preg_match('~^/api/enrollments/([a-f0-9]{32})/reject$~D',$path,$match)) { Input::keys(Http::body(),[]); Http::json($this->enrollment->reject($actor,$match[1])); }
        if ($method === 'POST' && $path === '/api/logout') {
            Input::keys(Http::body(), []);
            $this->auth->logout($token); Http::cookie($this->config, ''); Http::json(['ok' => true]);
        }
        if ($method === 'POST' && $path === '/api/password') {
            $this->auth->changePassword($actor, Http::body());
            Http::cookie($this->config, ''); Http::json(['ok' => true]);
        }
        if ($method === 'GET' && $path === '/api/overview') Http::json($this->management->overview($actor));
        if ($method === 'GET' && $path === '/api/tenants') Http::json($this->management->tenantListing($actor, $_GET));
        if ($method === 'POST' && $path === '/api/resellers') Http::json($this->management->createReseller($actor, Http::body()), 201);
        if ($method === 'POST' && preg_match('~^/api/tenants/([a-f0-9]{32})$~D', $path, $match)) Http::json($this->management->updateTenant($actor, $match[1], Http::body()));
        if ($method === 'GET' && $path === '/api/customers') Http::json($this->management->listing($actor, 'customers', $_GET));
        if ($method === 'POST' && $path === '/api/customers') Http::json($this->management->createCustomer($actor, Http::body()), 201);
        if ($method === 'GET' && preg_match('~^/api/customers/([a-f0-9]{32})$~D', $path, $match)) Http::json($this->management->customer($actor, $match[1]));
        if ($method === 'POST' && preg_match('~^/api/customers/([a-f0-9]{32})$~D', $path, $match)) Http::json($this->management->updateCustomer($actor, $match[1], Http::body()));
        if ($method === 'GET' && $path === '/api/devices') Http::json($this->management->listing($actor, 'devices', $_GET));
        if ($method === 'POST' && $path === '/api/devices') Http::json($this->management->createDevice($actor, Http::body()), 201);
        if ($method === 'GET' && preg_match('~^/api/devices/([a-f0-9]{32})$~D', $path, $match)) Http::json($this->management->device($actor, $match[1]));
        if ($method === 'POST' && preg_match('~^/api/devices/([a-f0-9]{32})$~D', $path, $match)) Http::json($this->management->updateDevice($actor, $match[1], Http::body()));
        if (preg_match('~^/api/devices/([a-f0-9]{32})/commands$~D', $path, $match)) {
            if ($method === 'GET') Http::json($this->commands->listing($actor, $match[1], $_GET));
            if ($method === 'POST') Http::json($this->commands->enqueue($actor, $match[1], Http::body(), $_SERVER['HTTP_IDEMPOTENCY_KEY'] ?? ''), 201);
        }
        if ($method === 'GET' && $path === '/api/audit') Http::json($this->management->audit($actor, $_GET));
        throw new Problem(404, 'Record not found.');
    }

    public function maintain(): array
    {
        $this->commands->expire();
        $sessions = $this->db->query('DELETE FROM sessions WHERE expires_at <= UTC_TIMESTAMP()')->rowCount();
        $this->db->query('DELETE FROM rate_limits WHERE expires_at <= UTC_TIMESTAMP()');
        $days = $this->config->number('retention_days', 90, 7, 365);
        $commands = $this->db->query("DELETE FROM commands WHERE status IN ('succeeded','failed','expired','cancelled') AND finished_at < DATE_SUB(UTC_TIMESTAMP(), INTERVAL $days DAY) LIMIT 1000")->rowCount();
        $events = $this->db->query("DELETE FROM audit_events WHERE created_at < DATE_SUB(UTC_TIMESTAMP(), INTERVAL $days DAY) LIMIT 1000")->rowCount();
        if((new Migrations($this->db,$this->config))->pending()===[]) $this->enrollment->maintain();
        return ['expired_sessions_removed' => $sessions, 'old_commands_removed' => $commands, 'old_audit_events_removed' => $events];
    }
}
