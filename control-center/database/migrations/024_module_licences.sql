ALTER TABLE platform_settings
 ADD COLUMN module_licences_enforced BOOLEAN NOT NULL DEFAULT FALSE,
 ADD COLUMN module_licences_revision INT UNSIGNED NOT NULL DEFAULT 1;
CREATE TABLE module_licences (
 id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 tenant_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 module VARCHAR(40) NOT NULL,
 key_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 key_hint VARCHAR(16) NOT NULL,
 status ENUM('awaiting_activation','active','suspended','revoked') NOT NULL,
 activated_at DATETIME NULL,
 expires_at DATETIME NULL,
 revision INT UNSIGNED NOT NULL DEFAULT 1,
 created_by CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 UNIQUE KEY workspace_module(tenant_id,module),
 UNIQUE KEY licence_key(key_hash),
 FOREIGN KEY(tenant_id) REFERENCES tenants(id),
 FOREIGN KEY(created_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
