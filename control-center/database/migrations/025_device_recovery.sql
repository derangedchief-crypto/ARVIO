ALTER TABLE platform_settings
 ADD COLUMN device_list_generation BIGINT UNSIGNED NOT NULL DEFAULT 0,
 ADD COLUMN device_list_reset_at DATETIME(6) NULL,
 ADD COLUMN device_recovery_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE devices ADD COLUMN checkin_generation BIGINT UNSIGNED NOT NULL DEFAULT 0;
CREATE INDEX devices_checkin_window ON devices(tenant_id,checkin_generation,created_at);
ALTER TABLE enrollment_requests
 ADD COLUMN recovery_hint_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD COLUMN recovery_state VARCHAR(24) NULL;
CREATE TABLE device_recovery_hints (
 device_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 account_ref CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 app_package VARCHAR(80) NOT NULL,
 panel_reseller_id VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 hint_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY (device_id) REFERENCES devices(id),
 INDEX recovery_match(account_ref,app_package,panel_reseller_id,hint_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE device_recovery_locks (
 scope_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 touched_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
