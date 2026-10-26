-- Old configuration-only approvals must NOT silently become live permission.
ALTER TABLE trial_plan_grants
 ADD COLUMN live_enabled BOOLEAN NOT NULL DEFAULT FALSE,
 ADD COLUMN daily_cap INT UNSIGNED NOT NULL DEFAULT 50,
 ADD COLUMN setup_revision INT UNSIGNED NOT NULL DEFAULT 0;

CREATE TABLE trial_catalog_refreshes (
 tenant_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 retry_after DATETIME NOT NULL,
 FOREIGN KEY(tenant_id) REFERENCES tenants(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
