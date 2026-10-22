<?php
declare(strict_types=1);
// Safe connection diagnostics; no database or background service is started.
require dirname(__DIR__) . '/src/bootstrap.php';

use ControlCenter\Database;

$cases = [
    [2002, 'Connection refused', 'refused the TCP'],
    [2002, 'No such file or directory', 'Unix socket was not found'],
    [2002, 'Permission denied', 'denied access'],
    [2002, 'SSL operation failed: certificate verify failed', 'TLS negotiation'],
    [2026, 'Failed SSL connection', 'TLS negotiation'],
    [1045, 'Access denied for user', 'rejected the configured login'],
    [1698, 'Access denied', 'rejected the configured login'],
    [1044, 'Access denied to database', 'does not have permission'],
    [1049, 'Unknown database', 'does not exist'],
    [2005, 'Unknown MySQL server host', 'could not resolve'],
    [2002, 'Connection timed out', 'timed out'],
    [2002, 'Unknown transport failure', 'could not establish'],
];
foreach ($cases as [$code,$text,$expected]) {
    $error = new PDOException($text . ' sensitive-host.example username=private-user password=private-value', $code);
    $error->errorInfo = ['HY000',$code,$error->getMessage()];
    $problem = Database::connectionProblem($error);
    if ($problem->status !== 503 || !str_contains($problem->getMessage(),$expected)) throw new RuntimeException('Wrong diagnostic for '.$text);
    foreach (['sensitive-host.example','private-user','private-value'] as $secret) {
        if (str_contains($problem->getMessage(),$secret)) throw new RuntimeException('Raw driver data leaked');
    }
    echo 'PASS safe diagnostic: ', $expected, "\n";
}
