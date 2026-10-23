CREATE TABLE device_setup_profiles (
 id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 tenant_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 name VARCHAR(80) NOT NULL,
 version INT UNSIGNED NOT NULL DEFAULT 1,
 definition JSON NOT NULL,
 archived BOOLEAN NOT NULL DEFAULT FALSE,
 created_by CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
 KEY setup_workspace(tenant_id,archived,updated_at),
 FOREIGN KEY (tenant_id) REFERENCES tenants(id),
 FOREIGN KEY (created_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE device_setup_versions (
 profile_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 version INT UNSIGNED NOT NULL,
 name VARCHAR(80) NOT NULL,
 definition JSON NOT NULL,
 PRIMARY KEY(profile_id,version),
 FOREIGN KEY (profile_id) REFERENCES device_setup_profiles(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE device_setup_defaults (
 tenant_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 profile_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 profile_version INT UNSIGNED NOT NULL,
 enabled BOOLEAN NOT NULL DEFAULT FALSE,
 revision INT UNSIGNED NOT NULL DEFAULT 1,
 set_by CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
 FOREIGN KEY(tenant_id) REFERENCES tenants(id),
 FOREIGN KEY(profile_id,profile_version) REFERENCES device_setup_versions(profile_id,version),
 FOREIGN KEY(set_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE device_setup_jobs (
 id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 device_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 admission_device_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL UNIQUE,
 tenant_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 profile_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 profile_version INT UNSIGNED NOT NULL,
 actor_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 automatic BOOLEAN NOT NULL,
 status VARCHAR(24) NOT NULL DEFAULT 'waiting',
 stage VARCHAR(32) NOT NULL DEFAULT 'read_settings',
 message VARCHAR(250) NOT NULL DEFAULT 'Waiting for the app to report supported capabilities',
 state JSON NOT NULL,
 next_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 expires_at DATETIME NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
 KEY setup_device(device_id,created_at),
 KEY setup_ready(status,next_at),
 FOREIGN KEY(device_id) REFERENCES devices(id),
 FOREIGN KEY(tenant_id) REFERENCES tenants(id),
 FOREIGN KEY(profile_id,profile_version) REFERENCES device_setup_versions(profile_id,version),
 FOREIGN KEY(actor_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
