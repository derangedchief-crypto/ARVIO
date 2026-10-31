CREATE TABLE renewal_trial_conversions (
 tenant_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
 package_id INT UNSIGNED NULL,
 plan_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 enabled TINYINT(1) NOT NULL DEFAULT 0,
 revision INT UNSIGNED NOT NULL DEFAULT 1,
 updated_by CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 FOREIGN KEY(tenant_id) REFERENCES tenants(id),
 FOREIGN KEY(updated_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
ALTER TABLE renewal_simulated_targets ADD is_trial TINYINT(1) NOT NULL DEFAULT 0;
