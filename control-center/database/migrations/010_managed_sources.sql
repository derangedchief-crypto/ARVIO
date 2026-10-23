CREATE TABLE source_snapshots (
 id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 device_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 command_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 profile_ref CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 revision CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 reason VARCHAR(20) NOT NULL,
 protected BOOLEAN NOT NULL DEFAULT FALSE,
 payload_cipher MEDIUMTEXT NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY command_reason(command_id,reason),
 KEY device_time(device_id,created_at),
 FOREIGN KEY (device_id) REFERENCES devices(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE source_drafts (
 id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 device_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 snapshot_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 profile_ref CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 expected_revision CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 restore_checkpoint BOOLEAN NOT NULL DEFAULT FALSE,
 payload_cipher MEDIUMTEXT NOT NULL,
 created_by CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 expires_at DATETIME NOT NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 KEY device_expiry(device_id,expires_at),
 FOREIGN KEY (device_id) REFERENCES devices(id) ON DELETE CASCADE,
 FOREIGN KEY (snapshot_id) REFERENCES source_snapshots(id) ON DELETE CASCADE,
 FOREIGN KEY (created_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
