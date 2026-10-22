<?php
declare(strict_types=1);
if (PHP_SAPI !== 'cli') { http_response_code(404); exit; }
require dirname(__DIR__) . '/src/bootstrap.php';

use ControlCenter\Application;
use ControlCenter\Config;
use ControlCenter\Migrations;

$config = new Config(dirname(__DIR__));
try {
    switch ($argv[1] ?? 'help') {
        case 'preflight':
            if (PHP_VERSION_ID < 80300) throw new RuntimeException('PHP 8.3+ is required');
            foreach (['pdo_mysql','mbstring','openssl','fileinfo','curl'] as $extension) {
                if (!extension_loaded($extension)) throw new RuntimeException("Enable PHP extension: $extension");
            }
            if (!is_dir($config->root . '/storage') || !is_writable($config->root . '/storage')) throw new RuntimeException('Make storage/ writable by the website user');
            if (!$config->configured()) throw new RuntimeException('Fill in the private config.php beside public/ first');
            $app = new Application($config);
            $app->db->query('SELECT 1');
            $config->secret('application.key');
            $config->secret('setup.key');
            echo "PHP, required extensions, private storage and database connection are ready.\n";
            break;
        case 'migrate':
            $app = new Application($config);
            $applied = (new Migrations($app->db, $config))->apply();
            echo json_encode(['applied' => $applied], JSON_THROW_ON_ERROR) . "\n";
            break;
        case 'maintenance':
            $app = new Application($config);
            if (!(new Migrations($app->db, $config))->installed()) throw new RuntimeException('Complete owner setup first');
            echo json_encode($app->maintain(), JSON_THROW_ON_ERROR) . "\n";
            break;
        case 'recover-owner':
            if (stream_isatty(STDIN)) throw new RuntimeException('Read the new password from a private file via stdin, not interactive terminal echo or command arguments. See installation instructions.');
            $password = rtrim(stream_get_contents(STDIN, 256), "\r\n");
            $app = new Application($config);
            $app->auth->recoverOwner($password);
            unset($password);
            echo "Owner password reset. All owner sessions were revoked.\n";
            break;
        default:
            echo "Usage: php cli/console.php preflight|migrate|maintenance|recover-owner\n";
    }
} catch (Throwable $error) {
    // Database exceptions can contain SQL/customer identifiers: do not echo them.
    fwrite(STDERR, $error instanceof PDOException ? "Database operation failed. Check private configuration and database permissions.\n" : $error->getMessage() . "\n");
    exit(1);
}
