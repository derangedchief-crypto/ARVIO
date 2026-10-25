-- Existing unassigned requests were for the isolated testing channel.
UPDATE app_brand_builds SET build_channel='testing' WHERE build_channel IS NULL;
ALTER TABLE app_brand_builds
 DROP INDEX brand_build_version,
 ADD UNIQUE KEY brand_build_version_channel(brand_id,brand_version,app_version,build_channel);

CREATE TABLE brand_main_rollouts (
 source_sha CHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 channel VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 cursor_brand CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT '',
 complete BOOLEAN NOT NULL DEFAULT FALSE,
 PRIMARY KEY(source_sha,channel),
 FOREIGN KEY(source_sha) REFERENCES brand_main_sources(source_sha)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
INSERT INTO brand_main_rollouts (source_sha,channel,cursor_brand,complete)
 SELECT source_sha,'testing',cursor_brand,complete FROM brand_main_sources;

CREATE TABLE brand_main_refreshes (
 brand_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(brand_id) REFERENCES app_brands(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
