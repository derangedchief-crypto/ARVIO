<?php
declare(strict_types=1);
namespace ControlCenter;

final class Commands
{
    public const KINDS = ['refresh_playlist', 'reload_epg', 'request_checkin', 'get_settings', 'clear_cache', 'get_diagnostics', 'show_message', 'restart_app', 'set_settings', 'get_categories', 'set_categories', 'get_category_layout', 'apply_category_layout', 'backup_favourites', 'preview_favourites_restore', 'restore_favourites'];
    public function __construct(private readonly Database $db, private readonly Config $config, private readonly Auth $auth, private readonly Management $management) {}

    private static function payload(string $kind, mixed $payload): array
    {
        if (!is_array($payload) || array_is_list($payload) && $payload !== []) throw new Problem(422, 'Invalid command payload.');
        if($kind==='preview_favourites_restore') {Input::keys($payload,['backup_id']);Input::id($payload['backup_id']??null);return $payload;}
        if($kind==='restore_favourites') {Input::keys($payload,['preview_id','mode']);Input::id($payload['preview_id']??null);if(!in_array($payload['mode']??null,['merge','replace'],true))throw new Problem(422,'Unsupported recovery mode.');return $payload;}
        if(in_array($kind,['get_categories','set_categories','get_category_layout','apply_category_layout'],true))return CommandCategories::payload($kind,$payload);
        if($kind==='set_settings') {
            Input::keys($payload,['profile_ref','expected_revision','sort_order']);
            EnrollmentProof::hex($payload['profile_ref']??null,64);EnrollmentProof::hex($payload['expected_revision']??null,64);
            if(!in_array($payload['sort_order']??null,['provider','number','name'],true))throw new Problem(422,'Unsupported channel sorting.');
            return $payload;
        }
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
            $existing = $this->db->one('SELECT * FROM commands WHERE requested_by = ? AND idempotency_key = ? FOR UPDATE', [$actor['id'], $key]);
            if ($existing) {
                if (!hash_equals($existing['request_hash'], $requestHash)) throw new Problem(409, 'This request key was already used for a different action.');
                return self::publicCommand($existing);
            }
            $tenant = $this->management->tenant($actor, $device['tenant_id']);
            if ($device['policy'] !== 'active' || $tenant['status'] !== 'active') throw new Problem(409, 'This device is restricted.');
            if ($device['enrolled_at'] === null || !in_array($kind, $device['capabilities'], true)) {
                throw new Problem(409, 'The connected app has not advertised support for this action.');
            }
            if($kind==='preview_favourites_restore'&&!$this->db->one('SELECT id FROM favourites_backups WHERE id=? AND device_id=?',[$payload['backup_id'],$deviceId]))throw new Problem(404,'Backup not found.');
            if($kind==='restore_favourites') {
                $preview=$this->db->one('SELECT preview FROM favourites_restore_previews WHERE command_id=? AND device_id=? AND expires_at>UTC_TIMESTAMP()',[$payload['preview_id'],$deviceId]);if(!$preview)throw new Problem(409,'Preview expired or does not belong to this device.');
                $p=json_decode($preview['preview'],true,16,JSON_THROW_ON_ERROR);
                if(!$p['matched_channels']&&!$p['matched_groups'])throw new Problem(409,'No saved favourites currently resolve.');
                if($payload['mode']==='replace'&&$p['missing_refs'])throw new Problem(409,'Replace refused: some backup entries no longer match.');
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
        if($row['kind']==='apply_category_layout') {
            $p=$row['payload'];$row['payload']=(object)['profile_ref'=>$p->profile_ref,'source_ref'=>$p->source_ref,'expected_revision'=>$p->expected_revision,'order_count'=>count($p->order),'hidden_count'=>count($p->hidden),'provider_order'=>$p->provider_order??false];
        }
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
    public function cancel(array $actor,string $deviceId,string $commandId): array
    {
        return $this->db->transaction(function()use($actor,$deviceId,$commandId):array {
            $device=$this->management->device($actor,$deviceId,true);$this->expire($deviceId);
            $row=$this->db->one('SELECT * FROM commands WHERE id=? AND device_id=? FOR UPDATE',[Input::id($commandId),$deviceId]);
            if(!$row)throw new Problem(404,'Record not found.');
            if($actor['role']!=='owner'&&$row['requested_by']!==$actor['id'])throw new Problem(403,'Only the requesting user or owner may cancel this command.');
            if($row['status']==='cancelled')return self::publicCommand($row);
            if(!in_array($row['status'],['pending','delivered'],true))throw new Problem(409,'This action is already running or finished; it cannot be recalled.');
            $this->db->query("UPDATE commands SET status='cancelled',finished_at=UTC_TIMESTAMP(),lease_hash=NULL,lease_until=NULL WHERE id=?",[$commandId]);
            $this->auth->audit($actor,'command.cancelled',$commandId,$device['tenant_id'],['device_id'=>$deviceId]);
            return self::publicCommand($this->db->one('SELECT * FROM commands WHERE id=?',[$commandId]));
        });
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
        Input::keys($input, ['schema_version', 'model', 'os_version', 'app_version', 'activity_state', 'capabilities', 'storage_total', 'storage_free', 'account_username']);
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
        $username=array_key_exists('account_username',$input)?Input::text($input['account_username'],'line username',256):null;
        return $this->db->transaction(function () use ($token, $device, $model, $os, $version, $activity, $caps, $total, $free, $ip, $username): array {
            $this->db->one('SELECT id FROM devices WHERE id = ? FOR UPDATE', [$device['id']]);
            // Recheck credentials/policy after taking the delivery lock.
            $current = $this->deviceIdentity($token);
            if($username!==null) {
                // The activation worker already signed this HMAC account ref.
                // Verify the candidate against it; never trust a reported label.
                $secret=(new EnrollmentProof($this->config,new Secrets($this->config)))->bridgeSecret();
                $ref=hash_hmac('sha256',"account-v1\n".$username,hex2bin($secret));
                $owned=$this->db->one("SELECT a.account_ref FROM enrollment_requests r JOIN managed_accounts a ON a.account_ref=r.account_ref WHERE r.device_id=? AND r.account_ref=? AND r.key_fingerprint=? AND r.app_package=? AND r.status='approved'",[$current['id'],$ref,$current['key_fingerprint'],$current['app_package']]);
                if($owned)$this->db->query('INSERT INTO verified_line_usernames (account_ref,username) VALUES (?,?) ON DUPLICATE KEY UPDATE username=VALUES(username),verified_at=UTC_TIMESTAMP()',[$ref,$username]);
            }
            $this->expire($current['id']);
            $this->db->query('UPDATE devices SET model = ?, os_version = ?, app_version = ?, activity_state = ?, capabilities = ?, storage_total = ?, storage_free = ?, last_ip = ?, last_seen_at = UTC_TIMESTAMP() WHERE id = ?',
                [$model, $os, $version, $activity, json_encode($caps, JSON_THROW_ON_ERROR), $total, $free, $ip, $current['id']]);
            $policy = $current['tenant_status'] === 'active' ? $current['policy'] : 'suspended';
            $deliveries = [];
            if ($policy === 'active') {
                $rows = $this->db->all("SELECT * FROM commands WHERE device_id = ? AND expires_at > UTC_TIMESTAMP() AND attempts < 3 AND (status = 'pending' OR (status IN ('delivered','running') AND lease_until <= UTC_TIMESTAMP())) ORDER BY created_at, id LIMIT 5 FOR UPDATE", [$current['id']]);
                $leaseSeconds = $this->config->number('command_lease_seconds', 90, 30, 600);
                foreach ($rows as $row) {
                    if($row['kind']==='apply_category_layout'&&$deliveries)break;
                    if (!in_array($row['kind'], $caps, true)) {
                        $this->db->query("UPDATE commands SET status = 'failed', finished_at = UTC_TIMESTAMP(), result = ? WHERE id = ?", [json_encode(['summary' => 'Capability is no longer advertised.']), $row['id']]);
                        continue;
                    }
                    $lease = bin2hex(random_bytes(32));
                    $this->db->query("UPDATE commands SET status = 'delivered', attempts = attempts + 1, lease_hash = ?, lease_until = DATE_ADD(UTC_TIMESTAMP(), INTERVAL ? SECOND) WHERE id = ?", [hash('sha256', $lease), $leaseSeconds, $row['id']]);
                    $deliveries[] = ['id' => $row['id'], 'kind' => $row['kind'], 'payload' => json_decode($row['payload'], false, 16, JSON_THROW_ON_ERROR), 'lease_token' => $lease, 'expires_at' => $row['expires_at']];
                    if($row['kind']==='apply_category_layout')break;
                }
            }
            $heartbeat=$this->config->number('heartbeat_seconds',60,15,300);
            if($deliveries)$heartbeat=min($heartbeat,$this->config->number('interactive_heartbeat_seconds',15,15,300));
            return ['schema_version' => 1, 'device_id'=>$current['id'], 'policy' => $policy, 'heartbeat_seconds' => $heartbeat, 'commands' => $deliveries,
                'update_policy' => ['managed_by' => 'platform_owner', 'source' => 'existing_central_channel']];
        });
    }

    public function acknowledge(string $token, string $commandId, array $input): array
    {
        Input::keys($input, ['schema_version', 'lease_token', 'status', 'summary', 'snapshot', 'catalog', 'favourites_preview']);
        Input::integer($input['schema_version'] ?? 1, 1, 1);
        $device = $this->deviceIdentity($token);
        $status = $input['status'] ?? '';
        $this->auth->limit('device-ack', $device['id'], 60, 60);
        $lease = $input['lease_token'] ?? '';
        if (!in_array($status, ['running','succeeded','failed'], true) || !is_string($lease) || !preg_match('/^[a-f0-9]{64}$/D', $lease)) throw new Problem(422, 'Invalid command acknowledgment.');
        $summary = Secrets::redact(Input::text($input['summary'] ?? '', 'summary', 1000, false));
        return $this->db->transaction(function () use ($token, $device, $commandId, $status, $lease, $summary, $input): array {
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
            $result=['summary'=>$summary];
            if($status==='succeeded'&&$row['kind']==='preview_favourites_restore'&&!array_key_exists('favourites_preview',$input))throw new Problem(422,'A recovery preview must return its match results.');
            if(array_key_exists('favourites_preview',$input)) {
                if($status!=='succeeded'||$row['kind']!=='preview_favourites_restore')throw new Problem(422,'Unexpected favourites preview.');
                $service=new Favourites($this->db,$this->management,$this,$this->auth,$this->config);
                $result['favourites_preview']=$service->storePreview($commandId,$current,$input['favourites_preview'],json_decode($row['payload'],true,16,JSON_THROW_ON_ERROR));
            }
            if($status==='succeeded'&&in_array($row['kind'],['get_categories','get_category_layout'],true)&&!array_key_exists('catalog',$input))throw new Problem(422,'A completed category read must include its catalog.');
            if(array_key_exists('catalog',$input)) {
                if($status!=='succeeded')throw new Problem(422,'Only completed reads can upload a category page.');
                $result['catalog']=CommandCategories::catalog($row['kind'],$input['catalog']);
                // DATETIME columns have second precision. Keep read ordering
                // unambiguous when two catalog replies finish in the same second.
                $result['received_at_us']=(int)floor(microtime(true)*1000000);
                $requested=json_decode($row['payload'],true,16,JSON_THROW_ON_ERROR);
                foreach(['profile_ref','source_ref','page'] as $field)if(isset($requested[$field])&&$result['catalog'][$field]!==$requested[$field])throw new Problem(422,'Category response does not match the requested target/page.');
                if($row['kind']==='get_category_layout') {
                    $catalog=$result['catalog'];$received=$result['received_at_us'];
                    $this->db->query('INSERT INTO category_layout_snapshots (command_id,device_id,profile_ref,source_ref,revision,total,catalog,received_at_us) VALUES (?,?,?,?,?,?,?,?)',[$commandId,$device['id'],$catalog['profile_ref'],$catalog['source_ref'],$catalog['revision'],$catalog['total'],json_encode($catalog,JSON_UNESCAPED_UNICODE|JSON_THROW_ON_ERROR),$received]);
                    $keep=$this->db->all('SELECT command_id FROM category_layout_snapshots WHERE device_id=? ORDER BY received_at_us DESC LIMIT 3',[$device['id']]);
                    $ids=array_column($keep,'command_id');$marks=implode(',',array_fill(0,count($ids),'?'));
                    $this->db->query("DELETE FROM category_layout_snapshots WHERE device_id=? AND command_id NOT IN ($marks)",array_merge([$device['id']],$ids));
                    $result['layout_summary']=['profile_ref'=>$catalog['profile_ref'],'source_ref'=>$catalog['source_ref'],'revision'=>$catalog['revision'],'total'=>$catalog['total']];unset($result['catalog']);
                }
            }
            if($status==='succeeded'&&in_array($row['kind'],['get_settings','get_diagnostics','set_settings'],true)&&!array_key_exists('snapshot',$input))throw new Problem(422,'A completed snapshot command must include its typed snapshot.');
            if(array_key_exists('snapshot',$input)) {
                if($status!=='succeeded')throw new Problem(422,'Only completed snapshot commands can upload a snapshot.');
                $result['snapshot']=CommandSnapshots::validate($row['kind'],$input['snapshot']);
            }
            $this->db->query('UPDATE commands SET status = ?, result = ?, finished_at = ' . ($status === 'running' ? 'NULL' : 'UTC_TIMESTAMP()') . ' WHERE id = ?', [$status, json_encode($result, JSON_THROW_ON_ERROR), $commandId]);
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
