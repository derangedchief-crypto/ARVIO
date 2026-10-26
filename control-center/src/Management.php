<?php
declare(strict_types=1);
namespace ControlCenter;

final class Management
{
    public function __construct(private readonly Database $db, private readonly Config $config, private readonly Auth $auth, private readonly ?PanelBridge $panelBridge=null) {}

    private function scope(array $actor, string $column, array &$arguments): string
    {
        if ($actor['role'] === 'owner') return '1 = 1';
        if ($actor['role'] !== 'reseller' || !$actor['tenant_id']) throw new Problem(403, 'Access denied.');
        $arguments[] = $actor['tenant_id'];
        return "$column = ?";
    }

    public function tenant(array $actor, string $id, bool $lock = false): array
    {
        $args = [Input::id($id)];
        $scope = $this->scope($actor, 'id', $args);
        return $this->db->one("SELECT * FROM tenants WHERE id = ? AND $scope" . ($lock ? ' FOR UPDATE' : ''), $args)
            ?? throw new Problem(404, 'Record not found.');
    }
    public function tenants(array $actor): array
    {
        return $this->tenantListing($actor, [])['items'];
    }
    /** The owner has no reseller tenant_id. Resolve its EXISTING workspace instead
     * of inventing a reseller, changing the login role, or returning a null scope. */
    public function workspace(array $actor,mixed $id=null,bool $lock=false):array {
        if($id===null||$id===''){
            if($actor['role']==='owner')$id=$this->db->one("SELECT id FROM tenants WHERE kind='owner' ORDER BY id LIMIT 1")['id']??null;
            else $id=$actor['tenant_id']??null;
        }
        return $this->tenant($actor,Input::id($id),$lock);
    }
    public function tenantListing(array $actor, array $input): array
    {
        Input::keys($input, ['page', 'search']);
        $page = Input::page($input['page'] ?? 1); $offset = ($page - 1) * 25;
        $args = [];
        $scope = $this->scope($actor, 't.id', $args);
        $search = Input::text($input['search'] ?? '', 'search', 120, false);
        if ($search !== '') { $scope .= ' AND LOCATE(?, t.name) > 0'; $args[] = $search; }
        $total = (int) $this->db->query("SELECT COUNT(*) FROM tenants t WHERE $scope", $args)->fetchColumn();
        $rows = $this->db->all("SELECT t.*, (SELECT COUNT(*) FROM devices d WHERE d.tenant_id = t.id AND d.policy != 'removed') AS devices_used FROM tenants t WHERE $scope ORDER BY (t.kind = 'owner') DESC, t.created_at, t.id LIMIT 25 OFFSET $offset", $args);
        return ['items' => $rows, 'page' => $page, 'pages' => max(1, (int) ceil($total / 25)), 'total' => $total];
    }
    public function createReseller(array $actor, array $input): array
    {
        Auth::owner($actor);
        Input::keys($input, ['company', 'name', 'email', 'password', 'device_limit', 'panel_username']);
        $company = Input::text($input['company'] ?? null, 'company', 120);
        $name = Input::text($input['name'] ?? null, 'name', 120);
        $email = Input::email($input['email'] ?? null);
        $hash = Auth::hashPassword(Input::password($input['password'] ?? null));
        $limit = Input::integer($input['device_limit'] ?? 100, 1, 100000);
        $panel=null;
        if(!empty($input['panel_username'])) {
            $bridge=$this->panelBridge??new PanelBridge(new EnrollmentProof($this->config,new Secrets($this->config)));
            $panel=$bridge->reseller(Input::text($input['panel_username'],'panel username',150));
            if($this->db->one('SELECT panel_id FROM panel_resellers WHERE panel_id=? OR username=?',[$panel['id'],$panel['username']]))throw new Problem(409,'That panel reseller is already linked to a workspace.');
        }
        return $this->db->transaction(function () use ($actor, $company, $name, $email, $hash, $limit, $panel): array {
            $id = Database::id(); $user = Database::id();
            $this->db->query("INSERT INTO tenants (id, name, kind, device_limit) VALUES (?, ?, 'reseller', ?)", [$id, $company, $limit]);
            $this->db->query("INSERT INTO users (id, tenant_id, email, display_name, password_hash, role) VALUES (?, ?, ?, ?, ?, 'reseller')", [$user, $id, $email, $name, $hash]);
            if($panel) {
                $this->db->query('INSERT INTO panel_resellers (panel_id,tenant_id,username,linked_by) VALUES (?,?,?,?)',[$panel['id'],$id,$panel['username'],$actor['id']]);
                $this->db->query("UPDATE enrollment_requests SET poll_expires_at=UTC_TIMESTAMP() WHERE status='pending' AND device_id IS NULL");
            }
            $this->auth->audit($actor, 'reseller.created', $id, $id, ['device_limit' => $limit]);
            if($panel)$this->auth->audit($actor,'reseller.panel_linked',$panel['id'],$id);
            return $this->tenant($actor, $id);
        });
    }
    public function updateTenant(array $actor, string $id, array $input): array
    {
        Auth::owner($actor);
        Input::keys($input, ['status', 'device_limit']);
        $status = $input['status'] ?? '';
        if (!in_array($status, ['active', 'suspended'], true)) throw new Problem(422, 'Invalid reseller status.');
        $limit = Input::integer($input['device_limit'] ?? 100, 1, 100000);
        return $this->db->transaction(function () use ($actor, $id, $status, $limit): array {
            $tenant = $this->tenant($actor, $id, true);
            if($tenant['kind']==='owner'){
                if($status!=='active'||$tenant['status']!=='active')throw new Problem(403,'The owner workspace cannot be suspended here.');
                $this->db->query('UPDATE tenants SET device_limit=? WHERE id=?',[$limit,$id]);
                $this->auth->audit($actor,'owner.allocation_updated',$id,$id,['device_limit'=>$limit]);return $this->tenant($actor,$id);
            }
            if ($tenant['kind'] !== 'reseller') throw new Problem(403, 'Workspace policy cannot be changed here.');
            $this->db->query('UPDATE tenants SET status = ?, device_limit = ? WHERE id = ?', [$status, $limit, $id]);
            if ($status === 'suspended') {
                $this->db->query('DELETE s FROM sessions s JOIN users u ON u.id = s.user_id WHERE u.tenant_id = ?', [$id]);
                $this->db->query("UPDATE commands SET status = 'cancelled', finished_at = UTC_TIMESTAMP() WHERE tenant_id = ? AND status IN ('pending','delivered','running')", [$id]);
            }
            $this->auth->audit($actor, 'reseller.updated', $id, $id, ['status' => $status, 'device_limit' => $limit]);
            return $this->tenant($actor, $id);
        });
    }

    public function customer(array $actor, string $id): array
    {
        $args = [Input::id($id)];
        $scope = $this->scope($actor, 'tenant_id', $args);
        return $this->db->one("SELECT * FROM customers WHERE id = ? AND $scope", $args) ?? throw new Problem(404, 'Record not found.');
    }
    public function createCustomer(array $actor, array $input): array
    {
        Input::keys($input, ['tenant_id', 'name', 'notes', 'external_reference']);
        $tenant = $this->workspace($actor, $input['tenant_id'] ?? null);
        if ($tenant['status'] !== 'active') throw new Problem(409, 'This workspace is suspended.');
        $name = Input::text($input['name'] ?? null, 'name', 120);
        $notes = Input::text($input['notes'] ?? '', 'notes', 2000, false);
        $reference = Input::text($input['external_reference'] ?? '', 'reference', 128, false);
        return $this->db->transaction(function () use ($actor, $tenant, $name, $notes, $reference): array {
            $id = Database::id();
            $this->db->query('INSERT INTO customers (id, tenant_id, name, notes, external_reference) VALUES (?, ?, ?, ?, ?)', [$id, $tenant['id'], $name, $notes, $reference ?: null]);
            $this->auth->audit($actor, 'customer.created', $id, $tenant['id']);
            return $this->customer($actor, $id);
        });
    }

    public function updateCustomer(array $actor, string $id, array $input): array
    {
        Input::keys($input, ['name', 'notes', 'external_reference']);
        $customer = $this->customer($actor, $id);
        $name = Input::text($input['name'] ?? $customer['name'], 'name', 120);
        $notes = Input::text($input['notes'] ?? $customer['notes'], 'notes', 2000, false);
        $reference = Input::text($input['external_reference'] ?? $customer['external_reference'] ?? '', 'reference', 128, false);
        return $this->db->transaction(function () use ($actor, $id, $customer, $name, $notes, $reference): array {
            $this->db->query('UPDATE customers SET name = ?, notes = ?, external_reference = ? WHERE id = ? AND tenant_id = ?', [$name, $notes, $reference ?: null, $id, $customer['tenant_id']]);
            $this->auth->audit($actor, 'customer.updated', $id, $customer['tenant_id']);
            return $this->customer($actor, $id);
        });
    }

    public function device(array $actor, string $id, bool $lock = false): array
    {
        $args = [Input::id($id)];
        $scope = $this->scope($actor, 'd.tenant_id', $args);
        $line=$this->deviceUsernameSql('d');
        $row = $this->db->one("SELECT d.*, TIMESTAMPDIFF(SECOND, d.last_seen_at, UTC_TIMESTAMP()) AS last_seen_age, COALESCE($line,c.name) AS customer_name, $line AS line_username, t.name AS tenant_name FROM devices d JOIN customers c ON c.id = d.customer_id JOIN tenants t ON t.id = d.tenant_id WHERE d.id = ? AND $scope" . ($lock ? ' FOR UPDATE' : ''), $args);
        if (!$row) throw new Problem(404, 'Record not found.');
        return $this->publicDevice($row);
    }
    public function createDevice(array $actor, array $input): array
    {
        Input::keys($input, ['customer_id', 'label', 'notes']);
        $customer = $this->customer($actor, Input::id($input['customer_id'] ?? null));
        $label = Input::text($input['label'] ?? null, 'device label', 120);
        $notes = Input::text($input['notes'] ?? '', 'notes', 2000, false);
        return $this->db->transaction(function () use ($actor, $customer, $label, $notes): array {
            $tenant = $this->tenant($actor, $customer['tenant_id'], true);
            if ($tenant['status'] !== 'active') throw new Problem(409, 'This reseller is suspended.');
            $count = (int) $this->db->query("SELECT COUNT(*) FROM devices WHERE tenant_id = ? AND policy != 'removed'", [$tenant['id']])->fetchColumn();
            if ($count >= (int) $tenant['device_limit']) throw new Problem(409, 'Device allocation reached. Contact the owner.');
            $id = Database::id();
            $this->db->query("INSERT INTO devices (id, tenant_id, customer_id, installation_id, label, notes, capabilities) VALUES (?, ?, ?, ?, ?, ?, '[]')", [$id, $tenant['id'], $customer['id'], 'pending:' . $id, $label, $notes]);
            $this->auth->audit($actor, 'device.provisioned', $id, $tenant['id']);
            return $this->device($actor, $id);
        });
    }
    public function updateDevice(array $actor, string $id, array $input): array
    {
        Input::keys($input, ['label', 'notes', 'policy']);
        return $this->db->transaction(function () use ($actor, $id, $input): array {
            $device = $this->device($actor, $id, true);
            $label = Input::text($input['label'] ?? $device['label'], 'device label', 120);
            $notes = Input::text($input['notes'] ?? $device['notes'], 'notes', 2000, false);
            $policy = $input['policy'] ?? $device['policy'];
            if (!in_array($policy, ['active', 'suspended', 'blocked', 'removed'], true)) throw new Problem(422, 'Invalid device policy.');
            if ($device['policy'] === 'removed') throw new Problem(409, 'Removed devices require a new owner-approved enrollment.');
            if ($policy !== $device['policy'] && (in_array($policy, ['blocked', 'removed'], true) || $device['policy'] === 'blocked')) Auth::owner($actor);
            $this->db->query('UPDATE devices SET label = ?, notes = ?, policy = ? WHERE id = ?', [$label, $notes, $policy, $id]);
            if ($policy !== 'active') $this->db->query("UPDATE commands SET status = 'cancelled', finished_at = UTC_TIMESTAMP() WHERE device_id = ? AND status IN ('pending','delivered','running')", [$id]);
            if ($policy === 'removed') $this->db->query('UPDATE device_credentials SET revoked_at = UTC_TIMESTAMP() WHERE device_id = ?', [$id]);
            $this->auth->audit($actor, 'device.updated', $id, $device['tenant_id'], ['policy' => $policy]);
            return $this->device($actor, $id);
        });
    }
    public function detachReseller(array $actor,string $id): array
    {
        Auth::owner($actor);
        return $this->db->transaction(function ()use($actor,$id):array {
            $device=$this->device($actor,$id,true);
            if(!empty($device['reseller_detached']))return $device;
            $owner=$this->db->one("SELECT * FROM tenants WHERE kind='owner' ORDER BY created_at,id LIMIT 1 FOR UPDATE");
            if(!$owner)throw new Problem(409,'Owner workspace is unavailable.');
            $customer=$this->db->one('SELECT id FROM customers WHERE tenant_id=? AND external_reference=? FOR UPDATE',[$owner['id'],'management:unassigned']);
            if(!$customer) {
                $customer=['id'=>Database::id()];
                $this->db->query("INSERT INTO customers (id,tenant_id,name,notes,external_reference) VALUES (?,?,'Unassigned devices','','management:unassigned')",[$customer['id'],$owner['id']]);
            }
            $this->db->query("UPDATE commands SET status='cancelled',finished_at=UTC_TIMESTAMP(),lease_hash=NULL,lease_until=NULL WHERE device_id=? AND status IN ('pending','delivered','running')",[$id]);
            // Cascading the composite FK preserves command history while removing
            // ALL former reseller access. Device tokens/login are not erased.
            $this->db->query("UPDATE devices SET tenant_id=?,customer_id=?,reseller_detached=1,policy='active' WHERE id=?",[$owner['id'],$customer['id'],$id]);
            DeviceProfiles::assignmentChanged($this->db,$id);
            // An older client may already have re-requested enrollment after
            // seeing "removed". Restore ONLY this record's previously bound key,
            // not arbitrary pending installations. Polling then requests a fresh
            // credential; previously revoked bearers are never revived.
            $this->db->query("UPDATE enrollment_requests SET status='approved',credential_cipher=NULL,credential_expires_at=NULL,reviewed_by=? WHERE device_id=? AND key_fingerprint=? AND app_package=? AND status='pending'",[$actor['id'],$id,$device['key_fingerprint'],$device['app_package']]);
            $this->auth->audit($actor,'device.reseller_detached',$id,$device['tenant_id'],['owner_workspace'=>$owner['id']]);
            $this->auth->audit($actor,'device.unassigned',$id,$owner['id'],['previous_tenant'=>$device['tenant_id']]);
            return $this->device($actor,$id);
        });
    }
    private function publicDevice(array $row): array
    {
        $row['capabilities'] = json_decode($row['capabilities'], true, 16, JSON_THROW_ON_ERROR);
        $row['is_test'] = (bool) $row['is_test'];
        $row['reseller_detached'] = (bool) ($row['reseller_detached'] ?? false);
        if(empty($row['line_username'])&&preg_match('/^Panel line [0-9]+$/D',$row['customer_name']??''))$row['customer_name']='Username awaiting app check-in';
        $age = $row['last_seen_age'] === null ? PHP_INT_MAX : (int) $row['last_seen_age'];
        unset($row['last_seen_age']);
        $threshold = $this->config->number('offline_after_seconds', 180, 60, 1800);
        $row['presence'] = $row['enrolled_at'] === null ? 'not_enrolled' : ($age > $threshold ? 'offline' : ($row['activity_state'] === 'idle' ? 'idle' : 'online'));
        return $row;
    }

    public function listing(array $actor, string $resource, array $input): array
    {
        Input::keys($input, array_merge(['page','search','tenant_id','policy'],$resource==='devices'?['customer_id']:[]));
        $page = Input::page($input['page'] ?? 1);
        $offset = ($page - 1) * 25;
        $args = [];
        $scope = $this->scope($actor, 'r.tenant_id', $args);
        if (isset($input['tenant_id']) && $input['tenant_id'] !== '') {
            $tenant = $this->tenant($actor, Input::id($input['tenant_id']));
            $scope .= ' AND r.tenant_id = ?'; $args[] = $tenant['id'];
        }
        $search = Input::text($input['search'] ?? '', 'search', 120, false);
        if ($resource === 'devices') {
            if(isset($input['customer_id'])){$customer=$this->customer($actor,Input::id($input['customer_id']));$scope.=' AND r.customer_id=?';$args[]=$customer['id'];}
            if (isset($input['policy']) && $input['policy'] !== '') {
                if (!in_array($input['policy'], ['active','suspended','blocked','removed'], true)) throw new Problem(422, 'Invalid policy filter.');
                $scope .= ' AND r.policy = ?'; $args[] = $input['policy'];
            } else $scope .= " AND r.policy != 'removed'";
            $joins = 'devices r JOIN customers c ON c.id = r.customer_id JOIN tenants t ON t.id = r.tenant_id';
            $line=$this->deviceUsernameSql('r');
            $fields = "r.*, TIMESTAMPDIFF(SECOND, r.last_seen_at, UTC_TIMESTAMP()) AS last_seen_age, COALESCE($line,c.name) AS customer_name, $line AS line_username, t.name AS tenant_name";
            if ($search !== '') {
                $scope .= " AND (LOCATE(?, r.label) > 0 OR LOCATE(?, c.name) > 0 OR LOCATE(?, CONVERT(r.id USING utf8mb4)) > 0 OR LOCATE(?, COALESCE(r.model,'')) > 0 OR LOCATE(?,COALESCE($line,''))>0)";
                array_push($args, $search, $search, $search, $search, $search);
            }
        } elseif ($resource === 'customers') {
            $joins = 'customers r JOIN tenants t ON t.id = r.tenant_id';
            $line=$this->hasVerifiedUsernames()?'(SELECT n.username FROM managed_accounts a JOIN verified_line_usernames n ON n.account_ref=a.account_ref WHERE a.customer_id=r.id ORDER BY n.verified_at DESC LIMIT 1)':'NULL';
            $fields = "r.*, t.name AS tenant_name, $line AS line_username";
            if ($search !== '') { $scope .= " AND (LOCATE(?, r.name)>0 OR LOCATE(?,COALESCE(r.external_reference,''))>0 OR LOCATE(?,COALESCE($line,''))>0)"; array_push($args, $search, $search, $search); }
        } else throw new Problem(404, 'Record not found.');
        $total = (int) $this->db->query("SELECT COUNT(*) FROM $joins WHERE $scope", $args)->fetchColumn();
        $rows = $this->db->all("SELECT $fields FROM $joins WHERE $scope ORDER BY r.created_at DESC, r.id LIMIT 25 OFFSET $offset", $args);
        if ($resource === 'devices') $rows = array_map($this->publicDevice(...), $rows);
        return ['items' => $rows, 'page' => $page, 'pages' => max(1, (int) ceil($total / 25)), 'total' => $total];
    }
    private function deviceUsernameSql(string $alias):string
    {
        if(!$this->hasVerifiedUsernames())return 'NULL';
        return "(SELECT n.username FROM enrollment_requests e JOIN verified_line_usernames n ON n.account_ref=e.account_ref WHERE e.device_id=$alias.id AND e.key_fingerprint=$alias.key_fingerprint AND e.app_package=$alias.app_package AND e.status='approved' ORDER BY n.verified_at DESC LIMIT 1)";
    }
    private function hasVerifiedUsernames():bool {return (bool)$this->db->one('SELECT name FROM schema_migrations WHERE name=?',['009_verified_line_usernames.sql']);}

    public function overview(array $actor,array $input=[]): array
    {
        Input::keys($input,['tenant_id']);
        $args = []; $scope = $this->scope($actor, 'tenant_id', $args);
        if(!empty($input['tenant_id'])){$tenant=$this->workspace($actor,$input['tenant_id']);$scope.=' AND tenant_id=?';$args[]=$tenant['id'];}
        $threshold = $this->config->number('offline_after_seconds', 180, 60, 1800);
        $stats = $this->db->one("SELECT COUNT(*) AS total, COALESCE(SUM(enrolled_at IS NULL),0) AS not_enrolled, COALESCE(SUM(last_seen_at >= DATE_SUB(UTC_TIMESTAMP(), INTERVAL $threshold SECOND)),0) AS connected, COALESCE(SUM(policy != 'active'),0) AS restricted FROM devices WHERE $scope AND policy != 'removed'", $args);
        $stats['customers'] = (int) $this->db->query("SELECT COUNT(*) FROM customers WHERE $scope", $args)->fetchColumn();
        $stats['pending_commands'] = (int) $this->db->query("SELECT COUNT(*) FROM commands WHERE $scope AND status IN ('pending','delivered','running') AND expires_at > UTC_TIMESTAMP()", $args)->fetchColumn();
        return $stats;
    }

    public function audit(array $actor, array $input): array
    {
        Input::keys($input, ['page','tenant_id','view']);$view=$input['view']??'history';if(!in_array($view,['current','history'],true))throw new Problem(422,'Invalid activity view.');$page = Input::page($input['page'] ?? 1); $offset = ($page - 1) * 25;
        $args = []; $scope = $this->scope($actor, 'tenant_id', $args);
        if(!empty($input['tenant_id'])){$tenant=$this->workspace($actor,$input['tenant_id']);$scope.=' AND tenant_id=?';$args[]=$tenant['id'];}
        if($view==='current')$scope.=' AND created_at>=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 24 HOUR)';
        $total = (int) $this->db->query("SELECT COUNT(*) FROM audit_events WHERE $scope", $args)->fetchColumn();
        $rows = $this->db->all("SELECT id, action, resource_id, created_at, metadata FROM audit_events WHERE $scope ORDER BY id DESC LIMIT 25 OFFSET $offset", $args);
        return ['items' => $rows, 'page' => $page, 'pages' => max(1, (int) ceil($total / 25)), 'total' => $total];
    }
}
