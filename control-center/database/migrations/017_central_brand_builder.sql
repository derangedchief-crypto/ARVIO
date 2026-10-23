ALTER TABLE app_brand_builds
 ADD COLUMN build_channel VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD COLUMN source_sha CHAR(40) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD COLUMN runner_run_id VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD COLUMN lease_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD COLUMN lease_until DATETIME NULL,
 ADD COLUMN build_attempt INT UNSIGNED NOT NULL DEFAULT 0,
 ADD COLUMN artifact_receipt JSON NULL,
 ADD COLUMN failure_code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD COLUMN build_started_at DATETIME NULL,
 ADD COLUMN build_finished_at DATETIME NULL,
 ADD COLUMN published_at DATETIME NULL,
 ADD KEY brand_builder_queue(state,lease_until,created_at);
ALTER TABLE app_brand_artifacts
 ADD COLUMN source_sha CHAR(40) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD COLUMN apk_size_bytes BIGINT UNSIGNED NULL,
 ADD COLUMN provenance_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL;
