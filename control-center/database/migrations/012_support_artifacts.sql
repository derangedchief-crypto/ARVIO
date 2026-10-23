CREATE TABLE support_artifacts (
 id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 command_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 device_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 profile_ref CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 kind VARCHAR(16) NOT NULL,
 bytes INT UNSIGNED NOT NULL,
 payload_cipher MEDIUMTEXT NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 expires_at DATETIME NOT NULL,
 UNIQUE KEY support_command(command_id),
 KEY support_device(device_id,created_at),
 FOREIGN KEY (device_id) REFERENCES devices(id) ON DELETE CASCADE,
 FOREIGN KEY (command_id) REFERENCES commands(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
