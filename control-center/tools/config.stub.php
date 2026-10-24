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
    // Separate iptv-auth-worker extension, NOT the activation worker/portal secret.
    // Blank disables worker reads. This does not enable public app trial offers.
    'trial_worker' => [
        'base_url' => 'https://resellerworker.extremeiptv.net',
        'shared_secret' => '',
    ],
    // Contact details only; minimal eligibility history is kept separately.
    'trial_contact_retention_days' => 90,
    // OFF until a separately authorized branded-build / panel pilot is complete.
    // Existing admin/default settings alone NEVER publish an offer.
    'trial_public_signup_enabled' => false,
    'trial_channel' => 'production',
    'trial_reseller_daily_limit' => 50,
    'trial_device_daily_limit' => 10,
    'trial_ip_hourly_limit' => 50,
    // Restricted SSH CLI builder; no public HTTP builder/signing API.
    // Map platform versions to reviewed FULL source commits only after committing.
    'brand_builder' => [
        'enabled' => false,
        'publication_enabled' => false,
        'channels' => ['testing'],
        'sources' => [],
    ],
    // Site-hosted APK copies. Enable only after the restricted CLI bridge is ready.
    'brand_downloads' => ['enabled' => false, 'x_accel' => false],
];
