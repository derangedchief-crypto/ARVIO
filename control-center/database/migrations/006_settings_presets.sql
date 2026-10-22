CREATE TABLE settings_presets (
 id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 tenant_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 name VARCHAR(80) NOT NULL,
 sort_order VARCHAR(16) NOT NULL,
 archived BOOLEAN NOT NULL DEFAULT FALSE,
 created_by CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY (tenant_id) REFERENCES tenants(id),
 FOREIGN KEY (created_by) REFERENCES users(id),
 INDEX preset_listing (tenant_id,archived,updated_at,id),
 CHECK (sort_order IN ('provider','name','number'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
