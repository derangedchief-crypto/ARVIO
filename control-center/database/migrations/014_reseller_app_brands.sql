CREATE TABLE app_brand_assets (
 id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 tenant_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 mime VARCHAR(32) NOT NULL,
 width INT UNSIGNED NOT NULL,
 height INT UNSIGNED NOT NULL,
 sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 image MEDIUMBLOB NOT NULL,
 created_by CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 KEY brand_asset_workspace(tenant_id,created_at),
 FOREIGN KEY(tenant_id) REFERENCES tenants(id),
 FOREIGN KEY(created_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE app_brands (
 id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 tenant_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 package_name VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 name VARCHAR(60) NOT NULL,
 version INT UNSIGNED NOT NULL DEFAULT 1,
 enabled BOOLEAN NOT NULL DEFAULT TRUE,
 loading_asset CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 logo_asset CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 icon_asset CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 updated_by CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
 FOREIGN KEY(tenant_id) REFERENCES tenants(id),
 FOREIGN KEY(loading_asset) REFERENCES app_brand_assets(id),
 FOREIGN KEY(logo_asset) REFERENCES app_brand_assets(id),
 FOREIGN KEY(icon_asset) REFERENCES app_brand_assets(id),
 FOREIGN KEY(updated_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE app_brand_builds (
 id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 brand_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 brand_version INT UNSIGNED NOT NULL,
 app_version VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 manifest JSON NOT NULL,
 state VARCHAR(24) NOT NULL DEFAULT 'waiting_builder',
 requested_by CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY brand_build_version(brand_id,brand_version,app_version),
 FOREIGN KEY(brand_id) REFERENCES app_brands(id),
 FOREIGN KEY(requested_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE app_brand_build_assets (
 build_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 asset_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 PRIMARY KEY(build_id,asset_id),
 FOREIGN KEY(build_id) REFERENCES app_brand_builds(id),
 FOREIGN KEY(asset_id) REFERENCES app_brand_assets(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
