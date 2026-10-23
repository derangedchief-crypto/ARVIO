<?php
declare(strict_types=1);
namespace ControlCenter;

use PDO;

final class Database
{
    public readonly PDO $pdo;
    private int $savepoint = 0;

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
            if (!is_string($ca) || !@is_file($ca) || !@is_readable($ca)) {
                throw new Problem(503, 'PHP cannot read the database CA certificate. aaPanel open_basedir may block the system CA bundle. Set ssl_ca in config.php to a trusted CA file inside private storage/, or use a local database connection if MySQL runs on this same server.');
            }
            $options[PDO::MYSQL_ATTR_SSL_CA] = $ca;
            $options[PDO::MYSQL_ATTR_SSL_VERIFY_SERVER_CERT] = true;
        }
        try {
            $this->pdo = new PDO($db['dsn'], $db['username'] ?? '', $db['password'] ?? '', $options);
        } catch (\PDOException $error) {
            throw self::connectionProblem($error);
        }
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

    /** Inspect driver text internally, but return only fixed credential-free hints. */
    public static function connectionProblem(\PDOException $error): Problem
    {
        $code = (int) ($error->errorInfo[1] ?? $error->getCode());
        $message = strtolower($error->getMessage());
        if ($code === 2026 || preg_match('/ssl|tls|certificate/', $message)) {
            $hint = 'MySQL TLS negotiation or certificate verification failed. If this is the same aaPanel server, use its local MySQL socket/loopback address. For a remote database, use the correct trusted CA and a certificate matching its hostname; do not disable verification.';
        } elseif (str_contains($message, 'connection refused')) {
            $hint = 'MySQL refused the TCP connection. Check that MySQL is running and listening on the host/port in config.php. If MySQL is local and socket-only, use its actual Unix socket path instead.';
        } elseif (str_contains($message, 'no such file or directory')) {
            $hint = 'The MySQL Unix socket was not found. In aaPanel, open MySQL settings and check the socket= path, then use that exact unix_socket path in the config.php DSN.';
        } elseif (str_contains($message, 'permission denied')) {
            $hint = 'PHP was denied access to the MySQL connection. Check the MySQL socket permissions and PHP file/network restrictions. Do not make private files world-writable.';
        } elseif ($code === 1045 || $code === 1698) {
            $hint = 'MySQL rejected the configured login. Check the username/password privately in config.php and the database user grants for this web server.';
        } elseif ($code === 1044) {
            $hint = 'The database user does not have permission to access the configured database. Check its grants in aaPanel.';
        } elseif ($code === 1049) {
            $hint = 'The configured database does not exist. Check the database name in config.php.';
        } elseif ($code === 2005 || str_contains($message, 'getaddrinfo') || str_contains($message, 'name resolution')) {
            $hint = 'PHP could not resolve the database hostname. Check the host in config.php and server DNS.';
        } elseif (str_contains($message, 'timed out') || str_contains($message, 'timeout')) {
            $hint = 'The MySQL connection timed out. Check the database host/port, firewall and whether the server accepts connections from this website.';
        } else {
            $hint = 'PHP could not establish the configured MySQL connection. Check the MySQL service, host/port or Unix socket path. This is a connection failure, not an application setup-key error.';
        }
        return new Problem(503, $hint . ($code > 0 ? ' (SQL code ' . $code . ')' : ''));
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
        if($this->pdo->inTransaction()) {
            $name='cc_nested_'.(++$this->savepoint);$this->pdo->exec("SAVEPOINT $name");
            try {$result=$work();$this->pdo->exec("RELEASE SAVEPOINT $name");return $result;}
            catch(\Throwable $error){$this->pdo->exec("ROLLBACK TO SAVEPOINT $name");$this->pdo->exec("RELEASE SAVEPOINT $name");throw $error;}
        }
        $this->pdo->beginTransaction();
        try { $result = $work(); $this->pdo->commit(); return $result; }
        catch (\Throwable $error) { if ($this->pdo->inTransaction()) $this->pdo->rollBack(); throw $error; }
    }
    public static function id(): string { return bin2hex(random_bytes(16)); }
}
