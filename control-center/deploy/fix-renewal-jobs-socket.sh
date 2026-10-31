#!/usr/bin/env bash
# WEBSITE only: expose the configured local DB socket to the existing job sandbox.
# Does not change database/config credentials or restart MySQL, PHP, auth or trials.
set -euo pipefail
umask 077
[[ $(id -u) == 0 ]] || { echo 'Run on the website host as root.' >&2; exit 1; }
site=/www/wwwroot/control.fecxbrfzku.net
php=/www/server/php/84/bin/php
unit=renewal-jobs.service
dropin=/etc/systemd/system/renewal-jobs.service.d/30-database-socket.conf
[[ $(systemctl show "$unit" -p User --value) == www ]] || { echo 'Unexpected renewal job user; stopped.' >&2; exit 1; }
command_line=$(systemctl show "$unit" -p ExecStart --value)
[[ $(systemctl show "$unit" -p WorkingDirectory --value) == "$site" && "$command_line" == *"$php"* && "$command_line" == *"cli/console.php renewal-jobs"* ]] || { echo 'Unexpected renewal job command; stopped.' >&2; exit 1; }
cd "$site"
socket=$(runuser -u www -- "$php" -d display_errors=stderr -d opcache.enable_cli=0 <<'PHP'
<?php
require 'src/bootstrap.php';
try {
    $config = new ControlCenter\Config(getcwd());
    $db = $config->get('database', []);
    $parts = [];
    foreach (explode(';', substr($db['dsn'] ?? '', 6)) as $part) {
        $pair = explode('=', $part, 2);
        if (count($pair) === 2) $parts[strtolower(trim($pair[0]))] = trim($pair[1]);
    }
    if (!str_starts_with($db['dsn'] ?? '', 'mysql:')) throw new RuntimeException();
    $socket = $parts['unix_socket'] ?? null;
    if ($socket === null) {
        if (($parts['host'] ?? 'localhost') !== 'localhost') {
            fwrite(STDERR, "Database uses TCP, not a local socket; no sandbox change made.\n");
            exit(2);
        }
        $socket = ini_get('pdo_mysql.default_socket');
    }
    if (!is_string($socket) || !preg_match('~^/[A-Za-z0-9_./-]+$~D', $socket)) throw new RuntimeException();
    // Prove this same website user can connect outside the sandbox first.
    new ControlCenter\Database($config);
    echo $socket;
} catch (Throwable $e) {
    fwrite(STDERR, "Could not safely verify the configured database socket; no sandbox change made.\n");
    exit(1);
}
PHP
)
[[ "$socket" =~ ^/[A-Za-z0-9_./-]+$ && -S "$socket" ]] || { echo 'Configured socket is unavailable; stopped.' >&2; exit 1; }
candidate=$(mktemp /root/renewal-socket-dropin-XXXXXX)
trap 'rm -f -- "$candidate"' EXIT
printf '# Managed renewal job database socket access\n[Service]\nBindReadOnlyPaths=%s\n' "$socket" > "$candidate"
[[ ! -L "$dropin" ]] || { echo 'Unexpected symlink at drop-in; stopped.' >&2; exit 1; }
if [[ -e "$dropin" ]] && ! cmp -s "$candidate" "$dropin"; then
    echo 'Existing socket override differs; preserved for review.' >&2
    exit 1
fi
install -d -m 0755 -o root -g root "$(dirname "$dropin")"
install -m 0644 -o root -g root "$candidate" "$dropin"
systemctl daemon-reload
systemctl reset-failed "$unit"
systemctl enable --now renewal-jobs.timer
# Do not kill an in-flight renewal. A currently running job finishes first;
# the timer's next activation uses the new bind mount automatically.
systemctl start --no-block "$unit"
echo 'Installed database-socket access for renewal-jobs.service only.'
echo 'PrivateTmp, service user and all existing payment checks are unchanged.'
systemctl show "$unit" -p PrivateTmp -p BindReadOnlyPaths
