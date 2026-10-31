<?php
declare(strict_types=1);

ini_set('display_errors', '0');
ini_set('log_errors', '1');
if (PHP_VERSION_ID < 80300) {
    http_response_code(503);
    header('Content-Type: text/plain; charset=utf-8');
    echo 'PHP 8.3 or newer is required.';
    exit;
}
require dirname(__DIR__) . '/src/bootstrap.php';

use ControlCenter\Application;
use ControlCenter\Config;
use ControlCenter\Http;
use ControlCenter\Problem;

Http::headers();
$requestId = bin2hex(random_bytes(8));
header('X-Request-ID: ' . $requestId);
set_error_handler(static function (int $severity, string $message, string $file, int $line): bool {
    if (!(error_reporting() & $severity)) return false;
    throw new ErrorException($message, 0, $severity, $file, $line);
});
try {
    if (PHP_VERSION_ID < 80300) throw new Problem(503, 'PHP 8.3 or newer is required.');
    foreach (['pdo_mysql', 'mbstring', 'openssl', 'fileinfo', 'curl'] as $extension) {
        if (!extension_loaded($extension)) throw new Problem(503, 'Enable required PHP extensions: PDO/MySQL, mbstring, OpenSSL, fileinfo and cURL.');
    }
    $config = new Config(dirname(__DIR__));
    $version=trim(file_get_contents(dirname(__DIR__).'/VERSION'));
    if(!preg_match('/^[0-9]+\.[0-9]+\.[0-9]+$/D',$version))throw new Problem(503,'Invalid website version.');
    header('X-Control-Version: '.$version);
    Http::guard($config);
    $path = parse_url($_SERVER['REQUEST_URI'] ?? '/', PHP_URL_PATH);
    $method = $_SERVER['REQUEST_METHOD'] ?? 'GET';
    if ($method === 'GET' && $path === '/favicon.ico') {
        http_response_code(204); exit;
    }
    if ($method === 'GET' && $path === '/') {
        header('Content-Type: text/html; charset=utf-8');
        $html=file_get_contents(__DIR__.'/index.html');
        $html=preg_replace('~(/assets/[^" ?]+)\?v=[0-9.]+~','${1}?v='.$version,$html);
        $html=preg_replace('~(<meta name="control-version" content=")[^"]*~','${1}'.$version,$html);
        $html=preg_replace('~Device management · [0-9.]+~','Device management · '.$version,$html);
        $html=preg_replace('~Version [0-9.]+(?=</span>)~','Version '.$version,$html);
        echo $html;exit;
    }
    if (!$config->configured()) {
        if ($method === 'GET' && $path === '/api/status') Http::json(['configured' => false, 'installed' => false]);
        throw new Problem(503, 'Enter the database password in config.php beside public/ before owner setup.');
    }
    (new Application($config))->serve($method, $path);
} catch (Problem $error) {
    if ($error->status >= 500) error_log(json_encode(['request_id' => $requestId, 'type' => 'StartupProblem', 'hint' => $error->getMessage()]));
    if ($error->status === 429) header('Retry-After: 60');
    Http::json(['error' => $error->getMessage(), 'request_id' => $requestId], $error->status);
} catch (Throwable $error) {
    // Do not log SQL text/parameters, credentials, headers or request bodies.
    error_log(json_encode(['request_id' => $requestId, 'type' => get_class($error), 'code' => (string) $error->getCode(), 'line' => $error->getLine(), 'file' => basename($error->getFile())]));
    $conflict = $error instanceof PDOException && $error->getCode() === '23000';
    Http::json(['error' => $conflict ? 'This operation conflicts with an existing record.' : 'The request could not be completed. Check the private server log using the request ID.', 'request_id' => $requestId], $conflict ? 409 : 503);
}
