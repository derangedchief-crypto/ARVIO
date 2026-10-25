CREATE TABLE brand_main_sources (
 source_sha CHAR(40) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 app_version VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 version_code INT UNSIGNED NOT NULL UNIQUE,
 workflow_ref VARCHAR(120) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 cursor_brand CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT '',
 complete BOOLEAN NOT NULL DEFAULT FALSE,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE brand_main_jobs (
 build_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 source_sha CHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 FOREIGN KEY(build_id) REFERENCES app_brand_builds(id),
 FOREIGN KEY(source_sha) REFERENCES brand_main_sources(source_sha)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
