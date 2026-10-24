<?php
declare(strict_types=1);
namespace ControlCenter;

final class Config
{
    public readonly string $root;
    public readonly array $values;

    public function __construct(string $root, ?array $override = null)
    {
        $this->root = $root;
        $file = $root . '/config.php';
        $this->values = $override ?? (is_file($file) ? require $file : []);
        if (!is_array($this->values)) throw new Problem(503, 'Invalid private configuration.');
    }

    public function get(string $key, mixed $default = null): mixed { return $this->values[$key] ?? $default; }
    public function configured(): bool
    {
        return isset($this->values['database']['dsn'], $this->values['origin'])
            && ($this->local() || ($this->values['database']['password'] ?? '') !== '');
    }
    public function origin(): string { return rtrim((string) $this->get('origin', 'https://control.fecxbrfzku.net'), '/'); }
    public function local(): bool
    {
        return in_array($this->get('environment'), ['development', 'testing'], true)
            && in_array(parse_url($this->origin(), PHP_URL_HOST), ['127.0.0.1', 'localhost', '[::1]'], true);
    }
    public function number(string $key, int $default, int $min, int $max): int
    {
        $value = $this->get($key, $default);
        if (!is_int($value) || $value < $min || $value > $max) throw new Problem(503, 'Invalid server limits.');
        return $value;
    }

    public function privateFile(string $name): string
    {
        $directory = $this->root . '/storage';
        if (!is_dir($directory) && !mkdir($directory, 0700, true) && !is_dir($directory)) {
            throw new Problem(503, 'Private storage is not writable.');
        }
        return $directory . '/' . $name;
    }

    public function secret(string $name, bool $create = true): string
    {
        if (!in_array($name, ['application.key', 'setup.key'], true)) throw new \LogicException('Unknown key');
        $file = $this->privateFile($name);
        if (is_file($file)) {
            // Established keys are read-only inputs. Never reopen c+, chmod away an
            // operator's narrow read ACL, or regenerate an empty/corrupt original.
            $handle = fopen($file, 'rb');
            if (!$handle) throw new Problem(503, 'The original key is not readable.');
            try {
                if (!flock($handle, LOCK_SH)) throw new Problem(503, 'Private storage lock failed.');
                if (fstat($handle)['size'] > 1024) throw new Problem(503, 'Invalid private key. Restore the original key backup.');
                $secret = trim(stream_get_contents($handle, 1024));
                if (!preg_match('/^[a-f0-9]{64}$/D', $secret)) throw new Problem(503, 'Invalid private key. Restore the original key backup.');
                return $secret;
            } finally { flock($handle, LOCK_UN); fclose($handle); }
        }
        if (!$create) throw new Problem(503, 'The original application key is missing. Restore it from the private backup.');
        $mask = umask(0077);
        try { $handle = @fopen($file, 'x+b'); } finally { umask($mask); }
        if (!$handle) {
            if (is_file($file)) return $this->secret($name, false);
            throw new Problem(503, 'Private storage is not writable.');
        }
        chmod($file, 0600);
        try {
            if (!flock($handle, LOCK_EX)) throw new Problem(503, 'Private storage lock failed.');
            $secret = bin2hex(random_bytes(32));
            if (fwrite($handle, $secret . "\n") !== 65 || !fflush($handle)) {
                throw new Problem(503, 'Could not save private key.');
            }
            if (!preg_match('/^[a-f0-9]{64}$/D', $secret)) throw new Problem(503, 'Invalid private key. Restore the original key backup.');
            return $secret;
        } finally { flock($handle, LOCK_UN); fclose($handle); }
    }
}
