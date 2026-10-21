<?php
declare(strict_types=1);
namespace ControlCenter;

final class Http
{
    public const COOKIE = 'control_session';
    public static function headers(): void
    {
        header_remove('X-Powered-By');
        header('Cache-Control: no-store, private');
        header('X-Content-Type-Options: nosniff');
        header('X-Frame-Options: DENY');
        header('Referrer-Policy: no-referrer');
        header("Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'; object-src 'none'");
        header('Permissions-Policy: camera=(), microphone=(), geolocation=()');
    }
    public static function guard(Config $config): void
    {
        if (!$config->local()) {
            if (parse_url($config->origin(), PHP_URL_SCHEME) !== 'https' || (($_SERVER['HTTPS'] ?? '') !== 'on' && (int) ($_SERVER['SERVER_PORT'] ?? 0) !== 443)) {
                throw new Problem(503, 'HTTPS must be enabled for this control center.');
            }
            if (realpath($_SERVER['DOCUMENT_ROOT'] ?? '') !== realpath($config->root . '/public')) {
                throw new Problem(503, 'Set the aaPanel website running directory to public/.');
            }
            header('Strict-Transport-Security: max-age=31536000');
        }
        $authority = parse_url($config->origin(), PHP_URL_HOST);
        $port = parse_url($config->origin(), PHP_URL_PORT);
        if ($port !== null) $authority .= ':' . $port;
        if (strtolower($_SERVER['HTTP_HOST'] ?? '') !== strtolower($authority)) throw new Problem(400, 'Unexpected website host.');
        if (strlen($_SERVER['QUERY_STRING'] ?? '') > 2048) throw new Problem(414, 'Query is too long.');
    }
    public static function sameOrigin(Config $config): void
    {
        if (($_SERVER['HTTP_ORIGIN'] ?? '') !== $config->origin()) throw new Problem(403, 'Request origin could not be verified.');
        if (strtolower(trim(explode(';', $_SERVER['CONTENT_TYPE'] ?? '')[0])) !== 'application/json') throw new Problem(415, 'Use an application/json request.');
    }
    public static function body(): array
    {
        if ((int) ($_SERVER['CONTENT_LENGTH'] ?? 0) > 32768) throw new Problem(413, 'Request is too large.');
        $raw = file_get_contents('php://input', false, null, 0, 32769);
        if (strlen($raw) > 32768) throw new Problem(413, 'Request is too large.');
        try {
            if (!(json_decode($raw, false, 20, JSON_THROW_ON_ERROR) instanceof \stdClass)) throw new \JsonException();
            return json_decode($raw, true, 20, JSON_THROW_ON_ERROR);
        } catch (\JsonException) { throw new Problem(400, 'Invalid JSON object.'); }
    }
    public static function json(mixed $value, int $status = 200): never
    {
        http_response_code($status);
        header('Content-Type: application/json; charset=utf-8');
        echo json_encode($value, JSON_THROW_ON_ERROR | JSON_UNESCAPED_SLASHES);
        exit;
    }
    public static function cookie(Config $config, string $token): void
    {
        setcookie(self::COOKIE, $token, ['expires' => $token === '' ? time() - 3600 : time() + $config->number('session_hours', 12, 1, 24) * 3600,
            'path' => '/', 'secure' => !$config->local(), 'httponly' => true, 'samesite' => 'Strict']);
    }
    public static function bearer(): string
    {
        $header = $_SERVER['HTTP_AUTHORIZATION'] ?? '';
        if (!preg_match('/^Bearer ([a-f0-9]{64})$/D', $header, $match)) throw new Problem(401, 'Device authorization required.');
        return $match[1];
    }
}
