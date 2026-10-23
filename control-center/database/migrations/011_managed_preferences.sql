CREATE TABLE preference_profiles (
 id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 tenant_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 name VARCHAR(80) NOT NULL,
 version INT UNSIGNED NOT NULL DEFAULT 1,
 values_json JSON NOT NULL,
 archived BOOLEAN NOT NULL DEFAULT FALSE,
 created_by CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 FOREIGN KEY (tenant_id) REFERENCES tenants(id),
 FOREIGN KEY (created_by) REFERENCES users(id),
 KEY preference_workspace(tenant_id,archived,updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE preference_snapshots (
 command_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 device_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 profile_ref CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 revision CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 document JSON NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY (device_id) REFERENCES devices(id) ON DELETE CASCADE,
 FOREIGN KEY (command_id) REFERENCES commands(id) ON DELETE CASCADE,
 KEY preference_snapshot_device(device_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE preference_drafts (
 id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 device_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 profile_ref CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 expected_revision CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 target JSON NOT NULL,
 expires_at DATETIME NOT NULL,
 FOREIGN KEY (device_id) REFERENCES devices(id) ON DELETE CASCADE,
 KEY preference_draft_device(device_id,expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
