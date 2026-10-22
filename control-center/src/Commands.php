<?php
declare(strict_types=1);
namespace ControlCenter;

final class Commands
{
    public const KINDS = ['refresh_playlist', 'reload_epg', 'request_checkin', 'get_settings', 'clear_cache', 'get_diagnostics', 'show_message', 'restart_app'];
    public function __construct(private readonly Database $db, private readonly Config $config, private readonly Auth $auth, private readonly Management $management) {}

    private static function payload(string $kind, mixed $payload): array
    {
        if (!is_array($payload) || array_is_list($payload) && $payload !== []) throw new Problem(422, 'Invalid command payload.');
        if ($kind === 'show_message') {
            Input::keys($payload, ['message', 'priority', 'duration_seconds']);
            $message = Input::text($payload['message'] ?? null, 'message', 1000);
            $priority = $payload['priority'] ?? 'normal';
            if (!in_array($priority, ['normal', 'urgent'], true)) throw new Problem(422, 'Invalid message priority.');
            return ['message' => $message, 'priority' => $priority, 'duration_seconds' => Input::integer($payload['duration_seconds'] ?? 300, 30, 3600)];
        }
        Input::keys($payload, []);
        return $kind === 'clear_cache' ? ['scope' => 'expendable_only'] : [];
    }

    public function enqueue(array $actor, string $deviceId, array $input, string $key): array
    {
        Input::keys($input, ['kind', 'payload', 'expires_seconds']);
        $kind = $input['kind'] ?? '';
        if (!in_array($kind, self::KINDS, true)) throw new Problem(422, 'Unsupported command.');
        if (!preg_match('/^[A-Za-z0-9-]{16,64}$/D', $key)) throw new Problem(422, 'A valid Idempotency-Key is required.');
        $payload = self::payload($kind, $input['payload'] ?? []);
        $expiry = Input::integer($input['expires_seconds'] ?? 600, 60, 86400);
        $requestHash = hash('sha256', json_encode([$deviceId, $kind, $payload, $expiry], JSON_THROW_ON_ERROR));
        return $this->db->transaction(function () use ($actor, $deviceId, $kind, $payload, $expiry, $key, $requestHash): array {
            $device = $this->management->device($actor, $deviceId, true);
            $existing = $this->db->one('SELECT * FROM commands WHERE requested_by = ? AND idempotency_key = ?', [$actor['id'], $key]);
            if ($existing) {
                if (!hash_equals($existing['request_hash'], $requestHash)) throw new Problem(409, 'This request key was already used for a different action.');
                return self::publicCommand($existing);
            }
            $tenant = $this->management->tenant($actor, $device['tenant_id']);
            if ($device['policy'] !== 'active' || $tenant['status'] !== 'active') throw new Problem(409, 'This device is restricted.');
            if ($device['enrolled_at'] === null || !in_array($kind, $device['capabilities'], true)) {
                throw new Problem(409, 'The connected app has not advertised support for this action.');
            }
            $id = Database::id();
            $this->db->query('INSERT INTO commands (id, device_id, tenant_id, requested_by, idempotency_key, request_hash, kind, payload, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, DATE_ADD(UTC_TIMESTAMP(), INTERVAL ? SECOND))',
                [$id, $deviceId, $device['tenant_id'], $actor['id'], $key, $requestHash, $kind, json_encode((object) $payload, JSON_THROW_ON_ERROR), $expiry]);
            $this->auth->audit($actor, 'command.queued', $id, $device['tenant_id'], ['kind' => $kind, 'device_id' => $deviceId]);
            return self::publicCommand($this->db->one('SELECT * FROM commands WHERE id = ?', [$id]));
        });
    }

    public static function publicCommand(array $row): array
    {
        unset($row['lease_hash'], $row['request_hash'], $row['idempotency_key']);
        $row['payload'] = json_decode($row['payload'], false, 16, JSON_THROW_ON_ERROR);
        $row['result'] = $row['result'] === null ? null : json_decode($row['result'], true, 16, JSON_THROW_ON_ERROR);
        return $row;
    }
    public function listing(array $actor, string $deviceId, array $input): array
    {
        Input::keys($input, ['page']); $page = Input::page($input['page'] ?? 1); $offset = ($page - 1) * 25;
        $this->management->device($actor, $deviceId);
        $this->expire($deviceId);
        $total = (int) $this->db->query('SELECT COUNT(*) FROM commands WHERE device_id = ?', [$deviceId])->fetchColumn();
        $rows = $this->db->all("SELECT * FROM commands WHERE device_id = ? ORDER BY created_at DESC, id DESC LIMIT 25 OFFSET $offset", [$deviceId]);
        return ['items' => array_map(self::publicCommand(...), $rows), 'page' => $page, 'pages' => max(1, (int) ceil($total / 25)), 'total' => $total];
    }
    public function expire(?string $deviceId = null): void
    {
        $condition = $deviceId === null ? '' : ' AND device_id = ?';
        $this->db->query("UPDATE commands SET status = 'expired', finished_at = UTC_TIMESTAMP() WHERE status IN ('pending','delivered','running') AND (expires_at <= UTC_TIMESTAMP() OR (attempts >= 3 AND lease_until <= UTC_TIMESTAMP()))$condition", $deviceId === null ? [] : [$deviceId]);
    }

    public function deviceIdentity(string $token): array
    {
        if (!preg_match('/^[a-f0-9]{64}$/D', $token)) throw new Problem(401, 'Invalid device credentials.');
        $device = $this->db->one("SELECT d.*, t.status AS tenant_status FROM device_credentials k JOIN devices d ON d.id = k.device_id JOIN tenants t ON t.id = d.tenant_id WHERE k.token_hash = ? AND k.revoked_at IS NULL AND k.expires_at > UTC_TIMESTAMP() AND d.policy != 'removed'", [hash('sha256', $token)]);
        if (!$device) throw new Problem(401, 'Invalid device credentials.');
        return $device;
    }

    public function checkIn(string $token, array $input, string $ip): array
    {
        Input::keys($input, ['schema_version', 'model', 'os_version', 'app_version', 'activity_state', 'capabilities', 'storage_total', 'storage_free']);
        Input::integer($input['schema_version'] ?? 1, 1, 1);
        $device = $this->deviceIdentity($token);
        $this->auth->limit('device-check-in', $device['id'], 20, 60);
        $model = Input::text($input['model'] ?? '', 'model', 120, false);
        $os = Input::text($input['os_version'] ?? '', 'OS version', 80, false);
        $version = Input::text($input['app_version'] ?? '', 'app version', 40, false);
        $activity = $input['activity_state'] ?? 'unknown';
        $caps = $input['capabilities'] ?? [];
        if (!in_array($activity, ['active','idle','unknown'], true) || !is_array($caps) || !array_is_list($caps) || count($caps) > count(self::KINDS)
            || array_filter($caps, static fn($cap) => !is_string($cap) || !in_array($cap, self::KINDS, true))) throw new Problem(422, 'Invalid device capabilities/status.');
        $caps = array_values(array_unique($caps));
        $total = isset($input['storage_total']) ? Input::integer($input['storage_total'], 0, PHP_INT_MAX) : null;
        $free = isset($input['storage_free']) ? Input::integer($input['storage_free'], 0, PHP_INT_MAX) : null;
        if ($free !== null && ($total === null || $free > $total)) throw new Problem(422, 'Invalid storage snapshot.');
        $ip = filter_var($ip, FILTER_VALIDATE_IP) ? $ip : null;
        return $this->db->transaction(function () use ($token, $device, $model, $os, $version, $activity, $caps, $total, $free, $ip): array {
            $this->db->one('SELECT id FROM devices WHERE id = ? FOR UPDATE', [$device['id']]);
            // Recheck credentials/policy after taking the delivery lock.
            $current = $this->deviceIdentity($token);
            $this->expire($current['id']);
            $this->db->query('UPDATE devices SET model = ?, os_version = ?, app_version = ?, activity_state = ?, capabilities = ?, storage_total = ?, storage_free = ?, last_ip = ?, last_seen_at = UTC_TIMESTAMP() WHERE id = ?',
                [$model, $os, $version, $activity, json_encode($caps, JSON_THROW_ON_ERROR), $total, $free, $ip, $current['id']]);
            $policy = $current['tenant_status'] === 'active' ? $current['policy'] : 'suspended';
            $deliveries = [];
            if ($policy === 'active') {
                $rows = $this->db->all("SELECT * FROM commands WHERE device_id = ? AND expires_at > UTC_TIMESTAMP() AND attempts < 3 AND (status = 'pending' OR (status IN ('delivered','running') AND lease_until <= UTC_TIMESTAMP())) ORDER BY created_at, id LIMIT 5 FOR UPDATE", [$current['id']]);
                $leaseSeconds = $this->config->number('command_lease_seconds', 90, 30, 600);
                foreach ($rows as $row) {
                    if (!in_array($row['kind'], $caps, true)) {
                        $this->db->query("UPDATE commands SET status = 'failed', finished_at = UTC_TIMESTAMP(), result = ? WHERE id = ?", [json_encode(['summary' => 'Capability is no longer advertised.']), $row['id']]);
                        continue;
                    }
                    $lease = bin2hex(random_bytes(32));
                    $this->db->query("UPDATE commands SET status = 'delivered', attempts = attempts + 1, lease_hash = ?, lease_until = DATE_ADD(UTC_TIMESTAMP(), INTERVAL ? SECOND) WHERE id = ?", [hash('sha256', $lease), $leaseSeconds, $row['id']]);
                    $deliveries[] = ['id' => $row['id'], 'kind' => $row['kind'], 'payload' => json_decode($row['payload'], false, 16, JSON_THROW_ON_ERROR), 'lease_token' => $lease, 'expires_at' => $row['expires_at']];
                }
            }
            return ['schema_version' => 1, 'device_id'=>$current['id'], 'policy' => $policy, 'heartbeat_seconds' => $this->config->number('heartbeat_seconds', 60, 15, 300), 'commands' => $deliveries,
                'update_policy' => ['managed_by' => 'platform_owner', 'source' => 'existing_central_channel']];
        });
    }

    public function acknowledge(string $token, string $commandId, array $input): array
    {
        Input::keys($input, ['schema_version', 'lease_token', 'status', 'summary']);
        Input::integer($input['schema_version'] ?? 1, 1, 1);
        $device = $this->deviceIdentity($token);
        $status = $input['status'] ?? '';
        $this->auth->limit('device-ack', $device['id'], 60, 60);
        $lease = $input['lease_token'] ?? '';
        if (!in_array($status, ['running','succeeded','failed'], true) || !is_string($lease) || !preg_match('/^[a-f0-9]{64}$/D', $lease)) throw new Problem(422, 'Invalid command acknowledgment.');
        $summary = Secrets::redact(Input::text($input['summary'] ?? '', 'summary', 1000, false));
        return $this->db->transaction(function () use ($token, $device, $commandId, $status, $lease, $summary): array {
            $this->db->one('SELECT id FROM devices WHERE id = ? FOR UPDATE', [$device['id']]);
            $current = $this->deviceIdentity($token);
            if ($current['policy'] !== 'active' || $current['tenant_status'] !== 'active') throw new Problem(403, 'Device is restricted; stop queued actions.');
            $this->expire($current['id']);
            $row = $this->db->one('SELECT *, (lease_until > UTC_TIMESTAMP()) AS lease_valid FROM commands WHERE id = ? AND device_id = ? FOR UPDATE', [Input::id($commandId), $current['id']]);
            if (!$row) throw new Problem(404, 'Record not found.');
            $leaseValid = (bool) $row['lease_valid'];
            unset($row['lease_valid']);
            if ($row['lease_hash'] === null || !hash_equals($row['lease_hash'], hash('sha256', $lease))) throw new Problem(409, 'Delivery lease is no longer valid.');
            if (in_array($row['status'], ['succeeded','failed'], true) && $row['status'] === $status) return self::publicCommand($row);
            if (!in_array($row['status'], ['delivered','running'], true) || !$leaseValid) throw new Problem(409, 'Command has expired or the lease is no longer valid.');
            $this->db->query('UPDATE commands SET status = ?, result = ?, finished_at = ' . ($status === 'running' ? 'NULL' : 'UTC_TIMESTAMP()') . ' WHERE id = ?', [$status, json_encode(['summary' => $summary], JSON_THROW_ON_ERROR), $commandId]);
            if ($status !== 'running') $this->auth->audit(null, 'command.' . $status, $commandId, $current['tenant_id'], ['device_id' => $current['id'], 'kind' => $row['kind']]);
            return self::publicCommand($this->db->one('SELECT * FROM commands WHERE id = ?', [$commandId]));
        });
    }
    /** A previously issued, known credential may read only its removal policy,
     * never commands/data. This is not an authentication failure guessed by APKs. */
    public function removalNotice(string $token): ?array
    {
        if(!preg_match('/^[a-f0-9]{64}$/D',$token))return null;
        $row=$this->db->one("SELECT d.id FROM device_credentials k JOIN devices d ON d.id=k.device_id WHERE k.token_hash=? AND d.policy='removed'",[hash('sha256',$token)]);
        if(!$row)return null;
        $this->auth->limit('removed-device-policy',$row['id'],20,60);
        return ['schema_version'=>1,'device_id'=>$row['id'],'policy'=>'removed','commands'=>[],'heartbeat_seconds'=>60];
    }

    /** No production HTTP enrollment endpoint exists until the activation/tenant
     * ownership contract is agreed. The local simulator cannot enroll real devices. */
    public function issueTestCredential(array $actor, string $deviceId): string
    {
        Auth::owner($actor);
        if (!$this->config->local()) throw new Problem(403, 'Test enrollment is disabled outside a local development/test environment.');
        return $this->db->transaction(function () use ($actor, $deviceId): string {
            $device = $this->management->device($actor, $deviceId, true);
            if ($device['policy'] !== 'active') throw new Problem(409, 'Device is restricted.');
            $this->db->query('UPDATE device_credentials SET revoked_at = UTC_TIMESTAMP() WHERE device_id = ?', [$deviceId]);
            $token = bin2hex(random_bytes(32));
            $this->db->query('INSERT INTO device_credentials (token_hash, device_id, expires_at) VALUES (?, ?, DATE_ADD(UTC_TIMESTAMP(), INTERVAL 1 DAY))', [hash('sha256', $token), $deviceId]);
            $this->db->query('UPDATE devices SET is_test = 1, enrolled_at = COALESCE(enrolled_at, UTC_TIMESTAMP()) WHERE id = ?', [$deviceId]);
            $this->auth->audit($actor, 'device.test_enrolled', $deviceId, $device['tenant_id']);
            return $token;
        });
    }
}
