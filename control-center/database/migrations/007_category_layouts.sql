CREATE TABLE category_layout_snapshots (
 command_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 device_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 profile_ref CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 source_ref CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 revision CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 total INT UNSIGNED NOT NULL,
 catalog JSON NOT NULL,
 received_at_us BIGINT UNSIGNED NOT NULL,
 FOREIGN KEY (command_id) REFERENCES commands(id) ON DELETE CASCADE,
 FOREIGN KEY (device_id) REFERENCES devices(id),
 INDEX layout_latest (device_id,received_at_us)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE category_layout_templates (
 id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 tenant_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 name VARCHAR(80) NOT NULL,
 entries JSON NOT NULL,
 version INT UNSIGNED NOT NULL DEFAULT 1,
 archived BOOLEAN NOT NULL DEFAULT FALSE,
 created_by CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY (tenant_id) REFERENCES tenants(id),
 FOREIGN KEY (created_by) REFERENCES users(id),
 INDEX layout_template_listing (tenant_id,archived,updated_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
