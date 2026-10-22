<?php
declare(strict_types=1);
// Only for PHP's local development server. Nginx production uses deploy/nginx.conf.example.
$path = parse_url($_SERVER['REQUEST_URI'], PHP_URL_PATH);
if (in_array($path, ['/assets/app.js', '/assets/app.css', '/assets/layout-editor.js'], true)) return false;
require dirname(__DIR__) . '/public/index.php';
