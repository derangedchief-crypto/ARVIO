<?php
declare(strict_types=1);
namespace ControlCenter;

final class Migrations
{
    public function __construct(private readonly Database $db, private readonly Config $config) {}

    public function apply(): array
    {
        $lock = 'control-migrations-' . substr(hash('sha256', $this->config->get('database')['dsn']), 0, 32);
        if ((int) $this->db->query('SELECT GET_LOCK(?, 10)', [$lock])->fetchColumn() !== 1) {
            throw new Problem(409, 'Another database upgrade is running.');
        }
        try {
            $tables = $this->db->query('SHOW TABLES')->fetchAll(\PDO::FETCH_COLUMN);
            if ($tables && !in_array('schema_migrations', $tables, true)) {
                throw new Problem(409, 'Use an empty dedicated database; existing unrelated tables were not modified.');
            }
            $this->db->query('CREATE TABLE IF NOT EXISTS schema_migrations (name VARCHAR(120) PRIMARY KEY, checksum CHAR(64) NOT NULL, applied_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP) ENGINE=InnoDB');
            $applied = [];
            foreach (glob($this->config->root . '/database/migrations/*.sql') as $file) {
                $name = basename($file);
                $sql = file_get_contents($file);
                $checksum = hash('sha256', $sql);
                $existing = $this->db->one('SELECT checksum FROM schema_migrations WHERE name = ?', [$name]);
                if ($existing) {
                    if (!hash_equals($existing['checksum'], $checksum)) throw new Problem(503, 'An applied migration was modified; restore the matching release.');
                    continue;
                }
                // Release migrations use simple standalone statements. DDL is
                // not transactional in MySQL: a failed migration requires review,
                // never a destructive automatic rollback/reinitialization.
                foreach (preg_split('/;\s*(?:\n|$)/', $sql) as $statement) {
                    if (trim($statement) !== '') $this->db->pdo->exec(trim($statement));
                }
                $this->db->query('INSERT INTO schema_migrations (name, checksum) VALUES (?, ?)', [$name, $checksum]);
                $applied[] = $name;
            }
            return $applied;
        } finally { $this->db->query('SELECT RELEASE_LOCK(?)', [$lock]); }
    }

    public function installed(): bool
    {
        try { return ($this->db->one('SELECT owner_id FROM installation WHERE id = 1')['owner_id'] ?? null) !== null; }
        catch (\PDOException $error) {
            if (($error->errorInfo[1] ?? null) === 1146) return false;
            throw $error;
        }
    }
}
