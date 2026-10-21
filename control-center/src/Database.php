<?php
declare(strict_types=1);
namespace ControlCenter;

use PDO;

final class Database
{
    public readonly PDO $pdo;

    public function __construct(Config $config)
    {
        $db = $config->get('database', []);
        if (!str_starts_with($db['dsn'] ?? '', 'mysql:') || !str_contains($db['dsn'], 'charset=utf8mb4')) {
            throw new Problem(503, 'Configure a dedicated MySQL/MariaDB database with utf8mb4.');
        }
        $options = [
            PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
            PDO::ATTR_EMULATE_PREPARES => false,
            PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
            PDO::ATTR_STRINGIFY_FETCHES => false,
            PDO::MYSQL_ATTR_MULTI_STATEMENTS => false,
        ];
        $parts = [];
        foreach (explode(';', substr($db['dsn'], 6)) as $part) {
            $pair = explode('=', $part, 2);
            if (count($pair) === 2) $parts[strtolower(trim($pair[0]))] = trim($pair[1]);
        }
        $local = isset($parts['unix_socket']) || in_array($parts['host'] ?? '', ['localhost', '127.0.0.1', '::1'], true);
        $requireTls = !$local || !empty($db['ssl_ca']);
        if ($requireTls) {
            $ca = $db['ssl_ca'] ?? (ini_get('openssl.cafile') ?: '/etc/ssl/certs/ca-certificates.crt');
            if (!is_string($ca) || !is_file($ca) || !is_readable($ca)) throw new Problem(503, 'Configure a readable CA certificate for the remote SQL connection.');
            $options[PDO::MYSQL_ATTR_SSL_CA] = $ca;
            $options[PDO::MYSQL_ATTR_SSL_VERIFY_SERVER_CERT] = true;
        }
        $this->pdo = new PDO($db['dsn'], $db['username'] ?? '', $db['password'] ?? '', $options);
        if ($requireTls) {
            $transport = $this->pdo->query("SHOW SESSION STATUS LIKE 'Ssl_cipher'")->fetch(PDO::FETCH_NUM);
            if (empty($transport[1])) throw new Problem(503, 'The SQL server did not negotiate an encrypted connection.');
        }
        $version = (string) $this->pdo->query('SELECT VERSION()')->fetchColumn();
        if (!self::supportedVersion($version)) throw new Problem(503, 'MariaDB 10.6+ or MySQL 8.0.16+ is required.');
        $this->pdo->exec("SET time_zone = '+00:00'");
    }

    public static function supportedVersion(string $version): bool
    {
        if (!preg_match('/^(?:5\.5\.5-)?(\d+\.\d+\.\d+)/', $version, $match)) return false;
        return version_compare($match[1], stripos($version, 'MariaDB') !== false ? '10.6.0' : '8.0.16', '>=');
    }

    public function query(string $sql, array $arguments = []): \PDOStatement
    {
        $query = $this->pdo->prepare($sql);
        $query->execute($arguments);
        return $query;
    }
    public function one(string $sql, array $arguments = []): ?array { return $this->query($sql, $arguments)->fetch() ?: null; }
    public function all(string $sql, array $arguments = []): array { return $this->query($sql, $arguments)->fetchAll(); }
    public function transaction(callable $work): mixed
    {
        $this->pdo->beginTransaction();
        try { $result = $work(); $this->pdo->commit(); return $result; }
        catch (\Throwable $error) { if ($this->pdo->inTransaction()) $this->pdo->rollBack(); throw $error; }
    }
    public static function id(): string { return bin2hex(random_bytes(16)); }
}
