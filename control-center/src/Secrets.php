<?php
declare(strict_types=1);
namespace ControlCenter;

final class Secrets
{
    public function __construct(private readonly Config $config) {}
    public function mac(string $value): string { return hash_hmac('sha256', $value, hex2bin($this->config->secret('application.key'))); }

    public function encrypt(string $plaintext): string
    {
        $iv = random_bytes(12);
        $tag = '';
        $cipher = openssl_encrypt($plaintext, 'aes-256-gcm', hex2bin($this->config->secret('application.key')), OPENSSL_RAW_DATA, $iv, $tag, 'control-center:v1');
        if ($cipher === false) throw new \RuntimeException('Encryption failed');
        return 'v1.' . base64_encode($iv . $tag . $cipher);
    }
    public function decrypt(string $encrypted): string
    {
        $bytes = str_starts_with($encrypted, 'v1.') ? base64_decode(substr($encrypted, 3), true) : false;
        if ($bytes === false || strlen($bytes) < 28) throw new \RuntimeException('Invalid encrypted value');
        $result = openssl_decrypt(substr($bytes, 28), 'aes-256-gcm', hex2bin($this->config->secret('application.key')), OPENSSL_RAW_DATA,
            substr($bytes, 0, 12), substr($bytes, 12, 16), 'control-center:v1');
        if ($result === false) throw new \RuntimeException('Encrypted value could not be authenticated');
        return $result;
    }

    public static function redact(string $text): string
    {
        $text = preg_replace('~https?://[^\s<>]+~i', '[URL REDACTED]', $text);
        $text = preg_replace('/Bearer\s+[A-Za-z0-9._~+\/=\-]+/i', 'Bearer [REDACTED]', $text);
        $pattern = <<<'REGEX'
~(password|passwd|username|token|secret|authorization|activation[_ -]?code)["']?\s*[:=]\s*(?:"[^"]*"|'[^']*'|[^\r\n,;]+)~i
REGEX;
        return preg_replace($pattern, '$1=[REDACTED]', $text);
    }
}
