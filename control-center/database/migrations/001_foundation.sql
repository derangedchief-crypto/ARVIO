CREATE TABLE tenants (
 id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 name VARCHAR(120) NOT NULL,
 kind VARCHAR(16) NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'active',
 device_limit INT UNSIGNED NOT NULL DEFAULT 100,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 CHECK (kind IN ('owner', 'reseller')),
 CHECK (status IN ('active', 'suspended')),
 CHECK (device_limit > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE users (
 id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 tenant_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
 email VARCHAR(254) NOT NULL UNIQUE,
 display_name VARCHAR(120) NOT NULL,
 password_hash VARCHAR(255) NOT NULL,
 role VARCHAR(16) NOT NULL,
 active BOOLEAN NOT NULL DEFAULT TRUE,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY (tenant_id) REFERENCES tenants(id),
 CHECK ((role = 'owner' AND tenant_id IS NULL) OR (role = 'reseller' AND tenant_id IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE installation (
 id TINYINT PRIMARY KEY,
 owner_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
 installed_at DATETIME NULL,
 FOREIGN KEY (owner_id) REFERENCES users(id),
 CHECK (id = 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO installation (id) VALUES (1);

CREATE TABLE sessions (
 token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 user_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 expires_at DATETIME NOT NULL,
 FOREIGN KEY (user_id) REFERENCES users(id),
 INDEX sessions_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE rate_limits (
 bucket CHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 attempts INT UNSIGNED NOT NULL,
 expires_at DATETIME NOT NULL,
 INDEX limits_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE customers (
 id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 tenant_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 name VARCHAR(120) NOT NULL,
 notes TEXT NOT NULL,
 external_reference VARCHAR(128) NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE (id, tenant_id),
 UNIQUE (tenant_id, external_reference),
 FOREIGN KEY (tenant_id) REFERENCES tenants(id),
 INDEX customers_tenant_name (tenant_id, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE devices (
 id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 tenant_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 customer_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 installation_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 label VARCHAR(120) NOT NULL,
 notes TEXT NOT NULL,
 policy VARCHAR(16) NOT NULL DEFAULT 'active',
 is_test BOOLEAN NOT NULL DEFAULT FALSE,
 model VARCHAR(120) NULL,
 os_version VARCHAR(80) NULL,
 app_version VARCHAR(40) NULL,
 activity_state VARCHAR(16) NOT NULL DEFAULT 'unknown',
 capabilities JSON NOT NULL,
 storage_total BIGINT UNSIGNED NULL,
 storage_free BIGINT UNSIGNED NULL,
 last_ip VARCHAR(45) NULL,
 enrolled_at DATETIME NULL,
 last_seen_at DATETIME NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE (id, tenant_id),
 FOREIGN KEY (customer_id, tenant_id) REFERENCES customers(id, tenant_id),
 FOREIGN KEY (tenant_id) REFERENCES tenants(id),
 INDEX devices_tenant_seen (tenant_id, last_seen_at, id),
 INDEX devices_customer (customer_id),
 CHECK (policy IN ('active', 'suspended', 'blocked', 'removed')),
 CHECK (activity_state IN ('active', 'idle', 'unknown'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE device_credentials (
 token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 device_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 expires_at DATETIME NOT NULL,
 revoked_at DATETIME NULL,
 FOREIGN KEY (device_id) REFERENCES devices(id),
 INDEX credentials_device (device_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE commands (
 id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 device_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 tenant_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 requested_by CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 idempotency_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 kind VARCHAR(40) NOT NULL,
 payload JSON NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'pending',
 attempts INT UNSIGNED NOT NULL DEFAULT 0,
 lease_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 lease_until DATETIME NULL,
 expires_at DATETIME NOT NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 finished_at DATETIME NULL,
 result JSON NULL,
 UNIQUE (requested_by, idempotency_key),
 FOREIGN KEY (device_id, tenant_id) REFERENCES devices(id, tenant_id),
 FOREIGN KEY (requested_by) REFERENCES users(id),
 INDEX commands_delivery (device_id, status, expires_at, created_at),
 INDEX commands_tenant_time (tenant_id, created_at),
 INDEX commands_expiry (status, expires_at),
 CHECK (status IN ('pending', 'delivered', 'running', 'succeeded', 'failed', 'expired', 'cancelled'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE audit_events (
 id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
 tenant_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
 actor_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
 action VARCHAR(80) NOT NULL,
 resource_id VARCHAR(64) NULL,
 metadata JSON NOT NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 INDEX audit_tenant_time (tenant_id, id),
 INDEX audit_retention (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
