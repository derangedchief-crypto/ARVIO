<?php
declare(strict_types=1);

// Copy to config.local.php OUTSIDE public/. Enter real values only on your server.
return [
    'environment' => 'production',
    'origin' => 'https://control.fecxbrfzku.net',
    'database' => [
        'dsn' => 'mysql:host=127.0.0.1;dbname=control_center;charset=utf8mb4',
        'username' => 'control_center',
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
