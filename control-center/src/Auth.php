<?php
declare(strict_types=1);
namespace ControlCenter;

final class Auth
{
    public function __construct(private readonly Database $db, private readonly Config $config, private readonly Secrets $secrets) {}

    public static function hashPassword(string $password): string
    {
        $algorithm = in_array('argon2id', password_algos(), true) ? PASSWORD_ARGON2ID : PASSWORD_BCRYPT;
        return password_hash(Input::password($password), $algorithm, $algorithm === PASSWORD_BCRYPT
            ? ['cost' => 12] : ['memory_cost' => 65536, 'time_cost' => 4, 'threads' => 1]);
    }

    public function audit(?array $actor, string $action, ?string $resource, ?string $tenant, array $metadata = []): void
    {
        $this->db->query('INSERT INTO audit_events (tenant_id, actor_id, action, resource_id, metadata) VALUES (?, ?, ?, ?, ?)',
            [$tenant, $actor['id'] ?? null, $action, $resource, json_encode($metadata, JSON_THROW_ON_ERROR)]);
        (new LiveConnection($this->config))->audited($this->db,$action,$resource,$tenant,$metadata);
    }

    public function bootstrap(string $setupKey, array $input): array
    {
        if (!hash_equals($this->config->secret('setup.key'), $setupKey)) throw new Problem(403, 'Invalid setup key.');
        $this->config->secret('application.key');
        Input::keys($input, ['email', 'name', 'password']);
        $email = Input::email($input['email'] ?? null);
        $name = Input::text($input['name'] ?? null, 'name', 120);
        $hash = self::hashPassword(Input::password($input['password'] ?? null));
        (new Migrations($this->db, $this->config))->apply();
        return $this->db->transaction(function () use ($email, $name, $hash): array {
            $installed = $this->db->one('SELECT owner_id FROM installation WHERE id = 1 FOR UPDATE');
            if ($installed['owner_id'] !== null) throw new Problem(409, 'Installation is already complete.');
            $tenant = Database::id();
            $id = Database::id();
            $this->db->query("INSERT INTO tenants (id, name, kind, device_limit) VALUES (?, 'Extreme Hub', 'owner', 100)", [$tenant]);
            $this->db->query("INSERT INTO users (id, email, display_name, password_hash, role) VALUES (?, ?, ?, ?, 'owner')", [$id, $email, $name, $hash]);
            $this->db->query('UPDATE installation SET owner_id = ?, installed_at = UTC_TIMESTAMP() WHERE id = 1', [$id]);
            $owner = ['id' => $id, 'tenant_id' => null, 'role' => 'owner', 'email' => $email, 'display_name' => $name];
            $this->audit($owner, 'installation.completed', $id, null);
            return $owner;
        });
    }

    public function limit(string $purpose, string $identity, int $maximum, int $seconds): void
    {
        $bucket = $this->secrets->mac($purpose . ':' . $identity);
        $this->db->query('INSERT INTO rate_limits (bucket, attempts, expires_at) VALUES (?, 1, DATE_ADD(UTC_TIMESTAMP(), INTERVAL ? SECOND)) '
            . 'ON DUPLICATE KEY UPDATE attempts = IF(expires_at <= UTC_TIMESTAMP(), 1, attempts + 1), '
            . 'expires_at = IF(expires_at <= UTC_TIMESTAMP(), DATE_ADD(UTC_TIMESTAMP(), INTERVAL ? SECOND), expires_at)', [$bucket, $seconds, $seconds]);
        if ((int) $this->db->one('SELECT attempts FROM rate_limits WHERE bucket = ?', [$bucket])['attempts'] > $maximum) {
            throw new Problem(429, 'Too many requests. Please try again later.');
        }
    }

    public function login(array $input, string $ip, bool $native=false): array
    {
        if ($native) (new MyHub($this->db,$this->config))->ready();
        Input::keys($input, ['email', 'password']);
        $email = Input::email($input['email'] ?? null);
        $password = $input['password'] ?? '';
        if (!is_string($password) || strlen($password) > 256) throw new Problem(401, 'Email or password is incorrect.');
        $this->limit('login-ip', $ip, 60, 900);
        $this->limit('login-account', $ip . ':' . $email, 8, 900);
        $user = $this->db->one('SELECT u.*, t.status AS tenant_status FROM users u LEFT JOIN tenants t ON t.id = u.tenant_id WHERE u.email = ?', [$email]);
        // Verify a real-cost dummy hash too, so missing users are not a fast path.
        $dummyHash = in_array('argon2id', password_algos(), true)
            ? '$argon2id$v=19$m=65536,t=4,p=1$aWUwSXlLdU5NWXBUUEZ0OA$Q5+SRAhVnDuHjoZjFJRBsoO8gvBj2yH3Vh2pVoU/LtE'
            : '$2y$12$e1PucugYmYMZc8rdbV6BHudcj7j8/EpdthauCu.2//DiO0UALExIq';
        $hash = $user['password_hash'] ?? $dummyHash;
        $verified = password_verify($password, $hash);
        if (!$user || !$verified || !$user['active'] || ($user['tenant_id'] !== null && $user['tenant_status'] !== 'active')) {
            $this->audit(null, 'auth.login_failed', null, null);
            throw new Problem(401, 'Email or password is incorrect.');
        }
        if ($native) MyHub::reseller($user);
        return $this->issueSession($user,$native);
    }

    public function loginPanel(array $input,string $ip,?MyHub $bridge=null,bool $native=true): array
    {
        $bridge??=new MyHub($this->db,$this->config);$bridge->ready();
        Input::keys($input,['username','password']);
        $username=Input::text($input['username']??null,'reseller username',150);
        $password=$input['password']??null;
        if (!is_string($password) || $password==='' || strlen($password)>256) throw new Problem(401,'Username or password is incorrect.');
        $this->limit('my-hub-login-ip',$ip,60,900);
        $this->limit('my-hub-login-account',mb_strtolower($username),20,900);
        $panelId=$bridge->authenticate($username,$password);
        return $this->db->transaction(function()use($panelId,$native):array {
            $link=$this->db->one('SELECT p.tenant_id,t.kind,t.status FROM panel_resellers p JOIN tenants t ON t.id=p.tenant_id WHERE p.panel_id=? FOR UPDATE',[$panelId]);
            if (!$link) throw new Problem(403,'Ask the administrator to link your reseller-panel account to a Hub reseller workspace.');
            if ($link['status']!=='active') throw new Problem(403,'Your Hub workspace is unavailable.');
            if ($link['kind']==='owner') {
                if(!$native)throw new Problem(403,'Use Owner / existing Hub login for the website owner account.');
                if (!$this->db->one('SELECT name FROM schema_migrations WHERE name=?',['035_my_hub_owner_workspaces.sql'])) throw new Problem(503,'Install the My Hub owner-workspace update first.');
                $user=$this->db->one("SELECT u.* FROM installation i JOIN users u ON u.id=i.owner_id WHERE i.id=1 AND u.role='owner' AND u.active=1 FOR UPDATE");
                if (!$user) throw new Problem(403,'The Hub owner account is unavailable.');
                return $this->issueSession($user,true,['workspace_id'=>$link['tenant_id'],'panel_id'=>$panelId]);
            }
            $users=$this->db->all("SELECT u.*,t.status AS tenant_status FROM users u JOIN tenants t ON t.id=u.tenant_id WHERE u.tenant_id=? AND u.role='reseller' ORDER BY u.id LIMIT 2 FOR UPDATE",[$link['tenant_id']]);
            if (count($users)!==1) throw new Problem(403,'Ask the administrator to configure one Hub reseller login for this workspace.');
            $user=$users[0];
            if (!$user['active'] || $user['tenant_status']!=='active') throw new Problem(403,'Your Hub reseller workspace is unavailable.');
            return $this->issueSession($user,$native);
        });
    }

    private function issueSession(array $user,bool $native,?array $ownerScope=null): array
    {
        $token = bin2hex(random_bytes(32));
        $hours = $this->config->number('session_hours', 12, 1, 24);
        $this->db->query('INSERT INTO sessions (token_hash, user_id, expires_at) VALUES (?, ?, DATE_ADD(UTC_TIMESTAMP(), INTERVAL ? HOUR))',
            [hash('sha256', $token), $user['id'], $hours]);
        if ($native) {
            try {
                $this->db->query('INSERT INTO my_hub_sessions (token_hash) VALUES (?)',[hash('sha256',$token)]);
                if ($ownerScope) $this->db->query('INSERT INTO my_hub_owner_workspaces (token_hash,workspace_id,panel_id) VALUES (?,?,?)',[hash('sha256',$token),$ownerScope['workspace_id'],$ownerScope['panel_id']]);
            }
            catch (\Throwable $e) { $this->logout($token); throw $e; }
        }
        $this->audit($user, 'auth.login', $user['id'], $user['tenant_id']);
        return ['token' => $token, 'user' => $native&&$ownerScope?$this->nativeOwner($user,$token):self::publicUser($user), 'csrf' => $this->csrf($token)];
    }

    public function user(string $token, bool $native=false): array
    {
        if (!preg_match('/^[a-f0-9]{64}$/D', $token)) throw new Problem(401, 'Please sign in.');
        if ((new MyHub($this->db,$this->config))->isNative($token)!==$native) throw new Problem(401,'Please sign in again.');
        $user = $this->db->one('SELECT u.*, t.status AS tenant_status FROM sessions s JOIN users u ON u.id = s.user_id '
            . 'LEFT JOIN tenants t ON t.id = u.tenant_id WHERE s.token_hash = ? AND s.expires_at > UTC_TIMESTAMP() AND u.active = 1', [hash('sha256', $token)]);
        if (!$user || ($user['tenant_id'] !== null && $user['tenant_status'] !== 'active')) throw new Problem(401, 'Please sign in.');
        return $native&&$user['role']==='owner'?$this->nativeOwner($user,$token):self::publicUser($user);
    }

    /** A panel-authenticated owner session is a workspace operator, never a global owner. */
    private function nativeOwner(array $user,string $token): array
    {
        $scope=$this->db->one("SELECT t.id FROM my_hub_owner_workspaces s JOIN tenants t ON t.id=s.workspace_id JOIN panel_resellers p ON p.tenant_id=t.id AND p.panel_id=s.panel_id JOIN installation i ON i.id=1 WHERE s.token_hash=? AND t.kind='owner' AND t.status='active' AND i.owner_id=?",[hash('sha256',$token),$user['id']]);
        if (!$scope) throw new Problem(401,'Owner workspace access changed. Please sign in again.');
        return self::publicUser(array_replace($user,['role'=>'reseller','tenant_id'=>$scope['id']]))+['_native_owner_workspace'=>$scope['id']];
    }

    public static function publicUser(array $user): array
    {
        return array_intersect_key($user, array_flip(['id', 'tenant_id', 'role', 'email', 'display_name']));
    }
    public function csrf(string $token): string { return $this->secrets->mac('csrf:' . $token); }
    public function verifyCsrf(string $token, string $submitted): void
    {
        if (!hash_equals($this->csrf($token), $submitted)) throw new Problem(403, 'The request could not be verified. Reload and try again.');
    }
    public function logout(string $token): void { $this->db->query('DELETE FROM sessions WHERE token_hash = ?', [hash('sha256', $token)]); }
    public function changePassword(array $actor, array $input): void
    {
        Input::keys($input, ['current_password', 'new_password']);
        $current = $input['current_password'] ?? '';
        if (!is_string($current) || strlen($current) > 256) throw new Problem(403, 'Current password is incorrect.');
        $this->limit('password-change', $actor['id'], 8, 900);
        $hash = self::hashPassword(Input::password($input['new_password'] ?? null));
        $this->db->transaction(function () use ($actor, $current, $hash): void {
            $user = $this->db->one('SELECT password_hash FROM users WHERE id = ? FOR UPDATE', [$actor['id']]);
            if (!$user || !password_verify($current, $user['password_hash'])) throw new Problem(403, 'Current password is incorrect.');
            $this->db->query('UPDATE users SET password_hash = ? WHERE id = ?', [$hash, $actor['id']]);
            $this->db->query('DELETE FROM sessions WHERE user_id = ?', [$actor['id']]);
            $this->audit($actor, 'auth.password_changed', $actor['id'], $actor['tenant_id']);
        });
    }
    public static function owner(array $actor): void { if ($actor['role'] !== 'owner') throw new Problem(403, 'Owner access is required.'); }
    /** Only nativeOwner creates this verified, request-local capability. It grants
     * owner-workspace features, never installation-wide owner administration. */
    public static function ownsWorkspace(array $actor,array $workspace): bool {
        return $workspace['kind']==='owner' && ($actor['role']==='owner' ||
            ($actor['role']==='reseller' && ($actor['_native_owner_workspace']??null)===$workspace['id'] && ($actor['tenant_id']??null)===$workspace['id']));
    }
    public static function ownerWorkspace(array $actor,array $workspace): void {
        if (!self::ownsWorkspace($actor,$workspace)) throw new Problem(403,'Owner workspace access is required.');
    }
    /** Filesystem-authorized CLI recovery only; no HTTP route exposes this. */
    public function recoverOwner(string $newPassword): void
    {
        $hash = self::hashPassword($newPassword);
        $this->db->transaction(function () use ($hash): void {
            $owner = $this->db->one("SELECT u.* FROM installation i JOIN users u ON u.id = i.owner_id WHERE i.id = 1 AND u.role = 'owner' FOR UPDATE");
            if (!$owner) throw new Problem(409, 'No installed owner account exists to recover.');
            $this->db->query('UPDATE users SET password_hash = ?, active = 1 WHERE id = ?', [$hash, $owner['id']]);
            $this->db->query('DELETE FROM sessions WHERE user_id = ?', [$owner['id']]);
            $this->audit($owner, 'auth.owner_recovered_by_cli', $owner['id'], null);
        });
    }
}
