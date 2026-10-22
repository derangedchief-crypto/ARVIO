<?php
declare(strict_types=1);

// This is packaged as config.php beside public/. Edit only that file on your server.
return [
    'environment' => 'production',
    'origin' => 'https://control.fecxbrfzku.net',
    'database' => [
        'dsn' => 'mysql:host=localhost;port=3306;dbname=sql_control_fecxbrfzku_net;charset=utf8mb4',
        'username' => 'sql_control_fecxbrfzku_net',
        'password' => '',
        // For remote MySQL, point to the provider's CA certificate when required.
        // Otherwise a readable system CA bundle is used. Certificate verification
        // remains enabled; custom TCP ports do not themselves imply TLS.
        'ssl_ca' => null,
    ],
    'session_hours' => 12,
    'heartbeat_seconds' => 60,
    'offline_after_seconds' => 180,
    'command_lease_seconds' => 90,
    'retention_days' => 90,
];
