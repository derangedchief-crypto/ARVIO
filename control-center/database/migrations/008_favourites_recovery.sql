CREATE TABLE favourites_backups (
 id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 device_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 profile_ref CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 source_ref CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 channel_count INT UNSIGNED NOT NULL,
 group_count INT UNSIGNED NOT NULL,
 protected BOOLEAN NOT NULL DEFAULT FALSE,
 label VARCHAR(80) NOT NULL DEFAULT '',
 reason VARCHAR(16) NOT NULL,
 payload_cipher MEDIUMTEXT NOT NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 last_seen_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY (device_id) REFERENCES devices(id),
 INDEX favourites_device_history (device_id,created_at,id),
 INDEX favourites_context (device_id,profile_ref,source_ref,content_hash),
 CHECK (channel_count+group_count>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE favourites_live_state (
 device_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 profile_ref CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 source_ref CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 revision CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 channel_count INT UNSIGNED NOT NULL,
 group_count INT UNSIGNED NOT NULL,
 updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY (device_id,profile_ref,source_ref),
 FOREIGN KEY (device_id) REFERENCES devices(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE favourites_restore_previews (
 command_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 device_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 backup_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 preview JSON NOT NULL,
 expires_at DATETIME NOT NULL,
 FOREIGN KEY (command_id) REFERENCES commands(id) ON DELETE CASCADE,
 FOREIGN KEY (device_id) REFERENCES devices(id),
 FOREIGN KEY (backup_id) REFERENCES favourites_backups(id) ON DELETE CASCADE,
 INDEX favourites_preview_lookup (device_id,backup_id,expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
