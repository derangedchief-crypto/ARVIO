<?php
declare(strict_types=1);

const HUB_COOKIE = '__Host-hub_manager';
const HUB_GUARD = "<?php http_response_code(404); exit; ?>\n";
const HUB_MESSAGE = 'We’re performing maintenance. Please wait; the app will reopen automatically.';

if (realpath($_SERVER['SCRIPT_FILENAME'] ?? '') === __FILE__) {
    http_response_code(404);
    exit;
}

final class HubError extends RuntimeException {
    public int $status;
    public function __construct(int $status, string $message) {
        parent::__construct($message);
        $this->status = $status;
    }
}

function hub_config(): array {
    $path = __DIR__ . '/config.php';
    if (!is_file($path)) throw new HubError(503, 'Missing config.php. Use the ready-to-upload website ZIP.');
    $config = require $path;
    if (!is_array($config) || !isset($config['origin'], $config['setup_key'])) throw new HubError(503, 'Invalid configuration');
    if ($config['setup_key'] === '') {
        // Bootstrap only on the destination server. A public download must never
        // contain a working owner/setup credential shared with its downloaders.
        $dir = __DIR__ . '/_private';
        $lock = fopen($dir . '/_lock.php', 'r');
        if ($lock === false || !flock($lock, LOCK_EX)) throw new HubError(503, 'Storage lock unavailable');
        try {
            $keyPath = $dir . '/setup-key.php';
            if (!is_file($keyPath)) {
                $tmp = $dir . '/.key-' . bin2hex(random_bytes(16)) . '.php';
                $content = HUB_GUARD . bin2hex(random_bytes(32));
                if (file_put_contents($tmp, $content, LOCK_EX) !== strlen($content)) { @unlink($tmp); throw new HubError(503, 'Make _private writable by the PHP website user in aaPanel.'); }
                chmod($tmp, 0600);
                if (!rename($tmp, $keyPath)) { @unlink($tmp); throw new HubError(503, 'Could not save private setup key'); }
            }
            $raw = file_get_contents($keyPath);
            if ($raw === false || !str_starts_with($raw, HUB_GUARD)) throw new HubError(503, 'Private setup key unavailable');
            $config['setup_key'] = trim(substr($raw, strlen(HUB_GUARD)));
            if (!preg_match('/^[a-f0-9]{64}$/D', $config['setup_key'])) throw new HubError(503, 'Invalid private setup key');
        } finally { flock($lock, LOCK_UN); fclose($lock); }
    }
    return $config;
}

function hub_headers(): void {
    header('Cache-Control: no-store, max-age=0');
    header('X-Content-Type-Options: nosniff');
    header('Referrer-Policy: no-referrer');
    header('X-Frame-Options: DENY');
    header("Content-Security-Policy: default-src 'none'; script-src 'self'; style-src 'unsafe-inline'; connect-src 'self'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'");
    header('Strict-Transport-Security: max-age=31536000');
}

function hub_reply(int $status, array $body): void {
    http_response_code($status);
    header('Content-Type: application/json; charset=utf-8');
    echo json_encode($body, JSON_UNESCAPED_UNICODE | JSON_THROW_ON_ERROR);
}

function hub_initial(): array {
    return ['admins' => [], 'sessions' => [], 'attempts' => [], 'audit' => [], 'maintenance' => [
        'active' => false, 'message' => HUB_MESSAGE, 'revision' => 0, 'updated_at' => 0, 'updated_by' => '',
    ]];
}

/** Guarded PHP files, atomic replacement, and a separate permanent lock file.
 * No JSON/password/session file can be downloaded through a PHP-enabled site.
 */
function hub_store(callable $operation, bool $write = false): mixed {
    $dir = __DIR__ . '/_private';
    $lock = fopen($dir . '/_lock.php', 'r');
    if ($lock === false || !flock($lock, LOCK_EX)) throw new HubError(503, 'Storage lock unavailable');
    try {
        $path = $dir . '/state.php';
        $state = hub_initial();
        if (is_file($path)) {
            $raw = file_get_contents($path);
            if ($raw === false || !str_starts_with($raw, HUB_GUARD)) throw new HubError(503, 'Stored state is unavailable');
            $state = json_decode(substr($raw, strlen(HUB_GUARD)), true, 32, JSON_THROW_ON_ERROR);
            if (!is_array($state) || !isset($state['maintenance'], $state['admins'])) throw new HubError(503, 'Invalid stored state');
        }
        $result = $operation($state);
        if ($write) {
            $tmp = $dir . '/.state-' . bin2hex(random_bytes(16)) . '.php';
            $content = HUB_GUARD . json_encode($state, JSON_UNESCAPED_UNICODE | JSON_THROW_ON_ERROR);
            if (file_put_contents($tmp, $content, LOCK_EX) !== strlen($content)) {
                @unlink($tmp);
                throw new HubError(503, 'Storage is not writable. Set the _private folder owner to your PHP website user in aaPanel.');
            }
            chmod($tmp, 0600);
            if (!rename($tmp, $path)) { @unlink($tmp); throw new HubError(503, 'Could not save state'); }
        }
        return $result;
    } finally {
        flock($lock, LOCK_UN);
        fclose($lock);
    }
}

function hub_admin(array $state): string {
    $token = $_COOKIE[HUB_COOKIE] ?? '';
    $session = $state['sessions'][hash('sha256', $token)] ?? null;
    if (!$session || $session['expires'] <= time() || !isset($state['admins'][$session['admin']])) throw new HubError(401, 'Sign in required');
    return $session['admin'];
}

function hub_credentials(mixed $name, mixed $password): void {
    if (!is_string($name) || !preg_match('/^[a-zA-Z0-9_.-]{1,60}$/D', $name) || !is_string($password)
        || strlen($password) < 16 || strlen($password) > 72) throw new HubError(400, 'Use usernames with letters/numbers and distinct passwords of 16–72 characters.');
}

function hub_epg_request(array $config, bool $refresh): array {
    $secret = $config['epg_worker_secret'] ?? '';
    $base = rtrim($config['epg_worker_url'] ?? '', '/');
    if (strlen($secret) < 32 || !preg_match('#^https://[a-z0-9.-]+(?::[0-9]+)?$#iD', $base)) throw new HubError(503, 'EPG worker connection is not configured. Install the worker update, then set epg_worker_url and epg_worker_secret in config.php.');
    $url = $base . '/epg/admin/' . ($refresh ? 'refresh' : 'status');
    $headers = ['Authorization: Bearer ' . $secret, 'Accept: application/json', 'Content-Type: application/json'];
    if (function_exists('curl_init')) {
        $ch = curl_init($url);
        curl_setopt_array($ch, [CURLOPT_RETURNTRANSFER => true, CURLOPT_FOLLOWLOCATION => false,
            CURLOPT_CONNECTTIMEOUT => 3, CURLOPT_TIMEOUT => 10, CURLOPT_HTTPHEADER => $headers,
            CURLOPT_SSL_VERIFYPEER => true, CURLOPT_SSL_VERIFYHOST => 2]);
        if ($refresh) curl_setopt_array($ch, [CURLOPT_POST => true, CURLOPT_POSTFIELDS => '{}']);
        $raw = curl_exec($ch); $code = curl_getinfo($ch, CURLINFO_HTTP_CODE); curl_close($ch);
    } else {
        $context = stream_context_create(['http' => ['method' => $refresh ? 'POST' : 'GET',
            'header' => implode("\r\n", $headers), 'content' => $refresh ? '{}' : '',
            'timeout' => 10, 'ignore_errors' => true, 'follow_location' => 0],
            'ssl' => ['verify_peer' => true, 'verify_peer_name' => true]]);
        $raw = @file_get_contents($url, false, $context, 0, 524289);
        preg_match('#HTTP/\S+\s+(\d+)#', $http_response_header[0] ?? '', $match);
        $code = intval($match[1] ?? 0);
    }
    if ($raw === false || strlen($raw) > 524288 || $code < 200 || $code >= 300) throw new HubError(502,
        $code === 401 ? 'EPG worker rejected the connection secret. Check both server configurations.' : 'EPG worker unavailable or not updated (HTTP ' . $code . '). Existing guide cache is unchanged.');
    $data = json_decode($raw, true);
    if (!is_array($data)) throw new HubError(502, 'Invalid EPG worker response');
    return $data;
}

function hub_run(string $action): void {
    hub_headers();
    try {
        $config = hub_config();
        $post = in_array($action, ['login', 'logout', 'update', 'setup', 'epg-refresh'], true);
        if (($_SERVER['REQUEST_METHOD'] ?? '') !== ($post ? 'POST' : 'GET')) throw new HubError(405, 'Method not allowed');
        $body = [];
        if ($post) {
            if (($_SERVER['HTTP_ORIGIN'] ?? '') !== rtrim($config['origin'], '/') || ($_SERVER['HTTP_X_HUB_MANAGER'] ?? '') !== '1') throw new HubError(403, 'Forbidden origin');
            $raw = file_get_contents('php://input', false, null, 0, 8193);
            if ($raw === false || strlen($raw) > 8192) throw new HubError(413, 'Request too large');
            try { $body = json_decode($raw, true, 16, JSON_THROW_ON_ERROR); }
            catch (JsonException $error) { throw new HubError(400, 'Invalid JSON'); }
            if (!is_array($body)) throw new HubError(400, 'Invalid request');
        }

        if ($action === 'status') {
            $state = hub_store(fn(array &$s) => $s['maintenance']);
            hub_reply(200, array_intersect_key($state, array_flip(['active', 'message', 'revision'])));
        } elseif ($action === 'admin-status') {
            hub_reply(200, hub_store(function (array &$s) { hub_admin($s); return $s['maintenance']; }));
        } elseif ($action === 'setup') {
            $key = $body['setup_key'] ?? '';
            if (!is_string($key) || strlen($config['setup_key']) < 64 || !hash_equals($config['setup_key'], $key)) throw new HubError(403, 'Invalid setup key. Copy it from _private/setup-key.php using aaPanel File Manager.');
            $first = $body['owner'] ?? '';
            $second = $body['second'] ?? '';
            $firstPassword = $body['owner_password'] ?? '';
            $secondPassword = $body['second_password'] ?? '';
            hub_credentials($first, $firstPassword);
            hub_credentials($second, $secondPassword);
            if ($first === $second || $firstPassword === $secondPassword) throw new HubError(400, 'Use different usernames and passwords for the two admins.');
            hub_store(function (array &$s) use ($first, $second, $firstPassword, $secondPassword) {
                // The file-manager setup key is also the owner's recovery key.
                // Replacing accounts preserves the maintenance flag and audit.
                $s['admins'] = [$first => password_hash($firstPassword, PASSWORD_DEFAULT), $second => password_hash($secondPassword, PASSWORD_DEFAULT)];
                $s['sessions'] = [];
                $s['attempts'] = [];
                return null;
            }, true);
            hub_reply(200, ['ok' => true]);
        } elseif ($action === 'login') {
            $name = $body['username'] ?? '';
            $password = $body['password'] ?? '';
            if (!is_string($name) || !is_string($password) || strlen($name) > 80 || strlen($password) > 256) throw new HubError(400, 'Invalid credentials');
            // Use PHP's real remote address; never trust a browser-supplied XFF.
            $ip = hash('sha256', $_SERVER['REMOTE_ADDR'] ?? 'unknown');
            $token = bin2hex(random_bytes(48));
            $outcome = hub_store(function (array &$s) use ($name, $password, $ip, $token) {
                $now = time();
                $s['attempts'] = array_values(array_filter($s['attempts'], fn($a) => $a['at'] > $now - 900));
                if (count(array_filter($s['attempts'], fn($a) => $a['ip'] === $ip)) >= 10) return 429;
                $s['attempts'][] = ['ip' => $ip, 'at' => $now];
                $hash = $s['admins'][$name] ?? (array_values($s['admins'])[0] ?? '$2y$12$92IXUNpkjO0rOQ5byMi.Ye4oKoEa3Ro9llC/.og/at2uheWG/igi.');
                $valid = password_verify($password, $hash);
                if (!$valid || !isset($s['admins'][$name])) return 401;
                $s['sessions'] = array_filter($s['sessions'], fn($session) => $session['expires'] > $now);
                $s['sessions'][hash('sha256', $token)] = ['admin' => $name, 'expires' => $now + 43200];
                return 200;
            }, true);
            if ($outcome !== 200) throw new HubError($outcome, $outcome === 429 ? 'Too many attempts. Wait 15 minutes.' : 'Invalid credentials');
            setcookie(HUB_COOKIE, $token, ['expires' => time() + 43200, 'path' => '/', 'secure' => true, 'httponly' => true, 'samesite' => 'Strict']);
            hub_reply(200, ['ok' => true]);
        } elseif ($action === 'logout') {
            hub_store(function (array &$s) { unset($s['sessions'][hash('sha256', $_COOKIE[HUB_COOKIE] ?? '')]); return null; }, true);
            setcookie(HUB_COOKIE, '', ['expires' => 1, 'path' => '/', 'secure' => true, 'httponly' => true, 'samesite' => 'Strict']);
            hub_reply(200, ['ok' => true]);
        } elseif ($action === 'update') {
            $state = hub_store(function (array &$s) use ($body) {
                $actor = hub_admin($s);
                if (!isset($body['active'], $body['revision'], $body['message']) || !is_bool($body['active']) || !is_int($body['revision']) || !is_string($body['message']) || strlen($body['message']) > 2000
                    || preg_match_all('/./us', $body['message']) > 500) throw new HubError(400, 'Invalid maintenance status or message (maximum 500 characters)');
                if ($body['revision'] !== $s['maintenance']['revision']) throw new HubError(409, 'Another admin changed the status. Reload before changing it again.');
                $s['maintenance'] = ['active' => $body['active'], 'message' => trim($body['message']) ?: HUB_MESSAGE,
                    'revision' => $body['revision'] + 1, 'updated_at' => time(), 'updated_by' => $actor];
                $s['audit'][] = $s['maintenance'];
                $s['audit'] = array_slice($s['audit'], -1000);
                return $s['maintenance'];
            }, true);
            hub_reply(200, $state);
        } elseif ($action === 'epg-status' || $action === 'epg-refresh') {
            hub_store(function (array &$s) { return hub_admin($s); });
            hub_reply(200, hub_epg_request($config, $action === 'epg-refresh'));
        } else throw new HubError(404, 'Not found');
    } catch (HubError $error) {
        hub_reply($error->status, ['detail' => $error->getMessage()]);
    } catch (Throwable $error) {
        error_log('Hub Manager: ' . get_class($error));
        hub_reply(503, ['detail' => 'Service/storage unavailable. Check PHP website permissions and server logs.']);
    }
}
